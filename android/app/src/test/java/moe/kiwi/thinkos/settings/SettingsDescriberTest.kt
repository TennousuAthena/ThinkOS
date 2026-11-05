package moe.kiwi.thinkos.settings

import moe.kiwi.thinkos.i18n.Labels
import moe.kiwi.thinkos.location.LocationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.io.File
import org.junit.Assert.assertThrows

class SettingsDescriberTest {
    @Test fun timeDescriptorsKeepAsciiClockAndDoNotAcquireTextOrNumberProperties() {
        val scope = SettingsScope("clock", "Clock", mapOf("at" to SettingField.parse("at",
            JSONObject("""{"type":"time","label":"时刻","default":"07:05"}"""), false)))
        val store = SettingsStore(Memory()).also { it.register(scope) }
        for (locale in listOf(Locale.SIMPLIFIED_CHINESE, Locale("ar"), Locale.US)) {
            val descriptor = requireNotNull(describer(store).describe("clock", locale))
            @Suppress("UNCHECKED_CAST")
            val field = (descriptor["fields"] as List<Map<String, Any>>).single()
            assertEquals("time", field["type"])
            assertEquals("07:05", field["value"])
            assertEquals("07:05", field["display"])
            for (key in listOf("min", "max", "step", "maxLength", "secret", "choices", "source")) assertFalse(field.containsKey(key))
        }
    }
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

    @Test fun warningsOnlyAppearWhenEnabledAndPlaceholdersDoNotBecomeRowHelp() {
        val store = SettingsStore(Memory()).also {
            it.register(scope("cast",
                "enabled" to """{"type":"bool","label":"Cast","default":false,"warn":"Anyone nearby can cast"}""",
                "address" to """{"type":"text","label":"Address","default":"","placeholder":"https://example.test"}"""))
        }
        fun fields() = (describer(store).describe("cast", Locale.US)!!["fields"] as List<*>).filterIsInstance<Map<*, *>>()
        assertFalse(fields()[0].containsKey("warn"))
        assertEquals("https://example.test", fields()[1]["placeholder"])
        assertFalse(fields()[1].containsKey("help"))
        store.set("cast", "enabled", true)
        assertEquals("Anyone nearby can cast", fields()[0]["warn"])
        store.set("cast", "enabled", false)
        assertFalse(fields()[0].containsKey("warn"))
    }

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

    @Test fun dependenciesCollapseWithoutClearingStoredValuesAndSurviveRestart() {
        val memory = Memory()
        val definition = scope("probe",
            "enabled" to """{"type":"bool","label":"Enabled","default":true}""",
            "delay" to """{"type":"number","label":"Delay","default":30,"min":0,"max":600,"depends":{"key":"enabled","value":true},"level":"advanced"}""")
        fun visible(store: SettingsStore) = (describer(store).describe("probe", Locale.US)!!["fields"] as List<*>).map { (it as Map<*, *>)["key"] }
        val first = SettingsStore(memory).also { it.register(definition) }
        first.set("probe", "delay", 125)
        first.set("probe", "enabled", false)
        assertEquals(listOf("enabled"), visible(first))
        assertEquals(125L, first.get("probe", "delay"))
        val next = SettingsStore(memory).also { it.register(definition) }
        assertEquals(listOf("enabled"), visible(next))
        next.set("probe", "enabled", true)
        assertEquals(listOf("enabled", "delay"), visible(next))
        assertEquals(125L, next.get("probe", "delay"))
        assertEquals("advanced", ((describer(next).describe("probe", Locale.US)!!["fields"] as List<*>)[1] as Map<*, *>)["level"])
    }

