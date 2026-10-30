package moe.kiwi.thinkos.ui

import kotlin.math.abs
import kotlin.math.floor

/** Integer geometry shared by native renderers; independent of Android so edge cases run on JVM. */
object RenderGeometry {
    /** Fixed photo-clock placement, not a plugin alignment/style option. */
    fun photoClock(width: Int, height: Int, density: Float): Box {
        val w = width.coerceAtLeast(0); val h = height.coerceAtLeast(0)
        val d = density.toDouble().takeIf { it.isFinite() && it > 0 } ?: 1.0
        fun dp(value: Int) = (value * d).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
        val edge = minOf(dp(24), w / 2, h / 2)
        val clockWidth = minOf(dp(220), w - 2 * edge)
        val clockHeight = minOf(dp(56), h - 2 * edge)
        return Box(w - edge - clockWidth, edge, w - edge, edge + clockHeight)
    }
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /**
     * Fixed OS vocabulary, not plugin-authored styling. Focus reserves optional 56 dp header/footer
     * bands inside 20 dp margins. Split uses 40/60 with a 16 dp gap, rotating with the canvas. Stage
     * covers the canvas and puts its over/under bands on top, without shrinking the background.
     */
    fun slot(
        layout: String,
        role: String,
        width: Int,
        height: Int,
        density: Float,
        hasHeader: Boolean = false,
        hasFooter: Boolean = false,
    ): Box {
        val w = width.coerceAtLeast(0)
        val h = height.coerceAtLeast(0)
        val d = density.toDouble().takeIf { it.isFinite() && it > 0 } ?: 1.0
        fun dp(value: Int) = (value * d).coerceIn(0.0, Int.MAX_VALUE.toDouble()).toInt()
        val x = minOf(dp(20), w / 2)
        val y = minOf(dp(20), h / 2)
        val interior = Box(x, y, w - x, h - y)
        if (layout == "stage") {
            val band = minOf(dp(56), interior.height)
            return when (role) {
                "header", "over" -> Box(x, y, w - x, y + band)
                "footer", "under" -> Box(x, h - y - band, w - x, h - y)
                else -> Box(0, 0, w, h)
            }
        }
        if (layout == "split") {
            if (w > h) {
                val gap = minOf(dp(16), interior.width)
                val lead = ((interior.width - gap) * 0.4).toInt()
                return when (role) {
                    "lead" -> Box(x, y, x + lead, h - y)
                    "main" -> Box(x + lead + gap, y, w - x, h - y)
                    else -> interior
                }
            }
            val gap = minOf(dp(16), interior.height)
            val lead = ((interior.height - gap) * 0.4).toInt()
            return when (role) {
                "lead" -> Box(x, y, w - x, y + lead)
                "main" -> Box(x, y + lead + gap, w - x, h - y)
                else -> interior
            }
        }
        val bands = (if (hasHeader) 1 else 0) + (if (hasFooter) 1 else 0)
        val band = if (bands == 0) 0 else minOf(dp(56), interior.height / bands)
        val header = if (hasHeader) band else 0
        val footer = if (hasFooter) band else 0
        return when (role) {
            "header", "over" -> Box(x, y, w - x, y + header)
            "footer", "under" -> Box(x, h - y - footer, w - x, h - y)
            else -> Box(x, y + header, w - x, h - y - footer)
        }
    }

    data class VerticalAllocation(val heights: List<Int>, val gap: Int, val offset: Int)

