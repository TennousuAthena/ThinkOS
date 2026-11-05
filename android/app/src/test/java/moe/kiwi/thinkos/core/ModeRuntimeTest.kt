package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.astro.Place
import moe.kiwi.thinkos.settings.SettingsPersistence
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.SystemSettings
import moe.kiwi.thinkos.tz.TzTestSupport
import moe.kiwi.thinkos.tz.ZoneClock
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

class ModeRuntimeTest {
    private val previous = Zones.database
    @Before fun installZones() { Zones.install(TzTestSupport.database) }
    @After fun restoreZones() { Zones.install(previous) }
    private class Memory : SettingsPersistence {
        val files = linkedMapOf<String, ByteArray>()
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
    }
    private fun settings(memory: Memory = Memory()): SettingsStore = SettingsStore(memory).also {
        val schema = File(TzTestSupport.dir.parentFile, "schema/settings/system.json").readText()
        it.register(SystemSettings.parse(schema))
    }
    private fun at(hour: Int, minute: Int = 0) = TzTestSupport.utc(2026, 11, 5, hour, minute) * 1_000
    private fun runtime(store: SettingsStore, calculate: (Long, ZoneClock, Place) -> ModeAstronomy.SolarDay = { noon, zone, _ ->
        val day = zone.fields(noon).days
        ModeAstronomy.SolarDay(ModeController.SunTimes(day * 86_400_000L + 6 * 3_600_000L,
            day * 86_400_000L + 18 * 3_600_000L))
    }): ModeRuntime = ModeRuntime(store, ModeAstronomy({ Zones.clock("Etc/UTC") }, { Place(22.3, 114.2) }, calculate = calculate))

    @Test fun schemaEditsDriveDwellAndHysteresisAndDoNotPersistTheActiveMode() {
        val memory = Memory()
        val store = settings(memory)
        store.setMany(SettingsStore.SYSTEM, mapOf("redshift.trigger" to "lux", "redshift.dark_dwell_s" to 2,
            "mode.minimum_switch_s" to 0, "theme.night_below_lux" to 4, "theme.night_above_lux" to 10))
        runtime(store).use { mode ->
            assertEquals(Mode.DAY, mode.evaluate(0, at(12), 4f, false).mode)
            assertEquals(Mode.DAY, mode.evaluate(1_000, at(12) + 1_000, 3f, false).mode)
            assertEquals(Mode.REDSHIFT, mode.evaluate(3_000, at(12) + 3_000, 3f, false).mode)
            assertEquals(Mode.REDSHIFT, mode.evaluate(4_000, at(12) + 4_000, 10f, false).mode)
            mode.evaluate(5_000, at(12) + 5_000, 11f, false)
            assertEquals(Mode.DAY, mode.evaluate(7_000, at(12) + 7_000, 11f, false).mode)
            mode.setManual(Mode.SLEEP)
            assertEquals(Mode.SLEEP, mode.evaluate(7_001, at(12) + 7_001, 11f, false).mode)
        }
        val restored = settings(memory)
        runtime(restored).use { mode ->
            assertEquals(Mode.DAY, mode.current)
            assertEquals(Mode.DAY, mode.evaluate(0, at(12), 100f, false).mode)
            assertNull(mode.decision?.manual)
        }
        assertFalse(memory.files.values.any { String(it).contains("SLEEP") })
    }

    @Test fun manualHoldIsReadFromSchemaAndTheActionIsNotRearmedOnEveryTick() {
        val store = settings()
        store.set(SettingsStore.SYSTEM, "mode.minimum_switch_s", 0)
        runtime(store).use { mode ->
            mode.setManual(Mode.DAY)
            val held = mode.evaluate(0, at(17), null, false)
            assertEquals(at(18), held.manual?.expiresAtWallMillis)
            val ended = mode.evaluate(3_600_000, at(18), null, false)
            assertNull(ended.manual)
            assertEquals(Mode.REDSHIFT, ended.mode)
            store.set(SettingsStore.SYSTEM, "mode.manual_hold", "forever")
            mode.setManual(Mode.SLEEP)
            assertEquals(Mode.SLEEP, mode.evaluate(3_600_001, at(18) + 1, 100f, false).mode)
            store.set(SettingsStore.SYSTEM, "redshift.trigger", "off")
            assertEquals(Mode.SLEEP, mode.evaluate(7_200_000, at(19), 100f, false).mode)
            mode.clearManual()
            val cleared = mode.evaluate(7_200_001, at(19) + 1, 100f, false)
            assertEquals(Mode.DAY, cleared.mode)
            assertTrue(cleared.exitSleepViaRedshift)
        }
    }

