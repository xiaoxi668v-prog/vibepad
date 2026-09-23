import AudioToolbox
import CoreAudio
import Foundation

private func vibePadAudioQueueCallback(
    _ userData: UnsafeMutableRawPointer?,
    _ audioQueue: AudioQueueRef,
    _ buffer: AudioQueueBufferRef
) {
    guard let userData else { return }
    Unmanaged<AudioSink>.fromOpaque(userData).takeUnretainedValue()
        .audioQueueDidFinish(audioQueue, buffer: buffer)
}

/// Owns the single virtual-audio output used by all authenticated VibePad sessions.
/// Network PCM is 24 kHz mono; output is explicitly converted to the native
/// 48 kHz stereo PCM accepted by the VibePadAudio HAL driver. All mutable state lives on `queue`.
final class AudioSink {
    static let targetDeviceUID = "com.xiaoxi.vibepad.audio.device"

    private static let requiredSampleRate: UInt32 = 24_000
    private static let requiredChannels: UInt8 = 1
    private static let requiredFormat: UInt8 = 1 // PCM16LE
    private static let requiredFramesPerPacket: UInt16 = 480
    private static let outputFramesPerPacket = 960
    private static let outputBytesPerPacket = outputFramesPerPacket * 2 * MemoryLayout<Int16>.size
    private static let prebufferPacketCount = 15 // 300 ms; measured LAN TCP RTT spikes to ~330 ms
    private static let outputBufferCount = 40 // 800 ms; exceeds the scheduled-latency cap below
    private static let maximumScheduledFrames = 38_400 // 800 ms at 48 kHz

    private let queue = DispatchQueue(label: "com.xiaoxi.vibepad.audio", qos: .userInitiated)
    private var owner: UUID?
    private var streamID: UInt32 = 0
    private var outputQueue: AudioQueueRef?
    private var allBuffers: [AudioQueueBufferRef] = []
    private var freeBuffers: [AudioQueueBufferRef] = []
    private var inUseBuffers: Set<UInt> = []
    private var pending: [Data] = []
    private var scheduledFrames = 0
    private var playbackStarted = false
    private var lastAudioSequence: UInt32?
    private var lastArrivalNs: UInt64 = 0

    @discardableResult
    func start(
        owner requestedOwner: UUID,
        streamID requestedStreamID: UInt32,
        sampleRate: UInt32,
        channels: UInt8,
        format: UInt8,
        framesPerPacket: UInt16
    ) -> Bool {
        queue.sync {
            guard sampleRate == Self.requiredSampleRate,
                  channels == Self.requiredChannels,
                  format == Self.requiredFormat,
                  framesPerPacket == Self.requiredFramesPerPacket else {
                print("Rejected unsupported VibePad audio format: \(sampleRate) Hz, \(channels) channel(s), format \(format), \(framesPerPacket) frames")
                return false
            }
            guard owner == nil || owner == requestedOwner else {
                print("Rejected VibePad audio start because the VibePadAudio device is already in use")
                return false
            }
            stopLocked(reason: "restart")
            guard Self.targetOutputDeviceExists() else {
                print("VibePad audio unavailable: VibePadAudio UID \(Self.targetDeviceUID) was not found as an output device")
                return false
            }

            var outputFormat = AudioStreamBasicDescription(
                mSampleRate: 48_000,
                mFormatID: kAudioFormatLinearPCM,
                mFormatFlags: kLinearPCMFormatFlagIsSignedInteger | kLinearPCMFormatFlagIsPacked,
                mBytesPerPacket: 4,
                mFramesPerPacket: 1,
                mBytesPerFrame: 4,
                mChannelsPerFrame: 2,
                mBitsPerChannel: 16,
                mReserved: 0
            )
            var createdQueue: AudioQueueRef?
            let createStatus = AudioQueueNewOutput(
                &outputFormat,
                vibePadAudioQueueCallback,
                Unmanaged.passUnretained(self).toOpaque(),
                nil,
                nil,
                0,
                &createdQueue
            )
            guard createStatus == noErr, let createdQueue else {
                print("VibePad audio could not create AudioQueue: OSStatus \(createStatus)")
                return false
            }

            var targetUID: CFString = Self.targetDeviceUID as CFString
            let routeStatus = withUnsafePointer(to: &targetUID) { pointer in
                AudioQueueSetProperty(
                    createdQueue,
                    kAudioQueueProperty_CurrentDevice,
                    pointer,
                    UInt32(MemoryLayout<CFString>.size)
                )
            }
            guard routeStatus == noErr else {
                AudioQueueDispose(createdQueue, true)
                print("VibePad audio could not direct AudioQueue to VibePadAudio: OSStatus \(routeStatus)")
                return false
            }

            var allocated: [AudioQueueBufferRef] = []
            for _ in 0..<Self.outputBufferCount {
                var buffer: AudioQueueBufferRef?
                let status = AudioQueueAllocateBuffer(
                    createdQueue,
                    UInt32(Self.outputBytesPerPacket),
                    &buffer
                )
                guard status == noErr, let buffer else {
                    AudioQueueDispose(createdQueue, true)
                    print("VibePad audio could not allocate AudioQueue buffers: OSStatus \(status)")
                    return false
                }
                allocated.append(buffer)
            }

            owner = requestedOwner
            streamID = requestedStreamID
            outputQueue = createdQueue
            allBuffers = allocated
            freeBuffers = allocated
            let prebufferMs = Self.prebufferPacketCount * Int(Self.requiredFramesPerPacket) * 1000 / Int(Self.requiredSampleRate)
            print("VibePad audio stream \(requestedStreamID) opened on directed VibePadAudio AudioQueue (\(prebufferMs) ms prebuffer)")
            return true
        }
    }

