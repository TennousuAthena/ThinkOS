package moe.kiwi.thinkos.ui

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class BurnInTest {
    @After fun release() { BurnIn.forced = null }

    @Test fun theNudgeStaysInsideItsBoundsInWholeEighthsOfAPixel() {
        var longest = 0f
        for (second in 0..6 * 3600 step 13) {
            val (x, y) = BurnIn.offset(second * 1000L)
            assertTrue("x=$x at second $second", Math.abs(x) <= BurnIn.MAX_X)
            assertTrue("y=$y at second $second", Math.abs(y) <= BurnIn.MAX_Y)
            assertEquals(x, Math.round(x * 8) / 8f, 0f)
            longest = maxOf(longest, Math.abs(x))
        }
        assertTrue("the nudge really reaches its far end: $longest", longest >= BurnIn.MAX_X - 0.5f)
    }

    @Test fun itMovesSlowlyEnoughToBeUnnoticedBetweenSeconds() {
        var previous = BurnIn.offset(0)
        for (second in 1..3600) {
            val now = BurnIn.offset(second * 1000L)
            assertTrue(Math.abs(now.first - previous.first) <= 0.5f && Math.abs(now.second - previous.second) <= 0.5f)
            previous = now
        }
    }

    @Test fun aDebugBuildCanPinItAndRelease() {
        BurnIn.forced = 8f to -6f
        assertEquals(8f to -6f, BurnIn.offset(123_456))
        BurnIn.forced = null
        assertEquals(BurnIn.offset(123_456), BurnIn.offset(123_456))
        assertNotEquals(8f to -6f, BurnIn.offset(0))
    }
}
