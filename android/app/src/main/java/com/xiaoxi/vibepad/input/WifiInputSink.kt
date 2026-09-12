package com.xiaoxi.vibepad.input

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Low-latency LAN input transport. A Mac helper advertises [_vibepad._tcp.] with Bonjour;
 * VibePad discovers it, opens a TCP_NODELAY socket, and sends compact ordered binary frames.
 */
class WifiInputSink(
    context: Context,
    private val statusListener: StatusListener = StatusListener { },
    private val remoteDataListener: RemoteDataListener = object : RemoteDataListener {},
    private val audioDisconnectListener: () -> Unit = {},
) : InputSink, Closeable {

    fun interface StatusListener {
        fun onStatusChanged(state: State)
    }

    data class State(val status: Status, val detail: String? = null)

    enum class Status {
        STARTING, DISCOVERING, CONNECTING, AUTHENTICATING, PAIRING_REQUIRED, PAIRING,
        CONNECTED, DISCONNECTED, ERROR, CLOSED
    }

    private data class Endpoint(val address: InetAddress, val port: Int, val name: String)
    private data class Event(val type: Int, val payload: ByteArray)
    private data class TouchBarFrameAssembly(
        val frameId: Long,
        val width: Int,
        val height: Int,
        val codec: Int,
        val chunks: Array<ByteArray?>,
        var receivedChunks: Int = 0,
        var receivedBytes: Int = 0,
    )

    private val appContext = context.applicationContext
    private val pairingStore = PairingStore(appContext)
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val io: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "vibepad-wifi-io").apply { isDaemon = true }
    }
    private val connectInFlight = AtomicBoolean(false)
    private val queue = ArrayDeque<Event>()
    private val queueLock = Any()
    private val drainScheduled = AtomicBoolean(false)
    private val pendingAudioWrites = AtomicInteger(0)
    private val stateLock = Any()
    private val touchBarFrameLock = Any()

    @Volatile private var closed = false
    @Volatile private var connected = false
    @Volatile private var transportConnected = false
    @Volatile private var socket: Socket? = null
    @Volatile private var output: DataOutputStream? = null
    @Volatile private var lastEndpoint: Endpoint? = null
    @Volatile private var resolving = false
    @Volatile private var serverNonce: ByteArray? = null
    @Volatile private var touchBarSubscribed = false
    // Set when the Mac answers our authentication with PAIRING_REQUIRED (for example
    // after "清除所有配对"). We then stop retrying the stale key on every reconnect and
    // ask the user to pair again instead of toasting a raw error code every 90 s.
    @Volatile private var secretRejectedByMac = false
    private var authClientNonce: ByteArray? = null
    private var pairingExchange: PairingExchange? = null
    private var touchBarFrameAssembly: TouchBarFrameAssembly? = null
    private var sequence = 1
    @Volatile private var lastPingSequence = 0
    @Volatile private var lastPingSentAtNanos = 0L
    private var rttSamples = 0
    private var rttTotalMs = 0.0
    private var rttMaxMs = 0.0
    private var discoveryStarted = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var highPerformanceLock: WifiManager.WifiLock? = null

    override val isConnected: Boolean get() = connected && !closed
    /** True only while the stored pairing secret is still accepted by the Mac. */
    val isPaired: Boolean get() = pairingStore.hasSecret && !secretRejectedByMac

    private val discoveryListener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = publish(Status.DISCOVERING, "正在查找 Mac")

        override fun onServiceFound(service: NsdServiceInfo) {
            if (closed || service.serviceType != SERVICE_TYPE || resolving || transportConnected) return
            resolving = true
            try {
                nsd.resolveService(service, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        resolving = false
                        Log.w(TAG, "Bonjour resolve failed: $errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        resolving = false
                        val host = serviceInfo.host ?: return
                        val endpoint = Endpoint(host, serviceInfo.port, serviceInfo.serviceName)
                        lastEndpoint = endpoint
                        connect(endpoint)
                    }
                })
            } catch (error: RuntimeException) {
                resolving = false
                Log.w(TAG, "Unable to resolve Bonjour service", error)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            if (service.serviceName == lastEndpoint?.name && !transportConnected) publish(Status.DISCONNECTED, "Mac 服务已离线")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            discoveryStarted = false
            publish(Status.ERROR, "局域网发现启动失败 ($errorCode)")
            scheduleDiscoveryRestart()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onDiscoveryStopped(serviceType: String) { discoveryStarted = false }
    }

    init {
        publish(Status.STARTING)
        acquireMulticastLock()
        startDiscovery()
        io.scheduleAtFixedRate({
            if (connected) enqueue(Event(TYPE_PING, longPayload(SystemClock.elapsedRealtime())))
            else if (!transportConnected) lastEndpoint?.let(::connect)
        }, HEARTBEAT_INTERVAL_MS, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS)
    }

    override fun move(dx: Int, dy: Int) = enqueueMotion(TYPE_MOVE, dx, dy)

    override fun mouseButton(buttonMask: Int, pressed: Boolean) = enqueue(
        Event(TYPE_BUTTON, byteArrayOf(buttonMask.toByte(), if (pressed) 1 else 0)),
    )

    override fun scroll(vertical: Int, horizontal: Int) = enqueueMotion(TYPE_SCROLL, vertical, horizontal)

    override fun gesture(gesture: TrackpadGesture) =
        enqueue(Event(TYPE_GESTURE, byteArrayOf(gesture.wireValue.toByte())))

    override fun key(keyUsage: Int, modifierMask: Int, pressed: Boolean) {
        enqueue(Event(TYPE_KEY, byteArrayOf(
            (keyUsage ushr 8).toByte(), keyUsage.toByte(), modifierMask.toByte(), if (pressed) 1 else 0,
        )))
    }

    override fun releaseAll() = enqueue(Event(TYPE_RELEASE_ALL, ByteArray(0)))

    override fun requestApps() = enqueue(Event(TYPE_APPS_REQUEST, ByteArray(0)))

    fun setTouchBarSubscribed(enabled: Boolean) {
        touchBarSubscribed = enabled
        synchronized(touchBarFrameLock) {
            if (!enabled) touchBarFrameAssembly = null
        }
        if (isConnected) {
            enqueue(Event(TYPE_TOUCH_BAR_SUBSCRIBE, byteArrayOf(if (enabled) 1 else 0)))
        }
    }

    fun sendTouchBarEvent(phase: Int, normalizedX: Int, normalizedY: Int) {
        if (phase !in TOUCH_PHASE_DOWN..TOUCH_PHASE_UP || !isConnected) return
        val x = normalizedX.coerceIn(0, USHRT_MAX)
        val y = normalizedY.coerceIn(0, USHRT_MAX)
        val event = Event(TYPE_TOUCH_BAR_EVENT, byteArrayOf(
            phase.toByte(),
            0,
            (x ushr 8).toByte(),
            x.toByte(),
            (y ushr 8).toByte(),
            y.toByte(),
        ))
        synchronized(queueLock) {
            // Rendering can produce many ACTION_MOVE events between drain passes. Only
            // the newest consecutive drag coordinate matters; DOWN and UP are never merged.
            val tail = queue.lastOrNull()
            if (phase == TOUCH_PHASE_DRAGGED &&
                tail?.type == TYPE_TOUCH_BAR_EVENT &&
                tail.payload.firstOrNull()?.toInt() == TOUCH_PHASE_DRAGGED
            ) {
                queue.removeLast()
            }
            addBounded(event)
        }
        scheduleDrain()
    }

    fun startAudio(
        streamId: Long,
        sampleRate: Int,
        channels: Int,
        format: Int,
        framesPerPacket: Int,
    ) {
        if (!isConnected || sampleRate <= 0 || channels !in 1..255 ||
            format !in 1..255 || framesPerPacket !in 1..USHRT_MAX
        ) return
        val payload = ByteArray(12)
        putUInt32(payload, 0, streamId)
        putUInt32(payload, 4, sampleRate.toLong())
        payload[8] = channels.toByte()
        payload[9] = format.toByte()
        putUInt16(payload, 10, framesPerPacket)
        submitAudioWrite(Event(TYPE_AUDIO_START, payload))
    }

    fun sendAudioData(
        streamId: Long,
        audioSequence: Long,
        captureTimeNs: Long,
        sampleCount: Int,
        pcm16Le: ByteArray,
    ) {
        if (!isConnected || sampleCount !in 1..USHRT_MAX ||
            pcm16Le.size != sampleCount * PCM16_BYTES_PER_SAMPLE
        ) return
        if (pendingAudioWrites.incrementAndGet() > MAX_PENDING_AUDIO_WRITES) {
            pendingAudioWrites.decrementAndGet()
            return
        }
        val payload = ByteArray(AUDIO_DATA_HEADER_BYTES + pcm16Le.size)
        putUInt32(payload, 0, streamId)
        putUInt32(payload, 4, audioSequence)
        putUInt64(payload, 8, captureTimeNs)
        putUInt16(payload, 16, sampleCount)
        pcm16Le.copyInto(payload, AUDIO_DATA_HEADER_BYTES)
        try {
            io.execute {
                try {
                    if (connected) writeFrame(Event(TYPE_AUDIO_DATA, payload))
                } catch (error: Exception) {
                    Log.w(TAG, "Audio data send failed", error)
                    socket?.let(::handleDisconnect)
                } finally {
                    pendingAudioWrites.decrementAndGet()
                }
            }
        } catch (_: RuntimeException) {
            pendingAudioWrites.decrementAndGet()
        }
    }

    fun stopAudio(streamId: Long, reason: Int) {
        val payload = ByteArray(5)
        putUInt32(payload, 0, streamId)
        payload[4] = reason.coerceIn(0, 255).toByte()
        // The recorder is stopped and joined before this is called. Since all audio writes
        // use the same executor, STOP stays behind every DATA frame already submitted.
        submitAudioWrite(Event(TYPE_AUDIO_STOP, payload))
    }

    override fun launchApp(bundleId: String) {
        val bytes = bundleId.toByteArray(Charsets.UTF_8)
        if (bytes.size in 1..MAX_APP_ID_BYTES) enqueue(Event(TYPE_LAUNCH_APP, bytes))
    }

    override fun requestPairing() {
        val nonce = serverNonce
        if (!transportConnected || nonce == null) {
            remoteDataListener.onPairingMessage("尚未连接到 Mac Helper，请稍后再试")
            return
        }
        io.execute {
            try {
                val exchange = PairingExchange(pairingStore.clientId).also { pairingExchange = it }
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(63)
                publish(Status.PAIRING, "正在请求安全配对")
                writeFrame(Event(TYPE_PAIR_REQUEST, exchange.pairRequest(deviceName)))
            } catch (error: Exception) {
                Log.e(TAG, "Unable to start pairing", error)
                publish(Status.ERROR, "无法开始配对")
                remoteDataListener.onPairingMessage("配对初始化失败")
            }
        }
    }

    override fun close() {
        if (closed) return
        // Mark closed first so the reader thread and any late callbacks stop scheduling
        // work. The socket is closed synchronously here: submitting it to `io` right
        // before shutdownNow() would usually cancel it while the drain loop was still
        // busy, leaving the socket (and the Mac session) open until process death.
        closed = true
        if (discoveryStarted) {
            try { nsd.stopServiceDiscovery(discoveryListener) } catch (_: RuntimeException) { }
        }
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
        synchronized(queueLock) { queue.clear() }
        io.shutdownNow()
        // The Mac helper releases every held button and modifier when the connection
        // drops, so closing the socket is the reliable way to send "release all".
        synchronized(stateLock) { closeSocket() }
        publish(Status.CLOSED)
    }

    private fun acquireMulticastLock() {
        multicastLock = wifi?.createMulticastLock("vibepad-bonjour")?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun startDiscovery() {
        if (closed || discoveryStarted) return
        try {
            discoveryStarted = true
            nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (error: RuntimeException) {
            discoveryStarted = false
            publish(Status.ERROR, "无法启动局域网发现")
            scheduleDiscoveryRestart()
        }
    }

    private fun scheduleDiscoveryRestart() {
        if (closed) return
        try {
            io.schedule({ startDiscovery() }, RECONNECT_SECONDS, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) { }
    }

    private fun connect(endpoint: Endpoint) {
        if (closed || transportConnected || !connectInFlight.compareAndSet(false, true)) return
        publish(Status.CONNECTING, "正在连接 ${endpoint.name}")
        io.execute {
            try {
                val candidate = Socket()
                candidate.tcpNoDelay = true
                candidate.keepAlive = true
                candidate.sendBufferSize = 16 * 1024
                candidate.connect(InetSocketAddress(endpoint.address, endpoint.port), CONNECT_TIMEOUT_MS)
                if (closed) {
                    candidate.close()
                    return@execute
                }
                synchronized(stateLock) {
                    closeSocket()
                    socket = candidate
                    output = DataOutputStream(candidate.getOutputStream())
                    sequence = 1
                    transportConnected = true
                }
                synchronized(queueLock) { queue.clear() }
                publish(Status.AUTHENTICATING, "正在安全认证")
                startReader(candidate)
            } catch (error: Exception) {
                Log.w(TAG, "Mac helper connection failed", error)
                synchronized(stateLock) { closeSocket() }
                publish(Status.DISCONNECTED, "等待 Mac Wi-Fi 服务")
            } finally {
                connectInFlight.set(false)
            }
        }
    }

    private fun startReader(activeSocket: Socket) {
        Thread({
            try {
                val input = DataInputStream(activeSocket.getInputStream())
                while (!closed && activeSocket === socket) {
                    if (input.readUnsignedByte() != MAGIC_1 || input.readUnsignedByte() != MAGIC_2) throw EOFException("bad magic")
                    val version = input.readUnsignedByte()
                    if (version != PROTOCOL_VERSION) throw EOFException("unsupported protocol $version")
                    val type = input.readUnsignedByte()
                    val length = input.readUnsignedShort()
                    val frameSequence = input.readInt()
                    val payload = ByteArray(length)
                    input.readFully(payload)
                    when (type) {
                        TYPE_SERVER_CHALLENGE -> handleServerChallenge(payload)
                        TYPE_AUTHENTICATION_OK -> handleAuthenticationOK(payload)
                        TYPE_PAIR_OFFER -> handlePairOffer(payload)
                        TYPE_PAIR_ACCEPT -> handlePairAccept(payload)
                        TYPE_PAIR_REJECT -> handlePairFailure(payload, "Mac 拒绝了配对请求")
                        TYPE_PAIRING_DISABLED -> handlePairFailure(payload, "请先在 Mac 上允许配对")
                        TYPE_PING -> if (connected) enqueue(Event(TYPE_PONG, payload))
                        TYPE_PONG -> if (connected && frameSequence == lastPingSequence) {
                            recordRtt((SystemClock.elapsedRealtimeNanos() - lastPingSentAtNanos) / 1_000_000.0)
                            parseHealth(payload)
                        }
                        TYPE_USAGE -> if (connected) parseUsage(payload)
                        TYPE_APPS_BEGIN -> if (connected) remoteDataListener.onAppCatalogStarted()
                        TYPE_APP_ITEM -> if (connected) parseRemoteApp(payload)
                        TYPE_APPS_END -> if (connected) remoteDataListener.onAppCatalogFinished()
                        TYPE_TOUCH_BAR_FRAME -> if (connected) parseTouchBarFrame(payload)
                        TYPE_TOUCH_BAR_FRAME_CHUNK -> if (connected) parseTouchBarFrameChunk(payload)
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Secure Wi-Fi reader stopped", error)
                handleDisconnect(activeSocket)
            }
        }, "vibepad-wifi-reader").apply { isDaemon = true; start() }
    }

    private fun handleServerChallenge(payload: ByteArray) {
        if (payload.size != NONCE_BYTES) throw EOFException("invalid server challenge")
        serverNonce = payload.copyOf()
        when {
            secretRejectedByMac -> publish(Status.PAIRING_REQUIRED, "Mac 已不再认识这台平板，请重新配对")
            pairingStore.hasSecret -> sendAuthentication()
            else -> publish(Status.PAIRING_REQUIRED, "需要与这台 Mac 配对")
        }
    }

    private fun sendAuthentication() {
        val challenge = serverNonce ?: return
        val clientNonce = ByteArray(NONCE_BYTES).also(SecureRandom()::nextBytes)
        authClientNonce = clientNonce
        val authenticationData = "client-auth".toByteArray() + challenge + clientNonce + pairingStore.clientId
        val proof = pairingStore.hmac(authenticationData)
        publish(Status.AUTHENTICATING, "正在验证已配对设备")
        sendProtocolEvent(Event(TYPE_AUTHENTICATE, pairingStore.clientId + clientNonce + proof))
    }

    private fun handleAuthenticationOK(payload: ByteArray) {
        val challenge = serverNonce ?: throw EOFException("missing server challenge")
        val clientNonce = authClientNonce ?: throw EOFException("missing client challenge")
        val authenticationData = "server-auth".toByteArray() + challenge + clientNonce + pairingStore.clientId
        if (payload.size != HMAC_BYTES || !constantTimeEqual(pairingStore.hmac(authenticationData), payload)) {
            throw EOFException("invalid server authentication")
        }
        completeAuthentication()
    }

    private fun handlePairOffer(payload: ByteArray) {
        val exchange = pairingExchange ?: throw EOFException("unexpected pairing offer")
        val code = exchange.acceptOffer(payload)
        remoteDataListener.onPairingCode(code)
    }

    private fun handlePairAccept(payload: ByteArray) {
        val secret = pairingExchange?.verifyPairAccept(payload)
            ?: throw EOFException("invalid pairing proof")
        pairingStore.saveSecret(secret)
        pairingExchange = null
        secretRejectedByMac = false
        remoteDataListener.onPairingMessage("配对成功，已建立加密身份", true)
        completeAuthentication()
    }

    private fun handlePairFailure(payload: ByteArray, fallback: String) {
        pairingExchange = null
        val code = payload.toString(Charsets.UTF_8).trim()
        if (code == CODE_PAIRING_REQUIRED) secretRejectedByMac = true
        val message = pairingFailureMessage(code, fallback)
        publish(Status.PAIRING_REQUIRED, message)
        remoteDataListener.onPairingMessage(message)
    }

    /** The helper sends short machine-readable codes; never show them verbatim. */
    private fun pairingFailureMessage(code: String, fallback: String): String = when (code) {
        "" -> fallback
        CODE_PAIRING_REQUIRED -> "Mac 已不再认识这台平板，请在设置页重新配对"
        "INVALID_REQUEST" -> "配对请求无效，请重试"
        "PAIRING_REJECTED" -> "Mac 拒绝了本次配对"
        "KEY_AGREEMENT_FAILED" -> "密钥协商失败，请重试"
        else -> if (code.all { it.isUpperCase() || it == '_' }) fallback else code
    }

    private fun completeAuthentication() {
        if (connected) return
        connected = true
        acquireHighPerformanceLock()
        publish(Status.CONNECTED, "5GHz Wi-Fi · ${lastEndpoint?.name ?: "Mac"}")
        enqueue(Event(
            TYPE_TOUCH_BAR_SUBSCRIBE,
            byteArrayOf(if (touchBarSubscribed) 1 else 0),
        ))
    }

    private fun sendProtocolEvent(event: Event) {
        if (closed) return
        try {
            io.execute {
                try {
                    if (transportConnected) writeFrame(event)
                } catch (error: Exception) {
                    Log.w(TAG, "Secure protocol send failed", error)
                    socket?.let(::handleDisconnect)
                }
            }
        } catch (_: RejectedExecutionException) { }
    }

    private fun submitAudioWrite(event: Event) {
        if (!isConnected) return
        try {
            io.execute {
                try {
                    if (connected) writeFrame(event)
                } catch (error: Exception) {
                    Log.w(TAG, "Audio control send failed", error)
                    socket?.let(::handleDisconnect)
                }
            }
        } catch (_: RuntimeException) { }
    }

    private fun enqueueMotion(type: Int, first: Int, second: Int) {
        if (!isConnected) return
        synchronized(queueLock) {
            val tail = queue.lastOrNull()
            if (tail?.type == type && tail.payload.size == 4) {
                val a = readShort(tail.payload, 0) + first
                val b = readShort(tail.payload, 2) + second
                queue.removeLast()
                queue.addLast(Event(type, shortPairPayload(a, b)))
            } else {
                addBounded(Event(type, shortPairPayload(first, second)))
            }
        }
        scheduleDrain()
    }

    private fun enqueue(event: Event) {
        if (!isConnected && event.type != TYPE_RELEASE_ALL) return
        synchronized(queueLock) { addBounded(event) }
        scheduleDrain()
    }

    private fun addBounded(event: Event) {
        if (queue.size >= MAX_QUEUE_SIZE) {
            val iterator = queue.iterator()
            var removedMotion = false
            while (iterator.hasNext()) {
                val queued = iterator.next()
                if (queued.type == TYPE_MOVE ||
                    queued.type == TYPE_SCROLL ||
                    queued.type == TYPE_PING ||
                    isTouchBarDrag(queued)
                ) {
                    iterator.remove()
                    removedMotion = true
                    break
                }
            }
            if (!removedMotion && (event.type == TYPE_MOVE ||
                    event.type == TYPE_SCROLL ||
                    event.type == TYPE_PING ||
                    isTouchBarDrag(event))
            ) {
                return
            }
        }
        queue.addLast(event)
    }

    private fun isTouchBarDrag(event: Event): Boolean =
        event.type == TYPE_TOUCH_BAR_EVENT &&
            event.payload.firstOrNull()?.toInt() == TOUCH_PHASE_DRAGGED

    private fun parseHealth(payload: ByteArray) {
        if (payload.isEmpty()) return
        try {
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            remoteDataListener.onHelperHealth(HelperHealth(
                accessibilityTrusted = json.optBoolean("accessibilityTrusted", false),
                helperVersion = json.optString("helperVersion", ""),
                protocolVersion = json.optInt("protocolVersion", PROTOCOL_VERSION),
                lastInputAgeMs = json.optLongOrNull("lastInputAgeMs"),
                mouseButtons = json.optInt("mouseButtons", 0),
                modifiers = json.optInt("modifiers", 0),
            ))
        } catch (error: Exception) {
            Log.w(TAG, "Invalid helper health payload", error)
        }
    }

    private fun parseUsage(payload: ByteArray) {
        try {
            val root = JSONObject(payload.toString(Charsets.UTF_8))
            val claude = root.optJSONObject("claude")
            val codex = root.optJSONObject("codex")
            remoteDataListener.onUsageSnapshot(UsageSnapshot(
                claudeFiveHour = usageWindow(claude?.optJSONObject("five_hour")),
                claudeSevenDay = usageWindow(claude?.optJSONObject("seven_day")),
                // Fable is intentionally blank unless the local normalized endpoint
                // exposes a real field. We never infer it from another quota.
                claudeFable = usageWindow(claude?.optJSONObject("fable_5")),
                codexFiveHour = usageWindow(codex?.optJSONObject("five_hour")),
                codexWeekly = usageWindow(codex?.optJSONObject("weekly")),
            ))
        } catch (error: Exception) {
            Log.w(TAG, "Invalid usage payload", error)
        }
    }

    private fun usageWindow(json: JSONObject?): UsageWindow = UsageWindow(
        usedPercent = json?.optIntOrNull("used_pct"),
        resetsAt = json?.opt("reset_at")?.takeUnless { it == JSONObject.NULL }?.toString(),
    )

    private fun parseRemoteApp(payload: ByteArray) {
        try {
            val json = JSONObject(payload.toString(Charsets.UTF_8))
            val icon = json.optString("icon", "")
                .takeIf(String::isNotEmpty)
                ?.let { Base64.decode(it, Base64.DEFAULT) }
            remoteDataListener.onRemoteApp(RemoteApp(
                name = json.getString("name"),
                bundleId = json.getString("bundleId"),
                iconPng = icon,
            ))
        } catch (error: Exception) {
            Log.w(TAG, "Invalid app catalog item", error)
        }
    }

    private fun parseTouchBarFrame(payload: ByteArray) {
        if (payload.size < TOUCH_BAR_FRAME_HEADER_BYTES) {
            Log.w(TAG, "Ignoring short Touch Bar frame")
            return
        }
        val frameId = readUInt32(payload, 0)
        val width = readUInt16(payload, 4)
        val height = readUInt16(payload, 6)
        val codec = payload[8].toInt() and 0xff
        val dataSize = payload.size - TOUCH_BAR_FRAME_HEADER_BYTES
        if (!validTouchBarMetadata(width, height, codec) ||
            dataSize <= 0 ||
            dataSize > MAX_TOUCH_BAR_FRAME_BYTES
        ) {
            Log.w(TAG, "Ignoring invalid Touch Bar frame id=$frameId size=$dataSize")
            return
        }
        synchronized(touchBarFrameLock) { touchBarFrameAssembly = null }
        remoteDataListener.onTouchBarFrame(TouchBarFrame(
            frameId = frameId,
            width = width,
            height = height,
            codec = codec,
            bytes = payload.copyOfRange(TOUCH_BAR_FRAME_HEADER_BYTES, payload.size),
        ))
    }

    private fun parseTouchBarFrameChunk(payload: ByteArray) {
        if (payload.size < TOUCH_BAR_CHUNK_HEADER_BYTES) {
            Log.w(TAG, "Ignoring short Touch Bar frame chunk")
            return
        }
        val frameId = readUInt32(payload, 0)
        val width = readUInt16(payload, 4)
        val height = readUInt16(payload, 6)
        val codec = payload[8].toInt() and 0xff
        val chunkIndex = readUInt16(payload, 9)
        val chunkCount = readUInt16(payload, 11)
        val chunkSize = payload.size - TOUCH_BAR_CHUNK_HEADER_BYTES
        if (!validTouchBarMetadata(width, height, codec) ||
            chunkCount !in 1..MAX_TOUCH_BAR_CHUNKS ||
            chunkIndex !in 0 until chunkCount ||
            chunkSize <= 0 ||
            chunkSize > MAX_TOUCH_BAR_FRAME_BYTES
        ) {
            Log.w(TAG, "Ignoring invalid Touch Bar chunk id=$frameId index=$chunkIndex/$chunkCount")
            return
        }

        var completed: TouchBarFrame? = null
        synchronized(touchBarFrameLock) {
            var assembly = touchBarFrameAssembly
            if (assembly == null || assembly.frameId != frameId) {
                assembly = TouchBarFrameAssembly(
                    frameId = frameId,
                    width = width,
                    height = height,
                    codec = codec,
                    chunks = arrayOfNulls(chunkCount),
                )
                touchBarFrameAssembly = assembly
            }
            if (assembly.width != width ||
                assembly.height != height ||
                assembly.codec != codec ||
                assembly.chunks.size != chunkCount
            ) {
                Log.w(TAG, "Discarding inconsistent Touch Bar chunks for id=$frameId")
                touchBarFrameAssembly = null
                return
            }
            if (assembly.chunks[chunkIndex] == null) {
                if (assembly.receivedBytes + chunkSize > MAX_TOUCH_BAR_FRAME_BYTES) {
                    Log.w(TAG, "Discarding oversized Touch Bar frame id=$frameId")
                    touchBarFrameAssembly = null
                    return
                }
                assembly.chunks[chunkIndex] =
                    payload.copyOfRange(TOUCH_BAR_CHUNK_HEADER_BYTES, payload.size)
                assembly.receivedChunks++
                assembly.receivedBytes += chunkSize
            }
            if (assembly.receivedChunks == chunkCount) {
                val bytes = ByteArray(assembly.receivedBytes)
                var offset = 0
                for (chunk in assembly.chunks) {
                    val part = chunk ?: return
                    part.copyInto(bytes, offset)
                    offset += part.size
                }
                completed = TouchBarFrame(frameId, width, height, codec, bytes)
                touchBarFrameAssembly = null
            }
        }
        completed?.let(remoteDataListener::onTouchBarFrame)
    }

    private fun validTouchBarMetadata(width: Int, height: Int, codec: Int): Boolean =
        width in 1..MAX_TOUCH_BAR_DIMENSION &&
            height in 1..MAX_TOUCH_BAR_DIMENSION &&
            codec == TOUCH_BAR_CODEC_PNG

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key).coerceIn(0, 100)

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key)

    private fun scheduleDrain() {
        if (closed || !drainScheduled.compareAndSet(false, true)) return
        io.execute {
            try {
                while (!closed && connected) {
                    val event = synchronized(queueLock) { queue.pollFirst() } ?: break
                    writeFrame(event)
                }
            } catch (error: Exception) {
                Log.w(TAG, "Wi-Fi input send failed", error)
                socket?.let(::handleDisconnect)
            } finally {
                drainScheduled.set(false)
                if (connected && synchronized(queueLock) { queue.isNotEmpty() }) scheduleDrain()
            }
        }
    }

    private fun writeFrame(event: Event) {
        val stream = output ?: throw EOFException("not connected")
        val frameSequence = sequence++
        if (event.type == TYPE_PING) {
            lastPingSequence = frameSequence
            lastPingSentAtNanos = SystemClock.elapsedRealtimeNanos()
        }
        stream.writeByte(MAGIC_1)
        stream.writeByte(MAGIC_2)
        stream.writeByte(PROTOCOL_VERSION)
        stream.writeByte(event.type)
        stream.writeShort(event.payload.size)
        stream.writeInt(frameSequence)
        stream.write(event.payload)
        stream.flush()
    }

    private fun recordRtt(rttMs: Double) {
        rttSamples++
        rttTotalMs += rttMs
        rttMaxMs = maxOf(rttMaxMs, rttMs)
        if (rttSamples >= RTT_LOG_SAMPLE_COUNT) {
            Log.i(TAG, "transport RTT avg=%.2fms max=%.2fms samples=%d".format(
                rttTotalMs / rttSamples,
                rttMaxMs,
                rttSamples,
            ))
            rttSamples = 0
            rttTotalMs = 0.0
            rttMaxMs = 0.0
        }
    }

    private fun handleDisconnect(activeSocket: Socket) {
        synchronized(stateLock) {
            if (activeSocket !== socket) return
            closeSocket()
        }
        synchronized(queueLock) { queue.clear() }
        if (closed) return
        publish(Status.DISCONNECTED, "Wi-Fi 已断开，正在重连")
        audioDisconnectListener()
        val endpoint = lastEndpoint ?: return
        try {
            io.schedule({ connect(endpoint) }, RECONNECT_SECONDS, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            // close() raced with the reader thread; nothing left to reconnect.
        }
    }

    private fun closeSocket() {
        connected = false
        transportConnected = false
        serverNonce = null
        authClientNonce = null
        pairingExchange = null
        synchronized(touchBarFrameLock) { touchBarFrameAssembly = null }
        highPerformanceLock?.let { if (it.isHeld) it.release() }
        highPerformanceLock = null
        output = null
        try { socket?.close() } catch (_: Exception) { }
        socket = null
    }

    @Suppress("DEPRECATION")
    private fun acquireHighPerformanceLock() {
        if (highPerformanceLock?.isHeld == true) return
        highPerformanceLock = wifi?.createWifiLock(
            WifiManager.WIFI_MODE_FULL_HIGH_PERF,
            "vibepad-low-latency",
        )?.apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun publish(status: Status, detail: String? = null) {
        Log.i(TAG, "status=$status detail=$detail")
        statusListener.onStatusChanged(State(status, detail))
    }

    private fun shortPairPayload(first: Int, second: Int): ByteArray {
        val a = first.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val b = second.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        return byteArrayOf((a ushr 8).toByte(), a.toByte(), (b ushr 8).toByte(), b.toByte())
    }

    private fun readShort(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() shl 8) or (bytes[offset + 1].toInt() and 0xff)).toShort().toInt()

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or
            (bytes[offset + 1].toInt() and 0xff)

    private fun readUInt32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun longPayload(value: Long) = ByteArray(8) { index -> (value ushr ((7 - index) * 8)).toByte() }

    private fun putUInt16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun putUInt32(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 4) bytes[offset + index] = (value ushr ((3 - index) * 8)).toByte()
    }

    private fun putUInt64(bytes: ByteArray, offset: Int, value: Long) {
        for (index in 0 until 8) bytes[offset + index] = (value ushr ((7 - index) * 8)).toByte()
    }

    companion object {
        const val SERVICE_TYPE = "_vibepad._tcp."
        private const val TAG = "VibePadWifi"
        private const val MAGIC_1 = 0x57
        private const val MAGIC_2 = 0x50
        private const val PROTOCOL_VERSION = 2
        private const val TYPE_SERVER_CHALLENGE = 0x02
        private const val TYPE_AUTHENTICATE = 0x03
        private const val TYPE_AUTHENTICATION_OK = 0x04
        private const val TYPE_PAIR_REQUEST = 0x05
        private const val TYPE_PAIR_OFFER = 0x06
        private const val TYPE_PAIR_ACCEPT = 0x07
        private const val TYPE_PAIR_REJECT = 0x08
        private const val TYPE_PAIRING_DISABLED = 0x09
        private const val TYPE_MOVE = 0x10
        private const val TYPE_BUTTON = 0x11
        private const val TYPE_SCROLL = 0x12
        private const val TYPE_KEY = 0x13
        private const val TYPE_RELEASE_ALL = 0x14
        private const val TYPE_GESTURE = 0x15
        private const val TYPE_PING = 0x20
        private const val TYPE_PONG = 0x21
        private const val TYPE_USAGE = 0x30
        private const val TYPE_APPS_REQUEST = 0x40
        private const val TYPE_APPS_BEGIN = 0x41
        private const val TYPE_APP_ITEM = 0x42
        private const val TYPE_APPS_END = 0x43
        private const val TYPE_LAUNCH_APP = 0x44
        private const val TYPE_TOUCH_BAR_SUBSCRIBE = 0x50
        private const val TYPE_TOUCH_BAR_FRAME = 0x51
        private const val TYPE_TOUCH_BAR_FRAME_CHUNK = 0x52
        private const val TYPE_TOUCH_BAR_EVENT = 0x53
        private const val TYPE_AUDIO_START = 0x54
        private const val TYPE_AUDIO_DATA = 0x55
        private const val TYPE_AUDIO_STOP = 0x56
        private const val TOUCH_PHASE_DOWN = 0
        private const val TOUCH_PHASE_DRAGGED = 1
        private const val TOUCH_PHASE_UP = 2
        private const val TOUCH_BAR_CODEC_PNG = 1
        private const val TOUCH_BAR_FRAME_HEADER_BYTES = 9
        private const val TOUCH_BAR_CHUNK_HEADER_BYTES = 13
        private const val MAX_TOUCH_BAR_FRAME_BYTES = 1024 * 1024
        private const val MAX_TOUCH_BAR_CHUNKS = 4096
        private const val MAX_TOUCH_BAR_DIMENSION = 8192
        private const val USHRT_MAX = 0xffff
        private const val CONNECT_TIMEOUT_MS = 1_500
        private const val HEARTBEAT_INTERVAL_MS = 500L
        private const val RECONNECT_SECONDS = 1L
        private const val MAX_QUEUE_SIZE = 256
        private const val MAX_PENDING_AUDIO_WRITES = 6
        private const val AUDIO_DATA_HEADER_BYTES = 18
        private const val PCM16_BYTES_PER_SAMPLE = 2
        private const val MAX_APP_ID_BYTES = 512
        private const val RTT_LOG_SAMPLE_COUNT = 20
        private const val NONCE_BYTES = 32
        private const val HMAC_BYTES = 32
        private const val CODE_PAIRING_REQUIRED = "PAIRING_REQUIRED"
    }
}
