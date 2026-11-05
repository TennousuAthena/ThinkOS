package moe.kiwi.thinkos.ui.form

import moe.kiwi.thinkos.ui.Stroke
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import moe.kiwi.thinkos.ui.Palette
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** A 52×32dp switch. Mirrors in right-to-left layouts. */
internal class ToggleView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var position = 0f          // 0 = off, 1 = on
    private var animator: ValueAnimator? = null
    var palette = Palette.DARK
        set(value) { field = value; invalidate() }
    var checked = false
        private set

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun set(value: Boolean, animate: Boolean) {
        if (checked == value && !animate) return
        checked = value
        animator?.cancel()
        if (!animate || !isLaidOut || !ValueAnimator.areAnimatorsEnabled()) { position = if (value) 1f else 0f; invalidate(); return }
        animator = ValueAnimator.ofFloat(position, if (value) 1f else 0f).apply {
            duration = 160
            addUpdateListener { position = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) = setMeasuredDimension((52 * dp).toInt(), (32 * dp).toInt())

    override fun onDraw(canvas: Canvas) {
        val trackColor = Palette.mix(palette.divider, palette.accent, position)
        paint.color = trackColor
        val r = height / 2f
        canvas.drawRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), r, r, paint)
        paint.color = 0xffffffff.toInt()
        val travel = width - height
        val mirrored = layoutDirection == LAYOUT_DIRECTION_RTL
        val x = r + travel * (if (mirrored) 1f - position else position)
        canvas.drawCircle(x, r, r - 3 * dp, paint)
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}

/**
 * Horizontal slider that snaps to [step]. [onPreview] fires while dragging, [onCommit] once on release,
 * so a setting is only written when the finger lifts.
 */
internal class SliderView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var palette = Palette.DARK
        set(value) { field = value; invalidate() }
    var minimum = 0.0
    var maximum = 1.0
    var step = 1.0
    private var current = 0.0
    private var committed = 0.0
    private var tracking = false
    val isTracking: Boolean get() = tracking
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    /** The committed value; ignored while a finger is down so a refresh cannot yank the thumb. */
    var value: Double
        get() = current
        set(v) {
            committed = snap(v)
            if (!tracking) { current = committed; invalidate() }
        }
    var onPreview: (Double) -> Unit = {}
    var onCommit: (Double) -> Unit = {}
    var onCancel: () -> Unit = {}
    private var dragging = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun snap(v: Double): Double {
        val clamped = min(max(v, minimum), maximum)
        if (step <= 0) return clamped
        val snapped = minimum + Math.rint((clamped - minimum) / step) * step
        // Round away floating-point dust such as 0.30000000000000004.
        return min(max(Math.round(snapped * 1e6) / 1e6, minimum), maximum)
    }

    private fun fraction() = if (maximum > minimum) ((value - minimum) / (maximum - minimum)).toFloat() else 0f
    private fun mirrored() = layoutDirection == LAYOUT_DIRECTION_RTL

    private fun valueAt(x: Float): Double {
        val inset = 14 * dp
        val usable = max(1f, width - 2 * inset)
        var f = ((x - inset) / usable).coerceIn(0f, 1f)
        if (mirrored()) f = 1f - f
        return snap(minimum + f * (maximum - minimum))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (48 * dp).toInt())

    override fun onDraw(canvas: Canvas) {
        val inset = 14 * dp
        val cy = height / 2f
        val left = inset
        val right = width - inset
        val track = Stroke.TRACK * dp
        paint.color = palette.divider
        canvas.drawRoundRect(RectF(left, cy - track / 2, right, cy + track / 2), track, track, paint)
        val f = fraction()
        val x = if (mirrored()) right - (right - left) * f else left + (right - left) * f
        paint.color = palette.accent
        val (a, b) = if (mirrored()) x to right else left to x
        canvas.drawRoundRect(RectF(a, cy - track / 2, b, cy + track / 2), track, track, paint)
        canvas.drawCircle(x, cy, if (dragging) 13 * dp else 11 * dp, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { tracking = true; downX = event.x; downY = event.y }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (!dragging) {
                    val dx = abs(event.x - downX)
                    val dy = abs(event.y - downY)
                    if (max(dx, dy) <= touchSlop) return true
                    if (dy > dx) { cancelTracking(); return false }
                    dragging = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                update(event.x)
            }
            MotionEvent.ACTION_UP -> {
                if (!tracking) return false
                if (!dragging) performClick()
                update(event.x)
                tracking = false; dragging = false; committed = current
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                onCommit(value)
            }
            MotionEvent.ACTION_CANCEL -> cancelTracking()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun cancelTracking() {
        if (!tracking) return
        tracking = false; dragging = false
        current = committed
        parent?.requestDisallowInterceptTouchEvent(false)
        onPreview(current)
        onCancel()
        invalidate()
    }

    override fun onDetachedFromWindow() { cancelTracking(); super.onDetachedFromWindow() }
    fun cancelGesture() = cancelTracking()

    private fun update(x: Float) {
        val next = valueAt(x)
        if (abs(next - current) > 1e-9) { current = next; invalidate(); onPreview(next) }
    }
}
