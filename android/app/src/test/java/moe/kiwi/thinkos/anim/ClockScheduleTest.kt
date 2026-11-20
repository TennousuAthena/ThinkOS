package moe.kiwi.thinkos.anim

import org.junit.Assert.*
import org.junit.Test

class ClockScheduleTest {
    private val lead = ClockSchedule.PRESENT_LEAD_MILLIS
    private val window = ClockSchedule.POLL_WINDOW_MILLIS

    @Test fun bucketsAndBoundariesFollowTheirPeriod() {
        assertEquals(1_793_000_000L, ClockSchedule.bucket(1_793_000_000_999L, 1000))
        assertEquals(1_793_000_001_000L, ClockSchedule.boundary(1_793_000_000L, 1000))
        assertEquals(29_883_333L, ClockSchedule.bucket(1_793_000_000_000L, 60_000))
        assertEquals(1_793_000_040_000L, ClockSchedule.boundary(29_883_333L, 60_000))
    }

    @Test fun aFrameJustBeforeABoundaryAlreadyCarriesTheNewSecond() {
        val boundary = 1_793_000_001_000L
        // Recorded 10 ms early: on the glass 7 ms after the boundary.
        assertEquals(ClockSchedule.bucket(boundary, 1000), ClockSchedule.bucket(ClockSchedule.displayed(boundary - 10), 1000))
        // Recorded a whole refresh early would reach the glass before the second does.
        assertEquals(ClockSchedule.bucket(boundary, 1000) - 1, ClockSchedule.bucket(ClockSchedule.displayed(boundary - lead - 1), 1000))
    }

    @Test fun theHeartbeatWakesTwoFramesBeforeABoundaryNotEarlier() {
        val boundary = 1_793_000_001_000L
        assertEquals(1_000L - lead - window, ClockSchedule.armDelay(boundary - 1_000, boundary))
        // Already inside the polling window, or past the boundary: poll now.
        assertEquals(0L, ClockSchedule.armDelay(boundary - lead - window, boundary))
        assertEquals(0L, ClockSchedule.armDelay(boundary - 5, boundary))
        assertEquals(0L, ClockSchedule.armDelay(boundary + 700, boundary))
    }

    @Test fun stageCannotRevertTheBucketAlreadySelectedByTheTicker() {
        val period = 1_000L
        val boundary = 1_795_068_517_000L
        val stageWall = boundary - 1
        val tickerBucket = ClockSchedule.bucket(ClockSchedule.displayed(boundary - lead), period)
        val oldStageBucket = ClockSchedule.bucket(stageWall, period)
        assertEquals(tickerBucket - 1, oldStageBucket)
        assertEquals(1_017L, ClockSchedule.displayed(boundary) - oldStageBucket * period)
        assertFalse(ClockSchedule.acceptsBucket(oldStageBucket, tickerBucket, force = false))
        val stageBucket = ClockSchedule.bucket(ClockSchedule.displayed(stageWall), period)
        assertEquals(tickerBucket, stageBucket)
        assertTrue(ClockSchedule.acceptsBucket(stageBucket, tickerBucket, force = false))
    }

    @Test fun aWallClockStepBackTwoSecondsIsAcceptedImmediately() {
        val period = 1_000L
        val now = 1_795_068_517_000L
        val previous = ClockSchedule.bucket(ClockSchedule.displayed(now), period)
        val corrected = ClockSchedule.bucket(ClockSchedule.displayed(now - 2_000L), period)
        assertEquals(previous - 2, corrected)
        assertFalse(ClockSchedule.acceptsBucket(corrected, previous, force = false))
        assertTrue(ClockSchedule.acceptsBucket(corrected, previous, force = true))
    }

    @Test fun everyWakeOfALongRunLandsOnTheNextSecondWithoutSkippingOrRepeating() {
        // Simulate a 60 Hz display: from a random phase, deliver on the first frame displayed past each boundary.
        var wall = 1_793_000_000_321L
        var shown = ClockSchedule.bucket(ClockSchedule.displayed(wall), 1000)
        val delivered = ArrayList<Long>()
        val frame = 16.67
        var clock = wall.toDouble()
        repeat(3_000) {
            clock += frame
            val bucket = ClockSchedule.bucket(ClockSchedule.displayed(clock.toLong()), 1000)
            if (bucket != shown) {
                delivered += bucket
                // Never more than one frame past the true boundary on the glass.
                assertTrue((clock + lead) - bucket * 1000 < frame + lead + 1)
                shown = bucket
            }
        }
        assertTrue(delivered.size >= 49)
        for (i in 1 until delivered.size) assertEquals(1L, delivered[i] - delivered[i - 1])
    }
}
