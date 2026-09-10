package com.xiaoxi.vibepad.input

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Android 9+ Bluetooth HID device implementation used by the VibePad input layer.
 *
 * The Mac sees a single composite keyboard and mouse. Pointer deltas are kept as
 * 16-bit relative values. If Android rejects a mouse report because its Bluetooth
 * queue is temporarily full, the unsent delta remains accumulated for the next
 * report rather than being lost.
 */
@SuppressLint("MissingPermission")
class BluetoothHidInputSink(
    context: Context,
    private val statusListener: StatusListener = StatusListener { },
) : InputSink, Closeable {

    fun interface StatusListener {
        fun onStatusChanged(state: State)
    }

    data class State(
        val status: Status,
        val device: BluetoothDevice? = null,
        val detail: String? = null,
    )

    enum class Status {
        STARTING,
        BLUETOOTH_UNAVAILABLE,
        REGISTERING,
        REGISTERED,
        CONNECTING,
        CONNECTED,
        DISCONNECTED,
        ERROR,
        CLOSED,
    }

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "vibepad-hid-callback").apply { isDaemon = true }
    }
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private var hidDevice: BluetoothHidDevice? = null
    private var registered = false
    private var closed = false
    private var connectedDevice: BluetoothDevice? = null
    private var requestedDevice: BluetoothDevice? = null

    private var mouseButtons = 0
    private var pendingX = 0L
    private var pendingY = 0L
    private var pendingWheel = 0L
    private var pendingPan = 0L
    private val pendingMouseStateReports = ArrayDeque<ByteArray>()
    private var nextMouseReportAtMs = 0L
    private var mouseRetryDelayMs = INITIAL_MOUSE_RETRY_DELAY_MS

    private val activeKeys = LinkedHashSet<Int>()
    private val keyModifiers = HashMap<Int, Int>()
    private var standaloneModifiers = 0
    private val pendingKeyboardReports = ArrayDeque<ByteArray>()
    private var nextKeyboardReportAtMs = 0L
    private var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

    private val resetReconnectBackoff = Runnable {
        synchronized(this) {
            if (!closed && connectedDevice != null) {
                reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
            }
        }
    }

    private val mouseRetry = Runnable {
        synchronized(this) { flushMouseReports() }
    }
    private val keyboardRetry = Runnable {
        synchronized(this) { flushKeyboardReports() }
    }
    private val keepAlive = object : Runnable {
        override fun run() {
            synchronized(this@BluetoothHidInputSink) {
                if (closed || connectedDevice == null) return
                flushMouseReports(forceStateReport = true)
                mainHandler.postDelayed(this, KEEP_ALIVE_INTERVAL_MS)
            }
        }
    }
    private val reconnect = Runnable {
        synchronized(this) {
            if (!closed && connectedDevice == null) {
                val target = requestedDevice ?: pairedMacs().firstOrNull()
                if (target != null) connect(target)
            }
        }
    }

    override val isConnected: Boolean
        @Synchronized get() = !closed && connectedDevice != null

    /** Returns bonded computers, with devices whose names look like Macs first. */
    fun pairedMacs(): List<BluetoothDevice> {
        val devices = try {
            adapter?.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        return devices
            .filter { device ->
                device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER ||
                    safeName(device).contains("mac", ignoreCase = true)
            }
            .sortedWith(
                compareByDescending<BluetoothDevice> {
                    safeName(it).contains("mac", ignoreCase = true)
                }.thenBy { safeName(it) },
            )
    }

    /** Connects after registration is ready; callers do not need to wait for REGISTERED. */
    @Synchronized
    fun connect(device: BluetoothDevice): Boolean {
        if (closed || device.bondState != BluetoothDevice.BOND_BONDED) return false
        requestedDevice = device
        val profile = hidDevice
        if (!registered || profile == null) {
            publish(State(Status.CONNECTING, device, "Waiting for HID registration"))
            return true
        }
        publish(State(Status.CONNECTING, device))
        return profile.connect(device).also { accepted ->
            if (!accepted) publish(State(Status.ERROR, device, "Bluetooth rejected the connect request"))
        }
    }

    fun connectFirstPairedMac(): Boolean = pairedMacs().firstOrNull()?.let(::connect) ?: false

    @Synchronized
    fun disconnect(): Boolean {
        val device = connectedDevice ?: requestedDevice ?: return false
        releaseAll()
        requestedDevice = null
        return hidDevice?.disconnect(device) ?: false
    }

    @Synchronized
    override fun move(dx: Int, dy: Int) {
        pendingX = saturatedAdd(pendingX, dx.toLong())
        pendingY = saturatedAdd(pendingY, dy.toLong())
        flushMouseReports()
    }

    @Synchronized
    override fun mouseButton(buttonMask: Int, pressed: Boolean) {
        mouseButtons = if (pressed) {
            mouseButtons or (buttonMask and 0xff)
        } else {
            mouseButtons and buttonMask.inv()
        }
        flushMouseReports(forceStateReport = true)
    }

    @Synchronized
    override fun scroll(vertical: Int, horizontal: Int) {
        pendingWheel = saturatedAdd(pendingWheel, vertical.toLong())
        pendingPan = saturatedAdd(pendingPan, horizontal.toLong())
        flushMouseReports()
    }

    /**
     * keyUsage is a USB HID keyboard usage (0 means modifiers only). modifierMask
     * uses [HidModifiers]. A released chord removes the modifiers owned by that key.
     */
    @Synchronized
    override fun key(keyUsage: Int, modifierMask: Int, pressed: Boolean) {
        Log.i(TAG, "Key event usage=$keyUsage modifiers=$modifierMask pressed=$pressed connected=$isConnected")
        val modifiers = modifierMask and 0xff
        if (keyUsage == 0) {
            standaloneModifiers = if (pressed) {
                standaloneModifiers or modifiers
            } else {
                standaloneModifiers and modifiers.inv()
            }
        } else if (pressed) {
            activeKeys += keyUsage and 0xff
            keyModifiers[keyUsage and 0xff] = modifiers
        } else {
            activeKeys -= keyUsage and 0xff
            keyModifiers.remove(keyUsage and 0xff)
        }
        queueKeyboardReport()
    }

    @Synchronized
    override fun releaseAll() {
        mouseButtons = 0
        pendingX = 0
        pendingY = 0
        pendingWheel = 0
        pendingPan = 0
        activeKeys.clear()
        keyModifiers.clear()
        standaloneModifiers = 0
        queueKeyboardReport()
        flushMouseReports(forceStateReport = true)
        flushKeyboardReports()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        releaseAll()
        closed = true
        connectedDevice = null
        requestedDevice = null
        registered = false
        mainHandler.removeCallbacks(mouseRetry)
        mainHandler.removeCallbacks(keyboardRetry)
        mainHandler.removeCallbacks(keepAlive)
        mainHandler.removeCallbacks(reconnect)
        mainHandler.removeCallbacks(resetReconnectBackoff)
        hidDevice?.unregisterApp()
        hidDevice?.let { adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, it) }
        hidDevice = null
        callbackExecutor.shutdownNow()
        publish(State(Status.CLOSED))
    }

    private val profileListener: BluetoothProfile.ServiceListener by lazy {
        object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE || closed) return
            val hid = proxy as BluetoothHidDevice
            synchronized(this@BluetoothHidInputSink) {
                hidDevice = hid
                publish(State(Status.REGISTERING))
                val accepted = hid.registerApp(
                    SDP_SETTINGS,
                    null,
                    null,
                    callbackExecutor,
                    hidCallback,
                )
                if (!accepted) {
                    publish(State(Status.ERROR, detail = "Bluetooth rejected HID registration"))
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            synchronized(this@BluetoothHidInputSink) {
                hidDevice = null
                registered = false
                connectedDevice = null
                if (!closed) publish(State(Status.DISCONNECTED, detail = "HID profile service disconnected"))
            }
        }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            synchronized(this@BluetoothHidInputSink) {
                if (closed) return
                registered = isRegistered
                if (!isRegistered) {
                    connectedDevice = null
                    publish(State(Status.ERROR, pluggedDevice, "HID application was unregistered"))
                    return
                }
                publish(State(Status.REGISTERED, pluggedDevice))
                val target = requestedDevice ?: pluggedDevice
                if (target != null) connect(target)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            synchronized(this@BluetoothHidInputSink) {
                if (closed) return
                Log.i(TAG, "HID connection state=$state device=${safeName(device)}")
                when (state) {
                    BluetoothProfile.STATE_CONNECTING -> publish(State(Status.CONNECTING, device))
                    BluetoothProfile.STATE_CONNECTED -> {
                        connectedDevice = device
                        requestedDevice = device
                        mainHandler.removeCallbacks(reconnect)
                        mainHandler.removeCallbacks(keepAlive)
                        mainHandler.removeCallbacks(resetReconnectBackoff)
                        mainHandler.postDelayed(resetReconnectBackoff, STABLE_CONNECTION_RESET_MS)
                        publish(State(Status.CONNECTED, device))
                        // Never replay stale movement or held keys after a link timeout.
                        clearPendingInput()
                        flushMouseReports(forceStateReport = true)
                        queueKeyboardReport()
                        flushKeyboardReports()
                    }
                    BluetoothProfile.STATE_DISCONNECTING -> publish(
                        State(Status.DISCONNECTED, device, "Disconnecting"),
                    )
                    BluetoothProfile.STATE_DISCONNECTED -> {
                        if (connectedDevice == device) connectedDevice = null
                        mainHandler.removeCallbacks(keepAlive)
                        mainHandler.removeCallbacks(resetReconnectBackoff)
                        clearPendingInput()
                        publish(State(Status.DISCONNECTED, device))
                        mainHandler.removeCallbacks(reconnect)
                        mainHandler.postDelayed(reconnect, reconnectDelayMs)
                        reconnectDelayMs = (reconnectDelayMs * 2).coerceAtMost(MAX_RECONNECT_DELAY_MS)
                    }
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            synchronized(this@BluetoothHidInputSink) {
                val profile = hidDevice ?: return
                if (type != BluetoothHidDevice.REPORT_TYPE_INPUT) {
                    profile.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
                    return
                }
                when (id.toInt() and 0xff) {
                    MOUSE_REPORT_ID -> profile.replyReport(
                        device,
                        type,
                        id,
                        mouseReport(0, 0, 0),
                    )
                    KEYBOARD_REPORT_ID -> profile.replyReport(device, type, id, keyboardReport())
                    else -> profile.reportError(device, BluetoothHidDevice.ERROR_RSP_INVALID_RPT_ID)
                }
            }
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // macOS may send keyboard LED output reports. LEDs are not displayed by VibePad,
            // but acknowledging the report keeps the HID control channel healthy.
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            synchronized(this@BluetoothHidInputSink) {
                if (connectedDevice == device) connectedDevice = null
                if (requestedDevice == device) requestedDevice = null
                publish(State(Status.DISCONNECTED, device, "The host removed the HID pairing"))
            }
        }
    }

    init {
        publish(State(Status.STARTING))
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            publish(State(Status.BLUETOOTH_UNAVAILABLE, detail = "Bluetooth is unavailable or disabled"))
        } else {
            val requested = bluetoothAdapter.getProfileProxy(
                appContext,
                profileListener,
                BluetoothProfile.HID_DEVICE,
            )
            if (!requested) {
                publish(State(Status.ERROR, detail = "Unable to request the HID device profile"))
            }
        }
    }

    /** Sends all representable accumulated movement, stopping without loss on backpressure. */
    private fun flushMouseReports(forceStateReport: Boolean = false) {
        if (forceStateReport) {
            if (pendingMouseStateReports.size >= MAX_PENDING_MOUSE_STATE_REPORTS) {
                pendingMouseStateReports.removeFirst()
            }
            pendingMouseStateReports.addLast(mouseReport(0, 0, 0))
        }
        val profile = hidDevice ?: return
        val device = connectedDevice ?: return
        val spacingDelay = nextMouseReportAtMs - SystemClock.uptimeMillis()
        if (spacingDelay > 0L) {
            mainHandler.removeCallbacks(mouseRetry)
            mainHandler.postDelayed(mouseRetry, spacingDelay)
            return
        }
        val stateReport = pendingMouseStateReports.firstOrNull()
        val hasMotion = pendingX != 0L || pendingY != 0L || pendingWheel != 0L || pendingPan != 0L
        if (stateReport == null && !hasMotion) return

        val dx = if (stateReport == null) pendingX.coerceIn(BYTE_MIN, BYTE_MAX).toInt() else 0
        val dy = if (stateReport == null) pendingY.coerceIn(BYTE_MIN, BYTE_MAX).toInt() else 0
        val wheel = if (stateReport == null) pendingWheel.coerceIn(BYTE_MIN, BYTE_MAX).toInt() else 0
        val pan = if (stateReport == null) pendingPan.coerceIn(BYTE_MIN, BYTE_MAX).toInt() else 0
        val report = stateReport ?: mouseReport(dx, dy, wheel)
        if (!profile.sendReport(device, MOUSE_REPORT_ID, report)) {
            Log.w(TAG, "Mouse report backpressure; retrying in ${mouseRetryDelayMs}ms")
            mainHandler.removeCallbacks(mouseRetry)
            mainHandler.postDelayed(mouseRetry, mouseRetryDelayMs)
            mouseRetryDelayMs = (mouseRetryDelayMs * 2).coerceAtMost(MAX_MOUSE_RETRY_DELAY_MS)
            return
        }
        mouseRetryDelayMs = INITIAL_MOUSE_RETRY_DELAY_MS
        nextMouseReportAtMs = SystemClock.uptimeMillis() + MOUSE_REPORT_INTERVAL_MS
        if (stateReport != null) {
            pendingMouseStateReports.removeFirst()
        } else {
            pendingX -= dx
            pendingY -= dy
            pendingWheel -= wheel
            pendingPan -= pan
        }
        mainHandler.removeCallbacks(mouseRetry)
        if (pendingMouseStateReports.isNotEmpty() ||
            pendingX != 0L || pendingY != 0L || pendingWheel != 0L || pendingPan != 0L
        ) {
            mainHandler.postDelayed(mouseRetry, MOUSE_REPORT_INTERVAL_MS)
        }
    }

    private fun queueKeyboardReport() {
        if (pendingKeyboardReports.size >= MAX_PENDING_KEYBOARD_REPORTS) {
            pendingKeyboardReports.removeFirst()
        }
        pendingKeyboardReports.addLast(keyboardReport())
        flushKeyboardReports()
    }

    private fun clearPendingInput() {
        mouseButtons = 0
        pendingX = 0
        pendingY = 0
        pendingWheel = 0
        pendingPan = 0
        pendingMouseStateReports.clear()
        activeKeys.clear()
        keyModifiers.clear()
        standaloneModifiers = 0
        pendingKeyboardReports.clear()
        nextMouseReportAtMs = 0L
        nextKeyboardReportAtMs = 0L
        mouseRetryDelayMs = INITIAL_MOUSE_RETRY_DELAY_MS
        mainHandler.removeCallbacks(mouseRetry)
        mainHandler.removeCallbacks(keyboardRetry)
    }

    private fun flushKeyboardReports() {
        if (pendingKeyboardReports.isEmpty()) return
        val profile = hidDevice ?: return
        val device = connectedDevice ?: return
        val spacingDelay = nextKeyboardReportAtMs - SystemClock.uptimeMillis()
        if (spacingDelay > 0L) {
            mainHandler.removeCallbacks(keyboardRetry)
            mainHandler.postDelayed(keyboardRetry, spacingDelay)
            return
        }
        var sent = 0
        while (pendingKeyboardReports.isNotEmpty() && sent < MAX_KEYBOARD_REPORTS_PER_FLUSH) {
            val report = pendingKeyboardReports.first()
            if (!profile.sendReport(device, KEYBOARD_REPORT_ID, report)) {
                Log.w(TAG, "Keyboard report backpressure; retrying")
                mainHandler.removeCallbacks(keyboardRetry)
                mainHandler.postDelayed(keyboardRetry, REPORT_RETRY_DELAY_MS)
                return
            }
            Log.i(TAG, "Keyboard report accepted data=${report.joinToString { (it.toInt() and 0xff).toString() }}")
            pendingKeyboardReports.removeFirst()
            nextKeyboardReportAtMs = SystemClock.uptimeMillis() + KEYBOARD_REPORT_INTERVAL_MS
            sent++
        }
        mainHandler.removeCallbacks(keyboardRetry)
        if (pendingKeyboardReports.isNotEmpty()) {
            mainHandler.postDelayed(keyboardRetry, KEYBOARD_REPORT_INTERVAL_MS)
        }
    }

    private fun mouseReport(dx: Int, dy: Int, wheel: Int) = byteArrayOf(
        mouseButtons.toByte(),
        dx.toByte(),
        dy.toByte(),
        wheel.toByte(),
    )

    private fun keyboardReport(): ByteArray {
        val modifiers = keyModifiers.values.fold(standaloneModifiers) { result, mask -> result or mask }
        val report = ByteArray(KEYBOARD_REPORT_BYTES)
        report[0] = modifiers.toByte()
        activeKeys.take(KEYBOARD_KEY_SLOTS).forEachIndexed { index, key ->
            report[index + 2] = key.toByte()
        }
        return report
    }

    private fun publish(state: State) {
        mainHandler.post { statusListener.onStatusChanged(state) }
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name.orEmpty()
    } catch (_: SecurityException) {
        device.address
    }

    private fun saturatedAdd(current: Long, delta: Long): Long = when {
        delta > 0 && current > MAX_PENDING - delta -> MAX_PENDING
        delta < 0 && current < -MAX_PENDING - delta -> -MAX_PENDING
        else -> current + delta
    }

    companion object {
        const val KEYBOARD_REPORT_ID = 1
        const val MOUSE_REPORT_ID = 2

        private const val KEYBOARD_REPORT_BYTES = 8
        private const val KEYBOARD_KEY_SLOTS = 6
        private const val MAX_PENDING_MOUSE_STATE_REPORTS = 32
        private const val MAX_KEYBOARD_REPORTS_PER_FLUSH = 1
        private const val MAX_PENDING_KEYBOARD_REPORTS = 64
        private const val REPORT_RETRY_DELAY_MS = 16L
        private const val MOUSE_REPORT_INTERVAL_MS = 16L
        private const val INITIAL_MOUSE_RETRY_DELAY_MS = 16L
        private const val MAX_MOUSE_RETRY_DELAY_MS = 128L
        private const val KEYBOARD_REPORT_INTERVAL_MS = 18L
        private const val KEEP_ALIVE_INTERVAL_MS = 10_000L
        private const val INITIAL_RECONNECT_DELAY_MS = 1_500L
        private const val MAX_RECONNECT_DELAY_MS = 10_000L
        private const val STABLE_CONNECTION_RESET_MS = 30_000L
        private const val MAX_PENDING = Int.MAX_VALUE.toLong()
        private const val BYTE_MIN = -127L
        private const val BYTE_MAX = 127L
        private const val TAG = "VibePadHid"

        private val SDP_SETTINGS = BluetoothHidDeviceAppSdpSettings(
            "VibePad Keyboard and Mouse",
            "Vibe coding touchpad and keyboard",
            "Xiaoxi VibePad",
            BluetoothHidDevice.SUBCLASS1_COMBO,
            hidDescriptor(),
        )

        /** Android CTS-compatible composite 6-key keyboard plus relative mouse. */
        private fun hidDescriptor(): ByteArray {
            val mouse = intArrayOf(
            // Mouse, report ID 2.
            0x05, 0x01,       // Usage Page (Generic Desktop)
            0x09, 0x02,       // Usage (Mouse)
            0xA1, 0x01,       // Collection (Application)
            0x85, MOUSE_REPORT_ID,
            0x09, 0x01,       // Usage (Pointer)
            0xA1, 0x00,       // Collection (Physical)
            0x05, 0x09,       // Usage Page (Button)
            0x19, 0x01,
            0x29, 0x03,
            0x15, 0x00,
            0x25, 0x01,
            0x95, 0x03,
            0x75, 0x01,
            0x81, 0x02,       // 3 button bits
            0x95, 0x01,
            0x75, 0x05,
            0x81, 0x01,       // 5 bit padding
            0x05, 0x01,
            0x09, 0x30,       // X
            0x09, 0x31,       // Y
            0x09, 0x38,       // Wheel
            0x15, 0x81,
            0x25, 0x7F,
            0x75, 0x08,
            0x95, 0x03,
            0x81, 0x06,
            0xC0,
            0xC0,
            )

            // Keyboard is deliberately the first application collection. Android's official
            // CTS descriptor uses this order, and macOS then exposes the composite as a keyboard.
            val keyboard = intArrayOf(
            0x05, 0x01,
            0x09, 0x06,
            0xA1, 0x01,
            0x85, KEYBOARD_REPORT_ID,
            0x05, 0x07,
            0x19, 0xE0,
            0x29, 0xE7,
            0x15, 0x00,
            0x25, 0x01,
            0x75, 0x01,
            0x95, 0x08,
            0x81, 0x02,       // Modifier byte
            0x95, 0x01,
            0x75, 0x08,
            0x81, 0x01,       // Reserved byte
            0x95, 0x06,
            0x75, 0x08,
            0x15, 0x00,
            0x25, 0x65,
            0x19, 0x00,
            0x29, 0x65,
            0x81, 0x00,       // Six key usages
            0xC0,
            )
            return (keyboard + mouse).map(Int::toByte).toByteArray()
        }
    }
}
