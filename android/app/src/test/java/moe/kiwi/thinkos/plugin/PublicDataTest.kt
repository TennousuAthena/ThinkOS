package moe.kiwi.thinkos.plugin

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PublicDataTest {
    private val manifest = JSONObject("""{
        "exports":{"daily":{"path":"public.daily","shape":"photos/1"},"place":{"path":"public.place","shape":"place/1","requires":["location"]}},
        "uses":{"daily":{"export":"gallery.daily","shape":"photos/1"}},
        "calls":["gallery.refresh"],
        "commands":[{"id":"refresh","public":true,"cooldown":300},{"id":"next","public":true},{"id":"secret"}]}""")

    @Test fun declarationsParseAndCommandsStayPrivateUnlessMarked() {
        assertEquals(setOf("location"), PublicData.exports(manifest).getValue("place").requires)
        assertEquals(PublicUse("gallery", "daily", "photos/1"), PublicData.uses(manifest).getValue("daily"))
        assertEquals(setOf("gallery.refresh"), PublicData.calls(manifest))
        assertEquals(mapOf("refresh" to 300_000L, "next" to 1_000L), PublicData.publicCommands(manifest))
        val empty = JSONObject()
        assertTrue(PublicData.exports(empty).isEmpty() && PublicData.uses(empty).isEmpty() && PublicData.calls(empty).isEmpty() && PublicData.publicCommands(empty).isEmpty())
    }
    @Test fun statusRequiresTheExportTheShapeAndTheProvidersPermissions() {
        val use = PublicUse("gallery", "daily", "photos/1")
        assertEquals(UseStatus.MISSING, PublicData.status(use, null, emptySet()))
        assertEquals(UseStatus.SHAPE, PublicData.status(use, PublicExport("p", "photos/2", emptySet()), emptySet()))
        assertEquals(UseStatus.PERMISSION, PublicData.status(use, PublicExport("p", "photos/1", setOf("location")), setOf("network")))
        assertEquals(UseStatus.AVAILABLE, PublicData.status(use, PublicExport("p", "photos/1", setOf("location")), setOf("location", "network")))
        assertEquals(UseStatus.AVAILABLE, PublicData.status(use, PublicExport("p", "photos/1", emptySet()), emptySet()))
    }
    @Test fun readFollowsThePathAndTreatsNullAsAbsent() {
        val state = JSONObject("""{"public":{"daily":{"cover":"a"},"none":null},"config":{"token":"secret"},"text":"x"}""")
        assertEquals("a", PublicData.read(state, "public.daily.cover"))
        assertNull(PublicData.read(state, "public.none"))
        assertNull(PublicData.read(state, "public.missing.deeper"))
        assertNull(PublicData.read(state, "text.deeper"))
    }
    @Test fun encodeProducesJsonTextForEveryExportableValue() {
        assertEquals("\"a\\\"b\"", PublicData.encode("a\"b"))
        assertEquals("3", PublicData.encode(3))
        assertEquals("true", PublicData.encode(true))
        assertEquals("""{"a":1}""", PublicData.encode(JSONObject("""{"a":1}""")))
    }
    @Test fun onlyAWholeBindingBorrowsTheProvidersImageGrant() {
        fun image(src: String) = JSONObject().put("type", "image").put("src", src)
        fun carousel(items: String) = JSONObject().put("type", "carousel").put("items", items)
        assertEquals("daily", PublicData.imageAlias(image("{{uses.daily.cover}}")))
        assertEquals("daily", PublicData.imageAlias(image("{{ uses.daily.photos.0.src }}")))
        assertEquals("daily", PublicData.imageAlias(carousel("{{uses.daily.photos}}")))
        assertEquals("daily", PublicData.imageAlias(carousel("uses.daily.photos")))
        // Composed or formatted values are the consumer's own request.
        assertNull(PublicData.imageAlias(image("https://me.example/{{uses.daily.cover}}")))
        assertNull(PublicData.imageAlias(image("{{uses.daily.cover}}?x=1")))
        assertNull(PublicData.imageAlias(image("{{uses.daily.cover | default \"https://me.example/a.jpg\"}}")))
        assertNull(PublicData.imageAlias(image("{{uses.a.x}}{{uses.b.y}}")))
        assertNull(PublicData.imageAlias(image("{{cover}}")))
        assertNull(PublicData.imageAlias(carousel("photos")))
        assertNull(PublicData.imageAlias(JSONObject().put("type", "text").put("value", "{{uses.daily.cover}}")))
    }
    @Test fun sameIgnoresKeyOrderButNotContent() {
        assertTrue(PublicData.same(JSONObject("""{"a":1,"b":[{"x":"y","z":2}]}"""), JSONObject("""{"b":[{"z":2.0,"x":"y"}],"a":1}""")))
        assertFalse(PublicData.same(JSONObject("""{"a":1}"""), JSONObject("""{"a":2}""")))
        assertFalse(PublicData.same(JSONObject("""{"a":1}"""), JSONObject("""{"a":1,"b":2}""")))
        assertFalse(PublicData.same(JSONObject("""{"a":[1,2]}"""), JSONObject("""{"a":[2,1]}""")))
        assertFalse(PublicData.same(null, JSONObject()))
        assertFalse(PublicData.same("1", 1))
    }
    @Test fun aLuaReadIsBoundedByTextAndByTableCount() {
        assertTrue(PublicData.fitsLua("""{"photos":[{"id":"a"}]}"""))
        assertFalse(PublicData.fitsLua("\"" + "x".repeat(64 * 1024) + "\""))
        assertTrue(PublicData.fitsLua("[" + "[],".repeat(4000) + "[]]"))
        assertFalse(PublicData.fitsLua("[" + "[],".repeat(4096) + "[]]"))
    }
    @Test fun overlappingExportsAndSettingsPermissionAreRejected() {
        val base = """"id":"probe","name":"Probe","version":"1.0.0","api":6"""
        for (bad in listOf(
            """"a":{"path":"public","shape":"s/1"},"b":{"path":"public.place","shape":"s/1","requires":["location"]}""",
            """"a":{"path":"public.x","shape":"s/1"},"b":{"path":"public.x","shape":"s/1"}""",
            """"a":{"path":"public.x","shape":"s/1","requires":["system.settings"]}""",
        )) assertThrows(bad, RuntimeException::class.java) { PluginSchema.validateManifest(JSONObject("""{$base,"exports":{$bad}}""")) }
        PluginSchema.validateManifest(JSONObject("""{$base,"exports":{"a":{"path":"public.x","shape":"s/1"},"b":{"path":"public.xy","shape":"s/1"}}}"""))
    }
    @Test fun cooldownIsPerCommandAcrossCallers() {
        val gate = CommandGate()
        assertTrue(gate.allow("refresh", 1_000, 300_000))
        assertFalse(gate.allow("refresh", 2_000, 300_000))
        assertTrue(gate.allow("next", 2_000, 1_000))
        assertTrue(gate.allow("refresh", 301_000, 300_000))
    }
    @Test fun aWholeSchemaManifestIsGatedByApi() {
        val base = """"id":"probe","name":"Probe","version":"1.0.0""""
        val fields = listOf(
            """"exports":{"daily":{"path":"public.daily","shape":"photos/1"}}""",
            """"uses":{"daily":{"export":"gallery.daily","shape":"photos/1"}}""",
            """"calls":["gallery.refresh"]""",
            """"commands":[{"id":"go","public":true}]""",
            """"pages":[{"id":"p","view":"views/p.json","title":"P"}],"widgets":[{"id":"w","view":"views/w.json","alternates":{"tile":"views/t.json"}}]""",
        )
        for (field in fields) {
            PluginSchema.validateManifest(JSONObject("""{$base,"api":6,$field}"""))
            assertThrows(field, IllegalArgumentException::class.java) { PluginSchema.validateManifest(JSONObject("""{$base,"api":5,$field}""")) }
        }
    }
    @Test fun aManifestCannotExportReservedStateOrLinkToItself() {
        val base = """"id":"probe","name":"Probe","version":"1.0.0","api":6"""
        for (path in listOf("config", "config.token", "uses", "uses.daily", "variant", "a..b", ".a", "a b")) {
            assertThrows(path, RuntimeException::class.java) {
                PluginSchema.validateManifest(JSONObject("""{$base,"exports":{"x":{"path":"$path","shape":"s/1"}}}"""))
            }
        }
        PluginSchema.validateManifest(JSONObject("""{$base,"exports":{"x":{"path":"configuration.a","shape":"s/1"}}}"""))
        for (bad in listOf(
            """"uses":{"x":{"export":"probe.daily","shape":"s/1"}}""",
            """"uses":{"Bad":{"export":"other.daily","shape":"s/1"}}""",
            """"uses":{"x":{"export":"other.daily","shape":"unversioned"}}""",
            """"exports":{"Bad":{"path":"a","shape":"s/1"}}""",
            """"calls":["probe.go"]""",
            """"commands":[{"id":"go","cooldown":5}]""",
        )) assertThrows(bad, RuntimeException::class.java) { PluginSchema.validateManifest(JSONObject("""{$base,$bad}""")) }
    }
    @Test fun aTileImageRequiresApiSix() {
        val tile = JSONObject("""{"template":"tile","image":{"type":"image","src":"{{a}}"},"faces":[{"label":"x"}]}""")
        PluginSchema.validateView(tile, 6)
        assertThrows(IllegalArgumentException::class.java) { PluginSchema.validateView(tile, 5) }
    }
    @Test fun aTileFaceCanBringItsOwnContentFromApiSix() {
        val tile = JSONObject("""{"template":"tile","faces":[{"label":"x","content":{"type":"clock","style":"analog"}}]}""")
        PluginSchema.validateView(tile, 6)
        assertThrows(IllegalArgumentException::class.java) { PluginSchema.validateView(tile, 5) }
    }
    @Test fun widgetReferencesInSlotsRequireApiSix() {
        val page = JSONObject("""{"layout":"focus","slots":{"hero":{"widget":"gallery.preview"}}}""")
        PluginSchema.validateView(page, 6)
        assertThrows(IllegalArgumentException::class.java) { PluginSchema.validateView(page, 5) }
        PluginSchema.validateView(JSONObject("""{"layout":"stacks","slots":{"left":[{"widget":"a.b"}],"right":[{"widget":"c.d"}]}}"""), 1)
    }
}
