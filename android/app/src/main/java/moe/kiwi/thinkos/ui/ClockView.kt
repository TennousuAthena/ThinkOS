package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.core.WallClock
import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.view.View
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.anim.MotionPrefs
import moe.kiwi.thinkos.anim.PropertyBreath
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.i18n.Formats
import moe.kiwi.thinkos.i18n.TimeLayout
import moe.kiwi.thinkos.tz.ZoneClock
import moe.kiwi.thinkos.tz.ZoneFormat
import moe.kiwi.thinkos.tz.ZoneNames
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONObject
import java.util.Locale
import kotlin.math.*

/** One dial of the world clock. [label] overrides the OS's localised city name. */
data class ClockZone(val id: String, val label: String? = null)

/**
 * The live clock the OS offers every plugin. Time zones come from the bundled IANA database (so daylight
 * saving is right even where the ROM's is stale), text follows the system locale and 12/24-hour setting,
 * and city names come from the platform's CLDR data. Plugins choose a style, a format and the zones.
 */
class ClockView(context: Context, private val declaration: JSONObject, private val compact: Boolean) : View(context) {
    var palette = Palette.DARK
        set(value) { if (field != value) { field = value; dialTint.colorFilter = PorterDuffColorFilter(value.secondary, PorterDuff.Mode.SRC_IN); invalidate() } }
    var clockStyle = "digital"
        set(value) { if (field != value) { field = value; lastBucket = -1; dialCache = null; updatePulse(); invalidate() } }

