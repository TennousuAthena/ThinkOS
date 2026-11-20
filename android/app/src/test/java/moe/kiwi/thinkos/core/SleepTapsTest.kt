package moe.kiwi.thinkos.core

import org.junit.Assert.*
import org.junit.Test

class SleepTapsTest {
    private val taps = SleepTaps(10f, 500, 40)
    private fun tap(at: Long): Boolean {
        taps.begin(0, 3f, 4f, at)
        return taps.end(0, 3f, 4f, at + 20)
    }

    @Test fun onlyTheSixthTapWakesAndReentryStartsFromZero() {
        repeat(5) { assertFalse(tap(200L * it)) }
        assertTrue(tap(1000))
        assertFalse(tap(1200))
        taps.reset()
        repeat(5) { assertFalse(tap(1400L + 200 * it)) }
        assertTrue(tap(2400))
    }

    @Test fun aLongPressMovedGestureExtraFingerAndBounceDoNotCount() {
        assertFalse(tap(0))
        assertFalse(tap(30))
        taps.begin(0, 3f, 4f, 100)
        assertFalse(taps.end(0, 3f, 4f, 600))
        taps.begin(0, 3f, 4f, 800)
        taps.move(0, 100f, 20f)
        repeat(10) { taps.move(0, 3f, 4f) }
        assertFalse(taps.end(0, 3f, 4f, 850))
        taps.begin(0, 3f, 4f, 900)
        taps.cancelGesture()
        assertFalse(taps.end(0, 3f, 4f, 950))
        repeat(4) { assertFalse(tap(1200L + 200 * it)) }
        assertTrue(tap(2000))
    }

    @Test fun focusOrLifecycleInterruptionDiscardsTheCurrentSequence() {
        repeat(5) { assertFalse(tap(200L * it)) }
        taps.begin(0, 3f, 4f, 1000)
        taps.reset()
        assertFalse(taps.end(0, 3f, 4f, 1020))
        assertFalse(tap(1200))
    }
}
