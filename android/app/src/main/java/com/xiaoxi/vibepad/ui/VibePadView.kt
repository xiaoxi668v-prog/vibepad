package com.xiaoxi.vibepad.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import com.xiaoxi.vibepad.R
import com.xiaoxi.vibepad.input.HelperHealth
import com.xiaoxi.vibepad.input.HidKeys
import com.xiaoxi.vibepad.input.HidModifiers
import com.xiaoxi.vibepad.input.InputSink
import com.xiaoxi.vibepad.input.MicrophoneStreamer
import com.xiaoxi.vibepad.input.RemoteApp
import com.xiaoxi.vibepad.input.TouchBarFrame
import com.xiaoxi.vibepad.input.WifiInputSink
import java.util.Locale

/**
 * 平板主界面。三套皮肤共用同一组组件与协议，只改变布局与色板：
 *
 * 三套皮肤顶行一致（01 经典黑排布：左侧状态图标，右侧整行 Touch Bar），身体部分：
 *
 * - [Skin.CLASSIC] 01 经典黑：左控制面板、右触控板。
 * - [Skin.GRAPHITE] 02 深空专业：左触控右快捷键、底部 App Dock。
 * - [Skin.TITANIUM] 05 双手操控：左 App 与编辑键、中央触控、右快捷键。
 *
 * 手势语义、键位功能、Typeless 按住说话与发送行为在三套皮肤里完全一致。
 */
