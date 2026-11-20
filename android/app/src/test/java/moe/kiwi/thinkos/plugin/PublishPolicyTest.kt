package moe.kiwi.thinkos.plugin

import org.junit.Assert.assertEquals
import org.junit.Test

class PublishPolicyTest {
    @Test fun userWindowIncludesThreeHundredMilliseconds() {
        val event = 1_000L
        for (age in listOf(0L, 299L, 300L)) assertEquals(0L, PublishPolicy.delay(event + age, event, null))
        assertEquals(50L, PublishPolicy.delay(event + 301, event, null))
        assertEquals(50L, PublishPolicy.delay(event, null, null))
    }

    @Test fun laterPublishesInTheBurstAreSpacedEvenAfterAnotherTap() {
        assertEquals(16L, PublishPolicy.delay(1_000, 1_000, 1_000))
        assertEquals(11L, PublishPolicy.delay(1_005, 1_004, 1_000))
        assertEquals(1L, PublishPolicy.delay(1_015, 1_004, 1_000))
        assertEquals(0L, PublishPolicy.delay(1_016, 1_004, 1_000))
    }
}
