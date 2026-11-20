package moe.kiwi.thinkos.ui.form

import moe.kiwi.thinkos.ui.Stroke
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.perf.InputTrace
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
    private val trackBounds = RectF()
    var palette = Palette.DARK
        set(value) { field = value; invalidate() }
    var minimum = 0.0
    var maximum = 1.0
    var step = 1.0
    private var current = 0.0
    private var committed = 0.0
    private var dragFraction: Float? = null
    private var snapAnimator: ValueAnimator? = null
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
            if (!tracking) {
                if (current != committed) snapAnimator?.cancel()
                current = committed
                invalidate()
            }
        }
    var onPreview: (Double) -> Unit = {}
    var onCommit: (Double) -> Unit = {}
    var onCancel: () -> Unit = {}
    private var dragging = false

    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }

    fun snap(v: Double): Double = SliderMath.snap(v, minimum, maximum, step)

    private fun fraction() = SliderMath.fraction(current, minimum, maximum)
    private fun mirrored() = layoutDirection == LAYOUT_DIRECTION_RTL
    private fun trackInset() = min(14 * dp, width / 2f)
    private fun thumbX(fraction: Float): Float = SliderMath.xAt(fraction, trackInset(), width - trackInset(), mirrored())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (48 * dp).toInt())

    override fun onDraw(canvas: Canvas) {
        val inset = trackInset()
        val cy = height / 2f
        val left = inset
        val right = width - inset
        val track = Stroke.TRACK * dp
        paint.color = palette.divider
        trackBounds.set(left, cy - track / 2, right, cy + track / 2)
        canvas.drawRoundRect(trackBounds, track, track, paint)
        val displayedFraction = dragFraction ?: (snapAnimator?.animatedValue as? Float) ?: fraction()
        val x = thumbX(displayedFraction)
        paint.color = palette.accent
        if (mirrored()) trackBounds.left = x else trackBounds.right = x
        canvas.drawRoundRect(trackBounds, track, track, paint)
        canvas.drawCircle(x, cy, if (dragging) 13 * dp else 11 * dp, paint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                snapAnimator?.cancel()
                tracking = true
                downX = event.x
                downY = event.y
            }
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
                val releasedFraction = dragFraction ?: fraction()
                val animateSnap = dragging
                dragFraction = null
                tracking = false; dragging = false; committed = current
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                if (animateSnap) settle(releasedFraction)
                onCommit(value)
            }
            MotionEvent.ACTION_CANCEL -> cancelTracking()
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    private fun cancelTracking() {
        snapAnimator?.cancel()
        if (!tracking) return
        tracking = false; dragging = false
        dragFraction = null
        current = committed
        parent?.requestDisallowInterceptTouchEvent(false)
        onPreview(current)
        onCancel()
        invalidate()
    }

    override fun onDetachedFromWindow() { cancelTracking(); super.onDetachedFromWindow() }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelTracking()
    }
    fun cancelGesture() = cancelTracking()

    private fun update(x: Float) {
        val inset = trackInset()
        val nextFraction = if (maximum > minimum) SliderMath.fractionAt(x, inset, width - inset, mirrored()) else 0f
        if (nextFraction != dragFraction) {
            dragFraction = nextFraction
            invalidate()
            InputTrace.applied(InputTrace.SLIDER, thumbX(nextFraction))
        }
        val next = snap(minimum + nextFraction * (maximum - minimum))
        if (abs(next - current) > 1e-9) { current = next; onPreview(next) }
    }

    private fun settle(releasedFraction: Float) {
        val target = fraction()
        if (!ValueAnimator.areAnimatorsEnabled() || abs(thumbX(releasedFraction) - thumbX(target)) <= 2 * dp) return
        val animator = ValueAnimator.ofFloat(releasedFraction, target).apply {
            duration = 80
            interpolator = Motion.EASE
            addUpdateListener { invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    snapAnimator = null
                    invalidate()
                }
            })
        }
        snapAnimator = animator
        animator.start()
    }
}

internal object SliderMath {
    fun fraction(value: Double, minimum: Double, maximum: Double): Float =
        if (maximum > minimum) ((value - minimum) / (maximum - minimum)).toFloat().coerceIn(0f, 1f) else 0f

    fun fractionAt(x: Float, left: Float, right: Float, rtl: Boolean): Float {
        val fraction = if (right > left) ((x - left) / (right - left)).coerceIn(0f, 1f) else 0f
        return if (rtl) 1f - fraction else fraction
    }

    fun xAt(fraction: Float, left: Float, right: Float, rtl: Boolean): Float =
        left + (right - left) * if (rtl) 1f - fraction else fraction

    fun snap(value: Double, minimum: Double, maximum: Double, step: Double): Double {
        val clamped = min(max(value, minimum), maximum)
        if (step <= 0) return clamped
        val snapped = minimum + Math.rint((clamped - minimum) / step) * step
        return min(max(Math.round(snapped * 1e6) / 1e6, minimum), maximum)
    }
}
