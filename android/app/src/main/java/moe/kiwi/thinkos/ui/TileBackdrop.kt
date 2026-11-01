package moe.kiwi.thinkos.ui

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable

/**
 * The tile page's own backdrop: a dark diagonal gradient with two faint light ribbons, painted once into
 * the display list and never animated. The three variants are the `tiles.background` system setting; a
 * plugin cannot supply or alter them (ADR-017). White text on every end colour reaches at least 7.9:1.
 */
internal class TileBackdrop(val variant: Variant) : Drawable() {
    enum class Variant(val key: String, val from: Int, val to: Int) {
        INDIGO("indigo", 0xff1e1b4b.toInt(), 0xff3730a3.toInt()),
        TEAL("teal", 0xff042f3d.toInt(), 0xff0e5a66.toInt()),
        WINE("wine", 0xff2e0a24.toInt(), 0xff6b1244.toInt());

        companion object {
            fun of(key: String?): Variant = entries.firstOrNull { it.key == key } ?: INDIGO
        }
    }

    private val fill = Paint(Paint.DITHER_FLAG)
    private val ribbonA = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x09ffffff }
    private val ribbonB = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x06ffffff }
    private val pathA = Path()
    private val pathB = Path()

    override fun onBoundsChange(bounds: Rect) {
        val w = bounds.width().toFloat(); val h = bounds.height().toFloat()
        val x = bounds.left.toFloat(); val y = bounds.top.toFloat()
        fill.shader = LinearGradient(x, y, x + w, y + h, variant.from, variant.to, Shader.TileMode.CLAMP)
        pathA.rewind()
        pathA.moveTo(x + w * .35f, y); pathA.lineTo(x + w * .75f, y); pathA.lineTo(x + w * .2f, y + h); pathA.lineTo(x - w * .2f, y + h); pathA.close()
        pathB.rewind()
        pathB.moveTo(x + w * .8f, y); pathB.lineTo(x + w, y); pathB.lineTo(x + w, y + h * .45f); pathB.lineTo(x + w * .5f, y + h); pathB.close()
    }

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        canvas.drawRect(bounds, fill)
        canvas.drawPath(pathA, ribbonA)
        canvas.drawPath(pathB, ribbonB)
    }

    override fun setAlpha(alpha: Int) = Unit
    override fun setColorFilter(filter: ColorFilter?) = Unit
    @Deprecated("Required by Drawable", ReplaceWith("PixelFormat.OPAQUE"))
    override fun getOpacity() = PixelFormat.OPAQUE
}
