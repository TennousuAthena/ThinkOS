package moe.kiwi.thinkos.ui

import kotlin.math.abs
import kotlin.math.floor

/** Integer geometry shared by native renderers; independent of Android so edge cases run on JVM. */
object RenderGeometry {
    /** Native app grid: 96dp target cells, at least three columns even in compact slots. */
    fun gridColumns(width: Int, density: Float): Int {
        require(density.isFinite() && density > 0f) { "density must be positive and finite" }
        return maxOf(3, floor(width.coerceAtLeast(0) / density / 96.0).toInt())
    }

    /**
     * The fixed tile grid: 8x6 landscape, 6x8 portrait. The cell size is limited by the short axis, and the
     * grid is anchored to the far end of the long axis so the remainder becomes a title rail (left in LTR
     * landscape, top in portrait). Every metric is derived here so placement and the title share it.
     */
    class TileGrid(val cols: Int, val rows: Int, val cell: Int, val gap: Int, val edge: Int, val left: Int, val top: Int,
        val width: Int, val height: Int, val landscape: Boolean) {
        val gridWidth get() = cols * cell + (cols - 1) * gap
        val gridHeight get() = rows * cell + (rows - 1) * gap
    }
    private fun dp(value: Double, density: Float): Int {
        val d = density.takeIf { it.isFinite() && it > 0 } ?: 1f
        return (value * d).coerceAtMost(Int.MAX_VALUE.toDouble()).toInt()
    }
    fun tileGrid(width: Int, height: Int, density: Float): TileGrid? {
        if (width <= 0 || height <= 0) return null
        val landscape = width > height
        val cols = if (landscape) 8 else 6
        val rows = if (landscape) 6 else 8
        val edge = minOf(dp(20.0, density), width / 2, height / 2)
        val gap = minOf(dp(8.0, density), (width - edge * 2) / (cols - 1), (height - edge * 2) / (rows - 1))
        val long = if (landscape) width else height
        val shortAxis = if (landscape) height else width
        val longCount = if (landscape) cols else rows
        val shortCount = if (landscape) rows else cols
        val rail = minOf(dp(112.0, density), long / 4)
        val usableShort = shortAxis - edge * 2 - gap * (shortCount - 1)
        val usableLong = long - edge * 2 - gap * (longCount - 1) - rail
        if (usableShort < shortCount || usableLong < longCount) return null
        val cell = minOf(usableShort / shortCount, usableLong / longCount)
        val gridW = cols * cell + (cols - 1) * gap
        val gridH = rows * cell + (rows - 1) * gap
        // Far end on the long axis, centred on the short one.
        val left = if (landscape) width - edge - gridW else (width - gridW) / 2
        val top = if (landscape) (height - gridH) / 2 else height - edge - gridH
        return TileGrid(cols, rows, cell, gap, edge, left, top, width, height, landscape)
    }
    /** First fit in declaration order. Missing boxes retain their input index (dropped tiles). */
    fun tiles(sizes: List<String>, width: Int, height: Int, density: Float, rtl: Boolean = false): List<Box?> {
        val grid = tileGrid(width, height, density) ?: return sizes.map { null }
        val cell = grid.cell; val gap = grid.gap
        val occupied = Array(grid.rows) { BooleanArray(grid.cols) }
        return sizes.map { size ->
            val (cw, ch) = when (size) {
                "small" -> 1 to 1; "medium" -> 2 to 2; "wide" -> 4 to 2; "large" -> 4 to 4
                else -> 0 to 0
            }
            var box: Box? = null
            if (cw > 0) search@ for (y in 0..grid.rows - ch) for (x in 0..grid.cols - cw) {
                if ((y until y + ch).any { row -> (x until x + cw).any { occupied[row][it] } }) continue
                for (row in y until y + ch) for (col in x until x + cw) occupied[row][col] = true
                val left = grid.left + cell * x + gap * x
                val right = grid.left + cell * (x + cw) + gap * (x + cw - 1)
                val top = grid.top + cell * y + gap * y
                val bottom = grid.top + cell * (y + ch) + gap * (y + ch - 1)
                box = if (rtl) Box(width - right, top, width - left, bottom) else Box(left, top, right, bottom)
                break@search
            }
            box
        }
    }
    /** The page title beside (landscape) or above (portrait) the grid; null when the rail is too small. */
    fun tileTitle(width: Int, height: Int, density: Float, rtl: Boolean = false): Box? {
        val grid = tileGrid(width, height, density) ?: return null
        val edge = grid.edge
        val box = if (grid.landscape) {
            val right = grid.left - edge
            Box(edge, edge, right, edge + minOf(dp(72.0, density), height - 2 * edge))
        } else {
            val bottom = grid.top - grid.gap
            Box(edge, edge, width - edge, edge + minOf(dp(72.0, density), bottom - edge))
        }
        if (box.width < dp(64.0, density) || box.height < dp(40.0, density)) return null
        return if (rtl) Box(width - box.right, box.top, width - box.left, box.bottom) else box
    }
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
