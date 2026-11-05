package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.update.UpdatePolicy
import org.junit.Assert.*
import org.junit.Test

class FeatureTouchTimeTest {
    private val policy = UpdatePolicy(false, "stable", emptyList(), 0, 0, 1000, 0, 1, 2, 5000, 2024, 1, 1)

    @Test fun releaseRetainsTheDeepSleepOffsetBetweenClockDomains() {
        val release = requireNotNull(FeatureTouchTime.releaseElapsed(9_900, 10_000, 110_000))
        assertEquals(109_900L, release)
        assertTrue(policy.fresh(release, 110_000))
    }

    @Test fun anOldReleaseQueuedOnMainDoesNotBecomeAFreshTap() {
        val release = requireNotNull(FeatureTouchTime.releaseElapsed(1_000, 10_000, 110_000))
        assertEquals(101_000L, release)
        assertFalse(policy.fresh(release, 110_000))
    }

    @Test fun conversionKeepsTheActualPolicyBoundaryInsteadOfClickTime() {
        val release = requireNotNull(FeatureTouchTime.releaseElapsed(5_000, 10_000, 110_000))
        assertTrue(policy.fresh(release, 110_000))
        assertFalse(policy.fresh(release, 110_001))
    }

    @Test fun futureNegativeOrInconsistentClockSamplesAreRefused() {
        assertNull(FeatureTouchTime.releaseElapsed(10_001, 10_000, 110_000))
        assertNull(FeatureTouchTime.releaseElapsed(-1, 10_000, 110_000))
        assertNull(FeatureTouchTime.releaseElapsed(0, -1, 110_000))
        assertNull(FeatureTouchTime.releaseElapsed(0, 10_000, -1))
        assertNull(FeatureTouchTime.releaseElapsed(1, 10_000, 9_999))
        assertEquals(Long.MAX_VALUE, FeatureTouchTime.releaseElapsed(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE))
    }
}
