package moe.kiwi.thinkos.plugin

import moe.kiwi.thinkos.tz.TzTestSupport
import moe.kiwi.thinkos.tz.Zones
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AstroHostTest {
    @Before fun installZones() { Zones.install(TzTestSupport.database) }
    private val ms = 1_782_041_400_000L
    private val hongKong = mapOf("name" to "Hong Kong", "latitude" to 22.3, "longitude" to 114.2,
        "elevation" to 0, "timezone" to "Asia/Hong_Kong", "source" to "timezone")

    @Test fun explicitPlacesNeverReadTheSystemLocation() {
        val api = AstroHost { error("system coordinates must stay private") }
        val result = api.call("astro.sun", arrayOf(ms, hongKong)) as Map<*, *>
        assertEquals("Asia/Hong_Kong", result["timezone"])
        assertTrue(result["sunrise"] is Long)
        val seasons = api.call("astro.seasons", arrayOf(2026)) as Map<*, *>
        assertEquals(2026, seasons["year"])
    }

    @Test fun implicitLocationRefreshesWhenSettingsChange() {
        var place = hongKong
        val api = AstroHost { place }
        val a = api.call("astro.sun", arrayOf(ms)) as Map<*, *>
        place = hongKong + mapOf("latitude" to -33.9, "longitude" to 18.4, "timezone" to "Africa/Johannesburg")
        val b = api.call("astro.sun", arrayOf(ms)) as Map<*, *>
        assertNotEquals(a["noon"], b["noon"])
        assertEquals("Africa/Johannesburg", b["timezone"])
    }

    @Test fun untrustedClockProducesNoAlmanac() {
        val api = AstroHost { error("invalid time must not read coordinates") }
        assertNull(api.call("astro.sun", arrayOf(0L)))
        assertNull(api.call("astro.moon", arrayOf(null)))
        assertThrows(IllegalArgumentException::class.java) { api.call("astro.sun", arrayOf(Double.NaN)) }
        assertThrows(IllegalArgumentException::class.java) { api.call("astro.seasons", arrayOf(2026.5)) }
    }

    @Test fun badCoordinatesAndUnknownZonesAreRejected() {
        val api = AstroHost { hongKong }
        assertThrows(IllegalArgumentException::class.java) {
            api.call("astro.sun", arrayOf(ms, hongKong + mapOf("latitude" to 95)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            api.call("astro.sun", arrayOf(ms, hongKong + mapOf("timezone" to "Not/AZone")))
        }
    }

    @Test fun deviceFixedOffsetZoneRemainsUsableWithoutBundledCityMetadata() {
        val previous = java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("GMT+08:00"))
            val api = AstroHost { hongKong + mapOf("timezone" to "GMT+08:00") }
            val result = api.call("astro.sun", arrayOf(ms)) as Map<*, *>
            assertEquals("GMT+08:00", result["timezone"])
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }
}
