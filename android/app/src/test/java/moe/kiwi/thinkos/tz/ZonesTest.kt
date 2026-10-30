package moe.kiwi.thinkos.tz

import moe.kiwi.thinkos.tz.TzTestSupport.database
import moe.kiwi.thinkos.tz.TzTestSupport.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class ZonesTest {
    private fun offsetHours(zone: String, instant: Long) = database.rules(zone)!!.offsetAt(instant) / 3600.0

    @Test
    fun databaseIsRecentAndComplete() {
        assertTrue("tzdata ${database.version}", database.version >= "2026a")
        assertTrue(database.zones.size > 400)
        assertEquals("CN", database.info("Asia/Shanghai")!!.country)
        assertEquals("Hong Kong", database.countryName("HK"))                 // iso3166.tab
        assertNotNull(database.zones.firstOrNull { it.id == "Etc/UTC" })
    }

    @Test
    fun aliasesResolveToTheirCanonicalZone() {
        assertEquals("Asia/Kolkata", database.canonical("Asia/Calcutta"))
        assertEquals("America/New_York", database.canonical("US/Eastern"))
        assertEquals("Etc/UTC", database.canonical("UTC"))
        assertEquals("Asia/Ho_Chi_Minh", database.canonical("Asia/Saigon"))
        assertNull(database.canonical("Mars/Olympus_Mons"))
        assertNull(database.rules("Mars/Olympus_Mons"))
    }

    @Test
    fun ordinaryWorldClockCities() {
        assertEquals(8.0, offsetHours("Asia/Hong_Kong", utc(2026, 7, 1)), 0.0)
        assertEquals(8.0, offsetHours("Asia/Hong_Kong", utc(2026, 1, 1)), 0.0)
        assertEquals(0.0, offsetHours("Europe/London", utc(2026, 1, 15)), 0.0)
        assertEquals(1.0, offsetHours("Europe/London", utc(2026, 7, 15)), 0.0)
        assertEquals(-5.0, offsetHours("America/New_York", utc(2026, 1, 15)), 0.0)
        assertEquals(-4.0, offsetHours("America/New_York", utc(2026, 7, 15)), 0.0)
        assertEquals(5.5, offsetHours("Asia/Kolkata", utc(2026, 7, 15)), 0.0)
        assertEquals(5.75, offsetHours("Asia/Kathmandu", utc(2026, 7, 15)), 0.0)
        assertEquals(10.5, offsetHours("Australia/Lord_Howe", utc(2026, 7, 15)), 0.0)
        assertEquals(11.0, offsetHours("Australia/Lord_Howe", utc(2026, 1, 15)), 0.0)
        assertEquals(13.0, offsetHours("Pacific/Auckland", utc(2026, 1, 15)), 0.0)
        assertEquals(12.0, offsetHours("Pacific/Auckland", utc(2026, 7, 15)), 0.0)
    }

    /** Rule changes after tzdata 2021a (the device ROM's copy): these zones would be wrong with the ROM data. */
    @Test
    fun recentGovernmentChangesAreCovered() {
        assertEquals("Mexico abolished DST in 2022", -6.0, offsetHours("America/Mexico_City", utc(2026, 7, 15)), 0.0)
        assertEquals("Iran abolished DST in 2022", 3.5, offsetHours("Asia/Tehran", utc(2026, 7, 15)), 0.0)
        assertEquals("Jordan moved to permanent +03 in 2022", 3.0, offsetHours("Asia/Amman", utc(2026, 1, 15)), 0.0)
        assertEquals("Syria moved to permanent +03 in 2022", 3.0, offsetHours("Asia/Damascus", utc(2026, 1, 15)), 0.0)
        assertEquals("Fiji suspended DST", 12.0, offsetHours("Pacific/Fiji", utc(2026, 1, 15)), 0.0)
        assertEquals("Kazakhstan unified on +05 in 2024", 5.0, offsetHours("Asia/Almaty", utc(2026, 7, 15)), 0.0)
    }

    @Test
    fun hemispheresAndTransitionsInstantsAreExact() {
        val newYork = database.rules("America/New_York")!!
        assertEquals(-18_000, newYork.offsetAt(utc(2026, 3, 8, 6, 59)))
        assertEquals(-14_400, newYork.offsetAt(utc(2026, 3, 8, 7, 0)))
        assertFalse(database.rules("America/Sao_Paulo")!!.typeAt(utc(2026, 1, 15)).dst)   // Brazil abolished DST in 2019
        assertTrue(database.rules("Australia/Sydney")!!.typeAt(utc(2026, 1, 15)).dst)
    }

    @Test
    fun farFutureUsesTheFooterRule() {
        // Beyond the explicit table (2037 in "fat" files) DST must keep working.
        assertEquals(3_600, database.rules("Europe/Paris")!!.offsetAt(utc(2090, 1, 15)))
        assertEquals(7_200, database.rules("Europe/Paris")!!.offsetAt(utc(2090, 7, 15)))
        assertEquals(-14_400, database.rules("America/New_York")!!.offsetAt(utc(2099, 7, 15)))
    }

    @Test
    fun zoneClockFieldsAndDayDelta() {
        Zones.install(database)
        try {
            val instant = utc(2026, 7, 1, 20, 30) * 1000        // 2026-07-01 20:30 UTC
            val hongKong = Zones.clock("Asia/Hong_Kong")
            val newYork = Zones.clock("America/New_York")
            assertTrue(hongKong.bundled)
            val hk = hongKong.fields(instant)
            assertEquals(listOf(2026, 7, 2, 4, 30), listOf(hk.year, hk.month, hk.day, hk.hour, hk.minute))
            assertEquals(1, hongKong.dayDelta(instant, newYork))
            assertEquals(-1, newYork.dayDelta(instant, hongKong))
            assertEquals("EDT", newYork.typeAt(instant).abbreviation)
            assertEquals("07/02 04:30", ZoneFormat.format("MM/dd HH:mm", instant, hongKong, Locale.US))
            assertEquals("UTC-4", ZoneFormat.offsetLabel(newYork.typeAt(instant).offset))
            assertEquals("UTC+5:30", ZoneFormat.offsetLabel(19_800))
            assertEquals("UTC-3:30", ZoneFormat.offsetLabel(-12_600))
            assertEquals("UTC", ZoneFormat.offsetLabel(0))
        } finally {
            Zones.install(null)
        }
    }

    @Test
    fun cityNamesFallBackToTheIdWithoutIcu() {
        assertEquals("Mexico City", ZoneNames.city("America/Mexico_City", Locale.ENGLISH))
        assertEquals("Asia", ZoneNames.area("Asia/Tokyo"))
        assertEquals("Etc", ZoneNames.area("Etc/UTC"))
        assertEquals("UTC", ZoneNames.city("Etc/UTC"))
        assertTrue(database.zones.all { ZoneNames.area(it.id) in ZoneNames.AREA_ORDER })
    }
}
