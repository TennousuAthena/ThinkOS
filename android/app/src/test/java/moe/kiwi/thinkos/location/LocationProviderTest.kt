package moe.kiwi.thinkos.location

import moe.kiwi.thinkos.settings.SettingField
import moe.kiwi.thinkos.settings.SettingsPersistence
import moe.kiwi.thinkos.settings.SettingsScope
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.tz.ZoneInfo
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class LocationProviderTest {
    private class Memory : SettingsPersistence {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
    }

    private fun store(memory: Memory = Memory()): SettingsStore {
        val declarations = mapOf(
            "mode" to """{"type":"choice","label":"Mode","default":"auto","choices":[{"value":"auto","label":"Auto"},{"value":"manual","label":"Manual"}]}""",
            "name" to """{"type":"choice","label":"Place","default":"","source":"place","allowEmpty":true}""",
            "latitude" to """{"type":"number","label":"Latitude","default":0,"min":-90,"max":90}""",
            "longitude" to """{"type":"number","label":"Longitude","default":0,"min":-180,"max":180}""",
            "elevation" to """{"type":"number","label":"Elevation","default":0,"min":-500,"max":10000}""",
            "timezone" to """{"type":"choice","label":"Time zone","default":"","source":"timezone","allowEmpty":true}""",
        )
        return SettingsStore(memory).also { settings ->
            settings.register(SettingsScope(SettingsStore.SYSTEM, "System", declarations.map { (key, value) ->
                "location.$key" to SettingField.parse("location.$key", JSONObject(value), true)
            }.toMap()))
        }
    }

    private fun provider(store: SettingsStore, found: Boolean = true) = LocationProvider(store,
        homeZone = { "Asia/Shanghai" },
        zoneInfo = { if (found) ZoneInfo(it, "CN", 31.233333, 121.466667, "") else null },
        cityName = { _, locale -> if (locale.language == "zh") "上海" else "Shanghai" },
    )

    @Test
    fun automaticLocationIsExplicitlyTheOfflineTimeZoneRepresentative() {
        val place = provider(store()).get(Locale.SIMPLIFIED_CHINESE)
        assertEquals("上海", place.name)
        assertEquals(31.233333, place.latitude, 0.000001)
        assertEquals(121.466667, place.longitude, 0.000001)
        assertEquals(0.0, place.elevation, 0.0)
        assertEquals("Asia/Shanghai", place.timezone)
        assertEquals("timezone", place.source)
        assertEquals(setOf("name", "latitude", "longitude", "elevation", "timezone", "source"), place.toMap().keys)
    }

    @Test
    fun manualLocationAndItsTimeZoneSurviveRestartAndIgnoreAutomaticCity() {
        val memory = Memory()
        val settings = store(memory)
        settings.setMany("system", mapOf("location.mode" to "manual", "location.name" to "Kathmandu",
            "location.latitude" to 27.7172, "location.longitude" to 85.324, "location.elevation" to 1400,
            "location.timezone" to "Asia/Kathmandu"))
        val place = provider(store(memory)).get(Locale.US)
        assertEquals(DeviceLocation("Kathmandu", 27.7172, 85.324, 1400.0, "Asia/Kathmandu", "manual"), place)
        assertEquals(27.7172, place.place.latitude, 0.0)
    }

    @Test
    fun missingBundledCoordinatesFallsBackToConfiguredCoordinatesWithoutNetwork() {
        val settings = store()
        settings.setMany("system", mapOf("location.latitude" to 22.3, "location.longitude" to 114.2))
        val place = provider(settings, found = false).get(Locale.US)
        assertEquals("manual-fallback", place.source)
        assertEquals(22.3, place.latitude, 0.0)
        assertEquals(114.2, place.longitude, 0.0)
        assertEquals("Asia/Shanghai", place.timezone)
        assertEquals("22.3000°, 114.2000°", place.name)
    }
}
