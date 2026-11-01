package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.ui.VideoGestures.Zone
import org.junit.Assert.*
import org.junit.Test

class VideoGesturesTest {
    @Test fun theMiddleHalfTogglesAndEachOuterQuarterSkips() {
        val width = 1280f
        assertEquals(Zone.REWIND, VideoGestures.zone(0f, width))
        assertEquals(Zone.REWIND, VideoGestures.zone(319f, width))
        assertEquals(Zone.TOGGLE, VideoGestures.zone(320f, width))
        assertEquals(Zone.TOGGLE, VideoGestures.zone(640f, width))
        assertEquals(Zone.TOGGLE, VideoGestures.zone(959f, width))
        assertEquals(Zone.FORWARD, VideoGestures.zone(960f, width))
        assertEquals(Zone.FORWARD, VideoGestures.zone(1279f, width))
        assertEquals(Zone.TOGGLE, VideoGestures.zone(10f, 0f))      // before the first layout: never skip by accident
    }

    @Test fun theLeftHalfIsBrightnessAndTheRightHalfVolume() {
        assertTrue(VideoGestures.isBrightness(0f, 1280f))
        assertTrue(VideoGestures.isBrightness(639f, 1280f))
        assertFalse(VideoGestures.isBrightness(640f, 1280f))
        assertFalse(VideoGestures.isBrightness(1279f, 1280f))
    }

    @Test fun aTouchShowsTheControlsAndATapPutsThemAway() {
        fun shown(attention: Attention, hidden: Boolean = false, status: String = "playing") = VideoGestures.controlsShown(attention, hidden, status)
        assertTrue(shown(Attention.ENGAGED))
        assertFalse(shown(Attention.ENGAGED, hidden = true))      // tapped away while the finger's attention is still up
        assertFalse(shown(Attention.AWAKE))
        assertFalse(shown(Attention.AMBIENT, status = "paused"))
        assertFalse(shown(Attention.ASLEEP))
    }

    @Test fun errorsEndingsAndPausesKeepTheControlsUp() {
        for (status in listOf("error", "ended")) for (hidden in listOf(false, true)) assertTrue(status, VideoGestures.controlsShown(Attention.AWAKE, hidden, status))
        assertTrue(VideoGestures.controlsShown(Attention.AWAKE, true, "paused"))
        assertTrue(VideoGestures.controlsShown(Attention.ENGAGED, true, "paused"))
    }

    @Test fun aFingerGoingUpRaisesTheLevelAndTheScaleIsBounded() {
        assertEquals(0.5f, VideoGestures.level(0.5f, 0f, 500f, 0f), 0f)
        assertEquals(0.7f, VideoGestures.level(0.5f, -100f, 500f, 0f), 0.0001f)
        assertEquals(0.3f, VideoGestures.level(0.5f, 100f, 500f, 0f), 0.0001f)
        assertEquals(1f, VideoGestures.level(0.9f, -1000f, 500f, 0f), 0f)
        assertEquals(0.2f, VideoGestures.level(0.5f, 1000f, 500f, 0.2f), 0f)      // brightness never goes below its floor
        assertEquals(0f, VideoGestures.level(0.5f, 1000f, 500f, 0f), 0f)
        assertEquals(0.5f, VideoGestures.level(0.5f, 0f, 0f, 0f), 0f)             // a zero travel must not divide by zero
    }

    @Test fun aSecondTapCloseInTimeAndPlaceIsADoubleTap() {
        val taps = DoubleTap(300, 100f)
        assertFalse(taps.tap(1000, 500f, 400f))
        assertTrue(taps.tap(1250, 520f, 410f))
        assertFalse("a third tap begins a new pair", taps.tap(1300, 520f, 410f))
        assertTrue(taps.tap(1350, 500f, 400f))
    }

    @Test fun tooSlowOrTooFarApartAreTwoSingleTaps() {
        val taps = DoubleTap(300, 100f)
        assertFalse(taps.tap(0, 500f, 400f))
        assertFalse(taps.tap(301, 500f, 400f))                 // just too late; it is now the first of a new pair
        assertFalse(taps.tap(400, 700f, 400f))                 // too far
        assertTrue(taps.tap(500, 690f, 400f))
        taps.reset()
        assertFalse(taps.tap(520, 690f, 400f))
    }

    @Test fun skipsAddUpWithinAWindowAndRestartOtherwise() {
        val tally = SkipTally(900)
        assertEquals(10, tally.add(0, 10))
        assertEquals(20, tally.add(500, 10))
        assertEquals(30, tally.add(1200, 10))                  // 700 ms after the last: still the same run
        assertEquals(10, tally.add(2300, 10))                  // a pause longer than the window
        assertEquals(-10, tally.add(2400, -10))                // the other direction starts over
        assertEquals(-20, tally.add(2800, -10))
    }
}
