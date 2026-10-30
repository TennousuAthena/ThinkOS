package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.fail

class PluginSchemaTest {
    private fun manifest() = JSONObject("""{"id":"example","name":"示例","version":"0.1.0","api":1,"pages":[{"id":"main","title":"示例","view":"views/main.json"}]}""")
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsupported plugin declaration must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun apiFourTilesColorsAndBoundedContent() {
        val tile = JSONObject("""{"template":"tile","faces":[{"label":"Weather","value":"{{temp}}"}]}""")
        val page = JSONObject("""{"layout":"tiles","slots":{"tiles":[{"widget":"clock.tile","size":"wide"}]}}""")
        for (view in listOf(tile, page)) {
            PluginSchema.validateView(view, 4)
            for (api in 1..3) rejected { PluginSchema.validateView(view, api) }
        }
        for (color in listOf("#2D89EF", "{{c}}", "red", "#FFF", "#2D89EF80")) {
            tile.put("color", color)
            page.getJSONObject("slots").getJSONArray("tiles").getJSONObject(0).put("color", color)
            for (view in listOf(tile, page)) {
                if (color in listOf("#2D89EF", "{{c}}")) PluginSchema.validateView(view, 4)
                else rejected { PluginSchema.validateView(view, 4) }
            }
        }
        tile.remove("color")
        for (content in listOf(
            """{"type":"list","items":"{{rows}}","item":{"title":"{{.name}}"}}""",
            """{"type":"image","src":"{{src}}"}""",
            """{"type":"carousel","items":"photos","interval":30,"duration":800}"""
        )) rejected { PluginSchema.validateView(JSONObject(tile.toString()).put("content", JSONObject(content)), 4) }
        page.getJSONObject("slots").getJSONArray("tiles").getJSONObject(0).apply { remove("color"); put("size", "huge") }
        rejected { PluginSchema.validateView(page, 4) }
    }

    @Test fun directoryIndexAndCatalogUseTheSharedContract() {
        val index = JSONObject("""{"id":"probe","version":"1.0.0","digest":"e52ad792c9cfdd084aa054903dd13150c03cd8b751c24acfe4a8e12172633490","files":[{"path":"manifest.json","size":3,"sha256":"ca3d163bab055381827226140568f3bef7eaac187cebd76878e0b63e9e442356"}]}""")
        PluginSchema.validateIndex(index)
        PluginSchema.validateCatalog(JSONObject().put("plugins", JSONArray().put(index)))
        for (path in listOf("../escape.lua", "a//b.lua", "a/./b.lua", "a/", "a\\b.lua")) {
            val invalid = JSONObject(index.toString())
            invalid.getJSONArray("files").getJSONObject(0).put("path", path)
            rejected { PluginSchema.validateIndex(invalid) }
        }
        for (size in listOf(-1, .5, "3", 1048577)) {
            val invalid = JSONObject(index.toString())
            invalid.getJSONArray("files").getJSONObject(0).put("size", size)
            rejected { PluginSchema.validateIndex(invalid) }
        }
        rejected { PluginSchema.validateIndex(JSONObject(index.toString()).put("execute", true)) }
        rejected { PluginSchema.validateCatalog(JSONObject().put("plugins", JSONArray().apply { repeat(33) { put(index) } })) }
    }

