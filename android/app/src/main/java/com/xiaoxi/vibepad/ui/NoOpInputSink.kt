package com.xiaoxi.vibepad.ui

import com.xiaoxi.vibepad.input.InputSink

/** Safe placeholder until Bluetooth HID or a fallback transport is attached. */
object NoOpInputSink : InputSink {
    override val isConnected = false
    override fun move(dx: Int, dy: Int) = Unit
    override fun mouseButton(buttonMask: Int, pressed: Boolean) = Unit
    override fun scroll(vertical: Int, horizontal: Int) = Unit
    override fun key(keyUsage: Int, modifierMask: Int, pressed: Boolean) = Unit
    override fun releaseAll() = Unit
}
