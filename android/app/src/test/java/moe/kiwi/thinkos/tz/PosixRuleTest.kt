package moe.kiwi.thinkos.tz

import moe.kiwi.thinkos.tz.TzTestSupport.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PosixRuleTest {
    private fun rule(text: String) = PosixRule.parse(text)

    @Test
    fun fixedOffsetWithQuotedName() {
        val iran = rule("<+0330>-3:30")
        assertEquals(12_600, iran.typeAt(utc(2026, 7, 1)).offset)
        assertEquals("+0330", iran.typeAt(utc(2026, 7, 1)).abbreviation)
        assertFalse(iran.typeAt(utc(2026, 1, 1)).dst)
        assertEquals(-3_600, rule("<-01>1").typeAt(0).offset)
    }

    @Test
    fun europeanRuleSwitchesAtOneUtc() {
        val london = rule("GMT0BST,M3.5.0/1,M10.5.0")
        assertEquals(0, london.typeAt(utc(2026, 3, 29, 0, 59)).offset)
        assertEquals(3_600, london.typeAt(utc(2026, 3, 29, 1, 0)).offset)
        assertEquals(3_600, london.typeAt(utc(2026, 10, 25, 0, 59)).offset)   // BST ends 02:00 BST = 01:00 UTC
        assertEquals(0, london.typeAt(utc(2026, 10, 25, 1, 0)).offset)
    }

    @Test
    fun northAmericanRuleUsesLocalTimes() {
        val newYork = rule("EST5EDT,M3.2.0,M11.1.0")
        assertEquals(-18_000, newYork.typeAt(utc(2026, 3, 8, 6, 59)).offset)   // 01:59 EST
        assertEquals(-14_400, newYork.typeAt(utc(2026, 3, 8, 7, 0)).offset)    // 03:00 EDT
        assertEquals(-14_400, newYork.typeAt(utc(2026, 11, 1, 5, 59)).offset)  // 01:59 EDT
        assertEquals(-18_000, newYork.typeAt(utc(2026, 11, 1, 6, 0)).offset)   // back to 01:00 EST
    }

    @Test
    fun southernHemisphereDaylightSpansTheNewYear() {
        val sydney = rule("AEST-10AEDT,M10.1.0,M4.1.0/3")
        assertTrue(sydney.typeAt(utc(2026, 1, 15)).dst)
        assertFalse(sydney.typeAt(utc(2026, 7, 15)).dst)
        assertTrue(sydney.typeAt(utc(2026, 12, 25)).dst)
        assertEquals(39_600, sydney.typeAt(utc(2026, 12, 25)).offset)
    }

    @Test
    fun halfHourDaylightSavingIsHonoured() {
        val lordHowe = rule("<+1030>-10:30<+11>-11,M10.1.0,M4.1.0")
        assertEquals(37_800, lordHowe.typeAt(utc(2026, 7, 1)).offset)
        assertEquals(39_600, lordHowe.typeAt(utc(2026, 1, 1)).offset)
    }

    @Test
    fun negativeAndOversizedTransitionTimes() {
        // Greenland-style: "-1" is 23:00 on Saturday in standard time -02, i.e. 01:00 UTC on Sunday.
        val nuuk = rule("<-02>2<-01>,M3.5.0/-1,M10.5.0/0")
        assertEquals(-7_200, nuuk.typeAt(utc(2026, 3, 29, 0, 59)).offset)
        assertEquals(-3_600, nuuk.typeAt(utc(2026, 3, 29, 1, 0)).offset)
        // 50 hours after midnight on the fourth Thursday (Gaza-style rule syntax).
        val oversized = rule("EET-2EEST,M3.4.4/50,M10.4.4/50")
        assertEquals(7_200, oversized.typeAt(utc(2026, 3, 27, 0, 0)).offset)
        assertEquals(10_800, oversized.typeAt(utc(2026, 4, 1, 0, 0)).offset)
    }

    @Test
    fun julianAndZeroBasedDates() {
        // J60 is always March 1 (February 29 is never counted); 59 is March 1 only in leap years.
        val julian = rule("AAA0BBB,J60,J300")
        assertTrue(julian.typeAt(utc(2027, 3, 1, 12)).dst)
        assertFalse(julian.typeAt(utc(2027, 2, 28, 12)).dst)
        assertTrue(julian.typeAt(utc(2028, 3, 1, 12)).dst)
        assertFalse(julian.typeAt(utc(2028, 2, 29, 12)).dst)
        val zero = rule("AAA0BBB,59,300")
        assertTrue(zero.typeAt(utc(2027, 3, 1, 12)).dst)
        assertFalse(zero.typeAt(utc(2028, 2, 28, 12)).dst)
        assertTrue(zero.typeAt(utc(2028, 2, 29, 12)).dst)
    }

    @Test
    fun lastWeekdayNeverSpillsIntoNextMonth() {
        val february = PosixRule.RuleDate.MonthWeekDay(2, 5, 0)     // last Sunday of February
        assertEquals(Civil.daysFromCivil(2026, 2, 22), february.dayIn(2026))
        assertEquals(Civil.daysFromCivil(2028, 2, 27), february.dayIn(2028))
    }

    @Test
    fun malformedRulesAreRejected() {
        for (text in listOf("", "EST", "EST5EDT,M13.1.0,M11.1.0", "EST5EDT,M3.6.0,M11.1.0", "EST5EDT,J0,J1", "<abc", "EST5EDT,M3.2.0")) {
            assertThrows(text, RuntimeException::class.java) { rule(text) }
        }
    }
}