    @Test fun validDeclarationsAcceptPublicVocabulary() {
        PluginSchema.validateManifest(manifest())
        PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"clock","style":"{{variant}}","zones":[{"label":"香港","timezone":"Asia/Hong_Kong"}]},"under":{"type":"text","value":"{{date}}","style":"body"}},"variants":[{"id":"digital","title":"数字"}]}"""))
        PluginSchema.validateView(JSONObject("""{"layout":"stacks","slots":{"left":[{"widget":"clock.now"}],"right":[{"widget":"clock.calendar"}]}}"""))
    }

    @Test fun rejectsNewApiAndPrivateFields() {
        rejected { PluginSchema.validateManifest(manifest().put("api", 5)) }
        rejected { PluginSchema.validateManifest(manifest().put("privateBridge", true)) }
        rejected { PluginSchema.validateManifest(manifest().put("main", "../escape.lua")) }
    }

    @Test fun rejectsDuplicateIdsAndInvalidConfiguration() {
        val duplicate = manifest()
        duplicate.getJSONArray("pages").put(duplicate.getJSONArray("pages").getJSONObject(0))
        rejected { PluginSchema.validateManifest(duplicate) }
        rejected { PluginSchema.validateManifest(manifest().put("config", JSONObject("""{"rate":{"type":"number","default":"fast"}}"""))) }
    }

    @Test fun rejectsUnsupportedRenderTrees() {
        rejected { PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"webview","url":"https://example.com"}}}""")) }
        rejected { PluginSchema.validateView(JSONObject("""{"layout":"stacks","slots":{"left":[],"right":[]}}""")) }
        rejected { PluginSchema.validateView(JSONObject("""{"template":"glance","children":[{"type":"text","value":"Hello","execute":"native"}]}""")) }
    }

    @Test fun rejectsInvalidTypesEvenWhenAndroidCouldCoerceThem() {
        rejected { PluginSchema.validateManifest(manifest().put("api", "1")) }
        rejected { PluginSchema.validateManifest(manifest().put("permissions", JSONArray().put(1))) }
        rejected { PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"text","value":null}}}""")) }
    }

    @Test fun apiTwoAcceptsFixedContentComponentsGroupsAndLayouts() {
        PluginSchema.validateManifest(manifest().put("api", 2).put("permissions", JSONArray().put("media").put("library")))
        PluginSchema.validateView(JSONObject("""{"layout":"split","slots":{
          "lead":{"type":"moon","path":"moon","hemisphere":"{{hemisphere}}","weight":1,"tap":"moon.details"},
          "main":{"children":[
            {"type":"progress","style":"ring","value":"{{played}}","max":"{{duration}}","weight":1},
            {"type":"chart","style":"area","values":"{{temperatures}}","highlight":"{{hour}}","min":0,"max":40,"weight":2},
            {"type":"list","items":"{{days}}","item":{"icon":"{{.icon}}","title":"{{.title}}","subtitle":"{{.summary}}","trailing":"{{.high}}°","progress":"{{.rain}}","max":100},"tap":"forecast.day","weight":3}
          ]}}}"""), 2)
        PluginSchema.validateView(JSONObject("""{"layout":"stage","slots":{"stage":{"type":"daylight","path":"sun"},"over":{"type":"text","value":"{{date}}"},"under":{"type":"progress","style":"bar","value":0.5}}}"""), 2)
        PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":{"children":[{"type":"chart","style":"bar","values":[1,null,-2,4]},{"type":"progress","value":0.2}]}}}"""), 2)
    }

    @Test fun capabilitiesWithoutRendererIntegrationAreRejectedBeforeTheyCanCrashTheUi() {
        for (component in listOf(
            """{"type":"video","fit":"cover"}""", """{"type":"controls"}""",
            """{"type":"carousel","items":"photos","interval":10,"duration":800}""",
            """{"type":"image","src":"{{art}}","fit":"contain"}"""
        )) rejected { PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":$component}}"""), 2) }
    }

    @Test fun apiThreeImageContractKeepsAllFramingAndMediaRemainsDeferred() {
        PluginSchema.validateManifest(manifest().put("api", 3))
        val view = JSONObject("""{"layout":"stage","slots":{"stage":{"type":"carousel","items":"photos","interval":"{{config.interval}}","duration":800,"fit":"pan","pan_speed":45,"caption":"auto","caption_hold":6}}}""")
        PluginSchema.validateView(view, 3)
        rejected { PluginSchema.validateView(view, 2) }
        for (component in listOf(
            """{"type":"image","src":"asset:assets/photo.png","fit":"cover"}""",
            """{"type":"carousel","items":"photos","interval":30,"duration":800,"caption":"always"}""",
            """{"type":"image","src":"{{cover}}","pan_speed":0}""",
            """{"type":"carousel","items":"photos","interval":30,"duration":800,"tap":"private"}""",
            """{"type":"video"}""", """{"type":"controls"}"""
        )) rejected { PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":$component}}"""), 3) }
    }

    @Test fun apiOneCannotSilentlyUseApiTwoVocabulary() {
        val declarations = listOf(
            """{"layout":"focus","slots":{"hero":{"type":"text","value":"Tap","tap":"open"}}}""",
            """{"layout":"focus","slots":{"hero":{"type":"clock","weight":1}}}""",
            """{"layout":"focus","slots":{"hero":{"children":[{"type":"text","value":"Group"}]}}}""",
            """{"layout":"focus","slots":{"hero":{"type":"progress","value":0.5}}}""",
            """{"layout":"split","slots":{"lead":{"type":"text","value":"A"},"main":{"type":"text","value":"B"}}}""",
        )
        declarations.forEach { declaration ->
            PluginSchema.validateView(JSONObject(declaration), 2)
            rejected { PluginSchema.validateView(JSONObject(declaration), 1) }
        }
    }

    @Test fun apiTwoStillRejectsAppearanceLanguagesAndUnboundedValues() {
        for (component in listOf(
            """{"type":"progress","value":0.5,"color":"red"}""",
            """{"type":"progress","value":0.5,"weight":-1}""",
            """{"type":"chart","values":[1,2],"style":"gradient"}""",
            """{"type":"list","items":"{{rows}}","item":{"title":"{{.title}}","lua":"execute()"}}""",
            """{"type":"daylight","path":"sun","latitude":10}""",
            """{"type":"moon","path":"moon","hemisphere":"east"}""",
            """{"type":"video","url":"https://example.com/private"}""",
            """{"type":"controls","buttons":["native-code"]}""",
            """{"type":"carousel","items":"photos","interval":-1,"duration":800}""",
        )) rejected { PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":$component}}"""), 2) }
    }
}
