package com.xiaoxi.vibepad.system

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Owns VibePad's Android 9 kiosk presentation.
 *
 * This class deliberately does not provision Device Owner privileges or add this
 * package to the lock-task allowlist. Those are administrator-controlled setup
 * operations. It only enters lock task after the system reports that the package
 * is already permitted.
 */
class KioskController(
    private val activity: Activity,
    private val statusListener: ((SystemStatus) -> Unit)? = null,
) {
    data class SystemStatus(
        val time: String,
        val batteryPercent: Int,
        val isCharging: Boolean,
    )

    data class KioskCapability(
        val isDeviceOwner: Boolean,
        val isLockTaskPermitted: Boolean,
        val isInLockTaskMode: Boolean,
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val devicePolicyManager =
        activity.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val activityManager =
        activity.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private var receiverRegistered = false
    private var lockTaskStartedByController = false
    private var immersiveEnabled = false
    private var lastBatteryPercent = 0
    private var lastCharging = false
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    private val restoreImmersive = Runnable { applyImmersiveMode() }

    private val systemStatusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_BATTERY_CHANGED -> updateBattery(intent)
                Intent.ACTION_TIME_TICK,
                Intent.ACTION_TIME_CHANGED,
                Intent.ACTION_TIMEZONE_CHANGED -> Unit
            }
            publishStatus()
        }
    }

    /** Call from Activity.onCreate/onStart. */
    fun start() {
        immersiveEnabled = true
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        applyImmersiveMode()
        installSystemUiRecovery()
        registerSystemStatusReceiver()
        publishStatus()
    }

    /** Call from Activity.onStop when the VibePad UI is no longer active. */
    fun stop() {
        immersiveEnabled = false
        mainHandler.removeCallbacks(restoreImmersive)
        activity.window.decorView.setOnSystemUiVisibilityChangeListener(null)
        unregisterSystemStatusReceiver()
    }

    /** Call from Activity.onWindowFocusChanged. */
    fun onWindowFocusChanged(hasFocus: Boolean) {
        if (hasFocus && immersiveEnabled) scheduleImmersiveRestore(0L)
    }

    fun currentStatus(): SystemStatus = SystemStatus(
        time = timeFormatter.format(Date()),
        batteryPercent = lastBatteryPercent,
        isCharging = lastCharging,
    )

    fun capability(): KioskCapability = KioskCapability(
        isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(activity.packageName),
        isLockTaskPermitted = devicePolicyManager.isLockTaskPermitted(activity.packageName),
        isInLockTaskMode = isInLockTaskMode(),
    )

    /**
     * Enters lock task only when a Device Owner/Profile Owner has already
     * allowlisted this package. Returns false without changing system state when
     * permission is absent.
     */
    fun enterLockTask(): Boolean {
        if (!devicePolicyManager.isLockTaskPermitted(activity.packageName)) return false
        if (isInLockTaskMode()) return true

        return try {
            activity.startLockTask()
            lockTaskStartedByController = true
            applyImmersiveMode()
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
    }

    /**
     * Leaves lock task only if this controller entered it and the package remains
     * allowlisted. This avoids accidentally dismissing an administrator-owned
     * lock-task session.
     */
    fun exitLockTask(): Boolean {
        if (!lockTaskStartedByController) return false
        if (!devicePolicyManager.isLockTaskPermitted(activity.packageName)) return false
        if (!isInLockTaskMode()) {
            lockTaskStartedByController = false
            return true
        }

        return try {
            activity.stopLockTask()
            lockTaskStartedByController = false
            true
        } catch (_: SecurityException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        }
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

    private fun registerSystemStatusReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        activity.registerReceiver(systemStatusReceiver, filter)?.let(::updateBattery)
        receiverRegistered = true
    }

    private fun unregisterSystemStatusReceiver() {
        if (!receiverRegistered) return
        try {
            activity.unregisterReceiver(systemStatusReceiver)
        } catch (_: IllegalArgumentException) {
            // Activity teardown can race a previous unregister; stopping stays idempotent.
        }
        receiverRegistered = false
    }

    private fun updateBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        lastBatteryPercent = if (level >= 0 && scale > 0) {
            (level * 100 / scale).coerceIn(0, 100)
        } else {
            0
        }

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        lastCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun publishStatus() {
        statusListener?.invoke(currentStatus())
    }

    private fun isInLockTaskMode(): Boolean =
        activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

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
