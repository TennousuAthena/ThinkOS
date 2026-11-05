package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.Assert.fail

class PluginSchemaTest {
    @Test fun timeSettingsRequireApiTenAndRejectAppearanceOrCustomSteps() {
        val value = manifest().put("api", 10).put("settings", JSONObject("""{"at":{"type":"time","label":"时刻","default":"23:55"}}"""))
        PluginSchema.validateManifest(value)
        for (api in 1..9) rejected { PluginSchema.validateManifest(JSONObject(value.toString()).put("api", api)) }
        for (bad in listOf("23:59", "24:00", "7:00", "12:00:00")) {
            val copy = JSONObject(value.toString())
            copy.getJSONObject("settings").getJSONObject("at").put("default", bad)
            rejected { PluginSchema.validateManifest(copy) }
        }
        for ((name, extra) in listOf("step" to 1, "style" to "clock", "maxLength" to 5)) {
            val copy = JSONObject(value.toString())
            copy.getJSONObject("settings").getJSONObject("at").put(name, extra)
            rejected { PluginSchema.validateManifest(copy) }
        }
    }
    @Test fun formDescriptorIconsRequireApiTenAndRegisteredVectorsWithoutStyleParameters() {
        val link = JSONObject("""{"key":"section:display","type":"link","label":"显示"}""")
        PluginSchema.validateFormLink(link, 1)
        link.put("icon", "monitor")
        PluginSchema.validateFormLink(link, 10)
        rejected { PluginSchema.validateFormLink(link, 9) }
        for (icon in listOf<Any>("../monitor", "MONITOR", "not-registered", "", 7, JSONObject.NULL)) {
            rejected { PluginSchema.validateFormLink(JSONObject(link.toString()).put("icon", icon), 10) }
        }
        rejected { PluginSchema.validateFormLink(JSONObject(link.toString()).put("color", "#ffffff"), 10) }
        val nav = JSONObject("""{"items":[{"id":"display","label":"显示","icon":"monitor"}],"current":"display"}""")
        PluginSchema.validateFormNavigation(nav, 10)
        rejected { PluginSchema.validateFormNavigation(nav, 9) }
        nav.getJSONArray("items").getJSONObject(0).remove("icon")
        PluginSchema.validateFormNavigation(nav, 9)
        rejected { PluginSchema.validateFormNavigation(nav, 8) }
        nav.getJSONArray("items").getJSONObject(0).put("icon", "not-registered")
        rejected { PluginSchema.validateFormNavigation(nav, 10) }
    }

