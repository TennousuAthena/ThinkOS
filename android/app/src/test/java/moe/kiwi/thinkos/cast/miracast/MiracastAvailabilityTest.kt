package moe.kiwi.thinkos.cast.miracast

import org.junit.Assert.*
import org.junit.Test

class MiracastAvailabilityTest {
    private class Fixture {
        val queued = ArrayDeque<() -> Unit>()
        val events = mutableListOf<Boolean?>()
        val availability: MiracastAvailability = MiracastAvailability({ queued.addLast(it) }) { events += availability.value }
        fun drain() { while (queued.isNotEmpty()) queued.removeFirst().invoke() }
    }

    @Test fun moduleAppearsAndDisappearsWithoutAnyNetworkPolling() {
        val f = Fixture()
        assertNull(f.availability.value)
        for (next in listOf(false, true, false)) {
            f.availability.set(next)
            assertEquals(next, f.availability.value) // Read model changes before notification.
            assertEquals(1, f.queued.size)
            f.drain()
        }
        assertEquals(listOf(false, true, false), f.events)
    }

    @Test fun unchangedFifteenSecondChecksProduceNoExtraNotification() {
        val f = Fixture()
        f.availability.set(null)
        assertTrue(f.queued.isEmpty())
        for (next in listOf(false, true, null)) {
            f.availability.set(next)
            repeat(100) { f.availability.set(next) }
            assertEquals(1, f.queued.size)
            f.drain()
            assertEquals(next, f.events.last())
        }
        assertEquals(listOf(false, true, null), f.events)
    }

    @Test fun disableInvalidatesAnAlreadyQueuedSuccessfulAnswer() {
        val f = Fixture()
        f.availability.set(true)
        f.availability.set(null) // Serialized disable happens before queued Main delivery.
        assertNull(f.availability.value)
        f.queued.removeFirst().invoke()
        assertTrue(f.events.isEmpty())
        f.drain()
        assertEquals(listOf<Boolean?>(null), f.events)
    }

    @Test fun reenableOnlyPublishesTheNewInspectionAfterAnOldDisable() {
        val f = Fixture()
        f.availability.set(true); f.drain()
        f.availability.set(null)
        f.availability.set(false)
        f.queued.removeFirst().invoke()
        assertEquals(listOf(true), f.events)
        f.drain()
        assertEquals(listOf(true, false), f.events)
    }

    @Test fun equalFinalValueDoesNotLetAnOldGenerationBecomeCurrentAgain() {
        val f = Fixture()
        f.availability.set(false)
        val old = f.queued.removeFirst()
        f.availability.set(true)
        f.availability.set(false)
        old.invoke()
        assertTrue(f.events.isEmpty())
        f.drain()
        assertEquals(listOf(false), f.events)
    }
}
