package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.*
import org.junit.Test

class UpdateQuietActivityTest {
    @Test fun restartDoesNotInventAnOldTouchOrAnHourOfSleep() {
        val activity = UpdateQuietActivity(10_000)
        activity.observe(Attention.ASLEEP, 0, 10_000)
        assertEquals(0L, activity.sinceTouch(10_000)); assertEquals(0L, activity.asleep(10_000))
        assertEquals(600_000L, activity.sinceTouch(610_000)); assertEquals(600_000L, activity.asleep(610_000))
    }
    @Test fun newTouchResetsIdleEvenWhenAttentionIsStillAsleep() {
        val activity = UpdateQuietActivity(0)
        activity.observe(Attention.ASLEEP, 0, 0)
        activity.observe(Attention.ASLEEP, 600_000, 600_000)
        assertEquals(0L, activity.sinceTouch(600_000)); assertEquals(600_000L, activity.asleep(600_000))
    }
    @Test fun wakingBreaksTheContinuousFallbackSleepInterval() {
        val activity = UpdateQuietActivity(0)
        activity.observe(Attention.ASLEEP, 0, 0)
        activity.observe(Attention.AWAKE, 0, 3_600_000)
        activity.observe(Attention.ASLEEP, 0, 3_600_001)
        assertEquals(0L, activity.asleep(3_600_001))
        activity.observe(Attention.ASLEEP, Long.MAX_VALUE, 3_700_000)
        assertEquals(3_700_000L, activity.sinceTouch(3_700_000))
    }
}