    /**
     * Weight zero preserves measured content height. Fixed content consumes the budget first; if
     * it overflows, later fixed children are clipped to the remaining height. Positive finite
     * weights share the rest. Gaps shrink on tiny canvases, and rounding never loses a pixel.
     * [offset] vertically centres a group which has no expanding children.
     */
    fun verticalHeights(
        availableHeight: Int,
        measuredHeights: List<Int>,
        weights: List<Double>,
        gap: Int,
    ): VerticalAllocation {
        require(measuredHeights.size == weights.size) { "one weight is required per child" }
        val available = availableHeight.coerceAtLeast(0)
        val count = measuredHeights.size
        if (count == 0) return VerticalAllocation(emptyList(), 0, available / 2)
        val actualGap = if (count == 1) 0 else gap.coerceAtLeast(0).coerceAtMost(available / (count - 1))
        var remaining = available.toLong() - actualGap.toLong() * (count - 1)
        val positive = weights.map { it.takeIf { value -> value.isFinite() && value > 0 } ?: 0.0 }
        val heights = MutableList(count) { 0 }
        for (index in heights.indices) if (positive[index] == 0.0) {
            val amount = measuredHeights[index].coerceAtLeast(0).toLong().coerceAtMost(remaining)
            heights[index] = amount.toInt()
            remaining -= amount
        }
        val largest = positive.maxOrNull() ?: 0.0
        if (largest > 0) {
            // Divide by the largest first: a sum of finite Double.MAX_VALUE weights must not
            // become infinity and turn every share into zero.
            val normal = positive.map { it / largest }
            val total = normal.sum()
            val distributable = remaining
            val fractions = mutableListOf<Pair<Int, Double>>()
            for (index in heights.indices) if (positive[index] > 0) {
                val ideal = distributable * (normal[index] / total)
                val base = floor(ideal).toLong().coerceIn(0, remaining)
                heights[index] = base.toInt()
                remaining -= base
                fractions += index to (ideal - floor(ideal))
            }
            // Largest remainder, with declaration order breaking ties. Floating-point sum error
            // can leave at most a few pixels; cycling also handles extreme underflow robustly.
            val order = fractions.sortedWith(compareByDescending<Pair<Int, Double>> { it.second }.thenBy { it.first })
            var next = 0
            while (remaining > 0 && order.isNotEmpty()) {
                val index = order[next % order.size].first
                heights[index]++
                remaining--
                next++
            }
        }
        return VerticalAllocation(heights, actualGap, (remaining / 2).toInt())
    }

    /** Invalid or absent progress stays absent rather than inventing a completed track. */
    fun progress(value: Double?, maximum: Double?): Double? {
        if (value == null || maximum == null || !value.isFinite() || !maximum.isFinite() || maximum <= 0) return null
        return (value / maximum).coerceIn(0.0, 1.0)
    }

    data class Point(val x: Double, val y: Double)
    data class ChartProjection(val points: List<Point?>, val baselineY: Double, val minimum: Double, val maximum: Double)

    /**
     * Project finite values to the plot. Null/NaN/infinite samples retain their index and become
     * null points so lines/areas break at missing data instead of drawing through a gap. Empty
     * ranges use 0..1; equal ranges expand around their sole value so the line remains visible.
     */
    fun chart(values: List<Double?>, width: Double, height: Double, minimum: Double? = null, maximum: Double? = null): ChartProjection {
        val w = width.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val h = height.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val finite = values.filterNotNull().filter(Double::isFinite)
        var low = minimum?.takeIf(Double::isFinite) ?: finite.minOrNull() ?: 0.0
        var high = maximum?.takeIf(Double::isFinite) ?: finite.maxOrNull() ?: 1.0
        if (low > high) { val swap = low; low = high; high = swap }
        if (low == high) {
            val margin = maxOf(abs(low) * 0.05, 1.0)
            low = (low - margin).takeIf(Double::isFinite) ?: -Double.MAX_VALUE
            high = (high + margin).takeIf(Double::isFinite) ?: Double.MAX_VALUE
        }
        fun ratio(value: Double): Double {
            // Scaling before subtracting avoids overflow for a -MAX_VALUE..MAX_VALUE range.
            val scale = maxOf(abs(low), abs(high), 1.0)
            val a = low / scale
            val b = high / scale
            val denominator = b - a
            return if (denominator > 0) ((value / scale - a) / denominator).coerceIn(0.0, 1.0) else 0.5
        }
        val points = values.mapIndexed { index, value ->
            value?.takeIf(Double::isFinite)?.let {
                Point(if (values.size <= 1) w / 2 else index / (values.size - 1.0) * w, (1 - ratio(it)) * h)
            }
        }
        return ChartProjection(points, (1 - ratio(0.0)) * h, low, high)
    }
}
