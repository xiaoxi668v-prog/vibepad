package com.xiaoxi.vibepad.input

/** Routes new input to Wi-Fi whenever it is available, retaining Bluetooth as a fallback. */
class PreferredInputSink(
    private val wifi: InputSink,
    private val bluetooth: InputSink,
) : InputSink {
    override val isConnected: Boolean
        get() = wifi.isConnected || bluetooth.isConnected

    private fun active(): InputSink? = when {
        wifi.isConnected -> wifi
        bluetooth.isConnected -> bluetooth
        else -> null
    }

    override fun move(dx: Int, dy: Int) = active()?.move(dx, dy) ?: Unit
    override fun mouseButton(buttonMask: Int, pressed: Boolean) =
        active()?.mouseButton(buttonMask, pressed) ?: Unit

    override fun scroll(vertical: Int, horizontal: Int) =
        active()?.scroll(vertical, horizontal) ?: Unit

    override fun gesture(gesture: TrackpadGesture) = active()?.gesture(gesture) ?: Unit

    override fun key(keyUsage: Int, modifierMask: Int, pressed: Boolean) =
        active()?.key(keyUsage, modifierMask, pressed) ?: Unit

    override fun releaseAll() {
        // Release both paths so switching transports can never leave a held key or button behind.
        wifi.releaseAll()
        bluetooth.releaseAll()
    }
}
