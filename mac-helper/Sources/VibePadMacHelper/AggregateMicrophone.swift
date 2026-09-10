import CoreAudio
import Foundation

/// Publishes TFFAudio through a public Aggregate Device. Typeless intentionally
/// hides virtual-transport devices, but accepts an Aggregate input device.
/// The aggregate is persistent at the CoreAudio layer so Typeless keeps a stable
/// UID across helper restarts; the helper only creates it when it is missing.
enum AggregateMicrophone {
    static let name = "VibePad Microphone"
    static let uid = "com.xiaoxi.vibepad.microphone"
    private static let tffUID = AudioSink.targetDeviceUID

    @discardableResult
    static func ensureAvailable() -> Bool {
        if let existing = findDevice(uid: uid), inputChannelCount(existing) > 0 {
            print("VibePad aggregate microphone is available (device \(existing))")
            return true
        }
        guard findDevice(uid: tffUID) != nil else {
            print("VibePad aggregate microphone unavailable: TFFAudio was not found")
            return false
        }

        let description: [String: Any] = [
            kAudioAggregateDeviceNameKey: name,
            kAudioAggregateDeviceUIDKey: uid,
            kAudioAggregateDeviceSubDeviceListKey: [[kAudioSubDeviceUIDKey: tffUID]],
            kAudioAggregateDeviceMasterSubDeviceKey: tffUID,
            kAudioAggregateDeviceIsPrivateKey: false,
            kAudioAggregateDeviceIsStackedKey: false
        ]
        var aggregate: AudioDeviceID = 0
        let status = AudioHardwareCreateAggregateDevice(description as CFDictionary, &aggregate)
        guard status == noErr else {
            print("Could not create VibePad aggregate microphone: OSStatus \(status)")
            return false
        }
        guard inputChannelCount(aggregate) > 0 else {
            AudioHardwareDestroyAggregateDevice(aggregate)
            print("Created VibePad aggregate microphone had no input channels")
            return false
        }
        print("Created VibePad aggregate microphone (device \(aggregate), UID \(uid))")
        return true
    }

    private static func findDevice(uid requestedUID: String) -> AudioDeviceID? {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioHardwarePropertyDevices,
            mScope: kAudioObjectPropertyScopeGlobal,
            mElement: kAudioObjectPropertyElementMain
        )
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(
            AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size
        ) == noErr else { return nil }
        var devices = [AudioDeviceID](
            repeating: 0,
            count: Int(size) / MemoryLayout<AudioDeviceID>.size
        )
        guard AudioObjectGetPropertyData(
            AudioObjectID(kAudioObjectSystemObject), &address, 0, nil, &size, &devices
        ) == noErr else { return nil }
        return devices.first { stringProperty($0, selector: kAudioDevicePropertyDeviceUID) == requestedUID }
    }

    private static func stringProperty(
        _ deviceID: AudioDeviceID,
        selector: AudioObjectPropertySelector
    ) -> String? {
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

    private static func inputChannelCount(_ deviceID: AudioDeviceID) -> Int {
        var address = AudioObjectPropertyAddress(
            mSelector: kAudioDevicePropertyStreamConfiguration,
            mScope: kAudioDevicePropertyScopeInput,
            mElement: kAudioObjectPropertyElementMain
        )
        var size: UInt32 = 0
        guard AudioObjectGetPropertyDataSize(deviceID, &address, 0, nil, &size) == noErr else { return 0 }
        let storage = UnsafeMutableRawPointer.allocate(
            byteCount: Int(size),
            alignment: MemoryLayout<AudioBufferList>.alignment
        )
        defer { storage.deallocate() }
        guard AudioObjectGetPropertyData(deviceID, &address, 0, nil, &size, storage) == noErr else { return 0 }
        return UnsafeMutableAudioBufferListPointer(
            storage.assumingMemoryBound(to: AudioBufferList.self)
        ).reduce(0) { $0 + Int($1.mNumberChannels) }
    }
}
