package moe.kiwi.thinkos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderGeometryTest {
    @Test fun photoClockUsesFixedCornersAndStaysInsideTinyViewports() {
        assertEquals(RenderGeometry.Box(1036, 24, 1256, 80), RenderGeometry.photoClock(1280, 800, 1f))
        assertEquals(RenderGeometry.Box(556, 24, 776, 80), RenderGeometry.photoClock(800, 1280, 1f))
        for ((w, h) in listOf(0 to 0, 2 to 3, 10 to 5, -1 to -2, Int.MAX_VALUE to Int.MAX_VALUE)) {
            for (d in listOf(2f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
                val box = RenderGeometry.photoClock(w, h, d)
                assertTrue(box.left >= 0 && box.top >= 0 && box.right <= w.coerceAtLeast(0) && box.bottom <= h.coerceAtLeast(0))
            }
        }
    }
    @Test
    fun focusReservesOnlyPresentHeaderAndFooterBands() {
        assertEquals(RenderGeometry.Box(20, 20, 1260, 780), RenderGeometry.slot("focus", "content", 1280, 800, 1f))
        assertEquals(RenderGeometry.Box(20, 76, 1260, 724), RenderGeometry.slot("focus", "content", 1280, 800, 1f, true, true))
        assertEquals(RenderGeometry.Box(20, 20, 1260, 76), RenderGeometry.slot("focus", "header", 1280, 800, 1f, true, true))
        assertEquals(RenderGeometry.Box(20, 724, 1260, 780), RenderGeometry.slot("focus", "footer", 1280, 800, 1f, true, true))
    }

    @Test
    fun splitRotatesFortySixtyAllocationAndConservesAvailablePixels() {
        val lead = RenderGeometry.slot("split", "lead", 1280, 800, 1f)
        val main = RenderGeometry.slot("split", "main", 1280, 800, 1f)
        assertEquals(RenderGeometry.Box(20, 20, 509, 780), lead)
        assertEquals(RenderGeometry.Box(525, 20, 1260, 780), main)
        assertEquals(1240, lead.width + main.width + 16)
        val portraitLead = RenderGeometry.slot("split", "lead", 800, 1280, 1f)
        val portraitMain = RenderGeometry.slot("split", "main", 800, 1280, 1f)
        assertEquals(RenderGeometry.Box(20, 20, 780, 509), portraitLead)
        assertEquals(RenderGeometry.Box(20, 525, 780, 1260), portraitMain)
    }

    @Test
    fun stageBackgroundIsFullBleedWhileBandsOverlayIt() {
        assertEquals(RenderGeometry.Box(0, 0, 800, 1280), RenderGeometry.slot("stage", "stage", 800, 1280, 1f, true, true))
        assertEquals(RenderGeometry.Box(20, 20, 780, 76), RenderGeometry.slot("stage", "over", 800, 1280, 1f, true, true))
        assertEquals(RenderGeometry.Box(20, 1204, 780, 1260), RenderGeometry.slot("stage", "under", 800, 1280, 1f, true, true))
    }

    @Test
    fun everySlotStaysInsideTinyAndExtremeCanvases() {
        for ((w, h) in listOf(0 to 0, 1 to 2, 2 to 1, 5 to 90, 90 to 5, -1 to -2, Int.MAX_VALUE to Int.MAX_VALUE)) {
            for (density in listOf(1f, 0f, Float.NaN, Float.POSITIVE_INFINITY, Float.MAX_VALUE)) {
                for (layout in listOf("focus", "split", "stage")) for (role in listOf("header", "footer", "content", "lead", "main", "stage", "over", "under")) {
                    val box = RenderGeometry.slot(layout, role, w, h, density, true, true)
                    assertTrue(box.left >= 0 && box.top >= 0)
                    assertTrue(box.right >= box.left && box.bottom >= box.top)
                    assertTrue(box.right <= w.coerceAtLeast(0) && box.bottom <= h.coerceAtLeast(0))
                }
            }
        }
    }

    @Test
    fun weightsShareOnlyTheHeightLeftAfterFixedContentAndGaps() {
        val result = RenderGeometry.verticalHeights(100, listOf(10, 99, 99), listOf(0.0, 1.0, 2.0), 5)
        assertEquals(listOf(10, 27, 53), result.heights)
        assertEquals(5, result.gap)
        assertEquals(0, result.offset)
        assertEquals(100, result.heights.sum() + result.gap * 2)
        val fixed = RenderGeometry.verticalHeights(100, listOf(10, 20), listOf(0.0, 0.0), 10)
        assertEquals(listOf(10, 20), fixed.heights)
        assertEquals(30, fixed.offset)
    }

    @Test
    fun tinyWeightedGroupsAndExtremeWeightsNeverOverflowOrLosePixels() {
        for (height in listOf(-1, 0, 1, 2, 100, Int.MAX_VALUE)) {
            val result = RenderGeometry.verticalHeights(height, listOf(3, 4, 5), listOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MIN_VALUE), Int.MAX_VALUE)
            val total = result.heights.sumOf(Int::toLong) + result.gap.toLong() * 2 + result.offset.toLong() * 2
            assertTrue(result.heights.all { it >= 0 })
            assertEquals(height.coerceAtLeast(0).toLong(), total)
        }
        val overflow = RenderGeometry.verticalHeights(10, listOf(Int.MAX_VALUE, Int.MAX_VALUE, 0), listOf(0.0, Double.NaN, 1.0), 0)
        assertEquals(listOf(10, 0, 0), overflow.heights)
    }

    @Test
    fun progressRejectsInvalidRangesAndClampsActualValues() {
        assertEquals(0.25, RenderGeometry.progress(1.0, 4.0)!!, 0.0)
        assertEquals(0.0, RenderGeometry.progress(-1.0, 4.0)!!, 0.0)
        assertEquals(1.0, RenderGeometry.progress(Double.MAX_VALUE, Double.MIN_VALUE)!!, 0.0)
        assertNull(RenderGeometry.progress(1.0, 0.0))
        assertNull(RenderGeometry.progress(Double.NaN, 4.0))
        assertNull(RenderGeometry.progress(1.0, null))
    }

    @Test
    fun chartsRetainMissingSamplesAsBreaksAndCentreSingletons() {
        val projected = RenderGeometry.chart(listOf(0.0, null, 10.0, Double.NaN), 300.0, 100.0)
        assertEquals(RenderGeometry.Point(0.0, 100.0), projected.points[0])
        assertNull(projected.points[1])
        assertEquals(RenderGeometry.Point(200.0, 0.0), projected.points[2])
        assertNull(projected.points[3])
        val single = RenderGeometry.chart(listOf(5.0), 300.0, 100.0)
        assertEquals(150.0, single.points.single()!!.x, 0.000000001)
        assertEquals(50.0, single.points.single()!!.y, 0.000000001)
    }

    @Test
    fun chartEmptyOrExtremeRangesAlwaysProduceFiniteBoundedPoints() {
        assertTrue(RenderGeometry.chart(emptyList(), 100.0, 100.0).points.isEmpty())
        val extreme = RenderGeometry.chart(listOf(-Double.MAX_VALUE, 0.0, Double.MAX_VALUE), 100.0, 100.0)
        assertEquals(listOf(RenderGeometry.Point(0.0, 100.0), RenderGeometry.Point(50.0, 50.0), RenderGeometry.Point(100.0, 0.0)), extreme.points)
        for (values in listOf(listOf(0.0), listOf(Double.MAX_VALUE), listOf(-Double.MAX_VALUE))) {
            val chart = RenderGeometry.chart(values, Double.POSITIVE_INFINITY, -100.0)
            assertTrue(chart.points.filterNotNull().all { it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.y >= 0 })
            assertTrue(chart.baselineY.isFinite())
        }
    }
}
