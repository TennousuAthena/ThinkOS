package moe.kiwi.thinkos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelGeometryTest {
    // The device: 800 x 1280 px at 260 dpi, so density 1.625; 492 x 788 dp in portrait.
    private val density = 1.625f
    private fun portrait(header: Boolean = true, quick: Boolean = true, levels: Int = 2, media: Boolean = true, footer: Boolean = true) =
        PanelGeometry.plan(800, 1280, density, header, quick, levels, media, footer)
    private fun landscape(header: Boolean = true, quick: Boolean = true, levels: Int = 2, media: Boolean = true, footer: Boolean = true) =
        PanelGeometry.plan(1280, 800, density, header, quick, levels, media, footer)

    @Test fun portraitStacksEverySlotInOneColumnWithoutOverlap() {
        val plan = portrait()
        val boxes = listOf(plan.header, plan.quick, plan.levels, plan.media, plan.footer).map { assertNotNull(it); it!! }
        for ((a, b) in boxes.zipWithNext()) assertTrue("$a above $b", a.bottom < b.top)
        assertTrue(boxes.all { it.left == boxes[0].left && it.right == boxes[0].right })
        assertEquals(800, plan.width)
        assertTrue(plan.height > boxes.last().bottom)
    }

    @Test fun portraitSheetLeavesRoomForThePageBelow() {
        // Everything on, media loaded: still under two thirds of the 788 dp screen.
        assertTrue(portrait().height < 1280 * 2 / 3)
    }

    @Test fun landscapePutsMediaBesideTheTogglesAndStaysShort() {
        val plan = landscape()
        val quick = plan.quick!!; val levels = plan.levels!!; val media = plan.media!!
        assertEquals(quick.left, levels.left)
        assertTrue("media is to the right of the toggles", media.left >= quick.right)
        assertEquals(quick.top, media.top)
        // 492 dp tall in landscape; the sheet must leave at least a third of it.
        assertTrue("height ${plan.height}", plan.height < 800 * 2 / 3)
        // The time and the network line share the top row.
        assertEquals(plan.header!!.top, plan.footer!!.top)
        assertTrue(plan.header!!.right < plan.footer!!.left)
        assertEquals((720 * density).toInt(), plan.width)
    }

    @Test fun landscapeWithoutMediaUsesTheFullWidth() {
        val plan = landscape(media = false)
        assertNull(plan.media)
        val quick = plan.quick!!
        assertEquals(plan.width - 2 * quick.left, quick.width)
    }
    @Test fun landscapeReservesTheNetworkCaptionRatherThanHalfTheDateRow() {
        val plan = PanelGeometry.plan(1280, 800, density, true, true, 2, true, true, footerTextWidth = 220)
        assertEquals(220, plan.footer!!.width)
        assertTrue("Localized date keeps more than two thirds of the row", plan.header!!.width > plan.footer!!.width * 2)
        assertEquals(landscape().height, plan.height)
    }

    @Test fun absentSlotsTakeNoSpace() {
        val bare = portrait(header = false, quick = false, levels = 0, media = false, footer = false)
        assertNull(bare.header); assertNull(bare.quick); assertNull(bare.levels); assertNull(bare.media); assertNull(bare.footer)
        assertTrue(bare.height < portrait().height / 4)
        val noMedia = portrait(media = false)
        assertTrue(noMedia.height < portrait().height)
        assertNull(noMedia.media)
    }

    @Test fun eachLevelAddsOneSliderRow() {
        val one = portrait(levels = 1).levels!!
        val three = portrait(levels = 3).levels!!
        assertEquals(one.height * 3, three.height)
    }

    @Test fun sheetNeverExceedsTheScreenWidth() {
        for ((w, h) in listOf(800 to 1280, 1280 to 800, 480 to 800, 320 to 320)) {
            val plan = PanelGeometry.plan(w, h, density, true, true, 2, true, true)
            assertTrue(plan.width <= w)
            for (box in listOf(plan.header, plan.quick, plan.levels, plan.media, plan.footer)) {
                assertTrue(box!!.left >= 0 && box.right <= plan.width)
            }
        }
    }

    @Test fun aShortPullFallsBackAndALongOneOpens() {
        assertFalse(PanelGeometry.settlesOpen(.10f, 0f, density, wasOpen = false))
        assertTrue(PanelGeometry.settlesOpen(.40f, 0f, density, wasOpen = false))
    }

    @Test fun updateResultSharesUnusedSpaceAndKeepsNetworkMediaAndClockVisible() {
        for ((w, h) in listOf(800 to 1280, 1280 to 800)) for (media in listOf(true, false)) {
            val plan = PanelGeometry.plan(w, h, density, true, true, 2, media, true, footerTextWidth = 130, update = true)
            val boxes = listOfNotNull(plan.header, plan.quick, plan.levels, plan.media, plan.footer, plan.update)
            assertNotNull(plan.update); assertNotNull(plan.footer)
            assertTrue(plan.height < h * 2 / 3)
            for (box in boxes) assertTrue(box.left >= 0 && box.right <= plan.width && box.bottom < plan.height)
            for ((index, a) in boxes.withIndex()) for (b in boxes.drop(index+1))
                assertTrue("$a must not overlap $b", a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
        }
    }

    @Test fun aFlickDecidesWhateverTheDistance() {
        assertTrue(PanelGeometry.settlesOpen(.05f, 2000f, density, wasOpen = false))
        assertFalse(PanelGeometry.settlesOpen(.95f, -2000f, density, wasOpen = true))
    }

    @Test fun anOpenSheetClosesOnceItIsPulledPastTheSameShareBack() {
        assertTrue(PanelGeometry.settlesOpen(.90f, 0f, density, wasOpen = true))
        assertFalse(PanelGeometry.settlesOpen(.60f, 0f, density, wasOpen = true))
        assertFalse(PanelGeometry.settlesOpen(.15f, 0f, density, wasOpen = false))
    }

    @Test fun sixQuickControlsRemainReachableInBothOrientationsWithMediaAndUpdate() {
        assertEquals(4, PanelGeometry.quickColumns(4))
        assertEquals(1, PanelGeometry.quickRows(4))
        assertEquals(3, PanelGeometry.quickColumns(6))
        assertEquals(2, PanelGeometry.quickRows(6))
        for ((w, h) in listOf(800 to 1280, 1280 to 800)) {
            val plan = PanelGeometry.plan(w, h, density, true, true, 2, true, true, 130, true, quickCount = 6)
            val quick = plan.quick!!
            assertEquals((88 * density).toInt() * 2, quick.height)
            assertTrue("Chinese labels and touch cells keep at least 88dp", quick.width / 3 >= (88 * density).toInt())
            assertTrue("whole sheet fits $w x $h", plan.height <= h)
            val boxes = listOfNotNull(plan.header, quick, plan.levels, plan.media, plan.footer, plan.update)
            for ((i, a) in boxes.withIndex()) for (b in boxes.drop(i + 1))
                assertTrue("$a must not overlap $b", a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
        }
    }
}
