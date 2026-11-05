package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModeSettingsMigrationTest {
    private val scope = SystemSettings.parse(File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata").parentFile.resolve("schema/settings/system.json").readText())
    private class Memory(local: String? = null, server: String? = null) : SettingsPersistence {
        val files = mutableMapOf<String, ByteArray>().apply {
            local?.let { put("local.json", it.toByteArray()) }
            server?.let { put("server.json", it.toByteArray()) }
        }
        var writes = 0
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes; writes++ }
        fun json(name: String) = JSONObject(String(requireNotNull(files[name])))
    }
    private fun store(memory: Memory) = SettingsStore(memory).also { it.register(scope) }
    private fun value(store: SettingsStore, key: String = "redshift.trigger") = store.get("system", key)

    @Test fun freshInstallDoesNotWriteAndUsesSafeDefaults() {
        val memory = Memory(); val current = store(memory)
        assertEquals("sun", value(current))
        assertEquals(true, value(current, "theme.night"))
        assertEquals(false, value(current, "sleep.enabled"))
        assertEquals("23:00", value(current, "sleep.start"))
        assertEquals("07:00", value(current, "sleep.end"))
        assertEquals("double", value(current, "sleep.wake"))
        assertEquals("next", value(current, "mode.manual_hold"))
        assertEquals(0, memory.writes)
    }

    @Test fun oldFalseIsConsumedAndDoesNotReturnAfterResetAndRestart() {
        val memory = Memory("""{"system":{"theme.night":false,"display.brightness_max":70},"weather":{"keep":"original"}}""")
        val current = store(memory)
        assertEquals("off", value(current))
        assertEquals(false, value(current, "theme.night"))
        assertFalse(memory.json("local.json").getJSONObject("system").has("theme.night"))
        assertEquals(70, memory.json("local.json").getJSONObject("system").getInt("display.brightness_max"))
        assertEquals("original", memory.json("local.json").getJSONObject("weather").getString("keep"))
        current.reset("system", "redshift.trigger")
        assertEquals("sun", value(store(memory)))
        val writes = memory.writes
        store(memory)
        assertEquals(writes, memory.writes)
    }

    @Test fun eachLayerKeepsItsPriorityAndNewKeysWinWithinThatLayer() {
        val cases = listOf(
            Triple("""{"theme.night":false}""", """{"redshift.trigger":"sun"}""", "off"),
            Triple("""{"theme.night":true}""", """{"redshift.trigger":"off"}""", "sun"),
            Triple("""{"theme.night":false,"redshift.trigger":"fixed"}""", """{"redshift.trigger":"off"}""", "fixed"),
            Triple("{}", """{"theme.night":false,"redshift.trigger":"lux"}""", "lux"),
            Triple("{}", """{"theme.night":false}""", "off"),
            Triple("""{"theme.night":true}""", "{}", "sun")
        )
        for ((local, server, expected) in cases) {
            val memory = Memory("""{"system":$local}""", """{"system":$server}""")
            assertEquals(expected, value(store(memory)))
            assertEquals(expected, value(store(memory)))
            for (name in listOf("local.json", "server.json")) assertFalse(memory.json(name).optJSONObject("system")?.has("theme.night") == true)
        }
    }

    @Test fun deprecatedWritesAndServerDocumentsUseTheSameSingleSetting() {
        val memory = Memory(); val current = store(memory)
        assertEquals(false, current.set("system", "theme.night", false))
        assertEquals("off", value(current))
        current.set("system", "redshift.trigger", "fixed")
        assertEquals(true, current.snapshot("system")["theme.night"])
        current.applyServer(JSONObject("""{"theme":{"night":false},"redshift":{"trigger":"lux"}}"""))
        assertEquals("lux", value(current))
        current.applyServer(JSONObject("""{"theme":{"night":false}}"""))
        assertEquals("off", value(current))
        for (name in listOf("local.json", "server.json")) assertFalse(memory.json(name).optJSONObject("system")?.has("theme.night") == true)
    }

    @Test fun timeWindowsAcceptEitherOrderButRejectEqualityAtomically() {
        val current = store(Memory())
        current.setMany("system", mapOf("sleep.start" to "08:00", "sleep.end" to "17:00"))
        assertEquals("08:00", value(current, "sleep.start"))
        current.setMany("system", mapOf("sleep.start" to "23:00", "sleep.end" to "07:00"))
        assertThrows(SettingError::class.java) { current.setMany("system", mapOf("sleep.start" to "07:00", "sleep.enabled" to true)) }
        assertEquals("23:00", value(current, "sleep.start"))
        assertEquals(false, value(current, "sleep.enabled"))
        current.applyServer(JSONObject("""{"sleep":{"start":"07:00","end":"07:00"}}"""))
        assertEquals("23:00", value(current, "sleep.start"))
        assertEquals("07:00", value(current, "sleep.end"))
        assertTrue(current.rejectedByServer().any { it.key == "sleep.start" })
    }

    @Test fun resetAndServerOverlayCannotCollapseAnActiveWindow() {
        val current = store(Memory())
        current.setMany("system", mapOf("sleep.start" to "08:00", "sleep.end" to "23:00"))
        assertThrows(SettingError::class.java) { current.reset("system", "sleep.start") }
        assertEquals("08:00", value(current, "sleep.start"))
        assertEquals("23:00", value(current, "sleep.end"))
        current.reset("system")
        current.set("system", "sleep.start", "08:00")
        current.applyServer(JSONObject("""{"sleep":{"end":"08:00"}}"""))
        assertEquals("08:00", value(current, "sleep.start"))
        assertEquals("07:00", value(current, "sleep.end"))
        assertTrue(current.rejectedByServer().any { it.key == "sleep.end" })
    }
}
