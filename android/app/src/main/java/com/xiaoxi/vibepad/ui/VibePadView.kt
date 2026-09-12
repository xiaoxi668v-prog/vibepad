package com.xiaoxi.vibepad.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.ArrayAdapter
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
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

class VibePadView(
    context: Context,
    private val sinkProvider: () -> InputSink,
    touchBarSinkProvider: () -> WifiInputSink? = { null },
    private val onSettingsClick: () -> Unit = {},
    private val onMicrophonePressStart: () -> Unit = {},
    private val onMicrophonePressEnd: () -> Unit = {},
) : LinearLayout(context) {
    private val density = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("vibepad_ui", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val appCatalog = mutableListOf<RemoteApp>()
    private var customShortcuts = loadShortcuts()
    private lateinit var appGrid: GridLayout
    private lateinit var customStrip: LinearLayout
    private lateinit var microphoneCard: LinearLayout
    private lateinit var microphoneIcon: ImageView
    private lateinit var microphoneLabel: TextView
    private val statusHeader = StatusHeaderView(
        context,
        sinkProvider,
        touchBarSinkProvider,
        onSettingsClick,
    )

    init {
        orientation = VERTICAL
        setBackgroundColor(COLOR_BACKGROUND)
        addView(statusHeader, LayoutParams(MATCH_PARENT, dp(34)))
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(10))
            addView(buildControlPanel(), LayoutParams(0, MATCH_PARENT, 36f).apply {
                marginEnd = dp(12)
            })
            addView(TrackpadView(context, sinkProvider), LayoutParams(0, MATCH_PARENT, 64f))
        }, LayoutParams(MATCH_PARENT, 0, 1f))
    }

    fun refreshConnectionState() = statusHeader.refresh()

    fun setConnectionDetail(detail: String) {
        statusHeader.connectionDetail = detail
        statusHeader.refresh()
    }

    fun setHelperHealth(health: HelperHealth) = statusHeader.setHealth(health)

    /** May be called from the network thread; decoding never runs on the UI thread. */
    fun setTouchBarFrame(frame: TouchBarFrame) = statusHeader.setTouchBarFrame(frame)

    fun setMicrophoneState(state: MicrophoneStreamer.State) {
        if (!::microphoneCard.isInitialized) return
        val recording = state == MicrophoneStreamer.State.RECORDING
        microphoneCard.isEnabled = true
        microphoneCard.background = when {
            recording -> recordingButtonBackground()
            else -> buttonBackground(true)
        }
        microphoneLabel.text = when (state) {
            MicrophoneStreamer.State.STARTING -> "正在启动…"
            MicrophoneStreamer.State.RECORDING -> "松开转录"
            else -> "按住说话"
        }
        val foreground = if (recording) Color.WHITE else COLOR_ON_PRIMARY
        microphoneLabel.setTextColor(foreground)
        microphoneIcon.setColorFilter(foreground)
        microphoneCard.contentDescription = when (state) {
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

    private fun buildControlPanel(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        background = rounded(COLOR_SURFACE, 14f, COLOR_OUTLINE)
        setPadding(dp(12), dp(9), dp(12), dp(10))

        addView(titleRow("常用 App", "自定义") { showAppPicker() }, matchFixed(dp(25)))
        appGrid = GridLayout(context).apply { columnCount = 3 }
        addView(appGrid, matchWrap(top = 2))
        renderApps()

        addView(View(context), LayoutParams(MATCH_PARENT, 0, 1f))

        addView(titleRow("Vibe Coding", "+") { showShortcutManager() }, matchFixed(dp(25), top = 3))
        customStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
        addView(customStrip, matchWrap(top = 4))
        renderCustomShortcuts()
        addView(buildKeyPanel(), matchWrap(top = 3))
        addView(buildBottomActions(), matchFixed(dp(66), top = 8))
    }

    private fun titleRow(title: String, action: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(TextView(context).apply {
            text = title
            textSize = 13f
            setTextColor(COLOR_MUTED)
            setTypeface(typeface, Typeface.BOLD)
        }, LayoutParams(0, MATCH_PARENT, 1f))
        addView(TextView(context).apply {
            text = action
            textSize = if (action == "+") 20f else 12f
            setTextColor(COLOR_PRIMARY)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, 0, 0)
            background = rounded(Color.TRANSPARENT, 8f)
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }

    private fun renderApps() {
        if (!::appGrid.isInitialized) return
        appGrid.removeAllViews()
        val preferredIds = listOf(
            "com.todesktop.230313mzl4w4u92",
            "com.apple.Terminal",
            "com.apple.finder",
            "com.google.Chrome",
            "com.openai.codex",
        )
        val configured = selectedAppIds().ifEmpty { preferredIds }
        val resolved = configured.mapNotNull { id -> appCatalog.firstOrNull { it.bundleId == id } }
        val fallback = listOf(
            RemoteApp("Cursor", "com.todesktop.230313mzl4w4u92"),
            RemoteApp("Terminal", "com.apple.Terminal"),
            RemoteApp("Finder", "com.apple.finder"),
            RemoteApp("Chrome", "com.google.Chrome"),
            RemoteApp("Codex", "com.openai.codex"),
        )
        val apps = (resolved.ifEmpty { fallback }).take(9)
        apps.forEachIndexed { index, app -> addAppCell(app, index) }
        val missingInRow = (3 - apps.size % 3) % 3
        repeat(missingInRow) { offset -> addAppSpacer(apps.size + offset) }
    }

    private fun addAppSpacer(index: Int) {
        appGrid.addView(View(context).apply { visibility = INVISIBLE }, GridLayout.LayoutParams().apply {
            width = 0
            height = dp(50)
            columnSpec = GridLayout.spec(index % 3, 1f)
            rowSpec = GridLayout.spec(index / 3)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun addAppCell(app: RemoteApp?, index: Int) {
        val cell = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            background = buttonBackground(false)
            isClickable = true
            isFocusable = true
            contentDescription = app?.let { "打开 ${it.name}" } ?: "自定义常用 App"
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
                    setTextColor(if (app == null) COLOR_MUTED else COLOR_PRIMARY)
                }, LayoutParams(dp(24), dp(24)))
            }
            addView(TextView(context).apply {
                text = app?.name ?: "自定义"
                textSize = 11f
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(COLOR_TEXT)
            }, LayoutParams(MATCH_PARENT, dp(18)))
            setOnClickListener {
                if (app == null) showAppPicker() else sinkProvider().launchApp(app.bundleId)
            }
        }
        appGrid.addView(cell, GridLayout.LayoutParams().apply {
            width = 0
            height = dp(50)
            columnSpec = GridLayout.spec(index % 3, 1f)
            rowSpec = GridLayout.spec(index / 3)
            setMargins(dp(4), dp(4), dp(4), dp(4))
        })
    }

    private fun showAppPicker() {
        if (appCatalog.isEmpty()) {
            sinkProvider().requestApps()
            AlertDialog.Builder(context)
                .setTitle("正在读取 Mac App")
                .setMessage("已向 VibePad Helper 请求受控 App 列表，请稍后再点“自定义”。")
                .setPositiveButton("知道了", null)
                .show()
            return
        }
        val selected = selectedAppIds().toMutableSet()
        val labels = appCatalog.map { it.name }.toTypedArray()
        val checked = BooleanArray(appCatalog.size) { appCatalog[it].bundleId in selected }
        AlertDialog.Builder(context)
            .setTitle("选择常用 App（最多 9 个）")
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                if (isChecked) {
                    if (selected.size >= 9) checked[which] = false else selected += appCatalog[which].bundleId
                } else selected -= appCatalog[which].bundleId
            }
            .setPositiveButton("保存") { _, _ ->
                val ordered = appCatalog.map { it.bundleId }.filter { it in selected }.take(9)
                prefs.edit().putString(PREF_APPS, JSONArray(ordered).toString()).apply()
                renderApps()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun selectedAppIds(): List<String> {
        val saved = prefs.getString(PREF_APPS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(saved)
            List(array.length()) { array.getString(it) }
        }.getOrDefault(emptyList())
    }

    private fun buildKeyPanel(): View = GridLayout(context).apply {
        columnCount = 5
        val actions = listOf(
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
        var column = 0
        var row = 0
        actions.forEach { action ->
            if (column + action.span > columnCount) {
                row++
                column = 0
            }
            addView(keyButton(action), GridLayout.LayoutParams().apply {
                width = 0
                height = dp(48)
                columnSpec = GridLayout.spec(column, action.span, action.span.toFloat())
                rowSpec = GridLayout.spec(row)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
            column += action.span
            if (column == columnCount) {
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
        addView(TextView(context).apply {
            text = action.label
            textSize = 11f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setTextColor(if (action.warning) COLOR_WARNING else COLOR_TEXT)
        }, LayoutParams(MATCH_PARENT, dp(16)))
        addView(TextView(context).apply {
            text = action.secondary.orEmpty()
            textSize = 9f
            gravity = Gravity.CENTER
            includeFontPadding = false
            alpha = if (action.secondary == null) 0f else 0.82f
            setTextColor(if (action.warning) COLOR_WARNING else COLOR_TEXT)
        }, LayoutParams(MATCH_PARENT, dp(13)))
        setOnClickListener { sinkProvider().tapKey(action.usage, action.modifier) }
    }

    private fun buildBottomActions(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        microphoneCard = touchCard(prominent = true).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            microphoneIcon = ImageView(context).apply {
                setImageResource(R.drawable.ic_microphone_vibepad)
            }
            addView(microphoneIcon, LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(8) })
            microphoneLabel = TextView(context).apply {
                text = "按住说话"
                textSize = 16f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(COLOR_ON_PRIMARY)
            }
            addView(microphoneLabel, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
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
        }
        addView(microphoneCard, LayoutParams(0, MATCH_PARENT, 3f).apply { marginEnd = dp(5) })
        addView(touchCard(prominent = false).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(context).apply {
                text = "发送"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(COLOR_TEXT)
            }, LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            addView(TextView(context).apply {
                text = "长按换行"
                textSize = 9f
                setTextColor(COLOR_MUTED)
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
        }, LayoutParams(0, MATCH_PARENT, 2f).apply { marginStart = dp(5) })
    }

    private fun touchCard(prominent: Boolean) = LinearLayout(context).apply {
        isClickable = true
        isFocusable = true
        clipToOutline = true
        background = buttonBackground(prominent)
        elevation = dp(1).toFloat()
    }

    private fun renderCustomShortcuts() {
        if (!::customStrip.isInitialized) return
        customStrip.removeAllViews()
        customStrip.visibility = if (customShortcuts.isEmpty()) GONE else VISIBLE
        customShortcuts.take(5).forEachIndexed { index, shortcut ->
            customStrip.addView(actionButton(shortcut.label, false).apply {
                textSize = 10f
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
        val panel = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("自定义快捷键")
            .setView(ScrollView(context).apply { addView(panel) })
            .setPositiveButton("添加") { _, _ -> showShortcutEditor(null) }
            .setNegativeButton("完成", null)
            .create()
        // Reorder/delete rebuild the list by reopening the dialog; dismiss the old one
        // first so dialogs do not stack up behind each other.
        fun reopen() {
            dialog.dismiss()
            showShortcutManager()
        }
        customShortcuts.forEachIndexed { index, shortcut ->
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
                                    val item = customShortcuts.removeAt(index)
                                    customShortcuts.add(index - 1, item)
                                    saveShortcuts()
                                    renderCustomShortcuts()
                                    reopen()
                                }
                                "↓" -> if (index < customShortcuts.lastIndex) {
                                    val item = customShortcuts.removeAt(index)
                                    customShortcuts.add(index + 1, item)
                                    saveShortcuts()
                                    renderCustomShortcuts()
                                    reopen()
                                }
                                "编辑" -> {
                                    dialog.dismiss()
                                    showShortcutEditor(index)
                                }
                                "删除" -> {
                                    customShortcuts.removeAt(index)
                                    saveShortcuts()
                                    renderCustomShortcuts()
                                    reopen()
                                }
                            }
                        }
                    }, LayoutParams(WRAP_CONTENT, dp(42)))
                }
            }, LayoutParams(MATCH_PARENT, dp(52)))
        }
        dialog.show()
    }

    private fun showShortcutEditor(index: Int?) {
        val current = index?.let(customShortcuts::get)
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
        // A new shortcut has no current modifiers; `null != 0` would tick every box.
        val currentModifiers = current?.modifiers ?: 0
        val command = CheckBox(context).apply { text = "Command ⌘"; isChecked = currentModifiers and HidModifiers.LEFT_GUI != 0 }
        val control = CheckBox(context).apply { text = "Control ⌃"; isChecked = currentModifiers and HidModifiers.LEFT_CONTROL != 0 }
        val shift = CheckBox(context).apply { text = "Shift ⇧"; isChecked = currentModifiers and HidModifiers.LEFT_SHIFT != 0 }
        val option = CheckBox(context).apply { text = "Option ⌥"; isChecked = currentModifiers and HidModifiers.LEFT_ALT != 0 }
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
                if (index == null) customShortcuts.add(shortcut) else customShortcuts[index] = shortcut
                saveShortcuts()
                renderCustomShortcuts()
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

    private fun loadShortcuts(): MutableList<CustomShortcut> {
        val json = prefs.getString(PREF_SHORTCUTS, null) ?: return mutableListOf()
        return runCatching {
            val array = JSONArray(json)
            MutableList(array.length()) { index ->
                val item = array.getJSONObject(index)
                CustomShortcut(item.getString("label"), item.getInt("usage"), item.getInt("modifiers"))
            }
        }.getOrDefault(mutableListOf())
    }

    private fun saveShortcuts() {
        val array = JSONArray()
        customShortcuts.forEach {
            array.put(JSONObject().put("label", it.label).put("usage", it.usage).put("modifiers", it.modifiers))
        }
        prefs.edit().putString(PREF_SHORTCUTS, array.toString()).apply()
    }

    private fun actionButton(label: String, prominent: Boolean): Button = Button(context).apply {
        text = label
        textSize = 12f
        setTextColor(if (prominent) COLOR_ON_PRIMARY else COLOR_TEXT)
        isAllCaps = false
        gravity = Gravity.CENTER
        minHeight = 0
        minWidth = 0
        setPadding(dp(5), 0, dp(5), 0)
        background = buttonBackground(prominent)
    }

    private fun buttonBackground(prominent: Boolean) = StateListDrawable().apply {
        val normal = if (prominent) COLOR_PRIMARY_STRONG else COLOR_PANEL
        val pressed = if (prominent) 0xFF62A6FF.toInt() else 0xFF3A3A3D.toInt()
        addState(intArrayOf(android.R.attr.state_pressed), rounded(pressed, 10f))
        addState(intArrayOf(), rounded(normal, 10f))
    }

    private fun recordingButtonBackground() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), rounded(0xFFD9342B.toInt(), 10f))
        addState(intArrayOf(), rounded(COLOR_RECORDING, 10f))
    }

    private fun rounded(color: Int, radiusDp: Float, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()
    private fun dp(value: Float) = (value * density + 0.5f).toInt()
    private fun matchWrap(top: Int = 0) = LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(top) }
    private fun matchFixed(height: Int, top: Int = 0) = LayoutParams(MATCH_PARENT, height).apply { topMargin = dp(top) }

    private data class KeyAction(
        val label: String,
        val usage: Int,
        val modifier: Int = 0,
        val span: Int = 1,
        val secondary: String? = null,
        val warning: Boolean = false,
    )

    private data class CustomShortcut(val label: String, val usage: Int, val modifiers: Int)

    /** 顶栏：时间、Wi-Fi/Helper 状态、电量、设置按钮，其余宽度显示 Mac Touch Bar 画面。 */
    private class StatusHeaderView(
        context: Context,
        private val sinkProvider: () -> InputSink,
        private val touchBarSinkProvider: () -> WifiInputSink?,
        onSettingsClick: () -> Unit,
    ) : LinearLayout(context) {
        var connectionDetail: String = "正在初始化"
        private val handler = Handler(Looper.getMainLooper())
        private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
        private val time = TextView(context)
        private val accessPoint = ImageView(context)
        private val battery = BatteryStatusView(context)
        private var networkConnected = false
        private var helperUsable = false
        @Volatile private var isWindowAttached = false
        private var subscribedSink: WifiInputSink? = null
        private val touchBarView = TouchBarImageView(context, touchBarSinkProvider)
        private val contentHost = FrameLayout(context)
        private val pendingFrame = AtomicReference<QueuedTouchBarFrame?>()
        private val decodeScheduled = AtomicBoolean(false)
        private val receivedSequence = AtomicLong(0L)
        private var displayedSequence = 0L
        @Volatile private var decodeGeneration = 0L
        @Volatile private var decodeExecutor: ExecutorService? = null
        private val updater = object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, 15_000L)
            }
        }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(context, 12), 0, dp(context, 12), 0)
            setBackgroundColor(COLOR_BACKGROUND)

            addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 4), 0, dp(context, 8), 0)
                addView(time.apply {
                    textSize = 12f
                    setTextColor(COLOR_TEXT)
                    setTypeface(typeface, Typeface.BOLD)
                    gravity = Gravity.START or Gravity.CENTER_VERTICAL
                }, LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(context, 10) })
                addView(accessPoint.apply {
                    setImageResource(R.drawable.ic_wifi_macos)
                    contentDescription = "Wi-Fi 状态"
                    alpha = 0.45f
                }, LayoutParams(dp(context, 18), dp(context, 18)).apply { marginEnd = dp(context, 9) })
                addView(battery, LayoutParams(dp(context, 34), dp(context, 18)))
                addView(ImageButton(context).apply {
                    setImageResource(R.drawable.ic_settings_vibepad)
                    contentDescription = "VibePad 设置"
                    setPadding(dp(context, 7), dp(context, 7), dp(context, 7), dp(context, 7))
                    background = null
                    setOnClickListener { onSettingsClick() }
                }, LayoutParams(dp(context, 28), dp(context, 28)).apply { marginStart = dp(context, 5) })
            }, LayoutParams(WRAP_CONTENT, MATCH_PARENT))

            contentHost.apply {
                setPadding(dp(context, 7), dp(context, 3), 0, dp(context, 3))
                addView(touchBarView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            }
            addView(contentHost, LayoutParams(0, MATCH_PARENT, 1f))
            refresh()
        }

        fun setHealth(value: HelperHealth) {
            helperUsable = value.inputUsable
            updateWifiColor()
        }

        fun setTouchBarFrame(frame: TouchBarFrame) {
            if (!isWindowAttached || frame.bytes.isEmpty()) return
            val queued = QueuedTouchBarFrame(
                sequence = receivedSequence.incrementAndGet(),
                generation = decodeGeneration,
                frame = frame,
            )
            pendingFrame.getAndSet(queued)
            scheduleDecode()
        }

        private fun scheduleDecode() {
            val executor = decodeExecutor ?: return
            if (!decodeScheduled.compareAndSet(false, true)) return
            try {
                executor.execute {
                    try {
                        while (!Thread.currentThread().isInterrupted) {
                            val queued = pendingFrame.getAndSet(null) ?: break
                            val bytes = queued.frame.bytes
                            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                            handler.post {
                                if (isWindowAttached &&
                                    queued.generation == decodeGeneration &&
                                    queued.sequence > displayedSequence
                                ) {
                                    displayedSequence = queued.sequence
                                    touchBarView.replaceBitmap(bitmap)
                                } else {
                                    bitmap.recycle()
                                }
                            }
                        }
                    } finally {
                        decodeScheduled.set(false)
                        if (pendingFrame.get() != null) scheduleDecode()
                    }
                }
            } catch (_: RejectedExecutionException) {
                decodeScheduled.set(false)
            }
        }

        fun refresh() {
            val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (level >= 0) level * 100 / scale else 0
            networkConnected = sinkProvider().isConnected
            time.text = timeFormat.format(Date())
            battery.setPercent(percent)
            updateWifiColor()
            syncSubscription()
        }

        private fun syncSubscription() {
            val candidate = touchBarSinkProvider()
            val shouldSubscribe = isWindowAttached && candidate?.isConnected == true
            if (shouldSubscribe && subscribedSink !== candidate) {
                subscribedSink?.setTouchBarSubscribed(false)
                candidate?.setTouchBarSubscribed(true)
                subscribedSink = candidate
            } else if (!shouldSubscribe && subscribedSink != null) {
                subscribedSink?.setTouchBarSubscribed(false)
                subscribedSink = null
                clearTouchBar()
            }
        }

        private fun clearTouchBar() {
            decodeGeneration++
            pendingFrame.set(null)
            displayedSequence = receivedSequence.get()
            touchBarView.clearBitmap()
        }

        private fun updateWifiColor() {
            val color = when {
                networkConnected && helperUsable -> 0xFF34C759.toInt()
                networkConnected -> COLOR_MUTED
                else -> 0xFF666B75.toInt()
            }
            accessPoint.setColorFilter(color)
            accessPoint.alpha = 1f
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            isWindowAttached = true
            if (decodeExecutor == null || decodeExecutor?.isShutdown == true) {
                decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
                    Thread(runnable, "VibePad-TouchBarDecode").apply { isDaemon = true }
                }
            }
            syncSubscription()
            handler.post(updater)
        }

        override fun onDetachedFromWindow() {
            isWindowAttached = false
            subscribedSink?.setTouchBarSubscribed(false)
            subscribedSink = null
            clearTouchBar()
            decodeExecutor?.shutdownNow()
            decodeExecutor = null
            decodeScheduled.set(false)
            handler.removeCallbacks(updater)
            super.onDetachedFromWindow()
        }

        private data class QueuedTouchBarFrame(
            val sequence: Long,
            val generation: Long,
            val frame: TouchBarFrame,
        )

        private class TouchBarImageView(
            context: Context,
            private val sinkProvider: () -> WifiInputSink?,
        ) : ImageView(context) {
            private var activePointerId = MotionEvent.INVALID_POINTER_ID
            private var lastMoveSentAt = 0L
            private var lastX = 0f
            private var lastY = 0f

            init {
                setBackgroundColor(Color.BLACK)
                scaleType = ScaleType.FIT_XY
                isClickable = true
                isFocusable = true
                contentDescription = "Mac Touch Bar"
            }

            override fun onTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        activePointerId = event.getPointerId(0)
                        lastMoveSentAt = event.eventTime
                        send(TOUCH_DOWN, event.x, event.y)
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val index = event.findPointerIndex(activePointerId)
                        if (index >= 0 && event.eventTime - lastMoveSentAt >= TOUCH_MOVE_INTERVAL_MS) {
                            lastMoveSentAt = event.eventTime
                            send(TOUCH_MOVE, event.getX(index), event.getY(index))
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val index = event.findPointerIndex(activePointerId).coerceAtLeast(0)
                        send(TOUCH_UP, event.getX(index), event.getY(index))
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                        performClick()
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        send(TOUCH_UP, lastX, lastY)
                        activePointerId = MotionEvent.INVALID_POINTER_ID
                    }
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> Unit
                }
                return true
            }

            override fun performClick(): Boolean {
                super.performClick()
                return true
            }

            private fun send(phase: Int, x: Float, y: Float) {
                lastX = x
                lastY = y
                val normalizedX = if (width > 0) {
                    (x.coerceIn(0f, width.toFloat()) / width * NORMALIZED_MAX).roundToInt()
                } else 0
                val normalizedY = if (height > 0) {
                    (y.coerceIn(0f, height.toFloat()) / height * NORMALIZED_MAX).roundToInt()
                } else 0
                sinkProvider()?.sendTouchBarEvent(phase, normalizedX, normalizedY)
            }

            fun replaceBitmap(bitmap: android.graphics.Bitmap) {
                setImageBitmap(bitmap)
            }

            fun clearBitmap() {
                setImageDrawable(null)
                setBackgroundColor(Color.BLACK)
            }

            companion object {
                private const val TOUCH_DOWN = 0
                private const val TOUCH_MOVE = 1
                private const val TOUCH_UP = 2
                private const val TOUCH_MOVE_INTERVAL_MS = 8L
                private const val NORMALIZED_MAX = 65_535f
            }
        }

        private class BatteryStatusView(context: Context) : View(context) {
            private val density = resources.displayMetrics.density
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            private val body = RectF()
            private var percent = 0

            fun setPercent(value: Int) {
                percent = value.coerceIn(0, 100)
                contentDescription = "电量 $percent%"
                invalidate()
            }

            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val terminalWidth = 2.5f * density
                body.set(
                    0.75f * density,
                    2.25f * density,
                    width - terminalWidth - 1.5f * density,
                    height - 2.25f * density,
                )
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.15f * density
                paint.color = COLOR_TEXT
                canvas.drawRoundRect(body, 2.2f * density, 2.2f * density, paint)
                paint.style = Paint.Style.FILL
                canvas.drawRoundRect(
                    body.right + 1.1f * density,
                    height / 2f - 2.4f * density,
                    width - 0.5f * density,
                    height / 2f + 2.4f * density,
                    0.8f * density,
                    0.8f * density,
                    paint,
                )
                paint.textAlign = Paint.Align.CENTER
                paint.textSize = 7.2f * density
                paint.typeface = Typeface.DEFAULT_BOLD
                val baseline = body.centerY() - (paint.ascent() + paint.descent()) / 2f
                canvas.drawText("$percent%", body.centerX(), baseline, paint)
            }
        }
    }

    companion object {
        private const val PREF_APPS = "selected_apps"
        private const val PREF_SHORTCUTS = "custom_shortcuts"
        private const val SEND_LONG_PRESS_MS = 650L
        private const val COLOR_BACKGROUND = 0xFF000000.toInt()
        private const val COLOR_SURFACE = 0xFF000000.toInt()
        private const val COLOR_PANEL = 0xFF2A2A2A.toInt()
        private const val COLOR_OUTLINE = 0xFF414754.toInt()
        private const val COLOR_TEXT = 0xFFE2E2E2.toInt()
        private const val COLOR_MUTED = 0xFFC0C6D6.toInt()
        private const val COLOR_PRIMARY = 0xFFAAC7FF.toInt()
        private const val COLOR_PRIMARY_STRONG = 0xFF3E90FF.toInt()
        private const val COLOR_ON_PRIMARY = 0xFF002957.toInt()
        private const val COLOR_WARNING = 0xFFFFB4AB.toInt()
        private const val COLOR_RECORDING = 0xFFFF3B30.toInt()
        private fun dp(context: Context, value: Int) =
            (value * context.resources.displayMetrics.density + 0.5f).toInt()
    }
}
