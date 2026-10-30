package moe.kiwi.thinkos.image

import kotlin.math.*

/** Pure geometry: retain every source pixel. A pan visits both ends of the overflow axis. */
internal object ImageGeometry {
    data class Frame(val width: Int, val height: Int, val startX: Float, val startY: Float,
                     val endX: Float, val endY: Float, val dwellMillis: Long, val panMillis: Long = dwellMillis) {
        val moving get() = startX != endX || startY != endY
        fun reversed() = copy(startX = endX, startY = endY, endX = startX, endY = startY)
    }

    fun frame(iw: Int, ih: Int, vw: Int, vh: Int, pan: Boolean, rtl: Boolean,
              intervalMillis: Long, pixelsPerSecond: Float, panMillis: Long = intervalMillis): Frame {
        val dwell = intervalMillis.coerceAtLeast(100)
        if (minOf(iw, ih, vw, vh) <= 0) return Frame(0, 0, 0f, 0f, 0f, 0f, dwell)
        val scale = if (pan) max(vw.toDouble() / iw, vh.toDouble() / ih)
                    else min(vw.toDouble() / iw, vh.toDouble() / ih)
        val w = max(1, (iw * scale).roundToInt())
        val h = max(1, (ih * scale).roundToInt())
        val dx = (vw - w).toFloat(); val dy = (vh - h).toFloat()
        if (!pan) return Frame(w, h, dx / 2, dy / 2, dx / 2, dy / 2, dwell)
        val x0 = if (dx < 0) if (rtl) dx else 0f else dx / 2
        val x1 = if (dx < 0) if (rtl) 0f else dx else dx / 2
        val y0 = if (dy < 0) 0f else dy / 2
        val y1 = if (dy < 0) dy else dy / 2
        // AccelerateDecelerateInterpolator peaks at pi/2 times the average speed.
        val sweep = ceil(max(abs(dx), abs(dy)) * Math.PI / 2 * 1000 / pixelsPerSecond.coerceAtLeast(1f)).toLong()
        val motion = max(panMillis.coerceAtLeast(100), sweep)
        return Frame(w, h, x0, y0, x1, y1, max(dwell, motion), motion)
    }

    fun decodeSize(width: Int, height: Int, targetWidth: Int, targetHeight: Int): Pair<Int, Int> {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 32_000_000) { "Image dimensions exceed budget" }
        val budget = min(2_000_000L, max(1L, targetWidth.toLong() * targetHeight) * 3 / 2).coerceAtLeast(1)
        val scale = min(1.0, min(sqrt(budget.toDouble() / (width.toLong() * height)), 4096.0 / max(width, height)))
        return max(1, floor(width * scale).toInt()) to max(1, floor(height * scale).toInt())
    }
}
