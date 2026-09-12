package com.xiaoxi.vibepad.system

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager

/**
 * Owns VibePad's immersive full-screen presentation: hides the system bars, keeps the
 * screen on, and re-hides the bars whenever the system brings them back (for example
 * after a swipe from the edge or a dialog). It never asks for Device Owner or lock-task
 * privileges; leaving the app is done through the in-app Exit action.
 */
class KioskController(private val activity: Activity) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var immersiveEnabled = false
    private val restoreImmersive = Runnable { applyImmersiveMode() }

    /** Call from Activity.onCreate. */
    fun start() {
        immersiveEnabled = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        installSystemUiRecovery()
    }

    /** Call from Activity.onDestroy. */
    fun stop() {
        immersiveEnabled = false
        mainHandler.removeCallbacks(restoreImmersive)
        activity.window.decorView.setOnSystemUiVisibilityChangeListener(null)
    }

    /** Call from Activity.onWindowFocusChanged. */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus && immersiveEnabled) scheduleImmersiveRestore(0L)
    }

    /** Restore Android system bars after the in-app Exit action is confirmed. */
    fun restoreSystemBars() {
        immersiveEnabled = false
        mainHandler.removeCallbacks(restoreImmersive)
        activity.window.decorView.setOnSystemUiVisibilityChangeListener(null)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
    }

    private fun applyImmersiveMode() {
        if (!immersiveEnabled) return
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        activity.window.decorView.systemUiVisibility = IMMERSIVE_FLAGS
    }

    private fun installSystemUiRecovery() {
        activity.window.decorView.setOnSystemUiVisibilityChangeListener { visibility ->
            val barsBecameVisible =
                visibility and View.SYSTEM_UI_FLAG_FULLSCREEN == 0 ||
                    visibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION == 0
            if (barsBecameVisible) scheduleImmersiveRestore(IMMERSIVE_RESTORE_DELAY_MS)
        }
    }

    private fun scheduleImmersiveRestore(delayMs: Long) {
        mainHandler.removeCallbacks(restoreImmersive)
        mainHandler.postDelayed(restoreImmersive, delayMs)
    }

    private companion object {
        private const val IMMERSIVE_RESTORE_DELAY_MS = 350L

        private const val IMMERSIVE_FLAGS =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LOW_PROFILE
    }
}
