package com.xiaoxi.vibepad.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.xiaoxi.vibepad.input.TouchBarFrame
import com.xiaoxi.vibepad.input.UsageSnapshot
import com.xiaoxi.vibepad.input.UsageWindow
import com.xiaoxi.vibepad.input.WifiInputSink
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * 顶部条：Mac 真实 Touch Bar 画面回传，或本地额度栏。三套皮肤共用同一份实现，
 * 只是摆放位置不同（经典黑与状态栏同一行，02 / 05 单独一行）。
 *
 * 设计原型里的固定 Touch Bar 标签、亮度与音量滑杆不实现：本项目回传的是 Mac 原生
 * Touch Bar 画面，触摸按归一化坐标回送，Mac 自己决定显示什么。
 */
class TouchBarStripView(
    context: Context,
    private val touchBarSinkProvider: () -> WifiInputSink?,
) : FrameLayout(context) {

    private val handler = Handler(Looper.getMainLooper())
    private val touchBarView = TouchBarImageView(context, touchBarSinkProvider)
    private val quotaContainer = LinearLayout(context)
    private val quotaViews = listOf(
        QuotaView(context, "Claude 5h"),
        QuotaView(context, "Claude 7d"),
        QuotaView(context, "Fable 5"),
        QuotaView(context, "Codex 5h"),
        QuotaView(context, "Codex 7d"),
    )
    private val pendingFrame = AtomicReference<QueuedTouchBarFrame?>()
    private val decodeScheduled = AtomicBoolean(false)
    private val receivedSequence = AtomicLong(0L)
    private var displayedSequence = 0L
    private var palette = Skin.CLASSIC.palette
    private var cornerRadiusDp = 0f
    private var subscribedSink: WifiInputSink? = null
    private var lastUsage = UsageSnapshot()

    @Volatile private var headerMode = HeaderMode.TOUCH_BAR
    @Volatile private var isWindowAttached = false
    @Volatile private var decodeGeneration = 0L
    @Volatile private var decodeExecutor: ExecutorService? = null

    init {
        quotaContainer.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            quotaViews.forEachIndexed { index, quota ->
                addView(quota, LinearLayout.LayoutParams(0, dp(27), 1f).apply {
                    marginStart = if (index == 0) 0 else dp(2)
                    marginEnd = if (index == quotaViews.lastIndex) 0 else dp(2)
                })
            }
        }
        addView(quotaContainer, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        addView(touchBarView, LayoutParams(MATCH_PARENT, MATCH_PARENT))
        applyPalette(palette, cornerRadiusDp)
        applyHeaderMode()
    }

    fun applyPalette(value: SkinPalette, cornerRadius: Float) {
        palette = value
        cornerRadiusDp = cornerRadius
        touchBarView.applyPalette(value)
        quotaViews.forEach { it.applyPalette(value) }
        background = GradientDrawable().apply {
            setColor(value.touchBar)
            this.cornerRadius = dp(cornerRadius).toFloat()
        }
    }

    fun setHeaderMode(mode: HeaderMode) {
        if (headerMode == mode) return
        headerMode = mode
        applyHeaderMode()
        syncSubscription()
    }

    fun headerMode(): HeaderMode = headerMode

    fun setUsage(usage: UsageSnapshot) {
        lastUsage = usage
        val windows = listOf(
            usage.claudeFiveHour, usage.claudeSevenDay, usage.claudeFable,
            usage.codexFiveHour, usage.codexWeekly,
        )
        quotaViews.zip(windows).forEach { (view, value) -> view.setUsage(value) }
    }

    /** 可能来自网络线程；解码永远不在主线程执行。 */
    fun setTouchBarFrame(frame: TouchBarFrame) {
        if (headerMode != HeaderMode.TOUCH_BAR || !isWindowAttached || frame.bytes.isEmpty()) return
        val queued = QueuedTouchBarFrame(
            sequence = receivedSequence.incrementAndGet(),
            generation = decodeGeneration,
            frame = frame,
        )
        pendingFrame.getAndSet(queued)
        scheduleDecode()
    }

    /** 连接状态变化后由外层调用，重新决定是否订阅 Touch Bar 画面。 */
    fun refresh() = syncSubscription()

    private fun applyHeaderMode() {
        touchBarView.visibility = if (headerMode == HeaderMode.TOUCH_BAR) VISIBLE else GONE
        quotaContainer.visibility = if (headerMode == HeaderMode.QUOTA) VISIBLE else GONE
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
                                headerMode == HeaderMode.TOUCH_BAR &&
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

    private fun syncSubscription() {
        val candidate = touchBarSinkProvider()
        val shouldSubscribe = isWindowAttached &&
            headerMode == HeaderMode.TOUCH_BAR &&
            candidate?.isConnected == true
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

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isWindowAttached = true
        if (decodeExecutor == null || decodeExecutor?.isShutdown == true) {
            decodeExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "VibePad-TouchBarDecode").apply { isDaemon = true }
            }
        }
        setUsage(lastUsage)
        syncSubscription()
    }

    override fun onDetachedFromWindow() {
        isWindowAttached = false
        subscribedSink?.setTouchBarSubscribed(false)
        subscribedSink = null
        clearTouchBar()
        decodeExecutor?.shutdownNow()
        decodeExecutor = null
        decodeScheduled.set(false)
        super.onDetachedFromWindow()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun dp(value: Float) = (value * resources.displayMetrics.density + 0.5f).toInt()

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
        private var idleColor = Color.BLACK

        init {
            setBackgroundColor(idleColor)
            scaleType = ScaleType.FIT_XY
            isClickable = true
            isFocusable = true
            contentDescription = "Mac Touch Bar"
        }

        fun applyPalette(palette: SkinPalette) {
            idleColor = palette.touchBar
            if (drawable == null) setBackgroundColor(idleColor)
            // 浅色皮肤（05 双手操控）下把 Mac 回传的深色 Touch Bar 画面反色，融入浅色主题
            colorFilter = if (palette.light) ColorMatrixColorFilter(INVERT_MATRIX) else null
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
            setBackgroundColor(idleColor)
        }

        companion object {
            private const val TOUCH_DOWN = 0
            private const val TOUCH_MOVE = 1
            private const val TOUCH_UP = 2
            private const val TOUCH_MOVE_INTERVAL_MS = 8L
            private const val NORMALIZED_MAX = 65_535f

            /** RGB 反色、Alpha 不动。 */
            private val INVERT_MATRIX = ColorMatrix(
                floatArrayOf(
                    -1f, 0f, 0f, 0f, 255f,
                    0f, -1f, 0f, 0f, 255f,
                    0f, 0f, -1f, 0f, 255f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        }
    }

    private class QuotaView(context: Context, private val label: String) : LinearLayout(context) {
        private val text = TextView(context)
        private val bar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
        private val density = resources.displayMetrics.density

        init {
            orientation = VERTICAL
            setPadding(dp(6), dp(2), dp(6), dp(2))
            addView(text.apply {
                this.text = "$label  --"
                textSize = 8.5f
                maxLines = 1
                gravity = Gravity.CENTER_VERTICAL
            }, LayoutParams(MATCH_PARENT, dp(15)))
            addView(bar.apply {
                max = 100
                progress = 0
            }, LayoutParams(MATCH_PARENT, dp(3)))
        }

        fun applyPalette(palette: SkinPalette) {
            background = GradientDrawable().apply {
                setColor(palette.key)
                cornerRadius = dp(5).toFloat()
            }
            text.setTextColor(palette.muted)
            bar.progressDrawable?.setTint(palette.accent)
        }

        fun setUsage(window: UsageWindow) {
            text.text = "$label  ${window.usedPercent?.let { "$it%" } ?: "--"}"
            bar.progress = window.usedPercent ?: 0
        }

        private fun dp(value: Int) = (value * density + 0.5f).toInt()
    }
}
