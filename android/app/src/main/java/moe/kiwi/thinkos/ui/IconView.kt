package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Exact names form the icon vocabulary; unknown names never silently turn into a sun. */
internal class IconView(context: Context) : View(context) {
    var palette = Palette.DARK
        set(value) { field = value; invalidate() }
    var name = "sun"
        set(value) { if (field != value) { field = value; contentDescription = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    override fun onDraw(canvas: Canvas) {
        paint.color = palette.accent
        paint.strokeWidth = 2f * resources.displayMetrics.density
        val x = width / 2f
        val y = height / 2f
        val r = min(width, height) * .22f
        DRAWERS[name]?.invoke(canvas, paint, x, y, r) ?: run {
            canvas.drawCircle(x, y, r, paint)
            canvas.drawLine(x, y - r * .4f, x, y + r * .1f, paint)
            canvas.drawPoint(x, y + r * .5f, paint)
        }
    }
    companion object {
        private val DRAWERS: Map<String, (Canvas, Paint, Float, Float, Float) -> Unit> = mapOf(
            "calendar" to { c, p, x, y, r ->
                c.drawRoundRect(x-r*1.1f, y-r, x+r*1.1f, y+r*1.15f, r*.2f, r*.2f, p)
                c.drawLine(x-r, y-r*.35f, x+r, y-r*.35f, p)
                for (sign in listOf(-1, 1)) c.drawLine(x+sign*r*.5f, y-r*1.25f, x+sign*r*.5f, y-r*.7f, p)
            },
            "moon" to { c, p, x, y, r ->
                c.drawArc(x-r, y-r, x+r, y+r, 40f, 280f, false, p)
                c.drawArc(x-r*.3f, y-r, x+r*1.2f, y+r*.5f, 70f, 180f, false, p)
            },
            "clock" to { c, p, x, y, r ->
                c.drawCircle(x, y, r, p)
                c.drawLine(x, y, x, y-r*.6f, p)
                c.drawLine(x, y, x+r*.4f, y+r*.3f, p)
            },
            "sun" to { c, p, x, y, r ->
                c.drawCircle(x, y, r*.55f, p)
                for (i in 0..7) {
                    val angle = i * Math.PI / 4
                    c.drawLine(x+(cos(angle)*r*.85).toFloat(), y+(sin(angle)*r*.85).toFloat(),
                        x+(cos(angle)*r*1.18).toFloat(), y+(sin(angle)*r*1.18).toFloat(), p)
                }
            }
        )
        val names: Set<String> get() = DRAWERS.keys
    }
}
