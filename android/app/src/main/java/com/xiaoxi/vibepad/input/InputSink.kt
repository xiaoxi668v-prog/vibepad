package com.xiaoxi.vibepad.input

interface InputSink {
    val isConnected: Boolean

    fun move(dx: Int, dy: Int)
    fun mouseButton(buttonMask: Int, pressed: Boolean)
    fun scroll(vertical: Int, horizontal: Int = 0)
    fun gesture(gesture: TrackpadGesture) = Unit
    fun key(keyUsage: Int, modifierMask: Int = 0, pressed: Boolean)
    fun releaseAll()
    fun requestApps() = Unit
    fun launchApp(bundleId: String) = Unit
    fun requestPairing() = Unit

    fun tapKey(keyUsage: Int, modifierMask: Int = 0) {
        key(keyUsage, modifierMask, true)
        key(keyUsage, modifierMask, false)
    }
}

enum class TrackpadGesture(val wireValue: Int) {
    MISSION_CONTROL(1),
    APP_EXPOSE(2),
    PREVIOUS_SPACE(3),
    NEXT_SPACE(4),
    SHOW_DESKTOP(5),
    OPEN_APPS(6),
    ZOOM_IN(7),
    ZOOM_OUT(8),
    LOOK_UP(9),
}

object HidButtons {
    const val LEFT = 1
    const val RIGHT = 2
    const val MIDDLE = 4
}

object HidModifiers {
    const val LEFT_CONTROL = 0x01
    const val LEFT_SHIFT = 0x02
    const val LEFT_ALT = 0x04
    const val LEFT_GUI = 0x08
}

object HidKeys {
    const val A = 0x04
    const val C = 0x06
    const val D = 0x07
    const val L = 0x0F
    const val N = 0x11
    const val X = 0x1B
    const val V = 0x19
    const val Y = 0x1C
    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52
}
