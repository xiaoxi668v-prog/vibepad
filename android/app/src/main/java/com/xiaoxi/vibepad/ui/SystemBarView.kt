package com.xiaoxi.vibepad.ui

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.xiaoxi.vibepad.R
import com.xiaoxi.vibepad.input.HelperHealth
import com.xiaoxi.vibepad.input.InputSink
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 顶部状态栏。两种排布：
 *  - COMPACT：01 经典黑，时间 / Wi-Fi / 电量 / 设置挤在左侧，右边整行留给 Touch Bar。
 *  - FULL：02 深空专业与 05 双手操控，左侧品牌，右侧连接、时间、电量、沉浸和设置。
 *
 * 原型里的 Vibe Coding / 日常模式切换不实现：本项目的快捷键集合由用户自定义条决定，
 * 没有第二套模式可切。
 */
class SystemBarView(
    context: Context,
    private val sinkProvider: () -> InputSink,
    private val onSettingsClick: () -> Unit,
    style: Style,
    private val onImmersiveToggle: (() -> Unit)? = null,
) : LinearLayout(context) {

    enum class Style { COMPACT, FULL }

    private val density = resources.displayMetrics.density
    private val handler = Handler(Looper.getMainLooper())
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val time = TextView(context)
    private val brand = TextView(context)
    private val connectionDot = View(context)
    private val connectionText = TextView(context)
    private val accessPoint = ImageView(context)
    private val battery = BatteryStatusView(context)
    private val settingsButton = ImageButton(context)
    private val immersiveButton = TextView(context)
    private var palette = Skin.CLASSIC.palette
    private var networkConnected = false
    private var helperUsable = false
    private var immersive = false

    var connectionDetail: String = "正在初始化"
        set(value) {
            field = value
            connectionText.text = value
        }

    private val updater = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 15_000L)
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        when (style) {
            Style.COMPACT -> buildCompact()
            Style.FULL -> buildFull()
        }
        applyPalette(palette)
        refresh()
    }

    fun applyPalette(value: SkinPalette) {
        palette = value
        setBackgroundColor(value.background)
        time.setTextColor(value.text)
        brand.setTextColor(value.text)
        connectionText.setTextColor(value.muted)
        battery.applyPalette(value)
        settingsButton.setColorFilter(value.icon)
        immersiveButton.setTextColor(value.muted)
        immersiveButton.background = GradientDrawable().apply {
            setColor(value.key)
            cornerRadius = dp(9).toFloat()
        }
        updateConnectionVisuals()
    }

    fun setHealth(value: HelperHealth) {
        helperUsable = value.inputUsable
        updateConnectionVisuals()
    }

    fun setImmersive(value: Boolean) {
        immersive = value
        immersiveButton.text = if (value) "退出沉浸" else "沉浸触控"
        immersiveButton.contentDescription =
            if (value) "退出沉浸触控，恢复 App 与快捷键" else "进入沉浸触控，只保留状态栏、Touch Bar 和触控区"
    }

    fun refresh() {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val percent = if (level >= 0) level * 100 / scale else 0
        networkConnected = sinkProvider().isConnected
        time.text = timeFormat.format(Date())
        battery.setPercent(percent)
        updateConnectionVisuals()
    }

    private fun buildCompact() {
        setPadding(dp(12), 0, dp(12), 0)
        addView(LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), 0, dp(8), 0)
            addView(time.apply {
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }, LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(10) })
            addView(accessPoint.apply {
                setImageResource(R.drawable.ic_wifi_macos)
                contentDescription = "Wi-Fi 状态"
            }, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(9) })
            addView(battery, LayoutParams(dp(34), dp(18)))
            addView(settingsButton.apply {
                setImageResource(R.drawable.ic_settings_vibepad)
                contentDescription = "VibePad 设置"
                setPadding(dp(7), dp(7), dp(7), dp(7))
                background = null
                setOnClickListener { onSettingsClick() }
            }, LayoutParams(dp(28), dp(28)).apply { marginStart = dp(5) })
        }, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
    }

    private fun buildFull() {
        setPadding(dp(20), 0, dp(16), 0)
        addView(brand.apply {
            text = "VibePad"
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }, LayoutParams(WRAP_CONTENT, MATCH_PARENT))
        addView(View(context), LayoutParams(0, MATCH_PARENT, 1f))
        addView(connectionDot, LayoutParams(dp(6), dp(6)).apply { marginEnd = dp(7) })
        addView(connectionText.apply {
            text = connectionDetail
            textSize = 11f
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }, LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(14) })
        addView(time.apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
        }, LayoutParams(WRAP_CONTENT, MATCH_PARENT).apply { marginEnd = dp(12) })
        addView(battery, LayoutParams(dp(34), dp(18)).apply { marginEnd = dp(6) })
        if (onImmersiveToggle != null) {
            addView(immersiveButton.apply {
                textSize = 11f
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                minWidth = dp(72)
                setPadding(dp(12), 0, dp(12), 0)
                setOnClickListener { onImmersiveToggle.invoke() }
            }, LayoutParams(WRAP_CONTENT, dp(34)).apply { marginEnd = dp(4) })
            setImmersive(immersive)
        }
        addView(accessPoint.apply {
            setImageResource(R.drawable.ic_wifi_macos)
            contentDescription = "Wi-Fi 状态"
        }, LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(4) })
        addView(settingsButton.apply {
            setImageResource(R.drawable.ic_settings_vibepad)
            contentDescription = "VibePad 设置"
            setPadding(dp(9), dp(9), dp(9), dp(9))
            background = null
            setOnClickListener { onSettingsClick() }
        }, LayoutParams(dp(40), dp(40)))
    }

    private fun updateConnectionVisuals() {
        val color = when {
            networkConnected && helperUsable -> palette.padOnline
            networkConnected -> palette.muted
            else -> if (palette.light) 0xFFB4ADA2.toInt() else 0xFF666B75.toInt()
        }
        accessPoint.setColorFilter(color)
        accessPoint.alpha = 1f
        connectionDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        connectionText.setTextColor(palette.muted)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(updater)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(updater)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * density + 0.5f).toInt()

    private class BatteryStatusView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val body = RectF()
        private var percent = 0
        private var color = Color.WHITE

        fun applyPalette(palette: SkinPalette) {
            color = palette.text
            invalidate()
        }

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
            paint.color = color
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