    @Test fun panelUpdatePreviewRequiresApiTenAndUsesOnlyTheFixedTextComponent() {
        val view = JSONObject("""{"layout":"panel","slots":{"update":{"type":"text","value":"已更新到 0.1.5","tap":"updates"}}}""")
        PluginSchema.validateView(view, 10)
        rejected { PluginSchema.validateView(view, 9) }
        rejected { PluginSchema.validateView(JSONObject("""{"layout":"panel","slots":{"update":{"widget":"clock.now"}}}"""), 10) }
    }
    @Test fun systemUpdateDeclarationsNeedApiTenEvenBeforeRuntimeAuthorityChecks() {
        val value = JSONObject("""{"id":"update-probe","name":"Updates","version":"0.1.0","api":10,"permissions":["system.update"]}""")
        PluginSchema.validateManifest(value)
        for (api in 1..9) rejected { PluginSchema.validateManifest(JSONObject(value.toString()).put("api", api)) }
    }
    @Test fun launcherRequiresApiNineAndTheDeclaredSystemCapability() {
        val value = JSONObject("""{"id":"launcher-test","name":"Launcher","version":"1.0.0","api":9,"permissions":["system.apps"],"pages":[{"id":"main","title":"Apps","view":"views/main.json","roles":["launcher"]}]}""")
        PluginSchema.validateManifest(value)
        rejected { PluginSchema.validateManifest(JSONObject(value.toString()).put("api", 8)) }
        rejected { PluginSchema.validateManifest(JSONObject(value.toString()).put("permissions", JSONArray())) }
    }
    @Test fun aeroRequiresApiFiveAndKeepsOsOwnedAppearance() {
        val page = JSONObject("""{"layout":"aero","slots":{"windows":[{"widget":"clock.desktop"}]}}""")
        PluginSchema.validateView(page,5)
        for(api in 1..4) rejected { PluginSchema.validateView(page,api) }
        page.getJSONObject("slots").getJSONArray("windows").getJSONObject(0).put("color","#FFFFFF")
        rejected { PluginSchema.validateView(page,5) }
    }
    private fun manifest() = JSONObject("""{"id":"example","name":"示例","version":"0.1.0","api":1,"pages":[{"id":"main","title":"示例","view":"views/main.json"}]}""")
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Unsupported plugin declaration must be rejected") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun apiSevenOpensTheMediaComponentsAndNothingEarlierDoes() {
        PluginSchema.validateManifest(manifest().put("api", 7).put("permissions", JSONArray().put("media")))
        for (component in listOf("""{"type":"video","fit":"cover"}""", """{"type":"video"}""", """{"type":"controls"}""",
            """{"type":"controls","mode":"seek"}""", """{"type":"controls","mode":"play","weight":0}""", """{"type":"seekbar","weight":0}""")) {
            val view = JSONObject("""{"layout":"focus","slots":{"hero":$component}}""")
            PluginSchema.validateView(view, 7)
            for (api in 1..6) rejected { PluginSchema.validateView(view, api) }
        }
        PluginSchema.validateView(JSONObject("""{"layout":"stage","slots":{"stage":{"type":"video"}}}"""), 7)
        for (component in listOf("""{"type":"controls","mode":"queue2"}""", """{"type":"seekbar","color":"red"}""",
            """{"type":"video","url":"https://x.test/a.mp4"}""", """{"type":"controls","buttons":["x"]}""", """{"type":"video","fit":"fill"}""")) {
            rejected { PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":$component}}"""), 7) }
        }
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

    @Test fun schemaBranchPrefilterRetainsStrictValidation() {
        for (declaration in listOf(
            """{"template":"tile","layout":"tiles","faces":[{"label":"A"}],"slots":{"tiles":[{"widget":"clock.tile","size":"wide"}]}}""",
            """{"template":4,"faces":[{"label":"A"}]}""",
            """{"template":"tile","faces":[{}]}""",
            """{"template":"glance","faces":[{"label":"A"}],"children":[]}"""
        )) rejected { PluginSchema.validateView(JSONObject(declaration),4) }
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
        rejected { PluginSchema.validateManifest(manifest().put("api", PluginSchema.API + 1)) }
        rejected { PluginSchema.validateManifest(manifest().put("privateBridge", true)) }
        rejected { PluginSchema.validateManifest(manifest().put("main", "../escape.lua")) }
    }

    @Test fun settingsWarningsAndPlaceholdersRequireApi9WhileLegacyHelpStillWorks() {
        val fields = JSONObject("""{"cast":{"type":"bool","label":"Cast","default":false,"warn":"Anyone nearby can cast"},"url":{"type":"text","label":"Address","default":"","placeholder":"https://example.test"}}""")
        PluginSchema.validateManifest(manifest().put("api", 9).put("settings", fields))
        rejected { PluginSchema.validateManifest(manifest().put("api", 8).put("settings", fields)) }
        PluginSchema.validateManifest(manifest().put("api", 1).put("settings", JSONObject("""{"old":{"type":"bool","label":"Old","default":false,"help":"Old third-party help"}}""")))
    }

    @Test fun theMusicPlayerVocabularyBelongsToApi8() {
        val player = JSONObject("""{"layout":"focus","variants":[{"id":"cover","title":"Cover"},{"id":"queue","title":"Queue"}],"slots":{"hero":{"type":"nowplaying","style":"full","idle":"{{nowTitle}}","notice":"{{playNotice}}"}}}""")
        PluginSchema.validateManifest(manifest().put("api", 8))
        PluginSchema.validateView(player, 8)
        rejected { PluginSchema.validateView(player, 7) }
        val library = JSONObject("""{"layout":"focus","slots":{"over":{"type":"tabs","items":"{{tabs}}","current":"{{tab}}","tap":"tab"},"hero":{"type":"list","items":"{{items}}","current":"{{current}}","item":{"title":"{{.title}}"},"tap":"play"},"under":{"type":"nowplaying","style":"bar","idle":"{{footer}}"}}}""")
        PluginSchema.validateView(library, 8)
        rejected { PluginSchema.validateView(library, 7) }
        // `current` on a list is API 8 on its own, and the components accept nothing the OS does not draw.
        rejected { PluginSchema.validateView(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"list","items":"{{items}}","current":2,"item":{"title":"{{.title}}"}}}}"""), 7) }
        PluginSchema.validateView(JSONObject("""{"template":"glance","children":[{"type":"nowplaying","style":"card","idle":"Nothing playing"}]}"""), 8)
        for (bad in listOf(
            """{"type":"nowplaying","style":"huge"}""", """{"type":"nowplaying","style":"full","color":"#ff0000"}""",
            """{"type":"nowplaying","tap":"x"}""", """{"type":"tabs"}""", """{"type":"tabs","items":"{{tabs}}","style":"pill"}""",
        )) rejected { PluginSchema.validateView(JSONObject("""{"template":"glance","children":[$bad]}"""), 8) }
    }

    @Test fun formNavigationIsOptionalAndRequiresApiNine() {
        val legacy = JSONObject("""{"layout":"form","slots":{"body":{"type":"form","path":"fields"}}}""")
        PluginSchema.validateView(legacy, 1)
        legacy.getJSONObject("slots").getJSONObject("body").put("nav", "settingsNav")
        PluginSchema.validateView(legacy, 9)
        for (api in 1..8) rejected { PluginSchema.validateView(legacy, api) }
        legacy.getJSONObject("slots").getJSONObject("body").put("nav", JSONObject())
        rejected { PluginSchema.validateView(legacy, 9) }
    }

    @Test fun withdrawnListFieldsAreRejectedAndGridKeepsItsExistingApi() {
        for (property in listOf("image", "group")) {
            val view = JSONObject("""{"layout":"focus","slots":{"hero":{"type":"list","items":"items","item":{"title":"{{.title}}"}}}}""")
            view.getJSONObject("slots").getJSONObject("hero").getJSONObject("item").put(property, "{{.$property}}")
            for (api in 2..9) rejected { PluginSchema.validateView(view, api) }
        }
        val grid = JSONObject("""{"layout":"focus","slots":{"hero":{"type":"grid","items":"items","item":{"title":"{{.title}}","image":"{{.image}}","icon":"apps"}}}}""")
        PluginSchema.validateView(grid, 8)
        PluginSchema.validateView(grid, 9)
        rejected { PluginSchema.validateView(grid, 7) }
    }

    @Test fun theTelevisionViewBelongsToApi8AndTakesOnlyWhatTheOsDraws() {
        val live = JSONObject("""{"layout":"stage","slots":{"stage":{"type":"tv","fit":"contain","channel":"{{tv}}","tap":"tv"}}}""")
        PluginSchema.validateView(live, 8)
        rejected { PluginSchema.validateView(live, 7) }
        PluginSchema.validateView(JSONObject("""{"layout":"stage","slots":{"stage":{"type":"tv"}}}"""), 8)
        for (bad in listOf(
            """{"type":"tv","fit":"stretch"}""", """{"type":"tv","color":"#ff0000"}""", """{"type":"tv","channel":5}""", """{"type":"tv","tap":"not valid"}""",
        )) rejected { PluginSchema.validateView(JSONObject("""{"layout":"stage","slots":{"stage":$bad}}"""), 8) }
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
