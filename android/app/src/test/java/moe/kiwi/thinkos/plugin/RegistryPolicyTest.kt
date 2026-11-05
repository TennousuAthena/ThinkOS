package moe.kiwi.thinkos.plugin

import moe.kiwi.thinkos.update.UpdateJson
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RegistryPolicyTest {
    private fun manifest(id: String = "probe", permissions: List<String> = emptyList(), roles: List<String> = emptyList()): JSONObject =
        JSONObject().put("id", id).put("name", "Probe").put("version", "1.0.0").put("api", 10)
            .put("permissions", JSONArray(permissions)).put("pages", JSONArray().put(JSONObject().put("id", "main").put("title", "Probe").put("view", "views/main.json").put("roles", JSONArray(roles))))
    private fun record(id: String = "probe", tier: String = "reviewed", confirmed: List<String> = emptyList()) =
        JSONObject().put("id", id).put("version", "1.0.0").put("tier", tier).put("confirmed", JSONArray(confirmed))
    private fun expected(permissions: List<String>) = JSONObject().put("permissions", JSONArray(permissions))
    private fun catalog(): JSONObject {
        val release = JSONObject().put("version", "1.0.0").put("commit", "a".repeat(40)).put("digest", "b".repeat(64)).put("api", 10).put("size", 20)
            .put("permissions", JSONArray().put("network")).put("why", JSONObject().put("network", JSONObject().put("zh-Hans", "读取天气"))).put("apps", JSONArray().put("main"))
        val plugin = JSONObject().put("id", "probe").put("tier", "reviewed").put("name", JSONObject().put("zh-Hans", "测试"))
            .put("summary", JSONObject().put("en", "Weather")).put("author", "alice").put("repo", "alice/probe").put("license", "MIT")
            .put("tags", JSONArray().put("weather")).put("releases", JSONArray().put(release))
        return JSONObject().put("schema", 1).put("seq", 1).put("generated", 1000).put("expires", 2000).put("blob_bases", JSONArray().put("https://example.test/"))
            .put("revoked", JSONArray()).put("plugins", JSONArray().put(plugin))
    }
    private fun parse(value: JSONObject) = RegistryPolicy.parseCatalog(value.toString().toByteArray())

    @Test fun semanticVersionsNeverOverflowAndStableWinsPrerelease() {
        assertTrue(RegistryPolicy.compare("999999999999999999.0.0", "2147483647.0.0") > 0)
        assertTrue(RegistryPolicy.compare("1.0.0", "1.0.0-rc.9") > 0)
        assertTrue(RegistryPolicy.compare("1.0.0-rc.10", "1.0.0-rc.9") > 0)
        assertEquals(0, RegistryPolicy.compare("1.0.0", "1.0.0"))
    }
    @Test fun exactCatalogSupportsLocalizedPermissionReasons() {
        assertEquals("probe", parse(catalog()).getJSONArray("plugins").getJSONObject(0).getString("id"))
    }
    @Test fun duplicateIdsReasonsAndNonDescendingVersionsAreRejected() {
        val duplicate = catalog(); duplicate.getJSONArray("plugins").put(duplicate.getJSONArray("plugins").getJSONObject(0))
        assertThrows(IllegalArgumentException::class.java) { parse(duplicate) }
        val missing = catalog(); missing.getJSONArray("plugins").getJSONObject(0).getJSONArray("releases").getJSONObject(0).put("why", JSONObject())
        assertThrows(IllegalArgumentException::class.java) { parse(missing) }
        val versions = catalog(); val releases = versions.getJSONArray("plugins").getJSONObject(0).getJSONArray("releases")
        releases.put(JSONObject(releases.getJSONObject(0).toString()).put("digest", "c".repeat(64)))
        assertThrows(IllegalArgumentException::class.java) { parse(versions) }
    }
    @Test fun unsignedSchemaExtensionsAndFractionalSequencesFailClosed() {
        assertThrows(IllegalArgumentException::class.java) { parse(catalog().put("keys", JSONObject())) }
        assertThrows(IllegalArgumentException::class.java) { parse(catalog().put("seq", 1.5)) }
        val broken = catalog().toString().replace("\"seq\":1", "\"seq\":1,\"seq\":2")
        assertThrows(IllegalArgumentException::class.java) { UpdateJson.read(broken.toByteArray(), 1024 * 1024) }
    }
    @Test fun rolesAndSystemPermissionsNeverEnterRegistryRuntime() {
        for (role in listOf("home", "settings", "panel", "onboarding", "launcher", "sleep")) {
            assertThrows(IllegalArgumentException::class.java) { RegistryPolicy.manifest(manifest(roles = listOf(role)), expected(emptyList()), record(), null) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegistryPolicy.manifest(manifest(permissions = listOf("system.settings")), expected(listOf("system.settings")), record(confirmed = listOf("system.settings")), null)
        }
    }
    @Test fun permissionIncreaseCannotExecuteWithoutMatchingConfirmedSet() {
        assertThrows(IllegalArgumentException::class.java) {
            RegistryPolicy.manifest(manifest(permissions = listOf("network")), expected(listOf("network")), record(), null)
        }
        RegistryPolicy.manifest(manifest(permissions = listOf("network")), expected(listOf("network")), record(confirmed = listOf("network")), null)
    }
    @Test fun factoryCollisionRequiresOfficialTierAndSystemBundlesStayWithApk() {
        val ordinary = manifest("player", listOf("media"))
        assertThrows(IllegalArgumentException::class.java) {
            RegistryPolicy.manifest(ordinary, expected(listOf("media")), record("player", confirmed = listOf("media")), ordinary)
        }
        RegistryPolicy.manifest(ordinary, expected(listOf("media")), record("player", "official", listOf("media")), ordinary)
        assertThrows(IllegalArgumentException::class.java) {
            RegistryPolicy.manifest(manifest("settings"), expected(emptyList()), record("settings", "official"), manifest("settings", listOf("system.settings"), listOf("settings")))
        }
    }
}
