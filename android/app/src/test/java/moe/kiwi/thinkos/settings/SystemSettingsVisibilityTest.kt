package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The device's own settings page lists only what a person would change. Thresholds, timings and
 * coordinates are tuned once and delivered by the Server, so they stay hidden (but valid and deliverable).
 */
class SystemSettingsVisibilityTest {
    private val schema = File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata").parentFile.resolve("schema/settings/system.json").readText()
    private val scope = SystemSettings.parse(schema)

    @Test fun theDeviceListsOnlyWhatAPersonWouldChange() {
        // A set: org.json on the JVM does not keep key order (Android's does), and order is not the point here.
        assertEquals(
            setOf("theme.mode", "theme.night", "display.orientation", "motion.clock", "display.brightness_max", "server.url", "server.token", "location.name", "kiosk.lock"),
            scope.visibleFields.map { it.key }.toSet())
    }

    @Test fun themeOffersAmbientLightAndSunriseSunset() {
        assertEquals(listOf("auto", "sun", "light", "dark"), scope.fields.getValue("theme.mode").choices.map { it.value })
        assertEquals("auto", scope.fields.getValue("theme.mode").default)
    }

    @Test fun tuningAndCoordinatesAreHiddenYetServerDeliverable() {
        val hidden = listOf("theme.dark_below_lux", "theme.light_above_lux", "theme.night_below_lux", "theme.night_above_lux",
            "attention.awake_to_ambient_s", "attention.to_asleep_s", "attention.engaged_idle_s", "attention.ambient_page_after_s",
            "location.mode", "location.latitude", "location.longitude", "location.elevation", "location.timezone", "location.search_url", "time.ntp_servers", "time.sync_hour")
        hidden.forEach { assertTrue("$it should be hidden", scope.fields.getValue(it).hidden) }

        val store = SettingsStore(object : SettingsPersistence {
            val files = mutableMapOf<String, ByteArray>()
            override fun read(name: String) = files[name]
            override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
        }).also { it.register(scope) }
        store.applyServer(JSONObject("""{"theme":{"dark_below_lux":70,"mode":"sun"},"attention":{"to_asleep_s":240},
            "location":{"mode":"manual","latitude":22.3,"longitude":114.2,"elevation":30}}"""))
        assertEquals(70L, store.get(SettingsStore.SYSTEM, "theme.dark_below_lux"))
        assertEquals("sun", store.get(SettingsStore.SYSTEM, "theme.mode"))
        assertEquals(240L, store.get(SettingsStore.SYSTEM, "attention.to_asleep_s"))
        assertEquals(22.3, store.get(SettingsStore.SYSTEM, "location.latitude"))
        assertTrue(store.rejectedByServer().isEmpty())
    }
}
