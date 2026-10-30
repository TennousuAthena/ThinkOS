package moe.kiwi.thinkos.astro

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class AstroEventsTest {
    @Test fun grazingPairsAreFoundBetweenScanSamplesAndAtBothEdges() {
        for (peak in listOf(5_000L, 300_000L, 595_000L)) {
            val events = AstroEvents.crossings(0, 600_000) { t ->
                val distance = (t - peak) / 1000.0
                1 - distance * distance
            }
            assertEquals("peak $peak", 2, events.size)
            assertTrue(events[0].rising)
            assertFalse(events[1].rising)
            assertTrue(abs(events[0].time - (peak - 1000)) < 250)
            assertTrue(abs(events[1].time - (peak + 1000)) < 250)
        }
    }

    @Test fun grazingDipsAreFoundAndTangenciesAreNotInvented() {
        val dip = AstroEvents.crossings(0, 600_000) { t ->
            val distance = (t - 300_000) / 1000.0
            distance * distance - 1
        }
        assertEquals(2, dip.size)
        assertFalse(dip[0].rising)
        assertTrue(dip[1].rising)
        assertTrue(AstroEvents.crossings(0, 600_000) { t ->
            val distance = (t - 300_000) / 1000.0
            distance * distance
        }.isEmpty())
    }

    @Test fun intervalIncludesItsStartAndExcludesItsEnd() {
        assertEquals(listOf(Crossing(0, true)), AstroEvents.crossings(0, 600_000) { it.toDouble() })
        assertTrue(AstroEvents.crossings(0, 600_000) { (it - 600_000).toDouble() }.isEmpty())
        assertTrue(AstroEvents.crossings(0, 0) { 0.0 }.isEmpty())
    }
}
