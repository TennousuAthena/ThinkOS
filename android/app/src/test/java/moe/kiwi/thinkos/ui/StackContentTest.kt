package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.plugin.PluginWidget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class StackContentTest {
    @Test
    fun missingStringAndObjectReferencesRenderAnEmptySlot() {
        for (item in listOf("weather.now", JSONObject().put("widget", "weather.now"))) {
            val (owner, view, opens) = resolveStackItem("home", item, emptyList())
            assertEquals("home", owner)
            assertEquals(0, view.getJSONArray("children").length())
            assertNull(opens)
        }
    }

    @Test
    fun aLaterCatalogRestoresTheWidgetAndItsSettingsOwner() {
        val item = JSONObject().put("widget", "weather.now")
        val widget = PluginWidget("weather", "now", JSONObject().put("type", "text").put("value", "21°"), "main")
        assertEquals(0, resolveStackItem("home", item, emptyList()).second.getJSONArray("children").length())
        val (owner, view, opens) = resolveStackItem("home", item, listOf(widget))
        assertEquals("weather", owner)
        assertSame(widget.view, view)
        assertEquals("weather.main", opens)
        assertEquals(0, resolveStackItem("home", item, emptyList()).second.getJSONArray("children").length())
    }

    @Test
    fun aContainerGetsTheWidgetsPresentationForItsTemplateAndFallsBackToTheDefault() {
        val glance = JSONObject().put("template", "glance")
        val tile = JSONObject().put("template", "tile")
        val both = PluginWidget("gallery", "preview", glance, "slideshow", mapOf("tile" to tile))
        assertSame(tile, resolveStackItem("page", "gallery.preview", listOf(both), "tile").second)
        assertSame(glance, resolveStackItem("page", "gallery.preview", listOf(both), "glance").second)
        assertSame(glance, resolveStackItem("page", "gallery.preview", listOf(both)).second)
        val single = PluginWidget("clock", "now", glance)
        assertSame(glance, resolveStackItem("page", "clock.now", listOf(single), "tile").second)
        assertEquals("gallery", resolveStackItem("page", "gallery.preview", listOf(both), "tile").first)
    }

    @Test
    fun qualifiedAndAbsentNavigationTargetsArePreserved() {
        val view = JSONObject().put("type", "text").put("value", "Card")
        assertEquals("other.main", resolveStackItem("home", "weather.now", listOf(PluginWidget("weather", "now", view, "other.main"))).third)
        assertNull(resolveStackItem("home", "weather.now", listOf(PluginWidget("weather", "now", view))).third)
    }

    @Test
    fun inlineComponentsKeepTheirDeclarationAndPageOwner() {
        val view = JSONObject().put("type", "text").put("value", "Hello")
        val resolved = resolveStackItem("home", view, emptyList())
        assertEquals("home", resolved.first)
        assertSame(view, resolved.second)
        assertNull(resolved.third)
    }

    @Test
    fun anEmptyStackHasNoComponentsOrNavigationTarget() {
        val (_, view, opens) = resolveStackItem("home", null, emptyList())
        assertEquals(0, view.getJSONArray("children").length())
        assertNull(opens)
    }
}
