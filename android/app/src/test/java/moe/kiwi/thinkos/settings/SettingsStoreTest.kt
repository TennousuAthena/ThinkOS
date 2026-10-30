package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsStoreTest {
    private class Memory : SettingsPersistence {
        val files = mutableMapOf<String, ByteArray>()
        var writes = 0
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes; writes++ }
    }

    private fun field(key: String, json: String, system: Boolean = false) = SettingField.parse(key, JSONObject(json), system)
    private val weather = SettingsScope("weather", "Weather", linkedMapOf(
        "city" to field("city", """{"type":"text","label":"City","default":"Hong Kong"}"""),
        "unit" to field("unit", """{"type":"choice","label":"Unit","default":"c","choices":[{"value":"c","label":"C"},{"value":"f","label":"F"}]}"""),
        "interval" to field("interval", """{"type":"number","label":"Every","default":10,"min":1,"max":60}"""),
    ))
    private val system = SettingsScope("system", "System", linkedMapOf(
        "theme.dark_below_lux" to field("theme.dark_below_lux", """{"type":"number","label":"a","default":50,"min":10,"max":200}""", true),
        "theme.light_above_lux" to field("theme.light_above_lux", """{"type":"number","label":"b","default":120,"min":50,"max":1000}""", true),
        "theme.mode" to field("theme.mode", """{"type":"choice","label":"m","default":"auto","choices":[{"value":"auto","label":"A"},{"value":"dark","label":"D"}]}""", true),
    ), listOf("theme.dark_below_lux" to "theme.light_above_lux"))

    private fun store(memory: Memory = Memory()) = SettingsStore(memory).also { it.register(weather); it.register(system) }

    @Test
    fun defaultsApplyUntilSomethingOverridesThem() {
        val settings = store()
        assertEquals("Hong Kong", settings.get("weather", "city"))
        assertEquals(10L, settings.get("weather", "interval"))
        assertEquals(mapOf("city" to "Hong Kong", "unit" to "c", "interval" to 10L), settings.snapshot("weather"))
    }

    @Test
    fun deviceEditsWinAndPersistAcrossRestarts() {
        val memory = Memory()
        val first = store(memory)
        assertEquals("Tokyo", first.set("weather", "city", "Tokyo"))
        assertTrue(first.resolved("weather", "city").overridden)
        val restarted = store(memory)
        assertEquals("Tokyo", restarted.get("weather", "city"))
        assertEquals("c", restarted.get("weather", "unit"))
    }

    @Test
    fun editingBackToTheLowerLayerLeavesNoOverride() {
        val settings = store()
        settings.set("weather", "city", "Tokyo")
        settings.set("weather", "city", "Hong Kong")
        assertEquals(0, settings.overrides().length())
    }

    @Test
    fun invalidEditsAreRefusedAndNothingChanges() {
        val settings = store()
        assertEquals("max", assertThrows(SettingError::class.java) { settings.set("weather", "interval", 99) }.code)
        assertEquals("option", assertThrows(SettingError::class.java) { settings.set("weather", "unit", "k") }.code)
        assertEquals("unknown", assertThrows(SettingError::class.java) { settings.set("weather", "nope", 1) }.code)
        assertEquals(10L, settings.get("weather", "interval"))
        assertEquals(0, settings.overrides().length())
    }

    @Test
    fun crossFieldConstraintsApplyToTheProposedValue() {
        val settings = store()
        val error = assertThrows(SettingError::class.java) { settings.set("system", "theme.dark_below_lux", 150) }
        assertEquals("order", error.code)
        assertEquals("theme.light_above_lux", error.args["key"])
        settings.set("system", "theme.light_above_lux", 400)
        settings.set("system", "theme.dark_below_lux", 150)
        assertEquals(150L, settings.get("system", "theme.dark_below_lux"))
        assertEquals("order", assertThrows(SettingError::class.java) { settings.set("system", "theme.light_above_lux", 100) }.code)
    }

    @Test
    fun serverValuesLayerBelowDeviceEdits() {
        val settings = store()
        settings.applyServer(JSONObject("""{"theme":{"mode":"dark"},"plugins":{"weather":{"city":"London","interval":30}}}"""))
        assertEquals("dark", settings.get("system", "theme.mode"))
        assertEquals("London", settings.get("weather", "city"))
        settings.set("weather", "city", "Paris")
        assertEquals("Paris", settings.get("weather", "city"))
        assertEquals(30L, settings.get("weather", "interval"))
    }

    @Test
    fun unrelatedServerUpdatesDoNotWipeDeviceEdits() {
        val settings = store()
        settings.applyServer(JSONObject("""{"plugins":{"weather":{"city":"London"}}}"""))
        settings.set("weather", "city", "Paris")
        settings.applyServer(JSONObject("""{"plugins":{"weather":{"city":"London","interval":20}}}"""))
        assertEquals("Paris", settings.get("weather", "city"))
        assertEquals(20L, settings.get("weather", "interval"))
    }

    @Test
    fun anExplicitServerChangeOverridesAnOldDeviceEdit() {
        val settings = store()
        settings.applyServer(JSONObject("""{"plugins":{"weather":{"city":"London"}}}"""))
        settings.set("weather", "city", "Paris")
        settings.applyServer(JSONObject("""{"plugins":{"weather":{"city":"Berlin"}}}"""))
        assertEquals("Berlin", settings.get("weather", "city"))
        assertFalse(settings.resolved("weather", "city").overridden)
    }

    @Test
    fun serverNumbersSurviveTheRestartRoundTrip() {
        val memory = Memory()
        val first = store(memory)
        first.applyServer(JSONObject("""{"plugins":{"weather":{"interval":30}}}"""))
        first.set("weather", "interval", 45)
        val restarted = store(memory)
        // Integer vs Long after parsing must not look like a "different Server value".
        restarted.applyServer(JSONObject("""{"plugins":{"weather":{"interval":30}}}"""))
        assertEquals(45L, restarted.get("weather", "interval"))
    }

    @Test
    fun invalidServerValuesAreIgnoredAndReported() {
        val settings = store()
        settings.applyServer(JSONObject("""{"plugins":{"weather":{"interval":999,"unit":"f"}}}"""))
        assertEquals(10L, settings.get("weather", "interval"))
        assertEquals("f", settings.get("weather", "unit"))
        val rejected = settings.rejectedByServer().single()
        assertEquals("weather", rejected.scope)
        assertEquals("interval", rejected.key)
    }

    @Test
    fun resettingRestoresTheLowerLayerAndNotifies() {
        val settings = store()
        val seen = mutableListOf<Pair<String, Set<String>>>()
        settings.addListener { scope, keys -> seen += scope to keys }
        settings.applyServer(JSONObject("""{"plugins":{"weather":{"city":"London"}}}"""))
        settings.set("weather", "city", "Paris")
        settings.reset("weather", "city")
        assertEquals("London", settings.get("weather", "city"))
        assertEquals(listOf("weather" to setOf("city")), seen.distinct())
        settings.set("weather", "unit", "f")
        settings.resetAll()
        assertEquals("c", settings.get("weather", "unit"))
        assertEquals(0, settings.overrides().length())
    }

    @Test
    fun valuesThatNoLongerPassTheDeclarationFallBack() {
        val memory = Memory()
        store(memory).set("weather", "interval", 55)
        val tighter = SettingsScope("weather", "Weather", linkedMapOf(
            "interval" to field("interval", """{"type":"number","label":"Every","default":10,"min":1,"max":30}"""),
        ))
        val upgraded = SettingsStore(memory).also { it.register(tighter) }
        assertEquals(10L, upgraded.get("weather", "interval"))
    }

    @Test
    fun secretsNeverAppearInTheReport() {
        val secretive = SettingsScope("system", "System", linkedMapOf(
            "server.token" to field("server.token", """{"type":"text","label":"t","default":"","secret":true}""", true),
            "server.url" to field("server.url", """{"type":"text","label":"u","default":""}""", true),
        ))
        val settings = SettingsStore(Memory()).also { it.register(secretive) }
        settings.set("system", "server.token", "hunter2")
        settings.set("system", "server.url", "http://192.168.1.2:8765")
        val report = settings.overrides().getJSONObject("system")
        assertFalse(report.has("server.token"))
        assertEquals("http://192.168.1.2:8765", report.getString("server.url"))
        assertEquals("hunter2", settings.get("system", "server.token"))
    }

    @Test
    fun aDamagedFileDoesNotStopTheStore() {
        val memory = Memory().also { it.files["local.json"] = "{not json".toByteArray() }
        val settings = store(memory)
        assertEquals("Hong Kong", settings.get("weather", "city"))
        settings.set("weather", "city", "Osaka")
        assertEquals("Osaka", store(memory).get("weather", "city"))
    }

    @Test
    fun writesAreCoalesced() {
        val memory = Memory()
        val pending = mutableListOf<() -> Unit>()
        val settings = SettingsStore(memory, schedule = { _, task -> pending += task }).also { it.register(weather) }
        settings.set("weather", "city", "A"); settings.set("weather", "city", "B"); settings.set("weather", "unit", "f")
        assertEquals(1, pending.size)
        assertEquals(0, memory.writes)
        pending.single()()
        assertEquals(2, memory.writes)               // server.json + local.json, once
        assertNull(memory.files["x"])
        assertEquals("B", JSONObject(String(memory.files.getValue("local.json"))).getJSONObject("weather").getString("city"))
    }

    @Test
    fun compoundEditsValidateTogetherAndPublishOneCoherentSnapshot() {
        val settings = store()
        val seen = mutableListOf<Map<String, Any>>()
        val keysSeen = mutableListOf<Set<String>>()
        settings.addListener { scope, keys -> seen += settings.snapshot(scope); keysSeen += keys }
        val patch = linkedMapOf("theme.dark_below_lux" to 150, "theme.light_above_lux" to 400)
        settings.setMany("system", patch)
        assertEquals(1, seen.size)
        assertEquals(150L, seen.single()["theme.dark_below_lux"])
        assertEquals(400L, seen.single()["theme.light_above_lux"])
        assertEquals(patch.keys, keysSeen.single())
    }

    @Test
    fun invalidCompoundEditWritesNothingAndEmitsNothing() {
        val settings = store()
        val seen = mutableListOf<String>()
        settings.addListener { scope, _ -> seen += scope }
        assertThrows(SettingError::class.java) {
            settings.setMany("weather", linkedMapOf("city" to "Tokyo", "interval" to 999))
        }
        assertEquals("Hong Kong", settings.get("weather", "city"))
        assertEquals(10L, settings.get("weather", "interval"))
        assertEquals(0, settings.overrides().length())
        assertTrue(seen.isEmpty())
    }
}
