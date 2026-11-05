package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.astro.Place
import moe.kiwi.thinkos.tz.Civil
import moe.kiwi.thinkos.tz.TzTestSupport
import moe.kiwi.thinkos.tz.Zones
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test

class ModeAstronomyTest {
    private val previous = Zones.database
    @Before fun installZones() { Zones.install(TzTestSupport.database) }
    @After fun restoreZones() { Zones.install(previous) }
    private val place = Place(52.5, 13.4)
    private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0) =
        TzTestSupport.utc(year, month, day, hour, minute) * 1_000

    @Test fun productionCivilAdapterUsesBundledRulesAcrossSpringGapAndAutumnFold() {
        val calendar = ModeAstronomy({ Zones.clock("Europe/Berlin") }, { place })
        assertNotNull(calendar.prepare(utc(2027, 3, 28, 12)))
        val spring = Civil.daysFromCivil(2027, 3, 28)
        assertEquals(utc(2027, 3, 28, 1, 30), calendar.atLocal(spring, 2 * 60 + 30))
        assertEquals(7 * 3_600_000L, calendar.atLocal(spring, 7 * 60) - calendar.atLocal(spring - 1, 23 * 60))
        val fall = Civil.daysFromCivil(2027, 10, 31)
        assertEquals(utc(2027, 10, 31, 0, 30), calendar.atLocal(fall, 2 * 60 + 30))
        assertEquals(9 * 3_600_000L, calendar.atLocal(fall, 7 * 60) - calendar.atLocal(fall - 1, 23 * 60))
    }

    @Test fun timezoneDatabaseAndTrustChangesInvalidateCachedSunAndCivilBoundaries() {
        var zone = Zones.clock("Europe/Berlin")
        var database: Any = Any()
        var calls = 0
        val calendar = ModeAstronomy({ zone }, { place }, { database }) { _, _, _ ->
            calls++
            ModeAstronomy.SolarDay(ModeController.SunTimes(null, null), polarDay = true)
        }
        val now = utc(2027, 3, 28, 12)
        calendar.prepare(now)
        val day = calendar.dayAt(now)
        val berlinMidnight = calendar.atLocal(day, 0)
        calendar.times(day); calendar.times(day)
        assertEquals(1, calls)
        assertTrue(calendar.daylight(now) == true)
        zone = Zones.clock("America/New_York")
        calendar.prepare(now)
        assertNotEquals(berlinMidnight, calendar.atLocal(day, 0))
        calendar.times(day)
        assertEquals(2, calls)
        database = Any()
        calendar.prepare(now); calendar.times(day)
        assertEquals(3, calls)
        assertNull(calendar.prepare(0))
        assertNull(calendar.times(day))
        calendar.prepare(now); calendar.times(day)
        assertEquals(4, calls)
    }

    @Test fun missingBundledZoneRulesNeverSilentlyUseTheRomsCivilRules() {
        Zones.install(null)
        val calendar = ModeAstronomy({ Zones.clock("Europe/Berlin") }, { place })
        assertNull(calendar.prepare(utc(2026, 11, 5, 23)))
        assertNull(calendar.times(Civil.daysFromCivil(2026, 11, 5)))
        assertNull(calendar.daylight(utc(2026, 11, 5, 23)))
    }

    @Test fun solarDaylightHandlesCrossMidnightEventsAndPolarDayAndNight() {
        val date = Civil.daysFromCivil(2026, 11, 5)
        val midnight = utc(2026, 11, 5)
        for (polar in listOf<Boolean?>(null, true, false)) {
            val calendar = ModeAstronomy({ Zones.clock("Etc/UTC") }, { place }, calculate = { noon, zone, _ ->
                val offset = zone.fields(noon).days - date
                ModeAstronomy.SolarDay(ModeController.SunTimes(midnight + offset * 86_400_000L + 23 * 3_600_000L,
                    midnight + (offset + 1) * 86_400_000L + 8 * 3_600_000L), polar)
            })
            calendar.prepare(midnight + 2 * 3_600_000L)
            assertEquals(polar ?: true, calendar.daylight(midnight + 2 * 3_600_000L))
            assertEquals(polar ?: false, calendar.daylight(midnight + 12 * 3_600_000L))
        }
    }
}
