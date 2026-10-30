package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class StatePatchTest {
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
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch("{\"text\":\"" + "汉".repeat(90_000) + "\"}") }
        assertThrows(IllegalArgumentException::class.java) { PluginRuntime.statePatch("{\"a\":" + "[".repeat(33) + "0" + "]".repeat(33) + "}") }
        assertThrows(RuntimeException::class.java) { PluginRuntime.statePatch("[]") }
    }
}
