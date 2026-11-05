package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import moe.kiwi.thinkos.R
import org.json.JSONObject
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * `toggle` and `slider`: the controls of the control sheet (ADR-035). The toggle only reports taps to its plugin; the slider
 * changes the device's own levels itself, because a finger on a slider must not wait for a Lua call.
 */
internal object DeviceComponents {
    fun createToggle(context: Context, declaration: JSONObject): RenderComponent = ToggleComponent(context, declaration)

    /** The sliders move system levels, so they only exist for the plugin that holds `system.device`. */
    fun createSlider(context: Context, pluginId: String, declaration: JSONObject, levels: DeviceLevels,
                     capabilityCheck: (String, String) -> Boolean): RenderComponent? {
        if (declaration.optString("type") != "slider") return null
        val allowed = { capabilityCheck(pluginId, "system.device") }
        if (!allowed()) return null
        return SliderComponent(context, declaration.optString("level"), levels, allowed)
    }

    /** The glyph and text colour on a filled button: whichever of white and black reads better on [fill]. */
    fun onFill(fill: Int): Int = if (TileColor.contrast(fill, Color.WHITE) >= TileColor.contrast(fill, Color.BLACK)) Color.WHITE else Color.BLACK
}

private class ToggleComponent(context: Context, private val definition: JSONObject) : RenderComponent {
    private val button = ToggleView(context)
    private val icon = Binding.compile(definition.optString("icon"))
    private val label = Binding.compile(definition.optString("label"))
    private val on = (definition.opt("on") as? String)?.let(Binding::compile)
    private val fixedOn = definition.opt("on") as? Boolean ?: false
    override val view: View get() = button
    override val defaultWeight = 1.0
    override val interactive = true

    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        button.glyph = icon.render(snapshot, variant, strings)
        button.text = label.render(snapshot, variant, strings)
        button.on = if (on != null) on.render(snapshot, variant, strings).let { it == "true" || it == "1" } else fixedOn
    }
    override fun colors(value: Palette) { button.palette = value }
}

/** A round button with a glyph and a one-line name under it. */
private class ToggleView(context: Context) : View(context) {
    var glyph = ""
        set(value) { if (field != value) { field = value; icon = Glyphs.drawable(context, value); invalidate() } }
    private var icon: Drawable? = null
    var text = ""
        set(value) { if (field != value) { field = value; contentDescription = value; invalidate() } }
    var on = false
        set(value) { if (field != value) { field = value; invalidate() } }
    var palette = Palette.DARK
        set(value) { field = value; invalidate() }
    private val dp = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TextScale.CAPTION * resources.displayMetrics.scaledDensity
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }

    init { isClickable = true; isFocusable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((72 * dp).toInt(), widthMeasureSpec), resolveSize((92 * dp).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val diameter = min(56 * dp, min(width.toFloat(), height - 24 * dp))
        val cx = width / 2f
        val cy = 2 * dp + diameter / 2
        val shrink = if (isPressed) .94f else 1f
        fill.color = if (on) palette.accent else palette.divider
        canvas.drawCircle(cx, cy, diameter / 2 * shrink, fill)
        val glyphColor = if (on) DeviceComponents.onFill(palette.accent) else palette.primary
        val half = diameter * .27f * shrink
        icon?.let {
            it.setTint(glyphColor)
            it.setBounds((cx - half).roundToInt(), (cy - half).roundToInt(), (cx + half).roundToInt(), (cy + half).roundToInt())
            it.draw(canvas)
        }
        labelPaint.color = if (on) palette.primary else palette.secondary
        val shown = TextUtils.ellipsize(text, android.text.TextPaint(labelPaint), width - 4 * dp, TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, cx, 2 * dp + diameter + 8 * dp - labelPaint.ascent(), labelPaint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.ToggleButton"
        info.isCheckable = true
        info.isChecked = on
    }
}

private class SliderComponent(context: Context, private val level: String, levels: DeviceLevels, allowed: () -> Boolean) : RenderComponent {
    private val bar = LevelSlider(context, level == "brightness", levels, allowed)
    override val view: View get() = bar
    override val defaultWeight = 1.0
    override val interactive = true
    override val handlesTap = true
    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) = bar.reread()
    override fun colors(value: Palette) { bar.palette = value }
    override fun capturesHorizontalAt(x: Float, y: Float) = true
    override fun localeChanged() { bar.contentDescription = bar.context.getString(if (level == "brightness") R.string.media_brightness else R.string.media_volume) }
    init { localeChanged() }
}