    func enqueue(
        owner requestedOwner: UUID,
        streamID requestedStreamID: UInt32,
        audioSequence: UInt32,
        captureTimeNs: UInt64,
        sampleCount: UInt16,
        pcm16LE: Data
    ) {
        queue.async { [weak self] in
            guard let self,
                  self.owner == requestedOwner,
                  self.streamID == requestedStreamID,
                  self.outputQueue != nil,
                  sampleCount == Self.requiredFramesPerPacket,
                  pcm16LE.count == Int(sampleCount) * 2 else { return }

            if let previous = self.lastAudioSequence, audioSequence != previous &+ 1 {
                print("VibePad audio discontinuity \(previous) -> \(audioSequence) at \(captureTimeNs) ns; rebuffering")
                self.resetPlaybackLocked()
            }
            self.lastAudioSequence = audioSequence
            let nowNs = DispatchTime.now().uptimeNanoseconds
            if self.lastArrivalNs != 0 {
                let gapMs = (nowNs - self.lastArrivalNs) / 1_000_000
                if gapMs > 150 { print("VibePad audio arrival gap \(gapMs) ms") }
            }
            self.lastArrivalNs = nowNs
            let converted = Self.upsampleTo48kStereo(pcm16LE)

            if self.playbackStarted,
               self.scheduledFrames + Self.outputFramesPerPacket > Self.maximumScheduledFrames {
                print("VibePad audio exceeded \(Self.maximumScheduledFrames / 48) ms queued latency; rebuffering latest audio")
                self.resetPlaybackLocked()
            }

            if self.playbackStarted {
                if !self.enqueueOutputLocked(converted) {
                    print("VibePad audio ran out of output buffers; rebuffering")
                    self.resetPlaybackLocked()
                    self.pending.append(converted)
                }
                return
            }

            self.pending.append(converted)
            self.startPlaybackIfReadyLocked()
        }
    }

    func stop(owner requestedOwner: UUID, streamID requestedStreamID: UInt32? = nil, reason: UInt8? = nil) {
        queue.async { [weak self] in
            guard let self, self.owner == requestedOwner else { return }
            if let requestedStreamID, requestedStreamID != self.streamID { return }
            let suffix = reason.map { "remote reason \($0)" } ?? "session closed"
            self.stopLocked(reason: suffix)
        }
    }

    fileprivate func audioQueueDidFinish(_ callbackQueue: AudioQueueRef, buffer: AudioQueueBufferRef) {
        queue.async { [weak self] in
            guard let self, self.outputQueue == callbackQueue else { return }
            let identity = UInt(bitPattern: buffer)
            guard self.inUseBuffers.remove(identity) != nil else { return }
            self.freeBuffers.append(buffer)
            self.scheduledFrames = max(0, self.scheduledFrames - Self.outputFramesPerPacket)
            // A virtual AudioQueue may return buffers as soon as their bytes have
            // entered the driver, before the loopback input exposes those samples.
            // Do not reset here: doing so truncates the VibePadAudio driver's internal pipeline.
            // AudioQueue itself emits silence during a genuine producer gap and
            // resumes when the next packet is enqueued.
        }
    }

    private func startPlaybackIfReadyLocked() {
        guard pending.count >= Self.prebufferPacketCount, let outputQueue else { return }
        let ready = pending
        pending.removeAll(keepingCapacity: true)
        for (index, packet) in ready.enumerated() {
            guard enqueueOutputLocked(packet) else {
                pending.append(contentsOf: ready[index...])
                resetPlaybackLocked(keepingPending: true)
                return
            }
        }
        let status = AudioQueueStart(outputQueue, nil)
        guard status == noErr else {
            print("VibePad AudioQueue failed to start: OSStatus \(status)")
            resetPlaybackLocked()
            return
        }
        playbackStarted = true
    }

