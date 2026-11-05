package moe.kiwi.thinkos.device

import moe.kiwi.thinkos.tz.TzTestSupport
import org.junit.Assert.*
import org.junit.Test

class SystemTimeZoneTest {
    @Test fun anActualRomIdIsPreferredToTheAliasAndUnknownNeverBecomesGmt() {
        assertEquals("Europe/Kyiv", SystemTimeZone.resolve("Europe/Kyiv", setOf("Europe/Kyiv", "Europe/Kiev")))
        assertEquals("Europe/Kiev", SystemTimeZone.resolve("Europe/Kyiv", setOf("Europe/Kiev")))
        assertNull(SystemTimeZone.resolve("Europe/Kyiv", setOf("GMT")))
        for (id in listOf("Mars/Unknown", "../Etc/UTC", "Etc/UTC\n", "A".repeat(65), "", "GMT+08:00", "亚洲/上海"))
            assertNull(SystemTimeZone.resolve(id, setOf("GMT", "Etc/UTC")))
    }
    @Test fun allThreeFallbacksMatchBundled2026eHourlyFrom2026Through2037() {
        val database = TzTestSupport.database
        assertEquals("2026e", database.version)
        assertEquals(3, SystemTimeZone.aliases.size)
        val start = TzTestSupport.utc(2026, 1, 1)
        val end = TzTestSupport.utc(2038, 1, 1)
        for ((modern, legacy) in SystemTimeZone.aliases) {
            assertEquals(legacy, SystemTimeZone.resolve(modern, setOf(legacy)))
            val current = database.rules(modern)!!
            val alternate = database.rules(legacy)!!
            var time = start
            while (time < end) {
                assertEquals("$modern vs $legacy at $time", current.offsetAt(time), alternate.offsetAt(time))
                time += 3600
            }
        }
    }
}
