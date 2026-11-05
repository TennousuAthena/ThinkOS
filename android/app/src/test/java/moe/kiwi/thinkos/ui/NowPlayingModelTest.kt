package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.ui.RenderGeometry.Box
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class NowPlayingModelTest {
    private val dark = intArrayOf(0xff0e0f12.toInt(), 0xff1b1d22.toInt())
    private val light = intArrayOf(0xfff7f8fa.toInt(), 0xffffffff.toInt())

    // ---- colours ---------------------------------------------------------------------------------------------------

    @Test fun aTrackAlwaysGetsTheSameHue() {
        assertEquals(CoverTone.hue("Song|Artist"), CoverTone.hue("Song|Artist"), 0f)
        for (key in listOf("", "a", "派对动物|五月天", "x".repeat(500))) assertTrue(CoverTone.hue(key) in 0f..359f)
        assertNotEquals(CoverTone.hue("Song|Artist"), CoverTone.hue("Song|Artists"))
    }

    @Test fun everyHueGivesAGraphicAndATextColourThatClearTheirContrast() {
        for (backdrops in listOf(dark, light)) for (saturation in listOf(.3f, .45f, .62f, .8f, .95f)) for (h in 0 until 360) {
            val hue = h.toFloat()
            val accent = CoverTone.accent(hue, saturation, backdrops)
            val text = CoverTone.accentText(hue, saturation, backdrops)
            for (backdrop in backdrops) {
                assertTrue("accent hue $hue s $saturation on ${Integer.toHexString(backdrop)}: ${CoverTone.contrast(accent, backdrop)}",
                    CoverTone.contrast(accent, backdrop) >= 3.0)
                assertTrue("text hue $hue s $saturation on ${Integer.toHexString(backdrop)}: ${CoverTone.contrast(text, backdrop)}",
                    CoverTone.contrast(text, backdrop) >= 4.5)
            }
        }
    }

    @Test fun aDrawnCoverCarriesReadableWhiteText() {
        for (saturation in listOf(.3f, .5f, .7f)) for (h in 0 until 360) {
            val fill = CoverTone.cover(h.toFloat(), saturation)
            assertTrue("hue $h: ${CoverTone.contrast(0xffffffff.toInt(), fill)}", CoverTone.contrast(0xffffffff.toInt(), fill) >= 4.5)
        }
    }

    @Test fun theGlyphOnAFillIsBlackOrWhiteWhicheverReadsBetter() {
        assertEquals(0xffffffff.toInt(), CoverTone.on(0xff1b3a8c.toInt()))
        assertEquals(0xff0e0f12.toInt(), CoverTone.on(0xfff4b860.toInt()))
        for (h in 0 until 360) {
            val fill = CoverTone.accent(h.toFloat(), .6f, dark)
            assertTrue(CoverTone.contrast(CoverTone.on(fill), fill) >= 4.5)
        }
    }

    @Test fun hueAndSaturationReadBackFromAColour() {
        for (h in 5 until 360 step 17) {
            val (hue, saturation) = CoverTone.hueSaturation(CoverTone.rgb(h.toFloat(), .6f, .5f))
            assertEquals(h.toFloat(), hue, 1.5f)
            assertEquals(.6f, saturation, .02f)
        }
        assertEquals(0f, CoverTone.hueSaturation(0xff808080.toInt()).second, 0f)
    }

    // ---- format line -----------------------------------------------------------------------------------------------

    @Test fun theFormatLineSaysOnlyWhatTheDecoderReported() {
        assertEquals("FLAC  24-bit  48 kHz", AudioFormatLine.of("flac", 48000, 24, 900000))
        assertEquals("FLAC  16-bit  44.1 kHz", AudioFormatLine.of("flac", 44100, 16, 0))
        assertEquals("MP3  320 kbps  44.1 kHz", AudioFormatLine.of("mpeg", 44100, 0, 320000))
        assertEquals("AAC  128 kbps  48 kHz", AudioFormatLine.of("mp4a-latm", 48000, 16, 128400))
        assertEquals("FLAC  96 kHz", AudioFormatLine.of("flac", 96000, 0, 0))
        assertEquals("Opus", AudioFormatLine.of("opus", 0, 0, 0))
        assertEquals("MS-WMA  48 kHz", AudioFormatLine.of("ms-wma", 48000, 0, 0))
        assertEquals("", AudioFormatLine.of("", 0, 0, 0))
    }

    @Test fun sampleRatesDropAZeroDecimal() {
        assertEquals("48 kHz", AudioFormatLine.sampleRate(48000))
        assertEquals("44.1 kHz", AudioFormatLine.sampleRate(44100))
        assertEquals("88.2 kHz", AudioFormatLine.sampleRate(88200))
        assertEquals("22 kHz", AudioFormatLine.sampleRate(22000))
    }

    // ---- tabs ------------------------------------------------------------------------------------------------------

    @Test fun idleLibraryFooterIsTextWithoutAnOrphanThumbnail() {
        for (rtl in listOf(false, true)) {
            val idle = NowPlayingGeometry.layout("bar", "cover", 720, 90, 1.625f, rtl, false, false)
            assertNull(idle.cover)
            assertEquals(720, idle.idle!!.width)
            assertEquals(ButtonSet.NONE, idle.buttons)
            assertNull(idle.controls)
        }
    }

    @Test fun tabsAreEqualWidthAndReadRightToLeftInRtl() {
        assertEquals(0, TabsGeometry.indexAt(0f, 400, 4, false))
        assertEquals(0, TabsGeometry.indexAt(99.9f, 400, 4, false))
        assertEquals(1, TabsGeometry.indexAt(100f, 400, 4, false))
        assertEquals(3, TabsGeometry.indexAt(399f, 400, 4, false))
        assertEquals(3, TabsGeometry.indexAt(0f, 400, 4, true))
        assertEquals(0, TabsGeometry.indexAt(399f, 400, 4, true))
        assertEquals(-1, TabsGeometry.indexAt(400f, 400, 4, false))
        assertEquals(-1, TabsGeometry.indexAt(-1f, 400, 4, false))
        assertEquals(-1, TabsGeometry.indexAt(10f, 400, 0, false))
        assertEquals(-1, TabsGeometry.indexAt(10f, 0, 3, false))
    }

    // ---- layout ----------------------------------------------------------------------------------------------------

    private class Case(val style: String, val variant: String, val w: Int, val h: Int)

    /** Sizes in dp: the device's two orientations after the 20 dp page margin, smaller screens, the home card both ways, the library footer. */
    private val cases = buildList {
        for ((w, h) in listOf(748 to 452, 700 to 400, 560 to 300, 460 to 260, 452 to 748, 400 to 620, 360 to 520, 300 to 420, 240 to 200, 120 to 90))
            for (variant in listOf("cover", "queue")) add(Case("full", variant, w, h))
        for ((w, h) in listOf(318 to 404, 404 to 318, 366 to 366, 200 to 200, 150 to 120, 90 to 90)) add(Case("card", "cover", w, h))
        for ((w, h) in listOf(748 to 56, 492 to 56, 300 to 56, 160 to 56, 100 to 56, 60 to 56, 748 to 40)) add(Case("bar", "cover", w, h))
    }

    private fun overlap(a: Box, b: Box) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    private fun check(case: Case, density: Float, rtl: Boolean, notice: Boolean, active: Boolean) {
        val w = (case.w * density).roundToInt()
        val h = (case.h * density).roundToInt()
        val layout = NowPlayingGeometry.layout(case.style, case.variant, w, h, density, rtl, notice, active)
        val label = "${case.style}/${case.variant} ${case.w}x${case.h} d=$density rtl=$rtl notice=$notice active=$active"
        val boxes = layout.boxes()
        for (box in boxes) {
            assertTrue("$label: $box outside", box.left >= 0 && box.top >= 0 && box.right <= w && box.bottom <= h)
            assertTrue("$label: $box is not a rectangle", box.width >= 0 && box.height >= 0)
        }
        for (i in boxes.indices) for (j in i + 1 until boxes.size) assertFalse("$label: ${boxes[i]} overlaps ${boxes[j]}", overlap(boxes[i], boxes[j]))
        if (!active) {
            assertNull("$label: a seek bar with nothing playing", layout.seek)
            assertNull("$label: buttons with nothing playing", layout.controls)
        }
        layout.controls?.let { controls ->
            val buttons = NowPlayingGeometry.buttons(layout.buttons, controls, density)
            for (b in buttons) {
                assertTrue("$label: button $b outside $controls", b.left >= controls.left && b.right <= controls.right && b.top >= controls.top && b.bottom <= controls.bottom)
                assertTrue("$label: button $b is too small to hit", b.width >= (40 * density).toInt() && b.height >= (40 * density).toInt())
            }
            for (i in buttons.indices) for (j in i + 1 until buttons.size) assertFalse("$label: buttons overlap", overlap(buttons[i], buttons[j]))
        }
    }

    @Test fun everyPartStaysInsideTheViewAndNeverOverlapsAnother() {
        for (case in cases) for (density in listOf(1f, 1.625f)) for (rtl in listOf(false, true)) for (notice in listOf(false, true)) for (active in listOf(false, true))
            check(case, density, rtl, notice, active)
    }

    @Test fun theDevicePagesGetTheDesignedArrangement() {
        val d = 1.625f
        fun dp(v: Int) = (v * d).roundToInt()
        // Landscape cover page: a square cover as tall as the page, and a column beside it.
        val land = NowPlayingGeometry.layout("full", "cover", dp(748), dp(452), d, false, false, true)
        assertEquals(dp(452), land.cover!!.width)
        assertEquals(dp(452), land.cover!!.height)
        assertTrue(land.title!!.left >= land.cover!!.right + dp(20))
        assertEquals(ButtonSet.FULL, land.buttons)
        assertNotNull(land.volume)
        assertEquals(2, land.titleLines)
        // Portrait: the cover is on top and nearly as wide as the page; everything else is below it.
        val port = NowPlayingGeometry.layout("full", "cover", dp(452), dp(748), d, false, false, true)
        assertTrue(port.cover!!.width >= dp(452) * 7 / 10)
        assertTrue(port.title!!.top >= port.cover!!.bottom)
        assertNotNull(port.volume)
        // Queue: the list takes the wide side, the cover shrinks to the narrow column.
        val queue = NowPlayingGeometry.layout("full", "queue", dp(748), dp(452), d, false, false, true)
        assertNotNull(queue.queue)
        assertTrue(queue.queue!!.width > queue.cover!!.width * 2)
        assertEquals(ButtonSet.TRANSPORT, queue.buttons)
        // Nothing playing: the queue variant falls back to the idle page, which has no list.
        assertNull(NowPlayingGeometry.layout("full", "queue", dp(748), dp(452), d, false, false, false).queue)
    }

    @Test fun rightToLeftMirrorsTheArrangementButNotTheButtons() {
        val d = 1f
        val ltr = NowPlayingGeometry.layout("full", "cover", 748, 452, d, false, false, true)
        val rtl = NowPlayingGeometry.layout("full", "cover", 748, 452, d, true, false, true)
        assertEquals(748 - ltr.cover!!.right, rtl.cover!!.left)
        assertEquals(ltr.buttons, rtl.buttons)
        assertEquals(748 - ltr.title!!.right, rtl.title!!.left)
    }

    @Test fun aTooSmallPageFallsBackToTheMiniPlayer() {
        val tiny = NowPlayingGeometry.layout("full", "cover", 240, 120, 1f, false, false, true)
        assertNotNull(tiny.line)          // the bar has a progress line along its bottom, the full page never does
        assertNull(tiny.volume)
    }

    @Test fun buttonSetsCountAndSpaceTheirButtons() {
        val box = Box(0, 0, 268, 64)
        val full = NowPlayingGeometry.buttons(ButtonSet.FULL, box, 1f)
        assertEquals(5, full.size)
        assertEquals(0, full.first().left)               // edge to edge, like the bar above it
        assertEquals(268, full.last().right)
        assertEquals(64, full[2].height)                 // the play button is the large one
        assertEquals(3, NowPlayingGeometry.buttons(ButtonSet.TRANSPORT, box, 1f).size)
        assertEquals(1, NowPlayingGeometry.buttons(ButtonSet.PLAY, box, 1f).size)
        assertTrue(NowPlayingGeometry.buttons(ButtonSet.NONE, box, 1f).isEmpty())
        // A narrow row squeezes the small buttons rather than letting them overlap.
        val narrow = NowPlayingGeometry.buttons(ButtonSet.FULL, Box(0, 0, 200, 64), 1f)
        assertTrue(narrow.zip(narrow.drop(1)).all { (a, b) -> a.right <= b.left })
        assertTrue(abs(narrow[2].width - 64) <= 0)
    }
}
