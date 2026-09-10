package com.xiaoxi.vibepad.input

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean

/** Captures the tablet microphone only while explicitly enabled by the user. */
class MicrophoneStreamer(
    private val sink: WifiInputSink,
    private val stateListener: (State, String?) -> Unit = { _, _ -> },
) {
    enum class State { IDLE, STARTING, RECORDING, ERROR }

    private val running = AtomicBoolean(false)
    private val stateLock = Any()
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var captureThread: Thread? = null
    @Volatile private var streamId = 0L

    val isRecording: Boolean get() = running.get()

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        synchronized(stateLock) {
            if (running.get()) return true
            stateListener(State.STARTING, null)
            val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
            if (minimum <= 0) {
                stateListener(State.ERROR, "平板不支持 24 kHz 麦克风录音")
                return false
            }
            val candidate = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    CHANNEL_CONFIG,
                    AUDIO_FORMAT,
                    maxOf(minimum * 2, PACKET_BYTES * 4),
                )
            } catch (error: Exception) {
                Log.e(TAG, "Unable to create AudioRecord", error)
                stateListener(State.ERROR, "无法打开平板麦克风")
                return false
            }
            if (candidate.state != AudioRecord.STATE_INITIALIZED) {
                candidate.release()
                stateListener(State.ERROR, "平板麦克风初始化失败")
                return false
            }
            try {
                candidate.startRecording()
            } catch (error: Exception) {
                candidate.release()
                Log.e(TAG, "Unable to start AudioRecord", error)
                stateListener(State.ERROR, "无法开始麦克风录音")
                return false
            }
            if (candidate.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                candidate.release()
                stateListener(State.ERROR, "平板麦克风未进入录音状态")
                return false
            }

            val id = SecureRandom().nextInt().toLong() and UINT32_MASK
            recorder = candidate
            streamId = id
            running.set(true)
            sink.startAudio(id, SAMPLE_RATE, CHANNELS, FORMAT_PCM16_LE, FRAMES_PER_PACKET)
            captureThread = Thread({ capture(candidate, id) }, "vibepad-mic-capture").apply {
                isDaemon = true
                start()
            }
            stateListener(State.RECORDING, null)
            return true
        }
    }

    fun stop(reason: Int = STOP_REASON_USER) {
        val activeRecorder: AudioRecord?
        val activeThread: Thread?
        val activeStreamId: Long
        synchronized(stateLock) {
            if (!running.getAndSet(false)) {
                if (captureThread == null) stateListener(State.IDLE, null)
                return
            }
            activeRecorder = recorder
            activeThread = captureThread
            activeStreamId = streamId
        }
        try {
            activeRecorder?.stop()
        } catch (_: IllegalStateException) { }
        if (activeThread !== Thread.currentThread()) {
            try { activeThread?.join(STOP_JOIN_TIMEOUT_MS) } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        synchronized(stateLock) {
            if (recorder === activeRecorder) recorder = null
            if (captureThread === activeThread) captureThread = null
        }
        try { activeRecorder?.release() } catch (_: Exception) { }
        sink.stopAudio(activeStreamId, reason)
        stateListener(State.IDLE, null)
    }

    private fun capture(activeRecorder: AudioRecord, activeStreamId: Long) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        var sequence = 0L
        val packet = ByteArray(PACKET_BYTES)
        try {
            while (running.get() && recorder === activeRecorder) {
                var filled = 0
                while (filled < packet.size && running.get()) {
                    val count = activeRecorder.read(
                        packet,
                        filled,
                        packet.size - filled,
                        AudioRecord.READ_BLOCKING,
                    )
                    if (count < 0) throw IllegalStateException("AudioRecord read failed: $count")
                    if (count == 0) continue
                    filled += count
                }
                if (filled != packet.size || !running.get()) break
                sink.sendAudioData(
                    streamId = activeStreamId,
                    audioSequence = sequence++ and UINT32_MASK,
                    captureTimeNs = SystemClock.elapsedRealtimeNanos(),
                    sampleCount = FRAMES_PER_PACKET,
                    pcm16Le = packet.copyOf(),
                )
            }
        } catch (error: Exception) {
            if (running.get()) {
                Log.e(TAG, "Microphone capture failed", error)
                stateListener(State.ERROR, "麦克风录音中断")
                stop(STOP_REASON_CAPTURE_ERROR)
            }
        }
    }

    companion object {
        const val STOP_REASON_USER = 0
        const val STOP_REASON_LIFECYCLE = 1
        const val STOP_REASON_DISCONNECTED = 2
        const val STOP_REASON_CAPTURE_ERROR = 3
        private const val TAG = "VibePadMicrophone"
        private const val SAMPLE_RATE = 24_000
        private const val CHANNELS = 1
        private const val FORMAT_PCM16_LE = 1
        private const val FRAMES_PER_PACKET = 480
        private const val PACKET_BYTES = FRAMES_PER_PACKET * 2
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
        private const val UINT32_MASK = 0xffff_ffffL
        private const val STOP_JOIN_TIMEOUT_MS = 500L
    }
}
