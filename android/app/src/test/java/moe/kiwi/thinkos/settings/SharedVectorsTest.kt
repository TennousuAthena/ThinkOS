package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assert.assertThrows
import moe.kiwi.thinkos.plugin.PluginSchema
import org.junit.Test
import java.io.File

/** The same declarations the Python packager checks (shared/schema/settings/vectors.json). */
class SharedVectorsTest {
    private val vectors = JSONObject(File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata").parentFile.resolve("schema/settings/vectors.json").readText())

    @Test
    fun validDeclarationsAreAccepted() {
        val valid = vectors.getJSONArray("valid")
        assertTrue(valid.length() > 5)
        for (i in 0 until valid.length()) {
            val item = valid.getJSONObject(i)
            SettingField.parse(item.getString("key"), item.getJSONObject("field"), item.optBoolean("system"))
        }
    }

    @Test
    fun invalidDeclarationsAreRejected() {
        val invalid = vectors.getJSONArray("invalid")
        assertTrue(invalid.length() > 15)
        for (i in 0 until invalid.length()) {
            val item = invalid.getJSONObject(i)
            try {
                SettingField.parse(item.getString("key"), item.getJSONObject("field"), item.optBoolean("system"))
                fail("accepted: ${item.getString("why")}")
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    @Test fun manifestsAndSystemSectionsAgreeWithThePackager() {
        for (name in listOf("validManifests", "invalidManifests", "validSystems", "invalidSystems")) {
            val items = vectors.getJSONArray(name)
            for (i in 0 until items.length()) {
                val item = items.getJSONObject(i)
                val check = {
                    if (name.endsWith("Manifests")) PluginSchema.validateManifest(item.optJSONObject("manifest") ?: item)
                    else SystemSettings.parse((item.optJSONObject("document") ?: item).toString())
                    Unit
                }
                if (name.startsWith("valid")) check()
                else assertThrows(item.getString("why"), IllegalArgumentException::class.java, check)
            }
        }
    }
}
