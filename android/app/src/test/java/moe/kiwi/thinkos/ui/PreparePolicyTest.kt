package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.plugin.LayoutVocabulary
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginWidget
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PreparePolicyTest {
    private fun page(layout: String, slots: String = "{}") =
        PluginPage("example", "page", "Page", JSONObject("""{"layout":"$layout","slots":$slots}"""))

    @Test fun knownLayoutsCanBePreparedWhenTheirComponentsAreSafe() {
        for (layout in LayoutVocabulary.slots.keys) assertTrue(layout, PreparePolicy.allowed(page(layout), emptyList()))
        assertFalse(PreparePolicy.allowed(page("unknown"), emptyList()))
        assertTrue(PreparePolicy.allowed(page("stage", """{"stage":{"type":"carousel"}}"""), emptyList()))
    }

    @Test fun mediaPagesAndNavigableFormsAreExcluded() {
        for (type in listOf("video", "tv", "nowplaying", "controls", "seekbar")) {
            assertFalse(type, PreparePolicy.allowed(page("stage", """{"stage":{"type":"$type"}}"""), emptyList()))
        }
        assertFalse(PreparePolicy.allowed(page("panel", """{"media":{"type":"nowplaying"}}"""), emptyList()))
        val form = page("form", """{"body":{"type":"form","nav":"categories"}}""")
        assertFalse(PreparePolicy.allowed(form, emptyList()))
        assertFalse(PreparePolicy.allowed(page("focus").copy(pluginId = "player", id = "library"), emptyList()))
        assertFalse(PreparePolicy.allowed(page("focus").copy(pluginId = "cast"), emptyList()))
        assertFalse(PreparePolicy.allowed(page("form").copy(pluginId = "settings"), emptyList()))
        assertFalse(PreparePolicy.allowed(page("focus").copy(roles = listOf("onboarding")), emptyList()))
        assertFalse(PreparePolicy.allowed(page("stage").copy(live = true), emptyList()))
    }

    @Test fun checksWidgetsAndAllAlternatesWithoutFollowingOpenTargets() {
        val widget = PluginWidget("player", "tile", JSONObject("""{"type":"text"}"""), opens = "library")
        val tiles = page("tiles", """{"tiles":[{"widget":"player.tile"}]}""")
        assertTrue(PreparePolicy.allowed(tiles, listOf(widget)))
        val nowPlaying = JSONObject("""{"children":[{"type":"nowplaying"}]}""")
        assertFalse(PreparePolicy.allowed(tiles, listOf(widget.copy(alternates = mapOf("glance" to nowPlaying)))))
        val stacks = page("stacks", """{"right":["player.tile"]}""")
        assertFalse(PreparePolicy.allowed(stacks, listOf(widget.copy(view = nowPlaying))))
        val art = page("focus", """{"hero":{"type":"image","src":"{{media.cover}}"}}""")
        assertFalse(PreparePolicy.allowed(art, emptyList()))
    }

    @Test fun hiddenImageLoadsPermitLocalSourcesAndGalleryBingOnly() {
        val bing = "https://www.bing.com/th?id=OHR.Photo"
        assertTrue(PreparePolicy.imageAllowedWhileWarm(bing, "gallery"))
        assertTrue(PreparePolicy.imageAllowedWhileWarm("asset:photo.jpg", "example"))
        assertFalse(PreparePolicy.imageAllowedWhileWarm(bing, "example"))
        assertFalse(PreparePolicy.imageAllowedWhileWarm("https://elsewhere.example/photo.jpg", "gallery"))
    }
}
