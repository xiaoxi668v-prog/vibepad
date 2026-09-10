package com.xiaoxi.vibepad.input

data class HelperHealth(
    val accessibilityTrusted: Boolean = false,
    val helperVersion: String = "",
    val protocolVersion: Int = 2,
    val lastInputAgeMs: Long? = null,
    val mouseButtons: Int = 0,
    val modifiers: Int = 0,
) {
    val inputUsable: Boolean
        get() = accessibilityTrusted && protocolVersion == 2
}

data class UsageWindow(
    val usedPercent: Int? = null,
    val resetsAt: String? = null,
)

data class UsageSnapshot(
    val claudeFiveHour: UsageWindow = UsageWindow(),
    val claudeSevenDay: UsageWindow = UsageWindow(),
    val claudeFable: UsageWindow = UsageWindow(),
    val codexFiveHour: UsageWindow = UsageWindow(),
    val codexWeekly: UsageWindow = UsageWindow(),
)

data class RemoteApp(
    val name: String,
    val bundleId: String,
    val iconPng: ByteArray? = null,
)

data class TouchBarFrame(
    val frameId: Long,
    val width: Int,
    val height: Int,
    val codec: Int,
    val bytes: ByteArray,
)

interface RemoteDataListener {
    fun onHelperHealth(health: HelperHealth) = Unit
    fun onUsageSnapshot(usage: UsageSnapshot) = Unit
    fun onAppCatalogStarted() = Unit
    fun onRemoteApp(app: RemoteApp) = Unit
    fun onAppCatalogFinished() = Unit
    fun onTouchBarFrame(frame: TouchBarFrame) = Unit
    fun onPairingCode(code: String) = Unit
    fun onPairingMessage(message: String, success: Boolean = false) = Unit
}
