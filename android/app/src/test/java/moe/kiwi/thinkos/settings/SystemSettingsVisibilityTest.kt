package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Device settings expose normal and advanced categories, including dim/sleep timing. Sensor
 * thresholds, coordinates and transport tuning remain hidden but valid and deliverable.
 */
class SystemSettingsVisibilityTest {
    private val schema = File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata").parentFile.resolve("schema/settings/system.json").readText()
    private val scope = SystemSettings.parse(schema)

    @Test fun theDeviceListsOnlyWhatAPersonWouldChange() {
        // A set: org.json on the JVM does not keep key order (Android's does), and order is not the point here.
        assertEquals(
            setOf("theme.mode", "redshift.trigger", "redshift.start", "redshift.end", "sleep.enabled", "sleep.start", "sleep.end", "sleep.wake", "mode.manual_hold", "display.orientation", "motion.clock", "display.brightness_max", "units.system", "units.wind", "location.name", "kiosk.lock", "tiles.live", "tiles.interval", "tiles.background", "media.public_http", "cast.enabled", "cast.airplay", "cast.airplay_mirror", "cast.miracast", "cast.name", "media.keep_awake_video", "panel.enabled", "apps.return_after_s",
                "attention.awake_to_ambient_s", "attention.to_asleep_s", "attention.engaged_idle_s", "attention.ambient_page_after_s", "apps.android", "update.enabled", "update.auto_install", "update.channel", "update.mirror",
                "plugins.auto_update", "plugins.community", "plugins.mirror"),
            scope.visibleFields.map { it.key }.toSet())
    }

    @Test fun themeOffersAmbientLightAndSunriseSunset() {
        assertEquals(listOf("auto", "sun", "light", "dark"), scope.fields.getValue("theme.mode").choices.map { it.value })
        assertEquals("auto", scope.fields.getValue("theme.mode").default)
    }

    @Test fun tuningAndCoordinatesAreHiddenYetServerDeliverable() {
        val hidden = listOf("theme.night", "theme.dark_below_lux", "theme.light_above_lux", "theme.night_below_lux", "theme.night_above_lux",
            "mode.minimum_switch_s", "mode.engaged_delay_s", "sleep.start_delay_s", "redshift.dark_dwell_s", "redshift.window_dwell_s", "redshift.polar_start", "redshift.polar_end", "theme.fallback_start", "theme.fallback_end",
            "location.mode", "location.latitude", "location.longitude", "location.elevation", "location.timezone", "location.search_url", "time.ntp_servers", "time.sync_interval", "media.buffer_s", "media.buffer_mb", "panel.edge_dp", "panel.idle_s", "apps.hidden", "server.url", "server.token", "nav.up_edge_dp", "nav.up_enabled", "nav.back_enabled",
            "plugins.sources", "plugins.trial_s", "plugins.start_batch_ms", "plugins.max_installed", "update.module_health_s", "update.module_guard_poll_s")
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

    @Test fun categoriesAndTimingPresetsComeFromTheSharedDeclaration() {
        assertEquals(listOf("display", "mode", "location", "desktop", "media", "system", "plugins"), scope.sections.map { it.id })
        assertTrue(scope.visibleFields.all { field -> scope.sections.any { it.id == field.section } })
        assertEquals(setOf("attention.engaged_idle_s", "attention.ambient_page_after_s", "update.channel", "update.mirror", "plugins.mirror"), scope.visibleFields.filter { it.level == FieldLevel.ADVANCED }.map { it.key }.toSet())
        assertEquals("plugins", scope.fields.getValue("plugins.auto_update").section)
        assertEquals("plugins", scope.fields.getValue("plugins.community").section)
        assertEquals("plugins", scope.fields.getValue("plugins.mirror").section)
        assertEquals(listOf("off", "official", "all"), scope.fields.getValue("plugins.auto_update").choices.map { it.value })
        assertEquals("official", scope.fields.getValue("plugins.auto_update").default)
        assertEquals(false, scope.fields.getValue("plugins.community").default)
        assertEquals(listOf(30L, 60L, 120L, 300L, 600L), scope.fields.getValue("attention.awake_to_ambient_s").presets)
        assertEquals("duration", scope.fields.getValue("apps.return_after_s").format)
        assertEquals(900L, scope.fields.getValue("apps.return_after_s").validate(900))
    }
}
