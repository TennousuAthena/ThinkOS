package moe.kiwi.thinkos.core

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class WallClockTest {
    private var realtime = 1_793_000_000_000L
    private var elapsed = 5_000_000L

    private fun fresh() = WallClock.reset({ realtime }, { elapsed })
    @After fun restore() = WallClock.reset()

    @Test fun beforeAnyReadingItIsTheSystemClockAndFollowsIt() {
        fresh()
        assertFalse(WallClock.synced)
        assertEquals(realtime, WallClock.now())
        realtime += 400_000   // someone set the clock
        assertEquals(realtime, WallClock.now())
    }

    @Test fun aReadingMakesItCountMonotonicTimeFromThatMoment() {
        fresh()
        // True time is 350 ms ahead of what the system clock says.
        WallClock.adopt(realtime + 350, elapsed)
        assertEquals(realtime + 350, WallClock.now())
        elapsed += 12_345; realtime += 12_345
        assertEquals(realtime + 350, WallClock.now())
    }

    @Test fun theSystemClockBeingStepOrSlewedCannotMoveItOnceItHasAReading() {
        fresh()
        WallClock.adopt(realtime + 350, elapsed)
        elapsed += 1_000
        val expected = realtime + 350 + 1_000
        realtime -= 90_000   // the ROM's NTP, or a person, steps the clock back a minute and a half
        assertEquals(expected, WallClock.now())
        realtime += 4_000_000
        assertEquals(expected, WallClock.now())
    }

    @Test fun aNewerReadingReplacesTheOldOneAndTellsListeners() {
        fresh()
        var heard = 0
        WallClock.addListener { heard++ }
        WallClock.adopt(realtime, elapsed)
        elapsed += 3_600_000
        // After an hour the crystal has run 40 ms fast: the next reading says true time is 40 ms behind our count.
        val before = WallClock.now()
        WallClock.adopt(before - 40, elapsed)
        assertEquals(before - 40, WallClock.now())
        assertEquals(2, heard)
    }

    @Test fun aSystemClockChangeOnlyNotifiesWhileTheSystemClockIsAllWeHave() {
        fresh()
        var heard = 0
        WallClock.addListener { heard++ }
        WallClock.systemClockChanged()
        assertEquals(1, heard)
        WallClock.adopt(realtime, elapsed)
        WallClock.systemClockChanged()
        assertEquals(2, heard)   // adopt notified; the change after it did not
    }

    @Test fun theAnchorIsAvailableForSaving() {
        fresh()
        assertNull(WallClock.anchor())
        WallClock.adopt(1_793_000_000_123L, 42)
        assertEquals(WallClock.Anchor(1_793_000_000_123L, 42), WallClock.anchor())
    }
}
