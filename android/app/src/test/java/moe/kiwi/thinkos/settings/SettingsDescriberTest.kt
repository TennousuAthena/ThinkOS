package moe.kiwi.thinkos.settings

import moe.kiwi.thinkos.i18n.Labels
import moe.kiwi.thinkos.location.LocationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class SettingsDescriberTest {
    private class Memory : SettingsPersistence {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
    }

    private fun settings(): SettingsStore {
        val fields = linkedMapOf(
            "mode" to """{"type":"choice","label":"Mode","default":"manual","choices":[{"value":"auto","label":"Auto"},{"value":"manual","label":"Manual"}]}""",
            "name" to """{"type":"choice","label":"Place","default":"Test place","source":"place","allowEmpty":true}""",
            "latitude" to """{"type":"number","label":"Latitude","default":27.717,"min":-90,"max":90}""",
            "longitude" to """{"type":"number","label":"Longitude","default":85.324,"min":-180,"max":180}""",
            "elevation" to """{"type":"number","label":"Elevation","default":1400,"min":-500,"max":10000}""",
            "timezone" to """{"type":"choice","label":"Zone","default":"","source":"timezone","allowEmpty":true}""",
            "search_url" to """{"type":"text","label":"Search","default":"https://example.invalid"}""",
        )
        return SettingsStore(Memory()).also { store ->
            store.register(SettingsScope(SettingsStore.SYSTEM, "System", fields.map { (key, json) ->
                "location.$key" to SettingField.parse("location.$key", JSONObject(json), true)
            }.toMap()))
        }
    }

    private fun editor(settings: SettingsStore): Map<*, *> {
        val labels = Labels({ it }) { _, _, _, _ -> null }
        val describer = SettingsDescriber(settings, labels) { _, _ -> "System" }
        val fields = describer.describe(SettingsStore.SYSTEM, Locale.US)!!["fields"] as List<*>
        return (fields.filterIsInstance<Map<*, *>>().first { it["key"] == "location.name" })["place"] as Map<*, *>
    }

    @Test fun anUnchangedPlaceEditDoesNotPinTheDeviceTimezone() {
        val settings = settings()
        val original = settings.snapshot(SettingsStore.SYSTEM)
        val edit = editor(settings)
        val patch = listOf("name", "latitude", "longitude", "elevation", "timezone").associate { key ->
            "location.$key" to requireNotNull(edit[key])
        }
        settings.setMany(SettingsStore.SYSTEM, patch)
        assertEquals(original, settings.snapshot(SettingsStore.SYSTEM))
        // Data consumers still receive a resolved zone for astronomical and date calculations.
        assertTrue(LocationProvider(settings).get(Locale.US).timezone.isNotEmpty())
    }

    @Test fun anExplicitPlaceTimezoneRemainsExplicitInTheEditor() {
        val settings = settings()
        settings.set(SettingsStore.SYSTEM, SystemSettings.Keys.LOCATION_TIMEZONE, "Asia/Kathmandu")
        val edit = editor(settings)
        assertEquals("Asia/Kathmandu", edit["timezone"])
        assertEquals("Asia/Kathmandu", LocationProvider(settings).get(Locale.US).timezone)
    }

    private fun describer(store: SettingsStore) = SettingsDescriber(store, Labels({ it }) { _, _, _, _ -> null }) { _, _ -> "System" }

    private fun scope(id: String, vararg fields: Pair<String, String>, system: Boolean = false) =
        SettingsScope(id, id, fields.associate { (key, json) -> key to SettingField.parse(key, JSONObject(json), system) })

    @Test fun hiddenFieldsAreNotListedButStayStoredAndWritable() {
        val store = SettingsStore(Memory()).also {
            it.register(scope(SettingsStore.SYSTEM,
                "theme.mode" to """{"type":"choice","label":"Mode","default":"auto","choices":[{"value":"auto","label":"A"},{"value":"sun","label":"S"}]}""",
                "theme.dark_below_lux" to """{"type":"number","label":"Dark","default":50,"min":10,"max":200,"hidden":true}""", system = true))
        }
        val described = describer(store).describe(SettingsStore.SYSTEM, Locale.US)!!
        assertEquals(listOf("theme.mode"), (described["fields"] as List<*>).map { (it as Map<*, *>)["key"] })
        assertEquals(1L, describer(store).list(Locale.US).single()["count"])
        // The Server and system code can still read and write it.
        assertEquals(50L, store.get(SettingsStore.SYSTEM, "theme.dark_below_lux"))
        store.set(SettingsStore.SYSTEM, "theme.dark_below_lux", 80)
        assertEquals(80L, store.get(SettingsStore.SYSTEM, "theme.dark_below_lux"))
    }

    @Test fun aScopeWhoseSettingsAreAllHiddenHasNoPage() {
        val store = SettingsStore(Memory()).also {
            it.register(scope("weather", "interval" to """{"type":"number","label":"s","default":600,"min":60,"max":3600,"hidden":true}"""))
        }
        assertTrue(describer(store).list(Locale.US).isEmpty())
        assertFalse(store.scope("weather")!!.visibleFields.isNotEmpty())
    }

    @Test fun thePlaceRowIsOverriddenWhenAnyPartOfItIs() {
        val store = settings()
        fun overridden() = ((describer(store).describe(SettingsStore.SYSTEM, Locale.US)!!["fields"] as List<*>)
            .filterIsInstance<Map<*, *>>().first { it["key"] == "location.name" })["overridden"]
        assertEquals(false, overridden())
        store.set(SettingsStore.SYSTEM, "location.latitude", 10)       // a coordinate alone is part of the place
        assertEquals(true, overridden())
        store.reset(SettingsStore.SYSTEM, "location.latitude")
        assertEquals(false, overridden())
    }
}
