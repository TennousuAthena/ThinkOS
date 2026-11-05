package moe.kiwi.thinkos.update

import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

/** All signing keys and document signatures are ephemeral test material, never deployed. */
internal class UpdateFixture {
    val active = ec(); val backup = ec(); val registry = ec(); val registryBackup = ec(); val test = ec()
    val payload = ByteArray(100_000) { (it * 13).toByte() }
    val signer = "a".repeat(64)
    val device = UpdateCatalog.Device("starfire", 27, 1001, signer)
    fun key(id: String, pair: KeyPair) = JSONObject().put("id", id).put("algorithm", "SHA256withECDSA")
        .put("spki", Base64.getEncoder().encodeToString(pair.public.encoded))
    fun production() = JSONObject().put("schema", 1)
        .put("channel", JSONObject().put("active", key("channel", active)).put("backup", key("channel-backup", backup)))
        .put("registry", JSONObject().put("active", key("registry", registry)).put("backup", key("registry-backup", registryBackup)))
    fun debug() = JSONObject().put("schema", 1).put("test", key("test", test))
    fun keys(debug: Boolean = false) = UpdateKeys.parse(bytes(production()), if (debug) bytes(debug()) else null)
    fun catalog(seq: Long = 1, channel: String = "stable", url: String = "https://updates.test/app.apk") = JSONObject()
        .put("schema", 1).put("channel", channel).put("seq", seq).put("published", 100).put("expires", 200)
        .put("release", JSONObject().put("version", "0.2.0").put("code", 2000).put("api", 9)
            .put("device", JSONArray().put("starfire")).put("sdk", 27).put("sha256", UpdateCatalog.sha256(payload))
            .put("size", payload.size).put("signer", signer).put("urls", JSONArray().put(url))
            .put("modules", JSONArray()).put("notes", JSONObject().put("zh-Hans", "更新内容")).put("source", "https://source.test/v0.2.0"))
    fun module(id: String = "thinkos-cast", kind: String = "magisk") = JSONObject().put("id", id).put("kind", kind)
        .put("version", "v1.1").put("code", 2).put("policy", "feature").put("sha256", "b".repeat(64)).put("size", 12)
        .put("urls", JSONArray().put("https://updates.test/module.zip")).put("manifest_urls", JSONArray().put("https://updates.test/module.manifest"))
    fun sign(bytes: ByteArray, pair: KeyPair = active) = Signature.getInstance("SHA256withECDSA").run {
        initSign(pair.private); update(bytes); sign()
    }
    fun verified(json: JSONObject = catalog(), keys: UpdateKeys = keys()): UpdateCatalog {
        val bytes = bytes(json)
        return UpdateCatalog.read(bytes, sign(bytes), keys, json.getString("channel"))
    }
    companion object {
        fun ec() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        fun bytes(json: JSONObject) = json.toString().toByteArray(Charsets.UTF_8)
    }
}
