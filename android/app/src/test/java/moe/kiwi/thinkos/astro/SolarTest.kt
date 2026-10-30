package moe.kiwi.thinkos.astro

import moe.kiwi.thinkos.tz.TzTestSupport
import moe.kiwi.thinkos.tz.Zones
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.math.abs

class SolarTest {
    @Before fun installZones() { Zones.install(TzTestSupport.database) }

    @Test fun independentSolarAlmanac() {
        var count = 0
        var maximumDifference = 0L
        val failures = ArrayList<String>()
        javaClass.getResourceAsStream("/astro-golden.tsv")!!.bufferedReader().useLines { lines ->
            lines.filter { !it.startsWith('#') && it.isNotBlank() }.forEach { row ->
                val v = row.split('\t')
                val place = Place(v[2].toDouble(), v[3].toDouble(), v[4].toDouble())
                val day = Solar.day(v[5].toLong(), Zones.clock(v[1]), place)
                val actual = listOf(day.noon, day.sunrise, day.sunset, day.civil.dawn, day.civil.dusk,
                    day.nautical.dawn, day.nautical.dusk, day.astronomical.dawn, day.astronomical.dusk)
                actual.forEachIndexed { index, value ->
                    val expected = v[index + 6].takeUnless { it == "-" }?.toLong()
                    val limit = if (index <= 2) 90_000 else 120_000
                    if (value == null || expected == null) {
                        if (value != expected) failures += "${v[0]} ${v[5]} event $index expected $expected actual $value"
                    } else {
                        val difference = abs(value - expected)
                        maximumDifference = maxOf(maximumDifference, difference)
                        if (difference > limit) failures += "${v[0]} ${v[5]} event $index differs by ${difference / 1000.0}s"
                    }
                }
                // All endpoints are independently checked against the -8/-4/+6 degree crossings.
                val endpoints = v.drop(15).mapNotNull { it.takeUnless { x -> x == "-" }?.toLong() }
                for (band in day.golden + day.blue) {
                    assertTrue(band.start < band.end)
                    for (endpoint in listOf(band.start, band.end)) {
                        if (abs(endpoint - (day.noon - 43_200_000)) < 1000 || abs(endpoint - (day.noon + 43_200_000)) < 1000) continue
                        if (endpoints.none { abs(it - endpoint) <= 120_000 }) failures += "${v[0]} colour band endpoint $endpoint has no independent match"
                    }
                }
                assertTrue(day.dayLengthMillis in 0..86_400_000L)
                count++
            }
        }
        println("Solar: $count independent days, max event error ${maximumDifference / 1000.0}s")
        assertTrue("Only $count vectors", count >= 1000)
        assertTrue(failures.take(25).joinToString("\n") + "\n${failures.size} mismatches", failures.isEmpty())
    }

    @Test fun localBoundariesFollowDstAndMidnightGaps() {
        val newYork = Zones.clock("America/New_York")
        val spring = AstroEvents.dayWindow(TzTestSupport.utc(2026, 3, 8, 15) * 1000, newYork)
        val fall = AstroEvents.dayWindow(TzTestSupport.utc(2026, 11, 1, 15) * 1000, newYork)
        assertEquals(23 * 3_600_000L, spring.end - spring.start)
        assertEquals(25 * 3_600_000L, fall.end - fall.start)
        val santiago = Zones.clock("America/Santiago")
        val gap = AstroEvents.dayWindow(TzTestSupport.utc(2026, 9, 6, 15) * 1000, santiago)
        assertEquals(1, santiago.fields(gap.start).hour)
        assertNotEquals(santiago.fields(gap.start).days, santiago.fields(gap.start - 1).days)
    }

    @Test fun polarDayAndNightHaveNoInventedEvents() {
        val place = Place(78.223, 15.627)
        val zone = Zones.clock("Arctic/Longyearbyen")
        val summer = Solar.day(TzTestSupport.utc(2026, 6, 21, 12) * 1000, zone, place)
        val winter = Solar.day(TzTestSupport.utc(2026, 12, 21, 12) * 1000, zone, place)
        assertEquals(PolarState.MIDNIGHT_SUN, summer.polar)
        assertEquals(86_400_000L, summer.dayLengthMillis)
        assertEquals(PolarState.POLAR_NIGHT, winter.polar)
        assertEquals(0L, winter.dayLengthMillis)
        assertNull(summer.sunrise)
        assertNull(winter.sunset)
    }

    @Test fun solarTimeCanCrossCivilMidnight() {
        val day = Solar.day(TzTestSupport.utc(2026, 6, 21, 12) * 1000,
            Zones.clock("Pacific/Kiritimati"), Place(1.872, -157.427))
        assertTrue(day.noon in day.dateStart until day.dateEnd)
        assertTrue(day.sunrise!! < day.noon && day.sunset!! > day.noon)
        assertEquals(day.sunset!! - day.sunrise!!, day.dayLengthMillis)
    }

    @Test fun invalidPlacesFailBeforeAnyComputation() {
        for (values in listOf(doubleArrayOf(Double.NaN, 0.0, 0.0), doubleArrayOf(0.0, 181.0, 0.0),
            doubleArrayOf(0.0, 0.0, Double.POSITIVE_INFINITY))) {
            assertThrows(IllegalArgumentException::class.java) { Place(values[0], values[1], values[2]) }
        }
    }
}
