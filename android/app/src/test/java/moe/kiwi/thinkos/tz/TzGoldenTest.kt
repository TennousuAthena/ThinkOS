package moe.kiwi.thinkos.tz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.zip.GZIPInputStream

/**
 * Every row was produced by CPython's zoneinfo reading the same files (tools/gen_tz_golden.py):
 * one second before and at each UTC transition from 1970 to 2100, plus ordinary probes.
 */
class TzGoldenTest {
    @Test
    fun matchesIndependentImplementationForEveryZone() {
        val stream = javaClass.getResourceAsStream("/tz-golden.tsv.gz") ?: error("tz-golden.tsv.gz missing; run tools/gen_tz_golden.py")
        val database = TzTestSupport.database
        val problems = mutableListOf<String>()
        var rows = 0
        val zones = mutableSetOf<String>()
        GZIPInputStream(stream).bufferedReader().useLines { lines ->
            lines.forEach { line ->
                val (zone, instant, offset, dst, abbreviation) = line.split('\t')
                val actual = database.rules(zone)!!.typeAt(instant.toLong())
                rows++
                zones += zone
                if (actual.offset != offset.toInt() || actual.abbreviation != abbreviation) {
                    if (problems.size < 20) problems += "$zone @ $instant: expected $offset/$abbreviation, got ${actual.offset}/${actual.abbreviation}"
                } else if (actual.dst != (dst == "1") && problems.size < 20) {
                    problems += "$zone @ $instant: dst flag expected $dst, got ${actual.dst}"
                }
            }
        }
        assertTrue("checked $rows rows over ${zones.size} zones, first mismatches:\n" + problems.joinToString("\n"), problems.isEmpty())
        assertEquals(database.zones.size, zones.size)
        assertTrue(rows > 50_000)
    }

    private operator fun <T> List<T>.component4() = this[3]
    private operator fun <T> List<T>.component5() = this[4]
}