    @Test fun relatedFieldsUseTheSystemScopeWithoutBecomingPluginConfiguration() {
        val store = SettingsStore(Memory())
        val system = scope(SettingsStore.SYSTEM,
            "cast.enabled" to """{"type":"bool","label":"Cast","default":true}""",
            "secret.token" to """{"type":"text","label":"Token","default":"private","secret":true}""",
            "hidden.value" to """{"type":"bool","label":"Hidden","default":false,"level":"hidden"}""", system = true)
        store.register(system)
        store.register(SettingsScope("cast", "Cast", emptyMap(), related = listOf("cast.enabled")))
        val fields = describer(store).describe("cast", Locale.US)!!["fields"] as List<*>
        assertEquals(SettingsStore.SYSTEM, (fields.single() as Map<*, *>)["scope"])
        assertEquals("cast", describer(store).list(Locale.US)[1]["scope"])
        assertTrue(store.snapshot("cast").isEmpty())
        assertThrows(SettingError::class.java) { store.set("cast", "cast.enabled", false) }
        for (key in listOf("secret.token", "hidden.value", "missing.value")) {
            assertThrows(IllegalArgumentException::class.java) { store.register(SettingsScope("probe", "Probe", emptyMap(), related = listOf(key))) }
        }
    }

    @Test fun durationsUseLocalisedUnitsAndPresetsDoNotRestrictDeliveredValues() {
        val memory = Memory()
        val store = SettingsStore(memory).also {
            it.register(scope("probe", "delay" to """{"type":"number","label":"Delay","default":900,"min":0,"max":7200,"format":"duration","zero":"Off","presets":[0,300,900,3600]}"""))
        }
        val tables = File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata").parentFile!!.resolve("i18n/system")
        fun row(tag: String): Map<*, *> {
            val table = JSONObject(tables.resolve("$tag.json").readText())
            val local = SettingsDescriber(store, Labels({ key -> table.optString(key.replace('.', '_')) }) { _, _, _, _ -> null }) { _, _ -> "Probe" }
            return (local.describe("probe", Locale.forLanguageTag(tag))!!["fields"] as List<*>).single() as Map<*, *>
        }
        assertEquals("15 minutes", row("en")["display"])
        assertEquals("15 分钟", row("zh-Hans")["display"])
        for (tag in listOf("en", "zh-Hans", "zh-Hant", "es", "fr", "pt", "ru", "ar", "hi", "bn", "ur")) {
            val display = row(tag)["display"] as String
            assertFalse("$tag: $display", display.contains("setting.") || display.contains("{1}") || display.contains("%1"))
        }
        store.set("probe", "delay", 3600)
        assertEquals("1 hour", row("en")["display"])
        store.set("probe", "delay", 125)
        assertEquals("125 seconds", row("en")["display"])
        store.set("probe", "delay", 0)
        assertEquals("Off", row("en")["display"])
        val presets = row("en")["presets"] as List<*>
        assertEquals(900L, (presets[2] as Map<*, *>)["value"])
        assertEquals("15 minutes", (presets[2] as Map<*, *>)["label"])
    }

    @Test fun negatedDependenciesCollapseTheWholeChainWithoutDiscardingValues() {
        val store = SettingsStore(Memory()).also {
            it.register(scope("probe",
                "source_1" to """{"type":"text","label":"One","default":""}""",
                "source_2" to """{"type":"text","label":"Two","default":"","depends":{"key":"source_1","not":""}}""",
                "source_3" to """{"type":"text","label":"Three","default":"","depends":{"key":"source_2","not":""}}"""))
        }
        fun visible() = (describer(store).describe("probe", Locale.US)!!["fields"] as List<*>).map { (it as Map<*, *>)["key"] }
        store.setMany("probe", mapOf("source_1" to "a", "source_2" to "b", "source_3" to "c"))
        assertEquals(listOf("source_1", "source_2", "source_3"), visible())
        store.set("probe", "source_1", "")
        assertEquals(listOf("source_1"), visible())
        assertEquals("b", store.get("probe", "source_2"))
        assertEquals("c", store.get("probe", "source_3"))
        store.set("probe", "source_1", "a")
        assertEquals(listOf("source_1", "source_2", "source_3"), visible())
    }

