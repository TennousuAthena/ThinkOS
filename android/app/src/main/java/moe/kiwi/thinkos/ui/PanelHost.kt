package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginWidget
import kotlin.math.abs

/**
 * The control sheet (ADR-035): a layer above the page that is showing, a dimming scrim and a sheet that slides down from the top
 * edge. It is *not* a page change, so the page below keeps running (a film keeps playing, a cast keeps its connection) and never
 * hears `page.leave`. The sheet's contents are an ordinary page of the plugin that holds the `panel` role.
 */
internal class PanelHost(
    context: Context,
    private val events: PageEvents,
    private val state: (String) -> org.json.JSONObject,
    private val dimBackground: () -> Boolean = { false },
    /** A finger is on the sheet: the display stays attentive. */
    private val interaction: () -> Unit,
) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val scrim = View(context).apply { setBackgroundColor(Color.BLACK); alpha = 0f; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val shape = GradientDrawable().apply { val r = 32 * dp; cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r) }
    private val sheet = Sheet(context)
    private var content: PageView? = null
    private var palette = Palette.DARK
    private var night = 0f
    private var settle: ValueAnimator? = null
    private var velocity: VelocityTracker? = null

    /** 0 closed, 1 open. */
    var progress = 0f
        private set
    /** True from the first pixel of a pull until the sheet is fully gone. */
    val showing: Boolean get() = visibility == VISIBLE
    val animating: Boolean get() = settle != null || showing && content?.activeAnimations == true
    fun debugTextMetrics(): org.json.JSONObject = content?.panelTextMetrics() ?: org.json.JSONObject()
    private var targetOpen = false
    private val idleClose = Runnable { if (targetOpen) close() }

    init {
        visibility = GONE
        addView(scrim, LayoutParams(-1, -1))
        addView(sheet, LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        sheet.background = shape
        sheet.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isClickable = true
    }

    private inner class Sheet(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val page = content
            if (page == null) { setMeasuredDimension(0, 0); return }
            val (w, h) = page.panelSize()
            page.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            setMeasuredDimension(w, h)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) { content?.layout(0, 0, r - l, b - t) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        content?.panelScreen(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        place()
    }

    /** Sets up the sheet's page for [page]; false when the plugin that owns the panel is not running. */
    fun prepare(page: PluginPage?, widgets: List<PluginWidget>): Boolean {
        if (page == null) return false
        val existing = content
        if (existing == null || existing.page.key != page.key) {
            existing?.let { sheet.removeView(it) }
            content = PageView(context, page, "main", widgets, events, state).also {
                sheet.addView(it, LayoutParams(-2, -2))
                it.colors(palette); it.night(night)
            }
        } else existing.update(page, widgets)
        return true
    }

    /** The plugin's declarations changed (hot update, language): keep the sheet in step. */
    fun refresh(page: PluginPage?, widgets: List<PluginWidget>) {
        if (page == null) { close(false); content?.let { sheet.removeView(it) }; content = null; return }
        if (content != null) prepare(page, widgets)
    }

    /** A finger went down in the edge zone and has begun to pull: show the sheet at progress 0 and follow the finger. */
    fun beginPull(screenWidth: Int, screenHeight: Int) {
        settle?.cancel(); settle = null
        removeCallbacks(idleClose)
        if (!showing) {
            visibility = VISIBLE
            content?.panelScreen(screenWidth, screenHeight)
            measure(MeasureSpec.makeMeasureSpec(screenWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(screenHeight, MeasureSpec.EXACTLY))
            layout(0, 0, screenWidth, screenHeight)
            progress = 0f
            content?.active(true)
            events.panelChanged(true)
        }
        velocity?.recycle(); velocity = VelocityTracker.obtain()
        place()
    }

    /** [distance] is how far below the top edge the finger is, in pixels. */
    fun pullTo(distance: Float, event: MotionEvent) {
        velocity?.addMovement(event)
        val height = sheet.height.coerceAtLeast(1)
        progress = (distance / height).coerceIn(0f, 1f)
        place()
    }

    /** The finger lifted ([cancelled] when something else took the gesture): settle open or closed. */
    fun release(cancelled: Boolean) {
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000)
        val speed = if (cancelled) 0f else tracker?.yVelocity ?: 0f
        tracker?.recycle(); velocity = null
        val open = !cancelled && PanelGeometry.settlesOpen(progress, speed, resources.displayMetrics.density, targetOpen)
        settleTo(if (open) 1f else 0f, speed / sheet.height.coerceAtLeast(1))
    }

    /** The same release rule without opening the sheet, used by the onboarding practice. */
    fun pullWouldOpen(distance: Float, speed: Float, screenWidth: Int, screenHeight: Int): Boolean {
        val page = content ?: return false
        page.panelScreen(screenWidth, screenHeight)
        val extent = page.panelSize().second.coerceAtLeast(1)
        return PanelGeometry.settlesOpen((distance / extent).coerceIn(0f, 1f), speed, dp, false)
    }

    fun open() {
        if (!showing) beginPull(width.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels, height.takeIf { it > 0 } ?: resources.displayMetrics.heightPixels)
        settleTo(1f, 0f)
    }

    fun close(animated: Boolean = true) {
        if (!showing) return
        removeCallbacks(idleClose)
        if (animated && !MotionOff.reduced(context)) settleTo(0f, 0f)
        else { settle?.cancel(); settle = null; progress = 0f; finish(false) }
    }

    private fun settleTo(target: Float, velocityPerSecond: Float) {
        settle?.cancel()
        targetOpen = target >= 1f
        removeCallbacks(idleClose)
        if (MotionOff.reduced(context)) { progress = target; place(); finish(targetOpen); return }
        settle = Motion.spring(progress, target, velocityPerSecond, { progress = it.coerceIn(0f, 1.04f); place() }) { settle = null; progress = target; place(); finish(targetOpen) }
    }

    private fun finish(open: Boolean) {
        targetOpen = open
        if (open) { scheduleIdle(); return }
        visibility = GONE
        content?.active(false)
        events.panelChanged(false)
    }

    private fun scheduleIdle() { removeCallbacks(idleClose); postDelayed(idleClose, events.panelIdleMillis) }

    /** Puts the sheet and scrim where [progress] says. */
    private fun place() {
        val h = sheet.height.toFloat()
        // A little overshoot (spring) is allowed, but never a gap above the sheet.
        sheet.translationY = -(1f - progress.coerceAtMost(1f)) * h
        scrim.alpha = if (dimBackground()) progress.coerceIn(0f, 1f) * SCRIM_ALPHA else 0f
    }

    // ---- touches on the open sheet ----
    private var downX = 0f
    private var downY = 0f
    private var closing = false
    private var closeFrom = 0f
    private var moved = false

    private var lastInteraction = 0L
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (showing) {
            // A drag on a slider is activity too, but the sensors need to hear about it twice a second, not sixty times.
            val now = SystemClock.uptimeMillis()
            if (event.actionMasked != MotionEvent.ACTION_MOVE || now - lastInteraction >= 500) { lastInteraction = now; interaction() }
            if (targetOpen && settle == null) scheduleIdle()
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTouch(event)
            MotionEvent.ACTION_MOVE -> if (!closing && pulledUp(event)) { startClosing(event); return true }
        }
        return false
    }

    private fun beginTouch(event: MotionEvent) {
        downX = event.x; downY = event.y; closing = false; moved = false
        // A finger on the sheet while it is still settling takes it over.
        if (settle != null) { settle?.cancel(); settle = null; targetOpen = progress > .5f }
    }

    private fun pulledUp(event: MotionEvent): Boolean {
        val up = downY - event.y
        return up > slop && up > abs(event.x - downX)
    }

    private fun startClosing(event: MotionEvent) {
        closing = true
        closeFrom = event.y
        velocity?.recycle(); velocity = VelocityTracker.obtain().also { it.addMovement(event) }
        removeCallbacks(idleClose)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> beginTouch(event)
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) moved = true
                if (!closing && pulledUp(event)) startClosing(event)
                if (closing) {
                    velocity?.addMovement(event)
                    val dragged = (closeFrom - event.y).coerceAtLeast(0f)
                    progress = (1f - dragged / sheet.height.coerceAtLeast(1)).coerceIn(0f, 1f)
                    place()
                }
            }
            MotionEvent.ACTION_UP -> {
                if (closing) { closing = false; release(false) }
                else if (!moved && event.y > sheet.bottom + sheet.translationY) close()      // a tap on the scrim
                else if (progress < 1f && settle == null) settleTo(if (progress > .5f) 1f else 0f, 0f)
                else if (targetOpen) scheduleIdle()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (closing) { closing = false; release(true) }
                else if (progress < 1f && settle == null) settleTo(if (progress > .5f) 1f else 0f, 0f)
            }
        }
        return true
    }

    // ---- what the stage forwards ----
    fun colors(value: Palette) { palette = value; shape.setColor(value.surface); content?.colors(value) }
    fun night(amount: Float) { night = amount; content?.night(amount) }
    fun bind(pluginId: String, paths: Set<String>) { content?.bind(pluginId, paths) }
    fun tick(now: Long, attention: Attention) { if (showing) content?.tick(now, attention) }
    fun localeChanged() { content?.localeChanged() }

    override fun onDetachedFromWindow() {
        removeCallbacks(idleClose)
        settle?.cancel(); settle = null
        velocity?.recycle(); velocity = null
        super.onDetachedFromWindow()
    }

    private companion object { const val SCRIM_ALPHA = .5f }
}

/** The system's "remove animations" setting: motion is skipped, the sheet just appears and goes. */
internal object MotionOff {
    fun reduced(context: Context): Boolean =
        runCatching { android.provider.Settings.Global.getFloat(context.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
}
