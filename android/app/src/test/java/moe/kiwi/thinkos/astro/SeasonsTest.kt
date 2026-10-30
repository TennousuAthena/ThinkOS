package moe.kiwi.thinkos.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class SeasonsTest {
    @Test
    fun allSupportedYearsMatchIndependentLibastroEvents() {
        val stream = javaClass.getResourceAsStream("/seasons-golden.tsv")
            ?: error("seasons-golden.tsv missing; run tools/gen_seasons_golden.py")
        var years = 0
        var largestErrorMillis = 0L
        stream.bufferedReader().useLines { lines ->
            lines.filter { it.isNotBlank() && !it.startsWith('#') }.forEach { line ->
                val columns = line.split('\t').map(String::toLong)
                val year = columns[0].toInt()
                val actual = Seasons.forYear(year)
                assertEquals(year, actual.year)
                val instants = listOf(actual.marchEquinox, actual.juneSolstice, actual.septemberEquinox, actual.decemberSolstice)
                assertTrue("event ordering in $year", instants.zipWithNext().all { (a, b) -> a < b })
                instants.forEachIndexed { event, instant ->
                    val difference = abs(instant - columns[event + 1])
                    largestErrorMillis = maxOf(largestErrorMillis, difference)
                    assertTrue("$year event $event differs by ${difference / 1000.0}s", difference <= 90_000)
                    if (year in 2020..2029) {
                        assertTrue("2020s event differs by ${difference / 1000.0}s", difference <= 60_000)
                    }
                }
                years++
            }
        }
        assertEquals(Seasons.MAX_YEAR - Seasons.MIN_YEAR + 1, years)
        assertTrue("expected meaningful independent comparison", largestErrorMillis > 1_000)
    }

    @Test
    fun rejectsYearsOutsideTheValidatedRange() {
        for (year in listOf(Int.MIN_VALUE, 1949, 2051, Int.MAX_VALUE)) {
            assertTrue("year $year", runCatching { Seasons.forYear(year) }.exceptionOrNull() is IllegalArgumentException)
        }
    }
}
