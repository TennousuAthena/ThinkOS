package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingsWriteGuardTest {
    private class Memory : SettingsPersistence {
        val files = mutableMapOf<String, ByteArray>()
        var writes = 0
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes; writes++ }
        fun bytes() = files.mapValues { it.value.toList() }
    }
    private fun store(memory: Memory) = SettingsStore(memory).also { store ->
        fun field(key: String, text: String) = SettingField.parse(key, JSONObject(text), true)
        store.register(SettingsScope(SettingsStore.SYSTEM, "System", linkedMapOf(
            "cast.miracast" to field("cast.miracast", """{"type":"bool","label":"Miracast","default":false}"""),
            "limits.low" to field("limits.low", """{"type":"number","label":"Low","default":10,"min":0,"max":100}"""),
            "limits.high" to field("limits.high", """{"type":"number","label":"High","default":90,"min":0,"max":100}"""),
        ), listOf("limits.low" to "limits.high")))
    }
    private fun reject(values: Map<String, Any>) {
        if (values["cast.miracast"] == true) throw SettingError("feature_required", message = "native confirmation required")
    }

    @Test fun aRefusedSingleWriteDoesNotPersistOrNotifyAndNativeWriteStillWorks() {
        val memory = Memory(); val settings = store(memory)
        var events = 0
        settings.addListener { _, _ -> events++ }
        assertEquals("feature_required", assertThrows(SettingError::class.java) {
            settings.set("system", "cast.miracast", true, ::reject)
        }.code)
        assertEquals(false, settings.get("system", "cast.miracast"))
        assertEquals(0, memory.writes); assertEquals(0, events)
        // The guard belongs only to the existing public settings caller.
        settings.set("system", "cast.miracast", true)
        assertEquals(true, settings.get("system", "cast.miracast"))
        assertEquals(1, events)
    }

    @Test fun aRefusedCompoundWriteLeavesOtherKeysAndDurableBytesUnchanged() {
        val memory = Memory(); val settings = store(memory)
        settings.set("system", "limits.low", 20)
        val before = memory.bytes(); val writes = memory.writes
        var events = 0; var proposed: Map<String, Any>? = null
        settings.addListener { _, _ -> events++ }
        assertThrows(SettingError::class.java) {
            settings.setMany("system", linkedMapOf("cast.miracast" to true, "limits.low" to 30)) {
                proposed = it.toMap(); reject(it)
            }
        }
        assertEquals(mapOf("cast.miracast" to true, "limits.low" to 30L), proposed)
        assertEquals(20L, settings.get("system", "limits.low"))
        assertEquals(false, settings.get("system", "cast.miracast"))
        assertEquals(before, memory.bytes()); assertEquals(writes, memory.writes); assertEquals(0, events)
    }

    @Test fun schemaAndCompoundConstraintsFinishBeforeTheGuard() {
        val memory = Memory(); val settings = store(memory)
        var calls = 0
        val guard: (Map<String, Any>) -> Unit = { calls++; reject(it) }
        assertEquals("order", assertThrows(SettingError::class.java) {
            settings.setMany("system", mapOf("cast.miracast" to true, "limits.low" to 95), guard)
        }.code)
        assertEquals("max", assertThrows(SettingError::class.java) {
            settings.setMany("system", mapOf("cast.miracast" to true, "limits.high" to 999), guard)
        }.code)
        assertEquals(0, calls); assertEquals(0, memory.writes)
    }

    @Test fun resetOfOneKeyOrTheWholeScopeChecksRevealedServerValuesBeforeAnyWrite() {
        for (key in listOf("cast.miracast", null)) {
            val memory = Memory(); val settings = store(memory)
            settings.applyServer(JSONObject("""{"cast":{"miracast":true}}"""))
            settings.set("system", "cast.miracast", false)
            settings.set("system", "limits.low", 20)
            val before = memory.bytes(); val writes = memory.writes
            var events = 0; var proposed: Map<String, Any>? = null
            settings.addListener { _, _ -> events++ }
            assertThrows(SettingError::class.java) {
                settings.reset("system", key) { proposed = it.toMap(); reject(it) }
            }
            assertEquals(true, requireNotNull(proposed)["cast.miracast"])
            if (key == null) assertEquals(10L, requireNotNull(proposed)["limits.low"])
            assertEquals(false, settings.get("system", "cast.miracast"))
            assertEquals(20L, settings.get("system", "limits.low"))
            assertEquals(before, memory.bytes()); assertEquals(writes, memory.writes); assertEquals(0, events)
            assertEquals(false, store(memory).get("system", "cast.miracast"))
        }
    }

    @Test fun retiredLocalKeysDoNotBlockScopeResetOrBypassTheKnownFeatureGuard() {
        val memory = Memory().also {
            it.files["server.json"] = """{"system":{"cast.miracast":true}}""".toByteArray()
            it.files["local.json"] = """{"system":{"retired.option":"old","cast.miracast":false,"limits.low":20}}""".toByteArray()
        }
        val settings = store(memory)
        val before = memory.bytes()
        val notified = mutableListOf<Set<String>>()
        var proposed: Map<String, Any>? = null
        settings.addListener { _, keys -> notified += keys }
        assertThrows(SettingError::class.java) {
            settings.reset("system") { proposed = it.toMap(); reject(it) }
        }
        assertEquals(mapOf("cast.miracast" to true, "limits.low" to 10L), proposed)
        assertEquals(before, memory.bytes()); assertEquals(0, memory.writes); assertTrue(notified.isEmpty())
        settings.reset("system") { proposed = it.toMap() }
        assertEquals(mapOf("cast.miracast" to true, "limits.low" to 10L), proposed)
        assertEquals(setOf("retired.option", "cast.miracast", "limits.low"), notified.single())
        assertEquals(0, settings.overrides().length())
        assertEquals(true, store(memory).get("system", "cast.miracast"))
        assertEquals(10L, store(memory).get("system", "limits.low"))
    }
}
