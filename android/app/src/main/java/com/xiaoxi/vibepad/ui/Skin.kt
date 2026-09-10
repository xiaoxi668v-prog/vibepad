package com.xiaoxi.vibepad.ui

/**
 * 三套皮肤对应 designs/skins/ 下的 01 经典黑、02 深空专业、05 双手操控。
 *
 * 皮肤只改变视觉变量与布局，不改变网络协议、配对流程、手势语义和键位功能
 * （designs/README.md「优化时必须遵守的边界」）。原型里在本项目上无法真正操作的元素
 * （固定 Touch Bar 标签、亮度/音量滑杆、演示转写草稿、文字输入弹层）一律不实现：
 * 顶部一律走 Mac 真实 Touch Bar 画面回传。
 */
enum class Skin(val id: String, val displayName: String, val summary: String) {
    CLASSIC("classic", "经典", "纯黑背景 · 左控制右触控"),
    GRAPHITE("graphite", "深空专业", "深空灰 · 左触控右快捷键 · 底部 App Dock"),
    TITANIUM("titanium", "双手操控", "暖钛浅色 · 中央触控 · 左右拇指分工");

    val palette: SkinPalette
        get() = when (this) {
            CLASSIC -> SkinPalette.CLASSIC
            GRAPHITE -> SkinPalette.GRAPHITE
            TITANIUM -> SkinPalette.TITANIUM
        }

    companion object {
        fun fromId(id: String?): Skin = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

/**
 * 皮肤色板与形状变量。取值来自 designs/orbit-source/design/tokens.json；
 * 经典黑沿用 0.4.1 已上线的实现值，改皮肤时不要改动它。
 */
data class SkinPalette(
    /** 整屏底色。 */
    val background: Int,
    /** 面板（常用 App / 快捷键 / Dock）底色。 */
    val panel: Int,
    /** 键帽与次级按钮底色。 */
    val key: Int,
    /** 键帽按下态底色。 */
    val keyPressed: Int,
    /** 面板与键帽描边。 */
    val outline: Int,
    /** 主文字。 */
    val text: Int,
    /** 次级文字。 */
    val muted: Int,
    /** 线性图标。 */
    val icon: Int,
    /** 强调文字（面板动作、App 首字母）。 */
    val accent: Int,
    /** 当前 App / 按下高亮的浅底。 */
    val accentSoft: Int,
    /** 主操作按钮底色（按住说话）。 */
    val accentStrong: Int,
    /** 主操作按钮按下态底色。 */
    val accentPressed: Int,
    /** 主操作按钮上的文字与图标。 */
    val onAccent: Int,
    /** 触控区底色。 */
    val pad: Int,
    /** 触控区未连接时的描边。 */
    val padOutline: Int,
    /** 触控区已连接时的描边。 */
    val padOnline: Int,
    /** 触控区中央的提示文字。 */
    val padLabel: Int,
    /** Touch Bar 条底色。 */
    val touchBar: Int,
    /** 危险键位（停止 ⌃C）。 */
    val warning: Int,
    /** 录音态。 */
    val recording: Int,
    /** 面板圆角（dp）。 */
    val panelRadius: Float,
    /** 触控区圆角（dp）。 */
    val padRadius: Float,
    /** 键帽圆角（dp）。 */
    val keyRadius: Float,
    /** 浅色皮肤为 true，用于决定阴影与状态点亮度。 */
    val light: Boolean,
) {
    companion object {
        /** 01 经典黑：0.4.1 已上线配色，逐值对应旧的 VibePadView 常量。 */
        val CLASSIC = SkinPalette(
            background = 0xFF000000.toInt(),
            panel = 0xFF2A2A2A.toInt(),
            key = 0xFF2A2A2A.toInt(),
            keyPressed = 0xFF3A3A3D.toInt(),
            outline = 0xFF414754.toInt(),
            text = 0xFFE2E2E2.toInt(),
            muted = 0xFFC0C6D6.toInt(),
            icon = 0xFFC0C6D6.toInt(),
            accent = 0xFFAAC7FF.toInt(),
            accentSoft = 0xFF25334C.toInt(),
            accentStrong = 0xFF3E90FF.toInt(),
            accentPressed = 0xFF62A6FF.toInt(),
            onAccent = 0xFF002957.toInt(),
            pad = 0xFF000000.toInt(),
            padOutline = 0xFF3D4551.toInt(),
            padOnline = 0xFF53B582.toInt(),
            padLabel = 0xFF697381.toInt(),
            touchBar = 0xFF000000.toInt(),
            warning = 0xFFFFB4AB.toInt(),
            recording = 0xFFFF3B30.toInt(),
            panelRadius = 14f,
            padRadius = 22f,
            keyRadius = 10f,
            light = false,
        )

        /** 02 深空专业 Graphite Pro。 */
        val GRAPHITE = SkinPalette(
            background = 0xFF151619.toInt(),
            panel = 0xFF202125.toInt(),
            key = 0xFF2D2F35.toInt(),
            keyPressed = 0xFF3C3F48.toInt(),
            outline = 0xFF32343B.toInt(),
            text = 0xFFEEEEEF.toInt(),
            muted = 0xFF8B8E99.toInt(),
            icon = 0xFFA3A8B5.toInt(),
            accent = 0xFFACC3ED.toInt(),
            accentSoft = 0xFF303C53.toInt(),
            accentStrong = 0xFFACC3ED.toInt(),
            accentPressed = 0xFF8FAEE2.toInt(),
            onAccent = 0xFF1C2941.toInt(),
            pad = 0xFF1B1D21.toInt(),
            padOutline = 0xFF32343B.toInt(),
            padOnline = 0xFF3C9168.toInt(),
            padLabel = 0xFF8B8E99.toInt(),
            touchBar = 0xFF0E0F11.toInt(),
            warning = 0xFFC65C59.toInt(),
            recording = 0xFFB95D68.toInt(),
            panelRadius = 19f,
            padRadius = 19f,
            keyRadius = 11f,
            light = false,
        )

        /** 05 双手操控 Titanium Duo。 */
        val TITANIUM = SkinPalette(
            background = 0xFFEAE7E1.toInt(),
            panel = 0xFFF7F5F1.toInt(),
            key = 0xFFEDE9E2.toInt(),
            keyPressed = 0xFFE1DBD1.toInt(),
            outline = 0xFFDEDAD2.toInt(),
            text = 0xFF49453F.toInt(),
            muted = 0xFF928B81.toInt(),
            icon = 0xFF958B7D.toInt(),
            accent = 0xFF776753.toInt(),
            accentSoft = 0xFFE9E1D5.toInt(),
            accentStrong = 0xFF776753.toInt(),
            accentPressed = 0xFF6A5B49.toInt(),
            onAccent = 0xFFFFFFFF.toInt(),
            pad = 0xFFF1EEE8.toInt(),
            padOutline = 0xFFD5CFC4.toInt(),
            padOnline = 0xFF3C9168.toInt(),
            padLabel = 0xFF958B7D.toInt(),
            touchBar = 0xFFF7F5F1.toInt(),
            warning = 0xFFB4524E.toInt(),
            recording = 0xFFB95D68.toInt(),
            panelRadius = 21f,
            padRadius = 21f,
            keyRadius = 11f,
            light = true,
        )
    }
}