    @discardableResult
    private func enqueueOutputLocked(_ data: Data) -> Bool {
        guard let outputQueue, let buffer = freeBuffers.popLast(), data.count == Self.outputBytesPerPacket else {
            return false
        }
        _ = data.withUnsafeBytes { source in
            memcpy(buffer.pointee.mAudioData, source.baseAddress!, data.count)
        }
        buffer.pointee.mAudioDataByteSize = UInt32(data.count)
        let status = AudioQueueEnqueueBuffer(outputQueue, buffer, 0, nil)
        guard status == noErr else {
            freeBuffers.append(buffer)
            print("VibePad AudioQueue enqueue failed: OSStatus \(status)")
            return false
        }
        inUseBuffers.insert(UInt(bitPattern: buffer))
        scheduledFrames += Self.outputFramesPerPacket
        return true
    }

    private func resetPlaybackLocked(keepingPending: Bool = false) {
        if let outputQueue {
            AudioQueuePause(outputQueue)
            AudioQueueReset(outputQueue)
        }
        if !keepingPending { pending.removeAll(keepingCapacity: true) }
        scheduledFrames = 0
        playbackStarted = false
        lastAudioSequence = nil
        // AudioQueueReset returns every enqueued buffer through the callback. The
        // callbacks are serialized behind this method and repopulate freeBuffers.
    }

    private func stopLocked(reason: String) {
        let oldStreamID = streamID
        let oldQueue = outputQueue
        outputQueue = nil // Makes late callbacks harmless before synchronous disposal.
        if let oldQueue {
            AudioQueueStop(oldQueue, true)
            AudioQueueDispose(oldQueue, true)
        }
        allBuffers.removeAll(keepingCapacity: false)
        freeBuffers.removeAll(keepingCapacity: false)
        inUseBuffers.removeAll(keepingCapacity: false)
        pending.removeAll(keepingCapacity: false)
        scheduledFrames = 0
        playbackStarted = false
        lastAudioSequence = nil
        owner = nil
        streamID = 0
        if oldStreamID != 0 { print("VibePad audio stream \(oldStreamID) stopped (\(reason))") }
    }

    private static func upsampleTo48kStereo(_ pcm16LE: Data) -> Data {
        var output = Data(count: outputBytesPerPacket)
        output.withUnsafeMutableBytes { outputRaw in
            pcm16LE.withUnsafeBytes { inputRaw in
                let input = inputRaw.bindMemory(to: UInt8.self)
                let destination = outputRaw.bindMemory(to: UInt8.self)
                for frame in 0..<Int(requiredFramesPerPacket) {
                    let low = input[frame * 2]
                    let high = input[frame * 2 + 1]
                    let firstOutputByte = frame * 8
                    // Duplicate each mono sample into L/R and duplicate it once in
                    // time: 24 kHz mono -> 48 kHz stereo, preserving PCM16LE bytes.
                    for offset in stride(from: 0, to: 8, by: 2) {
                        destination[firstOutputByte + offset] = low
                        destination[firstOutputByte + offset + 1] = high
                    }
                }
            }
        }
        return output
    }

    private static func targetOutputDeviceExists() -> Bool {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(
            AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size
        ) == noErr else { return false }
        var devices = [AudioDeviceID](
            repeating: 0,
            count: Int(size) / MemoryLayout<AudioDeviceID>.size
        )
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &devices
        ) == noErr else { return false }
        return devices.contains { deviceID in
            stringProperty(deviceID, selector: kAudioDevicePropertyDeviceUID) == targetDeviceUID &&
                hasOutputStreams(deviceID)
        }
    }

    private static func stringProperty(_ deviceID: AudioDeviceID, selector: AudioObjectPropertySelector) -> String? {
        var address = AudioObjectPropertyAddress(
            mSelector: selector,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var value: Unmanaged<CFString>?
        var size = UInt32(MemoryLayout<Unmanaged<CFString>?>.size)
        guard AudioObjectGetPropertyData(deviceID, &address, 0, nil, &size, &value) == noErr else {
            return nil
        }
        return value?.takeUnretainedValue() as String?
    }

    private static func hasOutputStreams(_ deviceID: AudioDeviceID) -> Bool {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyStreams,
            mScope: kAudioDevicePropertyScopeOutput,
            mElement: kAudioObjectPropertyElementMain
        )
        var size: UInt32 = 0
        return AudioObjectGetPropertyDataSize(deviceID, &address, 0, nil, &size) == noErr &&
            size >= UInt32(MemoryLayout<AudioStreamID>.size)
    }
}