/**
 * A glyph and a track. The accessible range carries the percentage. It writes the system level as the finger moves and picks up changes
 * made elsewhere (the volume keys, the settings page) while it is on screen.
 */
internal class LevelSlider(context: Context, private val brightness: Boolean, private val levels: DeviceLevels, private val allowed: () -> Boolean) : View(context) {
    var palette = Palette.DARK
        set(value) { field = value; invalidate() }
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val brightnessIcon = Glyphs.drawable(context, "brightness")
    private val volumeIcon = Glyphs.drawable(context, "volume")
    private val mutedIcon = Glyphs.drawable(context, "volume-off")
    private var shown = 0f
    private var applied = -1f
    private var dragging = false
    private val poll = object : Runnable {
        override fun run() {
            if (!dragging) reread()
            if (isShown) postDelayed(this, 500)
        }
    }

    init { layoutDirection = LAYOUT_DIRECTION_LTR; isClickable = true; importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    /** The track covers the range the device allows: brightness never goes below its configured minimum. */
    private fun toFraction(level: Float): Float {
        if (!brightness) return level
        val low = levels.minimumBrightness
        return ((level - low) / (1f - low)).coerceIn(0f, 1f)
    }
    private fun fromFraction(fraction: Float): Float {
        if (!brightness) return fraction
        val low = levels.minimumBrightness
        return low + fraction * (1f - low)
    }
    private fun left() = 64 * dp
    private fun right() = width - 20 * dp

    fun reread() {
        val next = toFraction(if (brightness) levels.brightness else levels.volume)
        if (next != shown) { shown = next; invalidate() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); reread(); post(poll) }
    override fun onDetachedFromWindow() { removeCallbacks(poll); super.onDetachedFromWindow() }
    override fun onVisibilityAggregated(isVisible: Boolean) { super.onVisibilityAggregated(isVisible); if (isVisible) { reread(); removeCallbacks(poll); post(poll) } }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize((240 * dp).toInt(), widthMeasureSpec), resolveSize((56 * dp).toInt(), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val size = 28 * dp
        (if (brightness) brightnessIcon else if (shown <= 0f) mutedIcon else volumeIcon)?.let {
            it.setTint(palette.secondary)
            it.setBounds((20 * dp).roundToInt(), (cy - size / 2).roundToInt(), (20 * dp + size).roundToInt(), (cy + size / 2).roundToInt())
            it.draw(canvas)
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Stroke.TRACK * dp
        paint.color = palette.divider
        canvas.drawLine(left(), cy, right(), cy, paint)
        val x = left() + (right() - left()) * shown.coerceIn(0f, 1f)
        paint.color = palette.accent
        canvas.drawLine(left(), cy, x, cy, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, cy, if (dragging) 11 * dp else 8 * dp, paint)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        val value = if (brightness) fromFraction(shown) else shown
        info.className = android.widget.SeekBar::class.java.name
        info.rangeInfo = AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT,
            if (brightness) levels.minimumBrightness else 0f, 1f, value)
        info.text = NumberFormat.getPercentInstance(Locale.getDefault()).format(value.toDouble())
    }

    private fun move(x: Float, final: Boolean) {
        shown = ((x - left()) / (right() - left())).coerceIn(0f, 1f)
        // One system call per step of the finger rather than per touch event.
        if (final || abs(shown - applied) >= .02f) {
            if (allowed()) { if (brightness) levels.brightness = fromFraction(shown) else levels.volume = shown }
            applied = shown
        }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; move(event.x, false) }
            MotionEvent.ACTION_MOVE -> if (dragging) move(event.x, false)
            MotionEvent.ACTION_UP -> if (dragging) { move(event.x, true); dragging = false; reread() }
            MotionEvent.ACTION_CANCEL -> { dragging = false; reread(); invalidate() }
        }
        return true
    }
}
