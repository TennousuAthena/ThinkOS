package moe.kiwi.thinkos.ui

import android.graphics.Canvas
import android.graphics.Paint
import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.sin

/** Shared solar dial: actual twilight/daylight intervals on a 24-hour ring, with solar noon at top. */
internal class DaylightPainter {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(canvas: Canvas, cx: Float, cy: Float, radius: Float, sun: JSONObject?, now: Long, palette: Palette, density: Float) {
        line.strokeWidth = Stroke.TRACK * density
        line.color = palette.divider
        line.alpha = 255
        canvas.drawCircle(cx, cy, radius, line)
        val noon = (sun?.opt("noon") as? Number)?.toLong() ?: return
        fun angle(t: Long): Float = -90f + (t - noon) / 86_400_000f * 360f
        fun segment(start: Long?, end: Long?, alpha: Int) {
            if (start == null || end == null || end <= start) return
            line.color = palette.accent
            line.alpha = alpha
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                angle(start), ((end - start) / 86_400_000f * 360f).coerceAtMost(360f), false, line)
        }
        fun twilight(name: String, threshold: Double, alpha: Int) {
            val value = sun.optJSONObject(name) ?: return
            val dawn = (value.opt("dawn") as? Number)?.toLong()
            val dusk = (value.opt("dusk") as? Number)?.toLong()
            if (dawn != null || dusk != null || sun.optDouble("maximumAltitude", -90.0) > threshold) {
                segment(dawn ?: (noon - 43_200_000), dusk ?: (noon + 43_200_000), alpha)
            }
        }
        twilight("astronomical", -18.0, 45)
        twilight("nautical", -12.0, 85)
        twilight("civil", -6.0, 130)
        if (sun.optString("polar") == "midnight_sun") segment(noon - 43_200_000, noon + 43_200_000, 255)
        else if (sun.optString("polar") != "polar_night") segment(
            (sun.opt("sunrise") as? Number)?.toLong() ?: (noon - 43_200_000),
            (sun.opt("sunset") as? Number)?.toLong() ?: (noon + 43_200_000), 255)
        val a = angle(now) * Math.PI / 180
        dot.color = if (sun.optDouble("altitude", -90.0) >= 0) palette.accent else palette.secondary
        canvas.drawCircle(cx + (cos(a) * radius).toFloat(), cy + (sin(a) * radius).toFloat(), 8 * density, dot)
    }
}
