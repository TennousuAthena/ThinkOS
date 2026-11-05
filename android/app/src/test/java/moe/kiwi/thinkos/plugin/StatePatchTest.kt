package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class StatePatchTest {
    @Test fun chineseEncodedPatchUsesBytesRatherThanCharacterCountAtTheLimit() {
        val prefix = "{\"text\":\""
        val suffix = "\"}"
        val room = 256 * 1024 - prefix.length - suffix.length
        val exact = prefix + "汉".repeat(room / 3) + "x".repeat(room % 3) + suffix
        assertEquals(256 * 1024, exact.toByteArray(Charsets.UTF_8).size)
        assertTrue(PluginRuntime.statePatch(exact).has("text"))
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch(exact.dropLast(2) + "x" + suffix) }
    }
    @Test fun typedAndEncodedPatchesHaveTheSameDataAndMergeSemantics() {
        val typed = PluginRuntime.statePatch(mapOf("photos" to listOf(mapOf("src" to "file:///a.jpg", "title" to "照片"))))
        val encoded = PluginRuntime.statePatch("""{"photos":[{"src":"file:///a.jpg","title":"照片"}]}""")
        assertEquals(typed.toString(), encoded.toString())
        val document = JsonDocument()
        document.merge(encoded)
        document.merge(PluginRuntime.statePatch("""{"photos":[],"notice":"empty"}"""))
        assertTrue(document.value.get("photos") is JSONArray)
        assertEquals(0, document.value.getJSONArray("photos").length())
    }
    @Test fun encodedPatchesCannotReplaceReservedSettingsOrBypassByteAndDepthLimits() {
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch("""{"config":{"pan":false}}""") }
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch(mapOf("config" to emptyMap<String,Any>())) }
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch("""{"uses":{"daily":{}}}""") }
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch(mapOf("uses" to emptyMap<String,Any>())) }
        // API 1-5 plugins predate the reservation and may hold their own state under this name.
        assertTrue(PluginRuntime.statePatch(mapOf("uses" to 3), reserveUses = false).has("uses"))
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch("{\"text\":\"" + "汉".repeat(90_000) + "\"}") }
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch("{\"a\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}") }
        assertThrows(RuntimeException::class.java) { PluginRuntime.statePatch("[]") }
    }
}