    @Test fun theLegacyNightOffAliasStillImmediatelyReleasesRedshiftWhileEngaged() {
        val store = settings()
        store.setMany(SettingsStore.SYSTEM, mapOf("redshift.trigger" to "lux", "redshift.dark_dwell_s" to 0))
        runtime(store).use { mode ->
            assertEquals(Mode.REDSHIFT, mode.evaluate(0, at(12), 0f, false).mode)
            store.set(SettingsStore.SYSTEM, "theme.night", false)
            assertEquals("off", store.get(SettingsStore.SYSTEM, "redshift.trigger"))
            assertEquals(Mode.DAY, mode.evaluate(1, at(12) + 1, 0f, true).mode)
        }
    }

    @Test fun removingTheWholeServerScopeReleasesCachedOffAndRestoresTheDefaultSunTrigger() {
        val store = settings()
        store.setMany(SettingsStore.SYSTEM, mapOf("mode.minimum_switch_s" to 0, "redshift.window_dwell_s" to 0))
        store.applyServer(JSONObject("""{"redshift":{"trigger":"off"}}"""))
        runtime(store).use { mode ->
            assertEquals(Mode.DAY, mode.evaluate(0, at(20), null, false).mode)
            store.applyServer(JSONObject())
            assertEquals("sun", store.get(SettingsStore.SYSTEM, "redshift.trigger"))
            assertEquals(Mode.REDSHIFT, mode.evaluate(1, at(20) + 1, null, false).mode)
        }
    }

    @Test fun liveSettingsInvalidateSolarCacheButEachOrdinaryTickReusesIt() {
        val store = settings()
        var calls = 0
        runtime(store) { noon, zone, _ ->
            calls++
            val day = zone.fields(noon).days
            ModeAstronomy.SolarDay(ModeController.SunTimes(day * 86_400_000L + 6 * 3_600_000,
                day * 86_400_000L + 18 * 3_600_000))
        }.use { mode ->
            mode.evaluate(0, at(12), 100f, false)
            assertEquals(4, calls)
            for (i in 1..20) mode.evaluate(i * 1_000L, at(12) + i * 1_000L, 100f, false)
            assertEquals(4, calls)
            store.set(SettingsStore.SYSTEM, "location.latitude", 30)
            mode.evaluate(21_000, at(12) + 21_000, 100f, false)
            assertEquals(8, calls)
            mode.invalidateTime()
            mode.evaluate(22_000, at(12) + 22_000, 100f, false)
            assertEquals(12, calls)
        }
    }

    @Test fun untrustedClockSkipsSunAndCannotAutoSleepButAllowsAnExplicitManualMode() {
        val store = settings()
        store.set(SettingsStore.SYSTEM, "sleep.enabled", true)
        runtime(store) { _, _, _ -> error("Must not calculate Sun before the clock is trusted") }.use { mode ->
            assertEquals(Mode.DAY, mode.evaluate(0, 0, 100f, false).mode)
            mode.setManual(Mode.SLEEP)
            val held = mode.evaluate(1, 1, 100f, false)
            assertEquals(Mode.SLEEP, held.mode)
            assertNull(held.manual?.expiresAtWallMillis)
        }
    }

    @Test fun hiddenFallbackWindowIsUsedThroughBundledCivilTimeAndCanCrossMidnight() {
        val store = settings()
        store.setMany(SettingsStore.SYSTEM, mapOf("theme.fallback_start" to "19:00", "theme.fallback_end" to "07:00"))
        runtime(store).use { mode ->
            mode.evaluate(0, at(20), 100f, false)
            assertEquals(Theme.LIGHT, mode.fallbackTheme(at(20)))
            assertEquals(Theme.DARK, mode.fallbackTheme(at(12)))
        }
    }

    @Test fun aLegacyEqualPairFallsBackToSchemaDefaultsWithoutMutatingTheInput() {
        val store = settings()
        val defaults = store.snapshot(SettingsStore.SYSTEM)
        val equal = defaults + mapOf("redshift.start" to "12:00", "redshift.end" to "12:00")
        val parsed = ModeSettings.parse(equal, defaults)
        assertEquals(ModeController.LocalWindow(19 * 60, 7 * 60), parsed.config.fixedWindow)
        assertEquals(setOf("redshift.start"), parsed.fallbackWindows)
        assertEquals("12:00", equal["redshift.start"])
        assertEquals("12:00", equal["redshift.end"])
    }
}
