package moe.kiwi.thinkos

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.core.Store
import moe.kiwi.thinkos.settings.SettingsPersistence
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.SystemValues
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.UpdateComponentUi
import moe.kiwi.thinkos.ui.UpdateDialog
import moe.kiwi.thinkos.update.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Native component UI from a real authenticated catalog CHECK. All channel
 * signing roots/configuration stay in RAM in an owned workOverride runtime;
 * the process-owned module coordinator, system roots and installed APK do not
 * receive fixture data. Nothing here downloads an artifact or installs it.
 *
 * Runner dispatch: "components_ui" -> UpdateComponentsProbe.run(this,
 * directory, token, result, ::test). Root drives ordinary touch/scroll/rotation.
 * control.json: {generation:N, action:checkpoint|accessibility_click|finish}.
 */
internal object UpdateComponentsProbe {
    private val ids = listOf("thinkos-updater", "thinkos-cast", "thinkos-immersive", "thinkos-shell", "thinkos-certs", "thinkos-power", "timesync")

    @Suppress("DEPRECATION")
    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit) {
        check(token.matches(Regex("[a-f0-9]{32}")))
        val context = runner.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun quiet() = (1..5).all { audio.getStreamVolume(it) == 0 }
        check(quiet()) { "Audio streams 1–5 must remain zero" }
        check(Locale.getDefault().language == "zh") { "Root must select Chinese before starting this fixture" }
        val area = File(directory, "components")
        check(!area.exists()) { "Refusing to overwrite another component fixture; use a new run_id" }
        check(area.mkdirs())
        val activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) as MainActivity
        val host = withContext(Dispatchers.Main) { (context.applicationContext as ThinkApplication).runtime }
        val initialSettings = withContext(Dispatchers.Main) { host.settings.snapshot(SettingsStore.SYSTEM) }
        val initialPage = withContext(Dispatchers.Main) {
            check(!host.router.canGoBack && !host.router.onboardingOpen && !host.store.current.safeMode)
            check(!host.updates.screensSnapshot().settings) { "Preserve an existing native/settings window" }
            val media = host.media.snapshot()
            check(media["owner"].toString().isEmpty() && (media["count"] as Number).toInt() == 0) { "Preserve the current media session" }
            host.store.current.deck.page
        }
        val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        check(installed.versionCode == BuildConfig.VERSION_CODE && installed.versionName == BuildConfig.VERSION_NAME)
        val installedFile = File(installed.applicationInfo!!.sourceDir)
        val apkBefore = sha(installedFile)
        val device = UpdateCatalog.Device(Build.DEVICE, Build.VERSION.SDK_INT, installed.versionCode,
            UpdateCatalog.sha256(installed.signatures!!.single().toByteArray()))
        val fixture = UpdateFixture()
        val keys = fixture.keys() // Channel/registry only; never ModuleManifest.Keys.
        val documents = ConcurrentHashMap<String, ByteArray>()
        val callbackCalls = AtomicInteger()
        var dialog: UpdateDialog? = null
        var markedWindow = false

        UpdateTestOrigin { exchange ->
            val bytes = documents[exchange.requestURI.path]
            if (bytes != null) UpdateTestOrigin.reply(exchange, bytes)
            else UpdateTestOrigin.reply(exchange, "metadata-only fixture".toByteArray(), 404)
        }.use { origin ->
            test("component fixture uses no usable system signing roots or optional updater") {
                check(ModuleManifest.loadKeys(context).isFailure)
                check(!UpdaterClient().hello())
                check(quiet())
            }
            runner.checkedSnapshot() // A failed preflight must not turn into a simulated component result.
            val nextCode = installed.versionCode.toLong() + 1
            check(nextCode <= Int.MAX_VALUE)
            val nextVersion = "${nextCode / 1_000_000}.${nextCode / 1000 % 1000}.${nextCode % 1000}"
            val modules = JSONArray()
            ids.forEachIndexed { index, id ->
                val payload = "metadata-only $id".toByteArray()
                modules.put(fixture.module(id, if (id == "thinkos-immersive") "vector" else "magisk")
                    .put("code", index + 2)
                    .put("version", if (id == "thinkos-updater") "v2.0.0-components-layout-abcdefghijklmnopqrstuvwxyz-0123456789" else "v2.${index}.0")
                    .put("policy", when (id) { "thinkos-cast" -> "feature"; "thinkos-updater", "thinkos-immersive", "timesync" -> "always"; else -> "installed" })
                    .put("sha256", UpdateCatalog.sha256(payload)).put("size", payload.size)
                    .put("urls", JSONArray().put(origin.url("/modules/$id.zip")))
                    .put("manifest_urls", JSONArray().put(origin.url("/modules/$id.manifest"))))
            }
            val document = fixture.catalog(601, url = origin.url("/app.apk")).apply {
                put("published", System.currentTimeMillis()).put("expires", System.currentTimeMillis() + 86400000L)
                getJSONObject("release").put("device", JSONArray().put(device.name)).put("sdk", device.sdk).put("api", 10)
                    .put("version", nextVersion).put("code", nextCode).put("signer", device.signer).put("modules", modules)
                    .put("notes", JSONObject().put("zh-Hans", "组件界面检查"))
                    .put("source", origin.url("/source"))
            }
            val raw = UpdateFixture.bytes(document)
            documents["/stable.json"] = raw; documents["/stable.json.sig"] = fixture.sign(raw)
            val ram = SettingsStore(object : SettingsPersistence {
                private val values = mutableMapOf<String, ByteArray>()
                override fun read(name: String) = values[name]?.copyOf()
                override fun write(name: String, bytes: ByteArray) { values[name] = bytes.copyOf() }
            })
            val owned = withContext(Dispatchers.Main) {
                ram.register(requireNotNull(host.settings.scope(SettingsStore.SYSTEM)))
                ram.setMany(SettingsStore.SYSTEM, mapOf("update.enabled" to false, "update.auto_install" to false,
                    "update.channel" to "stable", "update.sources" to "", "update.mirror" to origin.url("/stable.json")))
                UpdateRuntime(context, ram, SystemValues(ram), Store(host.store.current), { host.sensors.touches }, host.updates::screensSnapshot,
                    workOverride = { config, _ -> UpdateIo.run {
                        val root = File(area, "workflow")
                        val ledger = UpdateSequenceStore(File(root, "ledger"))
                        val fetcher = Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(config.connectMillis, config.readMillis))
                        val session = UpdateSession(config.channel,
                            UpdateCatalogClient(fetcher, keys, ledger, UpdateCatalogCache(File(root, "metadata"), keys)),
                            UpdateState(File(root, "state"), ledger), File(root, "downloads"), UpdatePackageVerifier.forContext(context),
                            { device }, { val now = UpdateInstallContext.now(context); UpdateState.Time(now.boot, now.elapsed, now.wall) })
                        UpdateRuntime.SessionWork(session, fetcher) // No installer or ModuleUpdateRuntime.
                    } }, environmentOverride = host.updates.environment)
            }
            try {
                withContext(Dispatchers.Main) { owned.start() }
                test("RAM channel signature passes the production check and publishes seven honest component targets") {
                    until { owned.status.value.configured }
                    check(withContext(Dispatchers.Main) { owned.manual(UpdateController.Action.CHECK, SystemClock.elapsedRealtime()) })
                    until { owned.status.value.state.phase == "AVAILABLE" && !owned.status.value.busy }
                    val state = withContext(Dispatchers.Main) { owned.publicStatus() }
                    check(state.components.map { it.id } == ids)
                    check(state.components.all { it.state == "requires_updater" && it.installed_code == null && it.downloaded == null && it.result == null })
                    check(state.can_check && !state.can_download && !state.can_install && state.downloaded == 0L)
                    check(origin.hits.any { it.path == "/stable.json" } && origin.hits.any { it.path == "/stable.json.sig" })
                    result.put("authenticated_catalog_digest", owned.status.value.state.catalog_digest)
                    result.put("scope", "Owned RAM channel CHECK and actual native dialog only; no artifact download, system root, installed module or success result fixture")
                }
                runner.checkedSnapshot()
                withContext(Dispatchers.Main) {
                    host.updates.nativeWindow(true); owned.nativeWindow(true); markedWindow = true
                    dialog = UpdateDialog(activity, Palette.forTheme(host.store.current.theme), owned::publicStatus, owned.changes,
                        { action, tap -> callbackCalls.incrementAndGet(); owned.manual(action, tap) }) {
                        host.sensors.touch(); host.router.noteInteraction()
                    }.apply {
                        setOnDismissListener {
                            host.updates.nativeWindow(false); owned.nativeWindow(false); markedWindow = false
                        }
                        show()
                    }
                }
                until { withContext(Dispatchers.Main) { dialog!!.window?.decorView?.width ?: 0 } > 0 }
                atomic(File(area, "ready.json"), withContext(Dispatchers.Main) { checkpoint(dialog!!, owned, callbackCalls.get()) }
                    .put("run_id", token).put("pid", Process.myPid()).put("page", initialPage)
                    .put("control", "cache/update-device-$token/components/control.json"))
                var generation = 0L
                var finished = false
                withTimeout(20 * 60 * 1000L) {
                    while (!finished) {
                        check(quiet()) { "Audio stream raised during component UI probe" }
                        val commandFile = File(area, "control.json")
                        if (commandFile.isFile && commandFile.length() in 1..4096) {
                            val command = runCatching { JSONObject(commandFile.readText()) }.getOrNull()
                            val incoming = command?.optLong("generation") ?: 0
                            if (command != null && incoming > generation) {
                                generation = incoming
                                val response = JSONObject().put("generation", incoming)
                                try {
                                    when (command.getString("action")) {
                                        "checkpoint" -> response.put("checkpoint", withContext(Dispatchers.Main) { checkpoint(dialog!!, owned, callbackCalls.get()) })
                                        "accessibility_click" -> {
                                            test("ACTION_CLICK cannot manufacture native update-button touch credit") {
                                                val beforeCalls = callbackCalls.get()
                                                val beforeHits = origin.hits.size
                                                val label = withContext(Dispatchers.Main) {
                                                    check(dialog!!.isShowing && dialog!!.window!!.decorView.hasWindowFocus())
                                                    dialog!!.context.getString(R.string.update_check)
                                                }
                                                val automation = runner.uiAutomation
                                                try {
                                                    val root = automation.rootInActiveWindow ?: error("No active native update window")
                                                    try {
                                                        val nodes = root.findAccessibilityNodeInfosByText(label)
                                                        try {
                                                            val button = nodes.single { it.isClickable && it.isEnabled && it.text?.toString() == label }
                                                            response.put("action_returned", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                                        } finally { nodes.forEach { it.recycle() } }
                                                    } finally { root.recycle() }
                                                    delay(500)
                                                    check(callbackCalls.get() == beforeCalls && origin.hits.size == beforeHits)
                                                    check(withContext(Dispatchers.Main) { dialog!!.isShowing && owned.publicStatus().components.size == 7 })
                                                } finally {
                                                    // API27 hidden test-only cleanup: disconnect() alone
                                                    // leaves Instrumentation caching a disconnected object.
                                                    automation.javaClass.getDeclaredMethod("destroy").apply { isAccessible = true }.invoke(automation)
                                                    response.put("automation_destroyed", true)
                                                }
                                            }
                                            runner.checkedSnapshot()
                                        }
                                        "finish" -> finished = true
                                        else -> error("Unknown fixed component fixture control")
                                    }
                                    response.put("handled", true)
                                } catch (error: Throwable) { response.put("error", error.toString()) }
                                atomic(File(area, "response.json"), response)
                            }
                        }
                        delay(150)
                    }
                }
                test("component fixture leaves installed APK, user settings, module authority and silence intact") {
                    check(sha(installedFile) == apkBefore)
                    check(withContext(Dispatchers.Main) { host.settings.snapshot(SettingsStore.SYSTEM) == initialSettings })
                    check(ModuleManifest.loadKeys(context).isFailure)
                    check(origin.hits.all { it.path in setOf("/stable.json", "/stable.json.sig") })
                    check(quiet())
                    result.put("apk_unchanged", true).put("user_settings_unchanged", true).put("artifact_requests", 0)
                    result.put("last_status", withContext(Dispatchers.Main) { UpdateModels.writeUpdateStatusDocument(owned.publicStatus()) })
                }
            } finally {
                withContext(Dispatchers.Main) {
                    dialog?.dismiss(); dialog = null
                    if (markedWindow) { host.updates.nativeWindow(false); owned.nativeWindow(false) }
                    owned.stop()
                }
            }
        }
    }

    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(20_000) { while (!predicate()) delay(25) }
    private fun atomic(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".new")
        FileOutputStream(temporary).use { it.write(value.toString(2).toByteArray()); it.fd.sync() }
        check(temporary.renameTo(file))
    }
    private fun sha(file: File): String {
        val hash = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) {
            val count = input.read(bytes); if (count < 0) break; hash.update(bytes, 0, count)
        } }
        return hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun checkpoint(dialog: UpdateDialog, runtime: UpdateRuntime, calls: Int): JSONObject {
        val decor = requireNotNull(dialog.window).decorView
        val labels = ids.associateWith { dialog.context.getString(UpdateComponentUi.name(it)) }
        val actions = listOf(R.string.update_check, R.string.update_download, R.string.update_install).map { dialog.context.getString(it) }
        val rows = JSONArray(); val buttons = JSONArray()
        var scroll: ScrollView? = null
        fun walk(view: View) {
            if (view is ScrollView) scroll = view
            if (view is TextView) {
                val text = view.text.toString()
                val id = labels.entries.firstOrNull { it.value == text }?.key
                if (id != null || text in actions) {
                    val rectangle = Rect()
                    val visible = view.visibility == View.VISIBLE && view.getGlobalVisibleRect(rectangle)
                    val item = JSONObject().put("text", text).put("visible", visible).put("enabled", view.isEnabled)
                        .put("bounds", JSONArray(listOf(rectangle.left, rectangle.top, rectangle.right, rectangle.bottom)))
                    if (id != null) rows.put(item.put("id", id)) else buttons.put(item)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
        }
        walk(decor)
        return JSONObject().put("status", UpdateModels.writeUpdateStatusDocument(runtime.publicStatus())).put("showing", dialog.isShowing)
            .put("width", decor.width).put("height", decor.height).put("rows", rows).put("buttons", buttons)
            .put("scroll_y", scroll?.scrollY ?: 0).put("can_scroll_down", scroll?.canScrollVertically(1) == true)
            .put("can_scroll_up", scroll?.canScrollVertically(-1) == true).put("callback_calls", calls)
    }
}