    @Test fun brightnessPreviewBelongsToTheRegisteredSystemFieldIncludingRelatedRows() {
        val store = SettingsStore(Memory())
        val brightness = SettingField.parse(SystemSettings.Keys.BRIGHTNESS_MAX,
            JSONObject("""{"type":"number","label":"Brightness","default":85,"min":20,"max":100,"step":5,"unit":"%"}"""), true)
        store.register(SettingsScope(SettingsStore.SYSTEM, "System", mapOf(brightness.key to brightness)))
        store.register(SettingsScope("probe", "Probe", emptyMap(), related = listOf(brightness.key)))
        val row = (describer(store).describe("probe", Locale.US)!!["fields"] as List<*>).single() as Map<*, *>
        assertEquals("brightness", row["preview"])
        assertEquals(SettingsStore.SYSTEM, row["scope"])
        assertEquals("85 %", row["display"])
        assertTrue(store.snapshot("probe").isEmpty())
        assertEquals(null, SettingsDescriber.preview("probe", brightness))
        assertEquals(null, SettingsDescriber.preview(SettingsStore.SYSTEM,
            SettingField.parse("other.level", brightness.toJson(), true)))
        assertEquals(null, SettingsDescriber.preview(SettingsStore.SYSTEM,
            SettingField.parse(brightness.key, brightness.toJson().put("presets", org.json.JSONArray(listOf(20, 85))), true)))
    }

    @Test fun numericPreviewAndCommittedDescriptorsUseTheSameLocalizedDisplay() {
        val store = SettingsStore(Memory()).also {
            it.register(scope("probe", "distance" to """{"type":"number","label":"Distance","default":1234.5,"min":0,"max":2000,"unit":"km"}"""))
        }
        for (locale in listOf(Locale.US, Locale.FRANCE, Locale.forLanguageTag("ar"))) {
            val row = (describer(store).describe("probe", locale)!!["fields"] as List<*>).single() as Map<*, *>
            assertEquals(row["display"], SettingsDescriber.numberDisplay(1234.5, "km", locale))
        }
    }

    @Test fun pluginDescriptorsCarryGlobalCategoriesWithoutAnotherSystemReadOrUnrelatedValues() {
        val store = SettingsStore(Memory())
        store.register(SettingsScope(SettingsStore.SYSTEM, "System", mapOf("display.value" to
            SettingField.parse("display.value", JSONObject("""{"type":"bool","label":"System value","default":true,"section":"display"}"""), true)),
            sections = listOf(SettingSection("display", "@category"))))
        store.register(scope("probe", "enabled" to """{"type":"bool","label":"Enabled","default":true}"""))
        val labels = Labels({ key -> "System $key" }) { _, _, _, _ -> "Plugin label" }
        val result = SettingsDescriber(store, labels) { id, _ -> id }.describe("probe", Locale.US)!!
        assertEquals(listOf(mapOf("id" to "display", "label" to "System category")), result["sections"])
        assertEquals(listOf("enabled"), (result["fields"] as List<*>).map { (it as Map<*, *>)["key"] })
    }

    @Test fun categoryIconsAreLocalizedAlongsideTheirLabelsWithoutDecoratingPluginFields() {
        val store = SettingsStore(Memory())
        store.register(SettingsScope(SettingsStore.SYSTEM, "System", emptyMap(),
            sections = listOf(SettingSection("display", "@category", "monitor"))))
        store.register(scope("probe", "enabled" to """{"type":"bool","label":"Enabled","default":true}"""))
        val labels = Labels({ key -> "System $key" }) { _, _, _, _ -> "Plugin label" }
        val result = SettingsDescriber(store, labels) { id, _ -> id }.describe("probe", Locale.US)!!
        assertEquals(listOf(mapOf("id" to "display", "label" to "System category", "icon" to "monitor")), result["sections"])
        assertTrue((result["fields"] as List<*>).all { !(it as Map<*, *>).containsKey("icon") })
    }
}
