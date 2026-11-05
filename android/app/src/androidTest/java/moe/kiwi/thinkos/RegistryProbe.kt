package moe.kiwi.thinkos

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Process
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.update.UpdateCatalog
import moe.kiwi.thinkos.update.UpdateFixture
import moe.kiwi.thinkos.update.UpdateTestOrigin
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** RAM-only signed origin for the real hub/native confirmation/PluginRuntime path.
 * Does not invoke install/activate/open/consent callbacks. Root drives public hub UI.
 * Control files change fixture publications or request a negative accessibility test.
 */
internal object RegistryProbe {
    private data class Package(val id: String, val name: String, val version: String, val digest: String, val index: ByteArray, val blobs: Map<String, ByteArray>, val permissions: List<String>)
    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit) {
        val context = runner.targetContext
        val area = File(directory, "registry").apply { mkdirs() }
        val id = "reg-probe-" + token.take(12)
        val sampleIds = setOf(id, id + "-home", id + "-settings", id + "-system", id + "-tampered") +
            (0..16).map { id + "-limit-" + it.toString().padStart(2, '0') }
        val ownership = File(area, "ownership.json")
        if (File(context.filesDir, "registry").exists()) {
            check(ownership.isFile && JSONObject(ownership.readText()).optString("id") == id) { "Preserve pre-existing authenticated registry cache and floor" }
        } else atomic(ownership, JSONObject().put("id", id).put("run_id", token))
        val state = File(context.filesDir, "plugins/registry-state.json")
        if (state.exists()) {
            val records = JSONObject(state.readText()).getJSONArray("installed")
            check((0 until records.length()).all { records.getJSONObject(it).getString("id") in sampleIds }) { "Preserve user registry plugins" }
        }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun quiet() = (1..5).all { audio.getStreamVolume(it) == 0 }
        check(quiet()) { "Keep all audio streams at zero" }
        val host = withContext(Dispatchers.Main) { (context.applicationContext as ThinkApplication).runtime }
        val keys = UpdateFixture()
        val bodies = ConcurrentHashMap<String, ByteArray>()
        val published = AtomicReference(ByteArray(0))
        val signature = AtomicReference(ByteArray(0))
        val ledger = File(context.filesDir, "registry/ledger.json")
        val sequence = AtomicLong(if (ledger.exists()) JSONObject(ledger.readText()).getLong("seq") else 0)
        val silent = runner.context.assets.open("runtime-video.mp4").use { it.readBytes() }
        val license = context.assets.open("legal/LICENSE").use { it.readBytes() }
        UpdateTestOrigin { exchange ->
            when (val path = exchange.requestURI.path) {
                "/registry/index.json" -> UpdateTestOrigin.reply(exchange, published.get())
                "/registry/index.json.sig" -> UpdateTestOrigin.reply(exchange, signature.get())
                "/silent.mp4" -> UpdateTestOrigin.artifact(exchange, silent)
                else -> bodies[path]?.let { UpdateTestOrigin.reply(exchange, it) } ?: UpdateTestOrigin.reply(exchange, "missing".toByteArray(), 404)
            }
        }.use { origin ->
            val versions = (0..4).map { patch -> pack(id, "1.0.$patch", origin.url("/silent.mp4"), patch, license) }
            val malicious = mapOf(
                "home" to pack(id + "-home", "1.0.0", origin.url("/silent.mp4"), 0, license, roles = listOf("home"), name = "首页角色拦截"),
                "settings" to pack(id + "-settings", "1.0.0", origin.url("/silent.mp4"), 0, license, roles = listOf("settings"), name = "设置角色拦截"),
                "system" to pack(id + "-system", "1.0.0", origin.url("/silent.mp4"), 0, license, hiddenPermissions = listOf("system.settings"), name = "系统权限拦截"),
                "tampered-blob" to pack(id + "-tampered", "1.0.0", origin.url("/silent.mp4"), 0, license, name = "文件摘要拦截"),
            )
            val limitPackages = (0..16).map { n ->
                pack(id + "-limit-" + n.toString().padStart(2, '0'), "1.0.0", origin.url("/silent.mp4"), 0, license,
                    advertisedPermissions = emptyList(), name = "数量测试 " + (n + 1).toString().padStart(2, '0'))
            }
            (versions + malicious.values + limitPackages).forEach { pkg ->
                bodies["/registry/files/" + pkg.digest + ".json"] = pkg.index
                pkg.blobs.forEach { (digest, bytes) -> bodies["/blobs/" + digest.take(2) + "/" + digest] = bytes }
            }
            // Keep the signed file list/digest unchanged and corrupt one delivered Lua blob.
            val corruptPackage = malicious.getValue("tampered-blob")
            val corruptEntries = JSONObject(String(corruptPackage.index)).getJSONArray("files")
            val corruptSha = (0 until corruptEntries.length()).map { corruptEntries.getJSONObject(it) }
                .single { it.getString("path") == "main.lua" }.getString("sha256")
            bodies["/blobs/" + corruptSha.take(2) + "/" + corruptSha] = corruptPackage.blobs.getValue(corruptSha).copyOf().also {
                check(it.isNotEmpty()); it[0] = (it[0].toInt() xor 1).toByte()
            }
            fun release(pkg: Package): JSONObject {
                val why = JSONObject()
                pkg.permissions.forEach { permission -> why.put(permission, JSONObject().put("zh-Hans", when (permission) {
                    "network" -> "读取本地测试台内容"
                    "media" -> "建立静音暂停会话"
                    else -> "读取测试城市设置"
                })) }
                val files = JSONObject(String(pkg.index)).getJSONArray("files")
                val size = (0 until files.length()).sumOf { files.getJSONObject(it).getLong("size") }
                return JSONObject().put("version", pkg.version).put("commit", UpdateCatalog.sha256((pkg.id + "@" + pkg.version).toByteArray()).take(40))
                    .put("digest", pkg.digest).put("api", 10).put("size", size).put("permissions", JSONArray(pkg.permissions))
                    .put("why", why).put("apps", JSONArray().put("main"))
            }
            fun plugin(pkg: Package, releases: List<Package>, tier: String = "reviewed"): JSONObject =
                JSONObject().put("id", pkg.id).put("tier", tier).put("name", JSONObject().put("zh-Hans", pkg.name))
                    .put("summary", JSONObject().put("zh-Hans", "本地功能验收")).put("author", "ThinkOS test")
                    .put("repo", "thinkos-tests/registry-probe").put("license", "MIT")
                    .put("tags", JSONArray().put("fixture")).put("releases", JSONArray(releases.map(::release)))
            fun publish(level: Int, revoked: Boolean = false, badSignature: Boolean = false, oldSequence: Boolean = false,
                        sample: String = "normal", limitCount: Int = 17, tier: String = "reviewed") {
                check(level in 1..5 && tier in setOf("reviewed", "community"))
                check(sample == "normal" || tier == "reviewed")
                val items = when (sample) {
                    "normal" -> listOf(plugin(versions[level - 1], versions.take(level).takeLast(3).asReversed(), tier))
                    "limits" -> { require(limitCount in 1..17); limitPackages.take(limitCount).map { plugin(it, listOf(it)) } }
                    "home", "settings", "system", "tampered-blob" -> malicious.getValue(sample).let { listOf(plugin(it, listOf(it))) }
                    else -> error("Unknown fixed fixture sample")
                }
                val revokedItems = JSONArray()
                if (revoked) items.forEach { entry ->
                    val latest = entry.getJSONArray("releases").getJSONObject(0)
                    revokedItems.put(JSONObject().put("id", entry.getString("id")).put("digest", latest.getString("digest")).put("reason", "测试台撤销此版本"))
                }
                val now = System.currentTimeMillis()
                val doc = JSONObject().put("schema", 1).put("seq", if (oldSequence) 1 else sequence.incrementAndGet())
                    .put("generated", now).put("expires", now + 86400000).put("blob_bases", JSONArray().put(origin.url("/")))
                    .put("revoked", revokedItems).put("plugins", JSONArray(items))
                val bytes = doc.toString().toByteArray()
                published.set(bytes)
                signature.set(keys.sign(bytes, if (badSignature) keys.active else keys.registry))
            }
            publish(1)
            test("registry probe injects ephemeral registry-role roots only into the debug process") {
                check(BuildConfig.DEBUG && Process.myUid() >= 10000 && quiet())
                host.plugins.registryOverrideForTest(keys.keys(), listOf(origin.url("/registry/index.json")))
            }
            runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val ready = JSONObject().put("uid", Process.myUid()).put("pid", Process.myPid()).put("id", id)
                .put("source", origin.url("/registry/index.json")).put("scope", "Fixture publication only; root drives public hub UI")
                .put("digests", JSONObject().apply { versions.forEach { put(it.version, it.digest) } })
                .put("samples", JSONObject().apply { malicious.forEach { (name, pkg) -> put(name, JSONObject().put("id", pkg.id).put("name", pkg.name).put("digest", pkg.digest)) } })
                .put("limits", JSONArray(limitPackages.map { pkg -> JSONObject().put("id", pkg.id).put("name", pkg.name).put("digest", pkg.digest) }))
                .put("tampered_blob_sha256", corruptSha)
                .put("capabilities", JSONArray(listOf("community-tier", "runtime-fault-v5", "persistent-bool-setting", "lifecycle-observation")))
                .put("settings_key", "remember").put("settings_label", "保留测试设置").put("fault_label", "触发错误")
            atomic(File(area, "ready.json"), ready)
            result.put("registry_probe", ready)
            var last = 0L
            var done = false
            withTimeout(45 * 60 * 1000L) {
                while (!done) {
                    check(quiet())
                    val file = File(area, "control.json")
                    if (file.isFile && file.length() in 1..4096) {
                        val command = runCatching { JSONObject(file.readText()) }.getOrNull()
                        val generation = command?.optLong("generation") ?: 0
                        if (command != null && generation > last) {
                            last = generation
                            val response = JSONObject().put("generation", generation)
                            try {
                                when (command.getString("action")) {
                                    "publish" -> {
                                        publish(command.optInt("level", 1), command.optBoolean("revoked"), command.optBoolean("bad_signature"), command.optBoolean("old_seq"),
                                            command.optString("sample", "normal"), command.optInt("limit_count", 17), command.optString("tier", "reviewed"))
                                        response.put("seq", sequence.get())
                                    }
                                    "accessibility_accept" -> test("programmatic accessibility activation cannot approve plugin permissions") {
                                        val automation = runner.uiAutomation
                                        try {
                                            val before = if (state.isFile) state.readBytes() else null
                                            val window = automation.rootInActiveWindow ?: error("No native confirmation window")
                                            val buttons = window.findAccessibilityNodeInfosByViewId("android:id/button1")
                                            check(buttons.size == 1) { "Expected the actual native positive button" }
                                            buttons.single().performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                            buttons.forEach { it.recycle() }; window.recycle()
                                            delay(400)
                                            val after = if (state.isFile) state.readBytes() else null
                                            check(if (before == null) after == null else after != null && before.contentEquals(after)) { "Non-touch action changed permission journal" }
                                            val still = automation.rootInActiveWindow ?: error("Confirmation disappeared")
                                            val visible = still.findAccessibilityNodeInfosByViewId("android:id/button1")
                                            check(visible.size == 1) { "Non-touch action dismissed the confirmation" }
                                            visible.forEach { it.recycle() }; still.recycle()
                                        } finally {
                                            // API27: destroy disconnects and marks the cached object
                                            // destroyed, so Instrumentation can reconnect next time.
                                            // Release the global automation service for host snapshots.
                                            automation.javaClass.getDeclaredMethod("destroy").invoke(automation)
                                        }
                                    }
                                    "checkpoint" -> {
                                        if (state.exists()) response.put("registry_state", JSONObject(state.readText()))
                                        response.put("requests", JSONArray(origin.hits.map { hit -> JSONObject().put("path", hit.path).put("headers", JSONObject(hit.headers)) }))
                                        withContext(Dispatchers.Main) {
                                            response.put("pid", Process.myPid()).put("elapsed", android.os.SystemClock.elapsedRealtime())
                                                .put("trial_s", host.settings.get("system", "plugins.trial_s"))
                                                .put("safe_mode", host.store.current.safeMode).put("page", host.store.current.deck.page)
                                                .put("media", JSONObject(host.media.snapshot()))
                                        }
                                    }
                                    "finish" -> done = true
                                    else -> error("Unknown fixed fixture control")
                                }
                                response.put("handled", true)
                            } catch (error: Throwable) { response.put("error", error.toString()) }
                            atomic(File(area, "response.json"), response)
                        }
                    }
                    delay(150)
                }
            }
            test("the registry fixture never raises audio or publishes mutable trust roots") {
                check(quiet())
                check(!File(context.filesDir, "registry/keys.json").exists())
                check(!File(context.filesDir, "plugins/keys.json").exists())
            }
        }
    }

    private fun atomic(file: File, json: JSONObject) {
        val temp = File(file.parentFile, file.name + ".new")
        FileOutputStream(temp).use { it.write(json.toString(2).toByteArray()); it.fd.sync() }
        check(temp.renameTo(file))
    }
    private fun pack(id: String, version: String, mediaUrl: String, patch: Int, license: ByteArray,
                     roles: List<String> = emptyList(), hiddenPermissions: List<String> = emptyList(),
                     advertisedPermissions: List<String>? = null, name: String = "仓库测试"): Package {
        val permissions = advertisedPermissions ?: if (patch >= 2) listOf("network", "media", "location") else listOf("network")
        val page = JSONObject().put("id", "main").put("title", name).put("view", "views/main.json")
        if (roles.isNotEmpty()) page.put("roles", JSONArray(roles))
        val apps = if (roles.isEmpty()) JSONArray().put(JSONObject().put("id", "main").put("page", "main").put("title", name)) else JSONArray()
        val manifest = JSONObject().put("id", id).put("name", name).put("version", version).put("api", 10).put("main", "main.lua")
            .put("permissions", JSONArray((permissions + hiddenPermissions).distinct())).put("apps", apps)
            .put("settings", JSONObject().put("remember", JSONObject().put("type", "bool")
                .put("label", "保留测试设置").put("default", false).put("level", "normal")))
            .put("pages", JSONArray().put(page))
        val view = JSONObject("""{"layout":"focus","slots":{"hero":{"type":"text","value":"{{label}}","style":"title"},"under":{"type":"text","value":"停止媒体","tap":"stop"}}}""")
        if (patch == 4) view.getJSONObject("slots").getJSONObject("under").put("value", "触发错误").put("tap", "fault")
        val failed = if (patch == 1) """error("Deliberate registry startup failure")""" else ""
        val media = if (patch == 2) """tos.media.load({{url=""" + JSONObject.quote(mediaUrl) + """,kind="video",title="Silent registry fixture"}},{autoplay=false})""" else ""
        val stop = if (patch >= 2) "tos.media.stop()" else """tos.log.info("REGISTRY_TAP",VERSION)"""
        val quotedName = JSONObject.quote(name)
        val lua = """
local VERSION = "$version"
local function authority()
 local denied=0
 for _,fn in ipairs{
  function() return tos.plugins.catalog{} end,
  tos.plugins.installed,
  function() return tos.plugins.open("$id") end,
  function() return tos.plugins.install("$id") end,
  function() return tos.plugins.update("$id") end,
  function() return tos.plugins.uninstall("$id") end,
  function() return tos.plugins.setAuto("$id",true) end
 } do if not pcall(fn) then denied=denied+1 end end
 tos.log.info("REGISTRY_DENIED",VERSION,denied)
 return denied
end
tos.on("plugins",function() tos.log.error("REGISTRY_EVENT_LEAK",VERSION) end)
tos.on("start",function()
 $failed
 local denied=authority()
 tos.store.set("probe.version",VERSION)
 tos.state.set{label=$quotedName.." "..VERSION,version=VERSION,denied=denied}
 tos.log.info("REGISTRY_SETTING",VERSION,tostring(tos.config.remember))
 $media
 tos.log.info("REGISTRY_STARTED",VERSION)
end)
tos.on("tap",function(name)
 if name=="stop" then $stop end
 ${if (patch == 4) "if name==\"fault\" then error(\"Deliberate registry runtime failure\") end" else ""}
end)
""".trimIndent()
        val files = sortedMapOf("manifest.json" to manifest.toString().toByteArray(), "views/main.json" to view.toString().toByteArray(), "main.lua" to lua.toByteArray(), "LICENSE" to license.copyOf())
        val entries = JSONArray()
        val canonical = StringBuilder()
        val blobs = mutableMapOf<String, ByteArray>()
        files.forEach { (path, bytes) ->
            val digest = UpdateCatalog.sha256(bytes); blobs[digest] = bytes
            canonical.append(path).append(0.toChar()).append(digest).append(10.toChar())
            entries.put(JSONObject().put("path", path).put("size", bytes.size).put("sha256", digest))
        }
        val digest = UpdateCatalog.sha256(canonical.toString().toByteArray())
        val index = JSONObject().put("id", id).put("version", version).put("digest", digest).put("files", entries)
        return Package(id, name, version, digest, index.toString().toByteArray(), blobs, permissions)
    }
}
