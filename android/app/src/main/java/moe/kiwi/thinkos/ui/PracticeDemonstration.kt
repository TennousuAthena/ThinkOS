package moe.kiwi.thinkos.ui

import kotlin.math.min

/** Fixed OS tutorial geometry and cancellation tickets; no navigation or gesture recognition. */
internal class PracticeDemonstration {
    data class Path(val fromX: Float, val fromY: Float, val toX: Float, val toY: Float, val press: Boolean = false)
    private var generation = 0L
    fun begin(): Long = ++generation
    fun cancel() { generation++ }
    fun accepts(ticket: Long, eligible: Boolean): Boolean = ticket == generation && eligible

    companion object {
        private val NAMES = setOf("swipe", "variant", "panel", "back", "home", "long-press")
        fun eligible(name: String?, complete: Boolean, touching: Boolean, attached: Boolean,
                     shown: Boolean, focused: Boolean, windowVisible: Boolean, motion: Boolean): Boolean =
            name != null && name in NAMES && !complete && !touching && attached && shown && focused && windowVisible && motion

        /** Screen coordinates are physical, so RTL never reverses Back, panel or Home. */
        fun path(name: String?, width: Int, height: Int, density: Float, reverse: Boolean): Path? {
            if (name == null || name !in NAMES || width <= 0 || height <= 0 || !density.isFinite() || density <= 0) return null
            val w = width.toFloat(); val h = height.toFloat()
            val inset = min(8 * density, min(w, h) / 2)
            // Each moving leg is at most one third of the smaller screen extent.
            val extent = min(48 * density, min(min(w, h) / 6, min(w - 2 * inset, h - 2 * inset) / 2)).coerceAtLeast(0f)
            val x = w / 2; val y = h * .42f
            val path = when (name) {
                "swipe" -> Path(x - extent, y, x + extent, y)
                "variant" -> Path(x, y - extent, x, y + extent)
                // Avoid the centered Back/Skip captions while showing the real physical edge.
                "panel" -> Path(w * .75f, inset, w * .75f, inset + 2 * extent)
                "back" -> Path(inset, y, inset + 2 * extent, y)
                "home" -> Path(w * .25f, h - inset, w * .25f, h - inset - 2 * extent)
                else -> Path(x, y, x, y, press = true)
            }
            return if (reverse && name in setOf("swipe", "variant"))
                Path(path.toX, path.toY, path.fromX, path.fromY) else path
        }
    }
}
