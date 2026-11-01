package moe.kiwi.thinkos.settings

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class UnitsTest {
    private val schema = File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata").parentFile.resolve("schema/settings/system.json").readText()
    private val scope = SystemSettings.parse(schema)

    @Test fun defaultsAreMetricAndWindFollowsTheSystem() {
        assertEquals("metric", scope.fields.getValue("units.system").default)
        assertEquals("auto", scope.fields.getValue("units.wind").default)
        assertEquals(listOf("auto", "kmh", "ms", "mph", "kn"), scope.fields.getValue("units.wind").choices.map { it.value })
        val units = Units("metric", "auto")
        assertEquals("c" to "kmh", units.temperature to units.wind)
    }

    @Test fun imperialSwitchesTemperatureAndAutoWind() {
        val units = Units("imperial", "auto")
        assertEquals("f" to "mph", units.temperature to units.wind)
        assertEquals(74.12, units.temperature(23.4), 0.01)
        assertEquals(8.0, Units("imperial", "auto").wind(13.0), 0.1)
    }

    @Test fun explicitWindUnitWinsOverTheSystem() {
        assertEquals("ms", Units("imperial", "ms").wind)
        assertEquals(10.0, Units("metric", "ms").wind(36.0), 1e-9)
        assertEquals(36.0, Units("metric", "kmh").wind(36.0), 1e-9)
        assertEquals(19.44, Units("metric", "kn").wind(36.0), 0.01)
        assertEquals(23.4, Units("metric", "kmh").temperature(23.4), 1e-9)
    }

    @Test fun pluginsGetResolvedUnitsOnly() {
        assertEquals(mapOf("system" to "imperial", "temperature" to "f", "wind" to "kn"), Units("imperial", "kn").toMap())
    }
}
