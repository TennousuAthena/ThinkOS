package moe.kiwi.thinkos.settings

import moe.kiwi.thinkos.i18n.Labels
import moe.kiwi.thinkos.plugin.PluginSchema
import moe.kiwi.thinkos.tz.TzDatabase
import moe.kiwi.thinkos.tz.TzSource
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Locale

class RegionSettingsTest {
    private var originalDatabase: TzDatabase? = null
    private class Memory : SettingsPersistence {
        val files = mutableMapOf<String, ByteArray>()
        var writes = 0
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes; writes++ }
    }
    private val declaration = """{"type":"choice","label":"Region","default":"","source":"region","allowEmpty":true}"""
    private fun field(json: String = declaration) = SettingField.parse("country", JSONObject(json), false, TimezoneSource)
    private fun store(memory: Memory) = SettingsStore(memory, TimezoneSource).also {
        it.register(SettingsScope("radio", "Radio", mapOf("country" to field())))
    }
    @Before fun installOfflineInventory() {
        originalDatabase = Zones.database
        val files = mapOf("VERSION" to "test", "links.tsv" to "", "countries.tsv" to "DE\tGermany\nCN\tChina\n",
            "zones.tsv" to "Europe/Berlin\tDE\t52.5\t13.4\t\nAsia/Shanghai\tCN\t31.2\t121.5\t\n")
        Zones.install(TzDatabase(object : TzSource {
            override fun read(path: String): ByteArray? = files[path]?.toByteArray()
        }))
    }
    @After fun restoreOfflineInventory() { Zones.install(originalDatabase) }

    @Test fun sharedInventoryNormalizesKnownCodesAndRejectsUnknownOrMalformedValues() {
        assertEquals(listOf("CN", "DE"), RegionOptions.codes())
        assertEquals("DE", field().validate("de", TimezoneSource))
        assertEquals("", field().validate("", TimezoneSource))
        for (bad in listOf("ZZ", "D", "DEU", " D", "D\n", "../DE"))
            assertEquals("option", assertThrows(SettingError::class.java) { field().validate(bad, TimezoneSource) }.code)
        val required = field("""{"type":"choice","label":"Region","default":"DE","source":"region"}""")
        assertEquals("required", assertThrows(SettingError::class.java) { required.validate("", TimezoneSource) }.code)
    }

    @Test fun legacyStringOverrideIsReadWithoutWritingOrChangingItsStoredBytes() {
        val memory = Memory()
        val raw = """{"radio":{"country":"de"}}""".toByteArray()
        memory.files["local.json"] = raw
        val settings = store(memory)
        assertEquals("DE", settings.get("radio", "country"))
        assertTrue(settings.resolved("radio", "country").overridden)
        assertArrayEquals(raw, memory.files["local.json"])
        assertEquals(0, memory.writes)
    }

    @Test fun selectingAllRegionsKeepsAnExplicitEmptyOverrideAboveTheServerRegion() {
        val memory = Memory()
        memory.files["server.json"] = """{"radio":{"country":"CN"}}""".toByteArray()
        val settings = store(memory)
        settings.set("radio", "country", "")
        assertEquals("", settings.get("radio", "country"))
        assertTrue(settings.resolved("radio", "country").overridden)
        assertEquals("", JSONObject(String(memory.files.getValue("local.json"))).getJSONObject("radio").getString("country"))
        assertEquals("", store(memory).get("radio", "country"))
    }

    @Test fun descriptionKeepsTheStringValueAndProvidesLocalizedNamesIncludingAllRegions() {
        val memory = Memory()
        val settings = store(memory)
        val labels = Labels({ key -> if (key == "form.region.all") "All regions" else null }) { _, _, _, _ -> null }
        val describe = SettingsDescriber(settings, labels) { _, _ -> "Radio" }
        fun entry() = ((describe.describe("radio", Locale.US)!!["fields"] as List<*>).single() as Map<*, *>)
        assertEquals("", entry()["value"])
        assertEquals("All regions", entry()["display"])
        assertEquals("region", entry()["source"])
        assertEquals(true, entry()["allowEmpty"])
        settings.set("radio", "country", "DE")
        assertEquals("DE", entry()["value"])
        assertEquals("Germany", entry()["display"])
    }

    @Test fun regionDeclarationRequiresApiTenWithoutUpgradingOldDynamicSources() {
        fun manifest(source: String, api: Int) = JSONObject("""{"id":"region-test","name":"Region","version":"1.0.0","api":$api,"settings":{"country":{"type":"choice","label":"Region","default":"","source":"$source","allowEmpty":true}}}""")
        PluginSchema.validateManifest(manifest("region", 10))
        assertThrows(IllegalArgumentException::class.java) { PluginSchema.validateManifest(manifest("region", 9)) }
        PluginSchema.validateManifest(manifest("timezone", 1))
        PluginSchema.validateManifest(manifest("place", 1))
    }
}