    private val dp = resources.displayMetrics.density
    private val dialTint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = PorterDuffColorFilter(palette.secondary, PorterDuff.Mode.SRC_IN) }
    private var dialCache: Bitmap? = null
    private val pulse = PropertyBreath(this, .82f)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); fontFeatureSettings = "tnum" }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif", Typeface.NORMAL) }
    private val photoOutline = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private var now = WallClock.now()
    private var lastBucket = -1L
    private var attention = Attention.AWAKE
    private var sun: JSONObject? = null
    private val daylight = DaylightPainter()

    fun setSun(value: JSONObject?) { sun = value; invalidate() }

    private var formatSpec = declaration.optString("format", "@time").ifBlank { "@time" }
    private var layout = TimeLayout.parse(Formats.pattern(formatSpec))
    private var zones: List<ClockZone> = declaration.optJSONArray("zones")?.let { array ->
        (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.optString("timezone")?.takeIf { it.isNotBlank() && "{{" !in it }?.let { ClockZone(it) } }
    }.orEmpty()

    private class Parts(val hour: String, val minute: String, val second: String?, val period: String?, val separator: String, val periodFirst: Boolean) {
        val main: String get() = hour + separator + minute + (second?.let { separator + it } ?: "")
    }
    private var parts = Parts("--", "--", null, null, ":", false)
    /** Roll transition: only the characters that changed move, clipped to their own cell, translation only. */
    private var rollFrom: String? = null
    private var rollStart = 0L
    private var rollMode = "roll"

    init { setLayerType(LAYER_TYPE_HARDWARE, null); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES }

    fun setFormat(spec: String) {
        val next = spec.ifBlank { "@time" }
        if (next == formatSpec) return
        formatSpec = next
        refreshLocale()
    }

    fun setZones(next: List<ClockZone>) {
        if (next == zones) return
        zones = next
        dialCache = null
        lastBucket = -1
        invalidate()
    }

    /** Call when the system locale or 12/24-hour switch may have changed. */
    fun refreshLocale() {
        layout = TimeLayout.parse(Formats.pattern(formatSpec))
        dialCache = null
        lastBucket = -1
        invalidate()
    }

    fun tick(monotonic: Long, level: Attention) {
        attention = level
        val wall = WallClock.now()
        val interval = when {
            level == Attention.ASLEEP -> 60_000L
            clockStyle == "analog" || clockStyle == "float" -> 1_000L
            layout.second != null -> 1_000L
            else -> 60_000L
        }
        val bucket = wall / interval
        if (bucket != lastBucket) {
            val first = lastBucket < 0L
            lastBucket = bucket
            now = wall
            val synced = wall >= SYNCED_AFTER
            val home = Zones.home()
            val next = if (synced) Parts(
                ZoneFormat.format(layout.hour, wall, home), ZoneFormat.format(layout.minute, wall, home),
                layout.second?.let { ZoneFormat.format(it, wall, home) }, layout.period?.let { ZoneFormat.format(it, wall, home) },
                layout.separator, layout.periodFirst,
            ) else Parts("--", "--", null, null, ":", false)
            val changed = next.main != parts.main || next.period != parts.period
            if (changed && !first && clockStyle == "digital" && MotionPrefs.clock != "none" && next.main.length == parts.main.length &&
                level in setOf(Attention.AWAKE, Attention.ENGAGED) && android.animation.ValueAnimator.areAnimatorsEnabled()
            ) { rollFrom = parts.main; rollStart = SystemClock.uptimeMillis(); rollMode = MotionPrefs.clock }
            parts = next
            if (changed || first) contentDescription = if (synced) ZoneFormat.format("@time", wall, home) else "--:--"
            if (first || changed || clockStyle in setOf("analog", "solar", "world", "float")) invalidate()
        }
        updatePulse()
    }

    private fun updatePulse() {
        val active = isAttachedToWindow && clockStyle == "digital" && attention in setOf(Attention.AWAKE, Attention.ENGAGED) && android.animation.ValueAnimator.areAnimatorsEnabled()
        if (active) pulse.start()
        else {
            pulse.stop()
            alpha = 1f
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); updatePulse() }
    override fun onDetachedFromWindow() { pulse.stop(); super.onDetachedFromWindow() }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { dialCache = null }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        paint.color = palette.primary
        if (now < SYNCED_AFTER && clockStyle != "float") { text(canvas, "--:--", width / 2f, height / 2f, min(width * .25f, height * .5f)); return }
        when (clockStyle) {
            "analog" -> {
                cachedDial(canvas)
                analog(canvas, width / 2f, height / 2f, min(width, height) * .44f, Zones.home(), true)
            }
            "solar" -> solar(canvas)
            "world" -> world(canvas)
            "float" -> digital(canvas)
            else -> digital(canvas)
        }
    }

    private fun drawMain(canvas: Canvas, text: String, x: Float, baseline: Float) {
        if (clockStyle == "float") { drawClockText(canvas, text, x, baseline, paint); return }
        val from = rollFrom
        if (from == null || from.length != text.length) { canvas.drawText(text, x, baseline, paint); return }
        val t = ((SystemClock.uptimeMillis() - rollStart).toFloat() / Motion.NUMBER).coerceIn(0f, 1f)
        if (t >= 1f) { rollFrom = null; canvas.drawText(text, x, baseline, paint); return }
        val e = Motion.EASE.getInterpolation(t)
        val top = baseline + paint.ascent()
        val bottom = baseline + paint.descent()
        val h = bottom - top
        val base = paint.color
        fun glyph(source: String, i: Int, cx: Float, dy: Float, alpha: Float) {
            paint.color = base
            paint.alpha = (255 * alpha).toInt()
            canvas.drawText(source, i, i + 1, cx, baseline + dy, paint)
        }
        for (i in text.indices) {
            val cx = x + paint.measureText(text, 0, i)
            if (text[i] == from[i]) { glyph(text, i, cx, 0f, 1f); continue }
            val w = paint.measureText(text, i, i + 1)
            canvas.save()
            when (rollMode) {
                "fade" -> { glyph(from, i, cx, 0f, 1 - e); glyph(text, i, cx, 0f, e) }
                "drop" -> { glyph(from, i, cx, h * .25f * e, 1 - e); glyph(text, i, cx, -h * .25f * (1 - e), e) }
                "zoom" -> {
                    val mx = cx + w / 2f
                    val my = (top + bottom) / 2f
                    canvas.save(); canvas.scale(1f + .3f * e, 1f + .3f * e, mx, my); glyph(from, i, cx, 0f, 1 - e); canvas.restore()
                    canvas.scale(.7f + .3f * e, .7f + .3f * e, mx, my); glyph(text, i, cx, 0f, e)
                }
                "flip" -> {
                    val my = (top + bottom) / 2f
                    if (t < .5f) { canvas.scale(1f, 1f - t * 2f, cx, my); glyph(from, i, cx, 0f, 1f) }
                    else { canvas.scale(1f, t * 2f - 1f, cx, my); glyph(text, i, cx, 0f, 1f) }
                }
                else -> {
                    canvas.clipRect(cx, top, cx + w, bottom)
                    glyph(from, i, cx, -e * h, 1f)
                    glyph(text, i, cx, (1 - e) * h, 1f)
                }
            }
            canvas.restore()
        }
        paint.color = base
        postInvalidateOnAnimation()
    }

    private fun digital(canvas: Canvas) {
        val p = parts
        val photo = clockStyle == "float"
        val portrait = !photo && !compact && height > width * 1.1f && p.second == null
        if (portrait) {
            val size = min(width * .67f, height * .39f)
            text(canvas, p.hour, width / 2f, height * .31f, size)
            text(canvas, p.minute, width / 2f, height * .72f, size)
            p.period?.let { text(canvas, it, width / 2f, height * .93f, size * .22f, palette.secondary) }
            return
        }
        val size = min(height * (if (compact) .61f else .76f), width / 2.65f)
        paint.textSize = size
        val period = p.period
        val gap = size * .12f
        val periodSize = size * .3f
        val labelWidth = if (period == null) 0f else { labelPaint.textSize = periodSize; labelPaint.measureText(period) + gap }
        val total = paint.measureText(p.main) + labelWidth
        val available = if (photo) (width - 2 * dp).coerceAtLeast(0f) else width * .94f
        val fit = if (total > available) available / total else 1f
        paint.textSize = size * fit
        labelPaint.textSize = periodSize * fit
        val mainWidth = paint.measureText(p.main)
        val periodWidth = if (period == null) 0f else labelPaint.measureText(period) + gap * fit
        val start = if (photo) width - dp - mainWidth - periodWidth else (width - (mainWidth + periodWidth)) / 2f
        val baseline = height / 2f - (paint.ascent() + paint.descent()) / 2
        paint.textAlign = Paint.Align.LEFT
        labelPaint.textAlign = Paint.Align.LEFT
        labelPaint.color = palette.secondary
        paint.color = palette.primary
        if (period != null && p.periodFirst) {
            drawClockText(canvas, period, start, baseline, labelPaint)
            drawMain(canvas, p.main, start + periodWidth, baseline)
        } else {
            drawMain(canvas, p.main, start, baseline)
            if (period != null) drawClockText(canvas, period, start + mainWidth + gap * fit, baseline, labelPaint)
        }
    }

    private fun text(canvas: Canvas, value: String, cx: Float, cy: Float, size: Float, color: Int = palette.primary, maxWidth: Float = width * .94f, p: Paint = paint) {
        p.color = color
        p.textSize = size
        p.textAlign = Paint.Align.CENTER
        val measured = p.measureText(value)
        if (measured > maxWidth) p.textSize = size * maxWidth / measured
        drawClockText(canvas, value, cx, cy - (p.ascent() + p.descent()) / 2, p)
    }

    /** Photo clocks stay white on either theme; a narrow black contour survives bright photo detail. */
    private fun drawClockText(canvas: Canvas, value: String, x: Float, baseline: Float, p: Paint) {
        if (clockStyle != "float") { canvas.drawText(value, x, baseline, p); return }
        photoOutline.set(p)
        photoOutline.color = 0xBB000000.toInt()
        photoOutline.style = Paint.Style.STROKE
        photoOutline.strokeWidth = 1.25f * dp
        photoOutline.strokeJoin = Paint.Join.ROUND
        canvas.drawText(value, x, baseline, photoOutline)
        val color = p.color
        p.color = Color.WHITE
        canvas.drawText(value, x, baseline, p)
        p.color = color
    }

    private fun analog(canvas: Canvas, cx: Float, cy: Float, r: Float, zone: ZoneClock, seconds: Boolean) {
        val f = zone.fields(now)
        fun hand(angle: Double, length: Float, stroke: Float, color: Int) {
            line.strokeWidth = stroke; line.color = color; line.alpha = 255
            canvas.drawLine(cx, cy, cx + (cos(angle - Math.PI / 2) * length).toFloat(), cy + (sin(angle - Math.PI / 2) * length).toFloat(), line)
        }
        hand(((f.hour % 12) + f.minute / 60.0) * Math.PI / 6, r * .50f, r * .04f, palette.primary)
        hand((f.minute + f.second / 60.0) * Math.PI / 30, r * .76f, r * .027f, palette.primary)
        if (seconds && attention != Attention.ASLEEP) hand(f.second * Math.PI / 30, r * .80f, r * .009f, palette.accent)
        paint.color = palette.accent
        canvas.drawCircle(cx, cy, r * .04f, paint)
    }

    private fun drawDial(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        line.color = Color.WHITE; line.alpha = 60; line.strokeWidth = dp * 1.4f
        canvas.drawCircle(cx, cy, r, line)
        for (i in 0..59) {
            val a = i * Math.PI / 30 - Math.PI / 2
            val length = if (i % 5 == 0) r * .11 else r * .04
            line.color = Color.WHITE; line.alpha = if (i % 5 == 0) 170 else 60
            canvas.drawLine((cx + cos(a) * (r - length)).toFloat(), (cy + sin(a) * (r - length)).toFloat(), (cx + cos(a) * r * .96).toFloat(), (cy + sin(a) * r * .96).toFloat(), line)
        }
    }

    private fun cachedDial(canvas: Canvas) {
        var bitmap = dialCache
        if (bitmap == null && width > 0 && height > 0) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            drawDial(Canvas(bitmap), width / 2f, height / 2f, min(width, height) * .44f)
            dialCache = bitmap
        }
        bitmap?.let { canvas.drawBitmap(it, 0f, 0f, dialTint) }
    }

    private fun solar(canvas: Canvas) {
        val cx = width / 2f
        val cy = height * .40f
        val r = min(width * .35f, height * .30f)
        daylight.draw(canvas, cx, cy, r, sun, now, palette, dp)
        text(canvas, parts.main, cx, cy, min(width * .22f, height * .21f))
        val zone = Zones.clock(sun?.optString("timezone"))
        fun event(key: String) = (sun?.opt(key) as? Number)?.toLong()?.let { ZoneFormat.format("@time", it, zone) } ?: "—"
        text(canvas, "↑ ${event("sunrise")}     ↓ ${event("sunset")}", cx, height * .83f,
            min(width * .075f, height * .08f), palette.secondary)
    }

    private fun world(canvas: Canvas) {
        val shown = zones.take(4).ifEmpty { listOf(ClockZone(Zones.home().id)) }
        val count = shown.size
        val columns = if (width > height) count else min(count, 2)
        val rows = ceil(count / columns.toDouble()).toInt()
        val cellW = width.toFloat() / columns
        val cellH = height.toFloat() / rows
        val radius = min(cellW * .33f, cellH * .24f)
        var bitmap = dialCache
        if (bitmap == null) {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val face = Canvas(bitmap)
            for (i in shown.indices) drawDial(face, (i % columns + .5f) * cellW, (i / columns + .36f) * cellH, radius)
            dialCache = bitmap
        }
        canvas.drawBitmap(bitmap, 0f, 0f, dialTint)
        val home = Zones.home()
        val locale = Locale.getDefault()
        val density = resources.displayMetrics.scaledDensity
        for ((i, zone) in shown.withIndex()) {
            val clock = Zones.clock(zone.id)
            val cx = (i % columns + .5f) * cellW
            val top = (i / columns) * cellH
            analog(canvas, cx, top + .36f * cellH, radius, clock, false)
            val type = clock.typeAt(now)
            val dayLabel = when (clock.dayDelta(now, home)) {
                -1 -> context.getString(R.string.day_yesterday)
                1 -> context.getString(R.string.day_tomorrow)
                else -> context.getString(R.string.day_today)
            }
            val detail = buildString {
                append(dayLabel).append(" · ").append(ZoneFormat.offsetLabel(type.offset))
                if (type.dst) append(" · ").append(context.getString(R.string.form_tz_dst))
            }
            val room = cellW * .92f
            text(canvas, ZoneFormat.format("@time", now, clock, locale), cx, top + .70f * cellH, min(28f * density, cellW * .16f), palette.primary, room)
            text(canvas, zone.label ?: ZoneNames.city(clock.id, locale), cx, top + .81f * cellH, min(20f * density, cellW * .12f), palette.primary, room, labelPaint)
            text(canvas, detail, cx, top + .91f * cellH, min(14f * density, cellW * .09f), palette.secondary, room, labelPaint)
        }
    }

    companion object { private const val SYNCED_AFTER = 1_577_836_800_000L } // 2020-01-01, see known issue #2.
}
