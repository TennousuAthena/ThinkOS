package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.plugin.LayoutVocabulary
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CompiledBindingTest {
    @Test fun onlyOverlappingPathsInvalidateBindings() {
        val template = Binding.compile("{{weather.current.temp | round}}°")
        assertEquals(setOf("weather.current.temp"), template.dependencies)
        assertTrue(template.affected(setOf("weather")))
        assertTrue(template.affected(setOf("weather.current.temp")))
        assertFalse(template.affected(setOf("playback.progress")))
        assertEquals("18°", template.render(JSONObject("""{"weather":{"current":{"temp":17.8}}}"""), "digital"))
    }
    @Test fun diffReportsOnlyChangedLeaves() {
        val a = JSONObject("""{"weather":{"temp":1,"city":"HK"},"queue":[1,2]}""")
        val b = JSONObject("""{"weather":{"temp":2,"city":"HK"},"queue":[1,2]}""")
        assertEquals(setOf("weather.temp"), Binding.changed(a, b))
    }
    @Test fun staticTemplatesDoNotDependOnPluginState() {
        val template = Binding.compile("一切就绪")
        assertTrue(template.dependencies.isEmpty())
        assertFalse(template.affected(setOf("anything")))
        assertEquals("一切就绪", template.render(JSONObject(), "digital"))
    }
    @Test fun slotNamesAndRolesComeFromGeneratedSchema() {
        assertEquals(listOf("over", "hero", "under"), LayoutVocabulary.slots["focus"])
        assertEquals("content", LayoutVocabulary.roles["focus"]?.get("hero"))
        assertEquals(listOf("left", "right"), LayoutVocabulary.slots["stacks"])
    }
}
