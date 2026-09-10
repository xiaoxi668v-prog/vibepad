package com.xiaoxi.vibepad.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import com.xiaoxi.vibepad.input.HidButtons
import com.xiaoxi.vibepad.input.InputSink
import com.xiaoxi.vibepad.input.TrackpadGesture
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt

class TrackpadView(
    context: Context,
    private val sinkProvider: () -> InputSink,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val prefs = context.getSharedPreferences("vibepad_ui", Context.MODE_PRIVATE)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bounds = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong() + 80L

    private var palette = Skin.CLASSIC.palette
    private var mode = Mode.IDLE
    private var twoFingerIntent = TwoFingerIntent.UNDECIDED
    private var downAt = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var accumulatedDistance = 0f
    private var pointerRemainderX = 0f
    private var pointerRemainderY = 0f
    private var scrollRemainderX = 0f
    private var scrollRemainderY = 0f
    private var twoFingerMaxTravel = 0f
    private var initialTwoFingerSpan = 0f
    private var lastTwoFingerSpan = 0f
    private var pinchAccumulator = 0f
    private var lastScrollSampleTime = 0L
    private var fingerVelocityX = 0f
    private var fingerVelocityY = 0f
    private var inertiaVelocityX = 0f
    private var inertiaVelocityY = 0f
    private var lastInertiaTime = 0L
    private var multiFingerCount = 0
    private var initialMultiSpan = 0f
    private var currentMultiSpan = 0f
    private var dragPressed = false

    private val beginDrag = Runnable {
        if (mode == Mode.ONE_FINGER && accumulatedDistance <= touchSlop) {
            sinkProvider().mouseButton(HidButtons.LEFT, true)
            dragPressed = true
            mode = Mode.DRAGGING
            invalidate()
        }
    }

    private val inertiaStep = object : Runnable {
        override fun run() {
            if (mode != Mode.IDLE && mode != Mode.IGNORE_UNTIL_UP) return
            val now = android.os.SystemClock.uptimeMillis()
            val elapsed = (now - lastInertiaTime).coerceIn(1L, 32L).toFloat()
            lastInertiaTime = now
            scrollRemainderX += inertiaVelocityX * elapsed
            scrollRemainderY += inertiaVelocityY * elapsed
            emitScroll()
            val decay = 0.90f.pow(elapsed / 16.67f)
            inertiaVelocityX *= decay
            inertiaVelocityY *= decay
            if (hypot(inertiaVelocityX, inertiaVelocityY) >= MIN_INERTIA_SPEED) {
                postOnAnimation(this)
            } else {
                inertiaVelocityX = 0f
                inertiaVelocityY = 0f
            }
        }
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "触控板"
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    /** 皮肤只改变触控区的配色与圆角，手势识别与阈值三套完全一致。 */
    fun applyPalette(value: SkinPalette) {
        palette = value
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val radius = palette.padRadius * density
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        paint.color = palette.pad
        canvas.drawRoundRect(bounds, radius, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = density
        paint.color = if (sinkProvider().isConnected) palette.padOnline else palette.padOutline
        canvas.drawRoundRect(
            density / 2f, density / 2f, width - density / 2f, height - density / 2f,
            radius, radius, paint,
        )
        paint.style = Paint.Style.FILL

        paint.textAlign = Paint.Align.CENTER
        paint.color = palette.padLabel
        paint.textSize = 16f * density
        canvas.drawText(if (dragPressed) "拖动中" else "TOUCHPAD", width / 2f, height / 2f, paint)
        paint.textSize = 12f * density
        canvas.drawText("双指自然滚动 · 三/四指系统手势", width / 2f, height / 2f + 28f * density, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelInertia()
                startOneFinger(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> when {
                event.pointerCount >= 3 -> startMultiFinger(event)
                event.pointerCount == 2 -> startTwoFinger(event)
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.ONE_FINGER, Mode.DRAGGING -> moveOneFinger(event)
                Mode.TWO_FINGER -> moveTwoFinger(event)
                Mode.MULTI_FINGER -> moveMultiFinger(event)
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> finishPointerTransition(event)
            MotionEvent.ACTION_UP -> finishGesture(event, cancelled = false)
            MotionEvent.ACTION_CANCEL -> finishGesture(event, cancelled = true)
        }
        return true
    }

    private fun startOneFinger(event: MotionEvent) {
        resetGestureState()
        mode = Mode.ONE_FINGER
        downAt = event.eventTime
        downX = event.x
        downY = event.y
        lastX = event.x
        lastY = event.y
        handler.postDelayed(beginDrag, longPressTimeout)
    }

    private fun moveOneFinger(event: MotionEvent) {
        for (historyIndex in 0 until event.historySize) {
            consumePointerSample(event.getHistoricalX(0, historyIndex), event.getHistoricalY(0, historyIndex))
        }
        consumePointerSample(event.getX(0), event.getY(0))
    }

    private fun consumePointerSample(x: Float, y: Float) {
        val rawDx = x - lastX
        val rawDy = y - lastY
        lastX = x
        lastY = y
        accumulatedDistance += hypot(rawDx, rawDy)
        if (accumulatedDistance > touchSlop) handler.removeCallbacks(beginDrag)

        val speed = hypot(rawDx, rawDy)
        val gain = when {
            speed < 2f * density -> 0.85f
            speed < 8f * density -> 1.05f
            else -> 1.30f
        }
        val sensitivity = prefs.getFloat(PREF_MOUSE_SENSITIVITY, 1f)
        pointerRemainderX += rawDx * gain * sensitivity
        pointerRemainderY += rawDy * gain * sensitivity
        val dx = pointerRemainderX.roundToInt()
        val dy = pointerRemainderY.roundToInt()
        pointerRemainderX -= dx
        pointerRemainderY -= dy
        if (dx != 0 || dy != 0) sinkProvider().move(dx, dy)
    }

    private fun startTwoFinger(event: MotionEvent) {
        handler.removeCallbacks(beginDrag)
        releaseDragIfNeeded()
        mode = Mode.TWO_FINGER
        twoFingerIntent = TwoFingerIntent.UNDECIDED
        downAt = event.eventTime
        val center = centroid(event, -1, 2)
        downX = center.first
        downY = center.second
        lastX = downX
        lastY = downY
        accumulatedDistance = 0f
        twoFingerMaxTravel = 0f
        scrollRemainderX = 0f
        scrollRemainderY = 0f
        initialTwoFingerSpan = distanceBetweenFirstTwo(event, -1)
        lastTwoFingerSpan = initialTwoFingerSpan
        pinchAccumulator = 0f
        lastScrollSampleTime = event.eventTime
        fingerVelocityX = 0f
        fingerVelocityY = 0f
        invalidate()
    }

    private fun moveTwoFinger(event: MotionEvent) {
        if (event.pointerCount < 2) return
        for (historyIndex in 0 until event.historySize) {
            consumeTwoFingerSample(
                centroid(event, historyIndex, 2),
                distanceBetweenFirstTwo(event, historyIndex),
                event.getHistoricalEventTime(historyIndex),
            )
        }
        consumeTwoFingerSample(centroid(event, -1, 2), distanceBetweenFirstTwo(event, -1), event.eventTime)
    }

    private fun consumeTwoFingerSample(point: Pair<Float, Float>, span: Float, eventTime: Long) {
        val dx = point.first - lastX
        val dy = point.second - lastY
        val spanDelta = span - lastTwoFingerSpan
        lastX = point.first
        lastY = point.second
        lastTwoFingerSpan = span
        accumulatedDistance += hypot(dx, dy)
        twoFingerMaxTravel = maxOf(twoFingerMaxTravel, hypot(point.first - downX, point.second - downY))

        if (twoFingerIntent == TwoFingerIntent.UNDECIDED) {
            val spanTravel = abs(span - initialTwoFingerSpan)
            val centerTravel = hypot(point.first - downX, point.second - downY)
            if (spanTravel > touchSlop * 1.25f) twoFingerIntent = TwoFingerIntent.PINCH
            else if (centerTravel > touchSlop) twoFingerIntent = TwoFingerIntent.SCROLL
        }

        when (twoFingerIntent) {
            TwoFingerIntent.PINCH -> {
                pinchAccumulator += spanDelta
                val step = PINCH_STEP_DP * density
                while (abs(pinchAccumulator) >= step) {
                    sinkProvider().gesture(if (pinchAccumulator > 0) TrackpadGesture.ZOOM_IN else TrackpadGesture.ZOOM_OUT)
                    pinchAccumulator += if (pinchAccumulator > 0) -step else step
                }
            }
            TwoFingerIntent.SCROLL -> consumeNaturalScroll(dx, dy, eventTime)
            TwoFingerIntent.UNDECIDED -> Unit
        }
    }

    private fun consumeNaturalScroll(dx: Float, dy: Float, eventTime: Long) {
        val elapsed = eventTime - lastScrollSampleTime
        if (elapsed in 1..50) {
            val instantX = dx / elapsed
            val instantY = dy / elapsed
            fingerVelocityX = fingerVelocityX * 0.62f + instantX * 0.38f
            fingerVelocityY = fingerVelocityY * 0.62f + instantY * 0.38f
        }
        lastScrollSampleTime = eventTime
        val gain = scrollGain()
        // Natural scrolling: content follows the fingers, matching macOS Trackpad settings.
        scrollRemainderX += dx * gain
        scrollRemainderY += dy * gain
        emitScroll()
    }

    private fun emitScroll() {
        val horizontal = scrollRemainderX.roundToInt().coerceIn(-MAX_SCROLL_STEP, MAX_SCROLL_STEP)
        val vertical = scrollRemainderY.roundToInt().coerceIn(-MAX_SCROLL_STEP, MAX_SCROLL_STEP)
        scrollRemainderX -= horizontal
        scrollRemainderY -= vertical
        if (vertical != 0 || horizontal != 0) sinkProvider().scroll(vertical, horizontal)
    }

    private fun startInertia() {
        if (twoFingerIntent != TwoFingerIntent.SCROLL) return
        val gain = scrollGain()
        inertiaVelocityX = fingerVelocityX * gain
        inertiaVelocityY = fingerVelocityY * gain
        if (hypot(inertiaVelocityX, inertiaVelocityY) < START_INERTIA_SPEED) return
        lastInertiaTime = android.os.SystemClock.uptimeMillis()
        removeCallbacks(inertiaStep)
        postOnAnimation(inertiaStep)
    }

    private fun scrollGain(): Float {
        val sensitivity = prefs.getFloat(PREF_TRACKPAD_SENSITIVITY, 1f).coerceIn(0.5f, 4f)
        return sensitivity / (2.2f * density)
    }

    private fun startMultiFinger(event: MotionEvent) {
        handler.removeCallbacks(beginDrag)
        releaseDragIfNeeded()
        mode = Mode.MULTI_FINGER
        multiFingerCount = event.pointerCount.coerceAtMost(4)
        downAt = event.eventTime
        val center = centroid(event, -1, multiFingerCount)
        downX = center.first
        downY = center.second
        lastX = downX
        lastY = downY
        initialMultiSpan = averageSpan(event, -1, multiFingerCount)
        currentMultiSpan = initialMultiSpan
        accumulatedDistance = 0f
    }

    private fun moveMultiFinger(event: MotionEvent) {
        if (event.pointerCount < 3) return
        val count = minOf(multiFingerCount, event.pointerCount)
        val center = centroid(event, -1, count)
        lastX = center.first
        lastY = center.second
        currentMultiSpan = averageSpan(event, -1, count)
        accumulatedDistance = hypot(lastX - downX, lastY - downY)
    }

    private fun finishMultiFinger(event: MotionEvent) {
        if (mode != Mode.MULTI_FINGER) return
        val dx = lastX - downX
        val dy = lastY - downY
        val spanDelta = currentMultiSpan - initialMultiSpan
        val swipeThreshold = MULTI_SWIPE_DP * density
        val pinchThreshold = MULTI_PINCH_DP * density
        val gesture = when {
            multiFingerCount >= 4 && abs(spanDelta) >= pinchThreshold && abs(spanDelta) > hypot(dx, dy) * 0.55f ->
                if (spanDelta > 0) TrackpadGesture.SHOW_DESKTOP else TrackpadGesture.OPEN_APPS
            hypot(dx, dy) >= swipeThreshold && abs(dx) > abs(dy) ->
                if (dx < 0) TrackpadGesture.NEXT_SPACE else TrackpadGesture.PREVIOUS_SPACE
            hypot(dx, dy) >= swipeThreshold ->
                if (dy < 0) TrackpadGesture.MISSION_CONTROL else TrackpadGesture.APP_EXPOSE
            event.eventTime - downAt <= tapTimeout && accumulatedDistance <= touchSlop * 1.5f ->
                TrackpadGesture.LOOK_UP
            else -> null
        }
        gesture?.let {
            sinkProvider().gesture(it)
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    private fun centroid(event: MotionEvent, historyIndex: Int, count: Int): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        val actualCount = minOf(count, event.pointerCount)
        for (index in 0 until actualCount) {
            x += xAt(event, index, historyIndex)
            y += yAt(event, index, historyIndex)
        }
        return Pair(x / actualCount, y / actualCount)
    }

    private fun distanceBetweenFirstTwo(event: MotionEvent, historyIndex: Int): Float = hypot(
        xAt(event, 0, historyIndex) - xAt(event, 1, historyIndex),
        yAt(event, 0, historyIndex) - yAt(event, 1, historyIndex),
    )

    private fun averageSpan(event: MotionEvent, historyIndex: Int, count: Int): Float {
        val center = centroid(event, historyIndex, count)
        var total = 0f
        val actualCount = minOf(count, event.pointerCount)
        for (index in 0 until actualCount) {
            total += hypot(xAt(event, index, historyIndex) - center.first, yAt(event, index, historyIndex) - center.second)
        }
        return total / actualCount
    }

    private fun xAt(event: MotionEvent, index: Int, historyIndex: Int): Float =
        if (historyIndex >= 0) event.getHistoricalX(index, historyIndex) else event.getX(index)

    private fun yAt(event: MotionEvent, index: Int, historyIndex: Int): Float =
        if (historyIndex >= 0) event.getHistoricalY(index, historyIndex) else event.getY(index)

    private fun finishPointerTransition(event: MotionEvent) {
        when (mode) {
            Mode.TWO_FINGER -> {
                if (event.pointerCount != 2) return
                if (event.eventTime - downAt <= tapTimeout && twoFingerMaxTravel <= touchSlop * 1.5f) {
                    sinkProvider().mouseButton(HidButtons.RIGHT, true)
                    sinkProvider().mouseButton(HidButtons.RIGHT, false)
                    performClick()
                } else {
                    startInertia()
                }
                mode = Mode.IGNORE_UNTIL_UP
            }
            Mode.MULTI_FINGER -> {
                finishMultiFinger(event)
                mode = Mode.IGNORE_UNTIL_UP
            }
            else -> Unit
        }
    }

    private fun finishGesture(event: MotionEvent, cancelled: Boolean) {
        handler.removeCallbacks(beginDrag)
        when {
            dragPressed -> sinkProvider().mouseButton(HidButtons.LEFT, false)
            !cancelled && mode == Mode.ONE_FINGER &&
                event.eventTime - downAt <= tapTimeout && accumulatedDistance <= touchSlop -> {
                sinkProvider().mouseButton(HidButtons.LEFT, true)
                sinkProvider().mouseButton(HidButtons.LEFT, false)
                performClick()
            }
        }
        resetGestureState()
        invalidate()
    }

    private fun releaseDragIfNeeded() {
        if (dragPressed) sinkProvider().mouseButton(HidButtons.LEFT, false)
        dragPressed = false
    }

    private fun cancelInertia() {
        removeCallbacks(inertiaStep)
        inertiaVelocityX = 0f
        inertiaVelocityY = 0f
    }

    private fun resetGestureState() {
        handler.removeCallbacks(beginDrag)
        mode = Mode.IDLE
        twoFingerIntent = TwoFingerIntent.UNDECIDED
        accumulatedDistance = 0f
        twoFingerMaxTravel = 0f
        pointerRemainderX = 0f
        pointerRemainderY = 0f
        dragPressed = false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(beginDrag)
        cancelInertia()
        releaseDragIfNeeded()
        super.onDetachedFromWindow()
    }

    private enum class Mode { IDLE, ONE_FINGER, DRAGGING, TWO_FINGER, MULTI_FINGER, IGNORE_UNTIL_UP }
    private enum class TwoFingerIntent { UNDECIDED, SCROLL, PINCH }

    private companion object {
        const val PREF_MOUSE_SENSITIVITY = "mouse_sensitivity"
        const val PREF_TRACKPAD_SENSITIVITY = "trackpad_sensitivity"
        const val PINCH_STEP_DP = 18f
        const val MULTI_SWIPE_DP = 46f
        const val MULTI_PINCH_DP = 30f
        const val START_INERTIA_SPEED = 0.035f
        const val MIN_INERTIA_SPEED = 0.006f
        const val MAX_SCROLL_STEP = 120
    }
}
