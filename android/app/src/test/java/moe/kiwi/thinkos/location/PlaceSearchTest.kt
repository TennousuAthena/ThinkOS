package moe.kiwi.thinkos.location

import moe.kiwi.thinkos.tz.ZoneInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PlaceSearchTest {
    @Test
    fun parsesDistrictHierarchyAndCoordinatesWithoutRelyingOnOptionalFields() {
        val place = PlaceSearch.parse("""{"results":[{"name":"海淀区","latitude":39.96,"longitude":116.3,
          "elevation":48,"timezone":"Asia/Shanghai","admin1":"北京市","admin2":"北京市","admin3":"海淀区","country":"中国"}]}""").single()
        assertEquals("海淀区", place.location.name)
        assertEquals("北京市, 中国", place.detail)
        assertEquals("Asia/Shanghai", place.formValue()["timezone"])
        assertEquals("manual", place.formValue()["mode"])
        assertEquals(39.96, place.formValue()["latitude"])
    }

    @Test
    fun rejectsUntrustedInvalidCoordinatesAndMissingNamesOrTimeZones() {
        val valid = """{"name":"Place","latitude":0,"longitude":0,"timezone":"Etc/UTC"}"""
        val invalid = listOf(valid.replace("\"latitude\":0", "\"latitude\":91"),
            valid.replace("\"longitude\":0", "\"longitude\":-181"),
            valid.replace("\"name\":\"Place\"", "\"name\":\"\""),
            valid.replace("\"timezone\":\"Etc/UTC\"", "\"timezone\":\"\""),
            valid.replace("\"latitude\":0", "\"latitude\":null"))
        assertTrue(PlaceSearch.parse("""{"results":[${invalid.joinToString(",")}]}""").isEmpty())
        assertEquals(20, PlaceSearch.parse("""{"results":[${List(30) { valid }.joinToString(",")}]}""").size)
        assertTrue(PlaceSearch.parse("{}").isEmpty())
        assertThrows(IllegalStateException::class.java) { PlaceSearch.parse("""{"error":true,"reason":"bad query"}""") }
    }

    private val zones = listOf(
        ZoneInfo("Asia/Hong_Kong", "HK", 22.2833, 114.15, ""),
        ZoneInfo("Asia/Kolkata", "IN", 22.5333, 88.3667, "West India"),
        ZoneInfo("Europe/London", "GB", 51.5083, -0.1253, ""),
        ZoneInfo("Etc/UTC", "", 0.0, 0.0, ""),
        ZoneInfo("America/New_York", "US", 40.7142, -74.0064, "Eastern"),
    )
    private val cities = mapOf("Asia/Hong_Kong" to "香港", "Asia/Kolkata" to "加尔各答", "Europe/London" to "伦敦", "Etc/UTC" to "UTC", "America/New_York" to "纽约")
    private fun offline(query: String) = PlaceSearch.offline(query, Locale.CHINA, zones, { id, _ -> cities.getValue(id) }, { code, _ -> "国家$code" })

    @Test
    fun offlineSearchFindsBundledTimeZoneCitiesWithTheirOwnCoordinates() {
        val found = offline("香港").single()
        assertEquals("香港", found.location.name)
        assertEquals("Asia/Hong_Kong", found.location.timezone)
        assertEquals(22.2833, found.location.latitude, 0.0)
        assertEquals(0.0, found.location.elevation, 0.0)
        assertEquals("国家HK", found.detail)
        assertEquals("manual", found.formValue()["mode"])
    }

    @Test
    fun offlineSearchMatchesCountryAndIdTextSkipsEtcAndNeedsTwoCharacters() {
        assertEquals(listOf("伦敦"), offline("国家GB").map { it.location.name })
        assertEquals(listOf("纽约"), offline("new york").map { it.location.name })    // IANA id with the underscore as a space
        assertTrue(offline("utc").isEmpty())
        assertTrue(offline("香").isEmpty())
        assertTrue(PlaceSearch.offline("香港", Locale.CHINA, emptyList()).isEmpty())
    }
}
