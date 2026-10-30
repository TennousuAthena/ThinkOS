package moe.kiwi.thinkos.location

import moe.kiwi.thinkos.astro.Place
import moe.kiwi.thinkos.astro.Sun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class DaylightProbeTest {
    private val hongKong = Place(22.3, 114.2)
    private fun at(iso: String) = Instant.parse(iso).toEpochMilli()
    private fun probe(place: Place?, iso: String) = DaylightProbe({ place }, { at(iso) })

    // Hong Kong, 2026-06-21: sunrise about 05:41, sunset about 19:08 (UTC+8).
    @Test fun followsSunriseAndSunsetAtTheConfiguredPlace() {
        assertEquals(false, probe(hongKong, "2026-06-20T21:30:00Z").isDay())   // 05:30, before sunrise
        assertEquals(true, probe(hongKong, "2026-06-20T22:00:00Z").isDay())    // 06:00
        assertEquals(true, probe(hongKong, "2026-06-21T05:00:00Z").isDay())    // 13:00
        assertEquals(true, probe(hongKong, "2026-06-21T10:50:00Z").isDay())    // 18:50
        assertEquals(false, probe(hongKong, "2026-06-21T11:30:00Z").isDay())   // 19:30, after sunset
    }

    @Test fun polarDayAndPolarNight() {
        val tromso = Place(69.649, 18.955)
        assertEquals(true, probe(tromso, "2026-06-21T22:00:00Z").isDay())      // midnight sun
        assertEquals(false, probe(tromso, "2026-12-21T11:00:00Z").isDay())     // sun stays below the horizon at noon
    }

    @Test fun anUntrustedClockOrMissingPlaceGivesNoAnswer() {
        assertNull(DaylightProbe({ hongKong }, { 1_000L }).isDay())            // device without a battery-backed clock
        assertNull(probe(null, "2026-06-21T05:00:00Z").isDay())
    }

    @Test fun movingThePlaceRecomputesImmediately() {
        var place = hongKong
        val now = at("2026-06-21T12:00:00Z")                                    // 20:00 in Hong Kong, 12:00 in Reykjavik
        val probe = DaylightProbe({ place }, { now })
        assertEquals(false, probe.isDay())
        place = Place(64.15, -21.94)
        assertEquals(true, probe.isDay())
    }

    @Test fun reusesTheAnswerForThirtySecondsThenRefreshes() {
        // Find that morning's sunrise with the same horizon the probe uses.
        fun up(t: Long) = Sun.altitude(t, hongKong) > Sun.horizonAltitude(t, hongKong)
        var low = at("2026-06-20T21:00:00Z"); var high = at("2026-06-20T23:00:00Z")
        repeat(40) { val mid = (low + high) / 2; if (up(mid)) high = mid else low = mid }
        val sunrise = high

        var now = sunrise - 10_000
        val probe = DaylightProbe({ hongKong }, { now })
        assertEquals(false, probe.isDay())
        now = sunrise + 10_000                    // the Sun is up, but the answer is only 20 s old
        assertEquals(false, probe.isDay())
        now = sunrise + 40_000                    // older than 30 s: asked again
        assertEquals(true, probe.isDay())
    }
}