class VibePadView(
    context: Context,
    private val sinkProvider: () -> InputSink,
    private val touchBarSinkProvider: () -> WifiInputSink? = { null },
    private val onSettingsClick: () -> Unit = {},
    private val onMicrophonePressStart: () -> Unit = {},
    private val onMicrophonePressEnd: () -> Unit = {},
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val store = PadConfigStore.get(context)
    private val appCatalog = mutableListOf<RemoteApp>()

    private var config = store.current()
    private var palette = config.skin.palette
    private var helperHealth = HelperHealth()
    private var microphoneState = MicrophoneStreamer.State.IDLE
    private var frontmostBundleId: String? = null
    private val heldModifiers = mutableSetOf<Int>()

    private val trackpad = TrackpadView(context, sinkProvider)
    private val touchBarStrip = TouchBarStripView(context, touchBarSinkProvider)
    private lateinit var systemBar: SystemBarView

    private var appGrid: GridLayout? = null
    private var appDock: LinearLayout? = null
    private var customStrip: LinearLayout? = null
    private var microphoneCard: LinearLayout? = null
    private var microphoneIcon: ImageView? = null
    private var microphoneLabel: TextView? = null

    private val configListener: (PadConfig, PadConfigStore.Origin) -> Unit = { updated, _ ->
        applyConfig(updated)
    }

    init {
        orientation = VERTICAL
        buildLayout()
    }

    // region 外部接口

    fun refreshConnectionState() {
        systemBar.refresh()
        touchBarStrip.refresh()
        trackpad.invalidate()
    }

    fun setConnectionDetail(detail: String) {
        systemBar.refresh()
    }

    fun setHelperHealth(health: HelperHealth) {
        helperHealth = health
        systemBar.setHealth(health)
        setFrontmostApp(health.frontmostApp)
    }

    /** 可能来自网络线程；解码永远不在主线程执行。 */
    fun setTouchBarFrame(frame: TouchBarFrame) = touchBarStrip.setTouchBarFrame(frame)

    fun currentSkin(): Skin = config.skin

    /** Mac 前台 App，用于高亮当前 App；未知时不高亮。 */
    fun setFrontmostApp(bundleId: String?) {
        val normalized = bundleId?.takeIf { it.isNotBlank() }
        if (normalized == frontmostBundleId) return
        frontmostBundleId = normalized
        renderApps()
    }

    fun setMicrophoneState(state: MicrophoneStreamer.State) {
        microphoneState = state
        val card = microphoneCard ?: return
        val recording = state == MicrophoneStreamer.State.RECORDING
        card.isEnabled = true
        card.background = if (recording) recordingBackground() else buttonBackground(true)
        microphoneLabel?.text = when (state) {
            MicrophoneStreamer.State.STARTING -> "正在启动…"
            MicrophoneStreamer.State.RECORDING -> "松开转录"
            else -> "按住说话"
        }
        val foreground = if (recording) Color.WHITE else palette.onAccent
        microphoneLabel?.setTextColor(foreground)
        microphoneIcon?.setColorFilter(foreground)
        card.contentDescription = when (state) {
            MicrophoneStreamer.State.RECORDING -> "松开并转录 Typeless 听写"
            MicrophoneStreamer.State.STARTING -> "正在启动平板麦克风"
            else -> "按住使用平板麦克风听写"
        }
    }

    fun beginAppCatalog() {
        appCatalog.clear()
    }

    fun addRemoteApp(app: RemoteApp) {
        appCatalog.removeAll { it.bundleId == app.bundleId }
        appCatalog.add(app)
    }

    fun finishAppCatalog() {
        appCatalog.sortBy { it.name.lowercase(Locale.getDefault()) }
        renderApps()
    }

    /** 断线、退到后台或退出前调用：松开 05 皮肤上锁定的 Command / Shift。 */
    fun clearModifierLocks() {
        if (heldModifiers.isEmpty()) return
        val locked = heldModifiers.toList()
        heldModifiers.clear()
        locked.forEach { mask -> sinkProvider().key(0, mask, false) }
        rebuildBody()
    }

    /** 应用一份配置（本地修改或 Mac 推来的都走这里）。 */
    fun applyConfig(updated: PadConfig) {
        val skinChanged = updated.skin != config.skin
        config = updated
        palette = updated.skin.palette
        if (skinChanged) {
            buildLayout()
        } else {
            renderApps()
            renderCustomShortcuts()
        }
    }

    // endregion

    // region 布局

    private fun createSystemBar(): SystemBarView = SystemBarView(
        context = context,
        sinkProvider = sinkProvider,
        onSettingsClick = onSettingsClick,
    ).apply {
        setHealth(helperHealth)
    }

    private fun rebuildBody() = buildLayout()

    private fun buildLayout() {
        detach(trackpad)
        detach(touchBarStrip)
        removeAllViews()
        appGrid = null
        appDock = null
        customStrip = null
        microphoneCard = null
        microphoneIcon = null
        microphoneLabel = null

        setBackgroundColor(palette.background)
        trackpad.applyPalette(palette)
        systemBar = createSystemBar()
        systemBar.applyPalette(palette)

        when (config.skin) {
            Skin.CLASSIC -> buildClassic()
            Skin.GRAPHITE -> buildGraphite()
            Skin.TITANIUM -> buildTitanium()
        }
        setMicrophoneState(microphoneState)
    }

    /** 三套皮肤共用的顶行：01 经典黑排布——左侧状态栏，右边整行 Touch Bar。 */
    private fun buildHeaderRow(): View {
        touchBarStrip.applyPalette(palette, 0f)
        touchBarStrip.setPadding(dp(7), dp(3), 0, dp(3))
        return LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(palette.background)
            addView(systemBar, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            addView(touchBarStrip, LayoutParams(0, MATCH_PARENT, 1f))
        }
    }

    /** 01 经典黑：0.4.1 已验收的布局，改其它皮肤时不要动它。 */
    private fun buildClassic() {
        addView(buildHeaderRow(), LayoutParams(MATCH_PARENT, dp(34)))

        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(buildClassicControlPanel(), LayoutParams(0, MATCH_PARENT, 36f).apply {
                marginEnd = dp(12)
            })
            addView(trackpad, LayoutParams(0, MATCH_PARENT, 64f))
        }, LayoutParams(MATCH_PARENT, 0, 1f))
    }

    /** 02 深空专业：左触控、右快捷键、底部 App Dock。 */
    private fun buildGraphite() {
        addView(buildHeaderRow(), LayoutParams(MATCH_PARENT, dp(34)))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(16), dp(11), dp(16), 0)
            addView(trackpad, LayoutParams(0, MATCH_PARENT, 1f).apply { marginEnd = dp(11) })
            addView(buildCommandsPanel(columns = 5, keyHeight = 56, stackedVoice = false),
                LayoutParams(dp(268), MATCH_PARENT))
        }, LayoutParams(MATCH_PARENT, 0, 1f))
        addView(buildAppDock(), LayoutParams(MATCH_PARENT, dp(80)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            topMargin = dp(11)
            bottomMargin = dp(14)
        })
    }

    /** 05 双手操控：左 App 与编辑键、中央触控、右快捷键与语音。 */
    private fun buildTitanium() {
        addView(buildHeaderRow(), LayoutParams(MATCH_PARENT, dp(34)))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(15), dp(12), dp(15), dp(16))
            addView(buildTitaniumLeftPanel(), LayoutParams(dp(212), MATCH_PARENT).apply {
                marginEnd = dp(12)
            })
            addView(trackpad, LayoutParams(0, MATCH_PARENT, 1f))
            addView(buildCommandsPanel(columns = 3, keyHeight = 60, stackedVoice = true),
                LayoutParams(dp(212), MATCH_PARENT).apply { marginStart = dp(12) })
        }, LayoutParams(MATCH_PARENT, 0, 1f))
    }

    private fun buildClassicControlPanel(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        background = rounded(palette.background, palette.panelRadius, palette.outline)
        setPadding(dp(12), dp(9), dp(12), dp(10))

        addView(titleRow("常用 App", "自定义") { showAppPicker() }, matchFixed(dp(25)))
        val grid = GridLayout(context).apply { columnCount = 3 }
        appGrid = grid
        addView(grid, matchWrap(top = 2))
        renderApps()

        addView(View(context), LayoutParams(MATCH_PARENT, 0, 1f))

        addView(titleRow("Vibe Coding", "+") { showShortcutManager() }, matchFixed(dp(25), top = 3))
        val strip = LinearLayout(context).apply { orientation = HORIZONTAL }
        customStrip = strip
        addView(strip, matchWrap(top = 4))
        renderCustomShortcuts()
        addView(buildKeyGrid(codingKeys(), columns = 5, keyHeight = 48), matchWrap(top = 3))
        addView(buildVoiceRow(stacked = false, talkHeight = 66), matchFixed(dp(66), top = 8))
    }

    /** 02 / 05 右侧快捷键面板。 */
    private fun buildCommandsPanel(columns: Int, keyHeight: Int, stackedVoice: Boolean): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            background = rounded(palette.panel, palette.panelRadius, palette.outline)
            setPadding(dp(14), dp(14), dp(14), dp(14))
            addView(titleRow("Vibe Coding", "+") { showShortcutManager() }, matchFixed(dp(26)))
            val strip = LinearLayout(context).apply { orientation = HORIZONTAL }
            customStrip = strip
            addView(strip, matchWrap(top = 6))
            renderCustomShortcuts()
            val keys = if (columns == 3) directionKeys() else codingKeys()
            addView(buildKeyGrid(keys, columns, keyHeight), matchWrap(top = 8))
            addView(View(context), LayoutParams(MATCH_PARENT, 0, 1f))
            addView(
                buildVoiceRow(stacked = stackedVoice, talkHeight = if (stackedVoice) 56 else 60),
                LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) },
            )
        }

    /** 05 左侧：常用 App + 编辑与修饰键。 */
    private fun buildTitaniumLeftPanel(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        background = rounded(palette.panel, palette.panelRadius, palette.outline)
        setPadding(dp(13), dp(14), dp(13), dp(14))
        addView(titleRow("常用 App", "自定义") { showAppPicker() }, matchFixed(dp(26)))
        val grid = GridLayout(context).apply { columnCount = 3 }
        appGrid = grid
        addView(grid, matchWrap(top = 6))
        renderApps()
        addView(View(context), LayoutParams(MATCH_PARENT, 0, 1f))
        addView(TextView(context).apply {
            text = "编辑与修饰键"
            textSize = 11f
            setTextColor(palette.muted)
        }, matchFixed(dp(20), top = 8))
        addView(buildKeyGrid(editKeys(), columns = 2, keyHeight = 46), matchWrap(top = 6))
        addView(buildModifierLockRow(), matchWrap(top = 6))
        addView(TextView(context).apply {
            text = "左手 · 应用与编辑"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(palette.muted)
        }, matchFixed(dp(20), top = 8))
    }

    /** 02 底部 App Dock：一行九个，当前 App 用浅底与状态点强调。 */
    private fun buildAppDock(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(palette.panel, palette.panelRadius, palette.outline)
        setPadding(dp(15), dp(6), dp(15), dp(6))
        addView(LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "常用 App"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.muted)
            }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(TextView(context).apply {
                text = "自定义"
                textSize = 11f
                setTextColor(palette.accent)
                isClickable = true
                isFocusable = true
                setPadding(0, dp(4), dp(8), dp(4))
                setOnClickListener { showAppPicker() }
            }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }, LayoutParams(dp(62), WRAP_CONTENT).apply { marginEnd = dp(10) })
        val dock = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        appDock = dock
        addView(dock, LayoutParams(0, MATCH_PARENT, 1f))
        renderApps()
    }

    private fun titleRow(title: String, action: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(palette.muted)
            setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(0, MATCH_PARENT, 1f))
        addView(TextView(context).apply {
            text = action
            textSize = if (action == "+") 20f else 12f
            setTextColor(palette.accent)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
            background = rounded(Color.TRANSPARENT, 8f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }

    // endregion

    // region 常用 App

    private fun selectedApps(): List<RemoteApp> {
        val preferredIds = listOf(
            "com.todesktop.230313mzl4w4u92",
            "com.apple.Terminal",
            "com.apple.finder",
            "com.google.Chrome",
            "com.openai.codex",
        )
        val configured = config.apps.ifEmpty { preferredIds }
        val resolved = configured.mapNotNull { id -> appCatalog.firstOrNull { it.bundleId == id } }
        val fallback = listOf(
            RemoteApp("Cursor", "com.todesktop.230313mzl4w4u92"),
            RemoteApp("Terminal", "com.apple.Terminal"),
            RemoteApp("Finder", "com.apple.finder"),
            RemoteApp("Chrome", "com.google.Chrome"),
            RemoteApp("Codex", "com.openai.codex"),
        )
        return (resolved.ifEmpty { fallback }).take(PadConfig.MAX_APPS)
    }

    private fun renderApps() {
        val apps = selectedApps()
        appGrid?.let { grid ->
            grid.removeAllViews()
            apps.forEachIndexed { index, app -> addAppCell(grid, app, index) }
            val missingInRow = (3 - apps.size % 3) % 3
            repeat(missingInRow) { offset -> addAppSpacer(grid, apps.size + offset) }
        }
        appDock?.let { dock ->
            dock.removeAllViews()
            apps.forEachIndexed { index, app ->
                dock.addView(appCell(app), LayoutParams(0, dp(62), 1f).apply {
                    marginStart = if (index == 0) 0 else dp(4)
                })
            }
        }
    }

    private fun addAppSpacer(grid: GridLayout, index: Int) {
        grid.addView(View(context).apply { visibility = INVISIBLE }, GridLayout.LayoutParams().apply {
            width = 0
            height = dp(50)
            columnSpec = GridLayout.spec(index % 3, 1f)
            rowSpec = GridLayout.spec(index / 3)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun addAppCell(grid: GridLayout, app: RemoteApp?, index: Int) {
        grid.addView(appCell(app), GridLayout.LayoutParams().apply {
            width = 0
            height = dp(50)
            columnSpec = GridLayout.spec(index % 3, 1f)
            rowSpec = GridLayout.spec(index / 3)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun appCell(app: RemoteApp?): View {
        val frontmost = app != null && app.bundleId == frontmostBundleId
        return LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = if (frontmost) frontmostBackground() else buttonBackground(false)
            isClickable = true
            isFocusable = true
            contentDescription = when {
                app == null -> "自定义常用 App"
                frontmost -> "${app.name}，Mac 当前前台 App"
                else -> "打开 ${app.name}"
            }
            if (app?.iconPng != null) {
                addView(ImageView(context).apply {
                    setImageBitmap(BitmapFactory.decodeByteArray(app.iconPng, 0, app.iconPng.size))
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                }, LayoutParams(dp(24), dp(24)))
            } else {
                addView(TextView(context).apply {
                    text = if (app == null) "＋" else app.name.take(1).uppercase()
                    textSize = if (app == null) 25f else 18f
                    gravity = Gravity.CENTER
                    setTextColor(if (app == null) palette.muted else palette.accent)
                }, LayoutParams(dp(24), dp(24)))
            }
            addView(TextView(context).apply {
                text = app?.name ?: "自定义"
                textSize = 11f
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(if (frontmost) palette.text else palette.muted)
            }, LayoutParams(MATCH_PARENT, dp(18)))
            setOnClickListener {
                if (app == null) showAppPicker() else sinkProvider().launchApp(app.bundleId)
            }
        }
    }

    /** 供 Mac 设置以外的入口调用：平板设置弹层里的「选择常用 App」。 */
    fun openAppPicker() = showAppPicker()

    private fun showAppPicker() {
        if (appCatalog.isEmpty()) {
            sinkProvider().requestApps()
            AlertDialog.Builder(context)
                .setTitle("正在读取 Mac App")
                .setMessage("已向 VibePad Helper 请求受控 App 列表，请稍后再点“自定义”。\n也可以直接在 Mac 菜单栏的 VibePad 设置里选。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val selected = config.apps.toMutableSet()
        val labels = appCatalog.map { it.name }.toTypedArray()
        val checked = BooleanArray(appCatalog.size) { appCatalog[it].bundleId in selected }
        AlertDialog.Builder(context)
            .setTitle("选择常用 App（最多 ${PadConfig.MAX_APPS} 个）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) {
                    if (selected.size >= PadConfig.MAX_APPS) checked[which] = false
                    else selected += appCatalog[which].bundleId
                } else selected -= appCatalog[which].bundleId
            }
            .setPositiveButton("保存") { _, _ ->
                val ordered = appCatalog.map { it.bundleId }
                    .filter { it in selected }
                    .take(PadConfig.MAX_APPS)
                store.update { it.copy(apps = ordered) }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // endregion

    // region 快捷键

    private fun codingKeys() = listOf(
        KeyAction("Esc", HidKeys.ESCAPE),
        KeyAction("停止", HidKeys.C, HidModifiers.LEFT_CONTROL, secondary = "⌃C", warning = true),
        KeyAction("是 Y", HidKeys.Y),
        KeyAction("↑", HidKeys.UP),
        KeyAction("否 N", HidKeys.N),
        KeyAction("Space", HidKeys.SPACE, span = 2),
        KeyAction("←", HidKeys.LEFT),
        KeyAction("↓", HidKeys.DOWN),
        KeyAction("→", HidKeys.RIGHT),
        KeyAction("剪切", HidKeys.X, HidModifiers.LEFT_GUI, secondary = "⌘X"),
        KeyAction("复制", HidKeys.C, HidModifiers.LEFT_GUI, secondary = "⌘C"),
        KeyAction("粘贴", HidKeys.V, HidModifiers.LEFT_GUI, secondary = "⌘V"),
        KeyAction("删除", HidKeys.BACKSPACE, span = 2, secondary = "⌫"),
    )

    /** 05 右侧三列：编辑键搬到左侧面板，这里只留退出、确认与方向键。 */
    private fun directionKeys() = listOf(
        KeyAction("Esc", HidKeys.ESCAPE),
        KeyAction("停止", HidKeys.C, HidModifiers.LEFT_CONTROL, secondary = "⌃C", warning = true),
        KeyAction("是 Y", HidKeys.Y),
        KeyAction("否 N", HidKeys.N),
        KeyAction("↑", HidKeys.UP),
        KeyAction("Space", HidKeys.SPACE),
        KeyAction("←", HidKeys.LEFT),
        KeyAction("↓", HidKeys.DOWN),
        KeyAction("→", HidKeys.RIGHT),
    )

    private fun editKeys() = listOf(
        KeyAction("剪切", HidKeys.X, HidModifiers.LEFT_GUI, secondary = "⌘X"),
        KeyAction("复制", HidKeys.C, HidModifiers.LEFT_GUI, secondary = "⌘C"),
        KeyAction("粘贴", HidKeys.V, HidModifiers.LEFT_GUI, secondary = "⌘V"),
        KeyAction("删除", HidKeys.BACKSPACE, secondary = "⌫"),
    )

    private fun buildKeyGrid(actions: List<KeyAction>, columns: Int, keyHeight: Int): View =
        GridLayout(context).apply {
            columnCount = columns
            var column = 0
            var row = 0
            actions.forEach { action ->
                val span = action.span.coerceAtMost(columns)
                if (column + span > columns) {
                    row++
                    column = 0
                }
                addView(keyButton(action), GridLayout.LayoutParams().apply {
                    width = 0
                    height = dp(keyHeight)
                    columnSpec = GridLayout.spec(column, span, span.toFloat())
                    rowSpec = GridLayout.spec(row)
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
                column += span
                if (column >= columns) {
                    row++
                    column = 0
                }
            }
        }

    private fun keyButton(action: KeyAction) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        background = buttonBackground(false)
        isClickable = true
        isFocusable = true
        contentDescription = action.label + (action.secondary?.let { " $it" } ?: "")
        addView(TextView(context).apply {
            text = action.label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(if (action.warning) palette.warning else palette.text)
        }, LayoutParams(MATCH_PARENT, dp(16)))
        addView(TextView(context).apply {
            text = action.secondary.orEmpty()
            textSize = 9f
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = if (action.secondary == null) 0f else 0.82f
            setTextColor(if (action.warning) palette.warning else palette.muted)
        }, LayoutParams(MATCH_PARENT, dp(13)))
        setOnClickListener { sinkProvider().tapKey(action.usage, action.modifier) }
    }

    /**
     * 05 的 Command / Shift 是显式锁定开关：按下发送 keydown，再点一次发送 keyup。
     * 断线、退到后台和退出前由 [clearModifierLocks] 成对释放。
     */
    private fun buildModifierLockRow(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        listOf(
            "⌘ Command" to HidModifiers.LEFT_GUI,
            "⇧ Shift" to HidModifiers.LEFT_SHIFT,
        ).forEachIndexed { index, (label, mask) ->
            val locked = mask in heldModifiers
            val button = TextView(context).apply {
                text = label
                textSize = 11f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                setTextColor(if (locked) palette.onAccent else palette.text)
                background = if (locked) buttonBackground(true) else buttonBackground(false)
                contentDescription = if (locked) "$label 已锁定，点按松开" else "$label 锁定"
                setOnClickListener { toggleModifierLock(mask) }
            }
            addView(button, LayoutParams(0, dp(44), 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
    }

    private fun toggleModifierLock(mask: Int) {
        if (mask in heldModifiers) {
            heldModifiers.remove(mask)
            sinkProvider().key(0, mask, false)
        } else {
            heldModifiers.add(mask)
            sinkProvider().key(0, mask, true)
        }
        rebuildBody()
    }

    private fun renderCustomShortcuts() {
        val strip = customStrip ?: return
        strip.removeAllViews()
        val shortcuts = config.shortcuts
        strip.visibility = if (shortcuts.isEmpty()) GONE else VISIBLE
        shortcuts.take(5).forEachIndexed { index, shortcut ->
            strip.addView(actionButton(shortcut.label).apply {
                setOnClickListener { sinkProvider().tapKey(shortcut.usage, shortcut.modifiers) }
                setOnLongClickListener {
                    showShortcutEditor(index)
                    true
                }
            }, LayoutParams(0, dp(40), 1f).apply {
                if (index > 0) marginStart = dp(5)
            })
        }
    }

    private fun showShortcutManager() {
        val shortcuts = config.shortcuts
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        shortcuts.forEachIndexed { index, shortcut ->
            panel.addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(context).apply {
                    text = shortcut.label
                    textSize = 15f
                    setTextColor(Color.BLACK)
                }, LayoutParams(0, dp(48), 1f))
                listOf("↑", "↓", "编辑", "删除").forEach { action ->
                    addView(Button(context).apply {
                        text = action
                        textSize = 11f
                        isAllCaps = false
                        setOnClickListener {
                            when (action) {
                                "↑" -> if (index > 0) {
                                    moveShortcut(index, index - 1)
                                    showShortcutManager()
                                }
                                "↓" -> if (index < shortcuts.lastIndex) {
                                    moveShortcut(index, index + 1)
                                    showShortcutManager()
                                }
                                "编辑" -> showShortcutEditor(index)
                                "删除" -> store.update { current ->
                                    current.copy(shortcuts = current.shortcuts.toMutableList().apply {
                                        if (index in indices) removeAt(index)
                                    })
                                }
                            }
                        }
                    }, LayoutParams(WRAP_CONTENT, dp(42)))
                }
            }, LayoutParams(MATCH_PARENT, dp(52)))
        }
        AlertDialog.Builder(context)
            .setTitle("自定义快捷键")
            .setView(ScrollView(context).apply { addView(panel) })
            .setPositiveButton("添加") { _, _ -> showShortcutEditor(null) }
            .setNegativeButton("完成", null)
            .show()
    }

    private fun moveShortcut(from: Int, to: Int) {
        store.update { current ->
            val list = current.shortcuts.toMutableList()
            if (from !in list.indices || to !in list.indices) return@update current
            list.add(to, list.removeAt(from))
            current.copy(shortcuts = list)
        }
    }

    private fun showShortcutEditor(index: Int?) {
        val current = index?.let { config.shortcuts.getOrNull(it) }
        val keys = shortcutKeys()
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val label = EditText(context).apply {
            hint = "按钮名称"
            setText(current?.label.orEmpty())
        }
        val spinner = Spinner(context).apply {
            adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, keys.map { it.first })
            setSelection(keys.indexOfFirst { it.second == current?.usage }.coerceAtLeast(0))
        }
        val command = CheckBox(context).apply { text = "Command ⌘"; isChecked = current?.modifiers?.and(HidModifiers.LEFT_GUI) != 0 }
        val control = CheckBox(context).apply { text = "Control ⌃"; isChecked = current?.modifiers?.and(HidModifiers.LEFT_CONTROL) != 0 }
        val shift = CheckBox(context).apply { text = "Shift ⇧"; isChecked = current?.modifiers?.and(HidModifiers.LEFT_SHIFT) != 0 }
        val option = CheckBox(context).apply { text = "Option ⌥"; isChecked = current?.modifiers?.and(HidModifiers.LEFT_ALT) != 0 }
        panel.addView(label)
        panel.addView(spinner)
        panel.addView(command)
        panel.addView(control)
        panel.addView(shift)
        panel.addView(option)
        val dialog = AlertDialog.Builder(context)
            .setTitle(if (index == null) "添加快捷键" else "编辑快捷键")
            .setView(panel)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val title = label.text.toString().trim()
                if (title.isEmpty()) {
                    label.error = "请输入名称"
                    return@setOnClickListener
                }
                var modifiers = 0
                if (command.isChecked) modifiers = modifiers or HidModifiers.LEFT_GUI
                if (control.isChecked) modifiers = modifiers or HidModifiers.LEFT_CONTROL
                if (shift.isChecked) modifiers = modifiers or HidModifiers.LEFT_SHIFT
                if (option.isChecked) modifiers = modifiers or HidModifiers.LEFT_ALT
                val shortcut = CustomShortcut(title.take(12), keys[spinner.selectedItemPosition].second, modifiers)
                store.update { stored ->
                    val list = stored.shortcuts.toMutableList()
                    if (index == null) {
                        if (list.size < PadConfig.MAX_SHORTCUTS) list.add(shortcut)
                    } else if (index in list.indices) {
                        list[index] = shortcut
                    }
                    stored.copy(shortcuts = list)
                }
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun shortcutKeys() = listOf(
        "A" to HidKeys.A, "C" to HidKeys.C, "D" to HidKeys.D, "L" to HidKeys.L,
        "N" to HidKeys.N, "V" to HidKeys.V, "X" to HidKeys.X, "Y" to HidKeys.Y,
        "Enter" to HidKeys.ENTER, "Esc" to HidKeys.ESCAPE, "Tab" to HidKeys.TAB,
        "Space" to HidKeys.SPACE, "Backspace" to HidKeys.BACKSPACE,
        "↑" to HidKeys.UP, "↓" to HidKeys.DOWN, "←" to HidKeys.LEFT, "→" to HidKeys.RIGHT,
    )

    // endregion

    // region 语音与发送

    private fun buildVoiceRow(stacked: Boolean, talkHeight: Int): View = LinearLayout(context).apply {
        orientation = if (stacked) VERTICAL else HORIZONTAL
        val talk = buildMicrophoneCard()
        val send = buildSendButton()
        if (stacked) {
            addView(talk, LayoutParams(MATCH_PARENT, dp(talkHeight)))
            addView(send, LayoutParams(MATCH_PARENT, dp(46)).apply { topMargin = dp(8) })
        } else {
            addView(talk, LayoutParams(0, dp(talkHeight), 3f).apply { marginEnd = dp(5) })
            addView(send, LayoutParams(0, dp(talkHeight), 2f).apply { marginStart = dp(5) })
        }
    }

    private fun buildMicrophoneCard(): View = touchCard(prominent = true).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.ic_microphone_vibepad)
            setColorFilter(palette.onAccent)
        }
        addView(icon, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
        val label = TextView(context).apply {
            text = "按住说话"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.onAccent)
        }
        addView(label, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        contentDescription = "按住使用平板麦克风听写"
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    onMicrophonePressStart()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.isPressed = false
                    onMicrophonePressEnd()
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    onMicrophonePressEnd()
                    true
                }
                else -> true
            }
        }
        setOnClickListener { }
        microphoneCard = this
        microphoneIcon = icon
        microphoneLabel = label
    }

    private fun buildSendButton(): View = touchCard(prominent = false).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER
        addView(TextView(context).apply {
            text = "发送"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(palette.text)
        }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        addView(TextView(context).apply {
            text = "长按换行"
            textSize = 9f
            setTextColor(palette.muted)
        }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        contentDescription = "短按发送 Enter，长按发送 Shift Enter"
        var longTriggered = false
        val longPress = Runnable {
            longTriggered = true
            sinkProvider().tapKey(HidKeys.ENTER, HidModifiers.LEFT_SHIFT)
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    longTriggered = false
                    view.isPressed = true
                    handler.postDelayed(longPress, SEND_LONG_PRESS_MS)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longPress)
                    view.isPressed = false
                    if (!longTriggered) sinkProvider().tapKey(HidKeys.ENTER)
                    view.performClick()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    view.isPressed = false
                    true
                }
                else -> true
            }
        }
    }

    private fun touchCard(prominent: Boolean) = LinearLayout(context).apply {
        isClickable = true
        isFocusable = true
        clipToOutline = true
        background = buttonBackground(prominent)
        elevation = if (palette.light) 0f else dp(1).toFloat()
    }

    // endregion

    // region 样式工具

    private fun actionButton(label: String): Button = Button(context).apply {
        text = label
        textSize = 10f
        setTextColor(palette.text)
        isAllCaps = false
        gravity = Gravity.CENTER
        minHeight = 0
        minWidth = 0
        setPadding(dp(5), 0, dp(5), 0)
        background = buttonBackground(false)
    }

    private fun buttonBackground(prominent: Boolean) = StateListDrawable().apply {
        val normal = if (prominent) palette.accentStrong else palette.key
        val pressed = if (prominent) palette.accentPressed else palette.keyPressed
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, palette.keyRadius))
        addState(intArrayOf(), rounded(normal, palette.keyRadius))
    }

    private fun frontmostBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(palette.keyPressed, palette.keyRadius))
        addState(intArrayOf(), rounded(palette.accentSoft, palette.keyRadius, palette.accent))
    }

    private fun recordingBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFFD9342B.toInt(), palette.keyRadius))
        addState(intArrayOf(), rounded(palette.recording, palette.keyRadius))
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun detach(view: View) {
        (view.parent as? ViewGroup)?.removeView(view)
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()
    private fun dp(value: Float) = (value * density + 0.5f).toInt()
    private fun matchWrap(top: Int = 0) = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun matchFixed(height: Int, top: Int = 0) = LayoutParams(MATCH_PARENT, height).apply { topMargin = dp(top) }

    // endregion

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        store.addListener(configListener)
        val stored = store.current()
        if (!stored.sameContent(config)) applyConfig(stored)
    }

    override fun onDetachedFromWindow() {
        store.removeListener(configListener)
        super.onDetachedFromWindow()
    }

    private data class KeyAction(
        val label: String,
        val usage: Int,
        val modifier: Int = 0,
        val span: Int = 1,
        val secondary: String? = null,
        val warning: Boolean = false,
    )

    private companion object {
        const val SEND_LONG_PRESS_MS = 650L
    }
}
