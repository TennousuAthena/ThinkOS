package moe.kiwi.thinkos

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Rect
import android.media.AudioManager
import android.os.Process
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.UpdateDialog
import moe.kiwi.thinkos.update.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.Locale

/** Actual native repair component, with ONLY its filesDir redirected into this
 * token's cache. Real Activity/Application/PM/fixed updater evidence are retained.
 * No UpdateRuntime, module singleton, installer, keys or recovery-clear fixture.
 * Root alone supplies kernel touch/rotation/foreground/silence guards.
 */
internal object UpdateRepairUiProbe {
    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit,
                    fixtureV3Digest: String? = null, fixturePublicDigest: String? = null) {
        check(BuildConfig.DEBUG && token.matches(Regex("[a-f0-9]{32}")))
        val context = runner.targetContext
        check((fixtureV3Digest == null) == (fixturePublicDigest == null))
        check(fixtureV3Digest == null || context.packageName == "moe.kiwi.thinkos.regfixture")
        val area = File(directory, "repair-ui")
        check(!area.exists() && area.mkdir())
        val files = File(area, "files").apply { check(mkdir()) }
        val ownedContext = object : ContextWrapper(context) { override fun getFilesDir(): File = files }
        check(ownedContext.applicationContext === context.applicationContext)
        val root = File(files, "update").apply { check(mkdir()) }
        check(root.canonicalPath.startsWith(directory.canonicalPath + File.separator))
        check(root.canonicalFile != UpdateInstallContext.directory(context).canonicalFile)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun quiet() = check((1..5).all { audio.getStreamVolume(it) == 0 })
        quiet(); check(Locale.getDefault().language == "zh")
        val activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) as MainActivity
        val host = main { (context.applicationContext as ThinkApplication).runtime }
        until { main { host.router.currentPage != null } }
        if (fixtureV3Digest != null) {
            check(fixtureV3Digest.matches(Regex("[a-f0-9]{64}")) && fixturePublicDigest?.matches(Regex("[a-f0-9]{64}")) == true)
            // Exact, existing root-owned test material only; never inject roots,
            // disable a plugin, clear records or treat this as public Stop proof.
            val keysRaw = withContext(Dispatchers.IO) { context.assets.open("update/keys.json").use { input ->
                val data = ByteArray(65537); var count = 0
                while (count < data.size) { val n = input.read(data, count, data.size-count); if (n < 0) break; count += n }
                check(count in 1..65536); data.copyOf(count)
            } }
            check(UpdateCatalog.sha256(keysRaw) == fixturePublicDigest)
            UpdateKeys.parse(keysRaw) // Actual P256/canonical/distinct APK roots.
            val keys = UpdateModels.readUpdateKeyDocument(UpdateJson.read(keysRaw, 65536))
            val rootId = Regex("reg2b-([a-f0-9]{32})-channel-active").matchEntire(keys.channel.active.id)?.groupValues?.get(1)
                ?: error("Explicit fixture key namespace required")
            check(keys.channel.backup.id == "reg2b-$rootId-channel-backup" &&
                keys.registry.active.id == "reg2b-$rootId-registry-active" && keys.registry.backup.id == "reg2b-$rootId-registry-backup")
            val id = "reg-probe-" + rootId.take(12)
            val stateFile = File(context.filesDir, "plugins/registry-state.json")
            val stateRaw = withContext(Dispatchers.IO) { checkNotNull(bytes(stateFile, 262144)) }
            val state = RegistryModels.readRegistryState(UpdateJson.read(stateRaw, 262144))
            val installed = state.installed.single()
            check(!state.disabled && installed.id == id && installed.version == "1.0.2" && installed.digest == fixtureV3Digest &&
                installed.repo == "thinkos-tests/registry-probe" && installed.name == "仓库测试" && installed.tier == "reviewed" &&
                installed.commit == UpdateCatalog.sha256("$id@1.0.2".toByteArray()).take(40) && installed.enabled && !installed.trial &&
                installed.error == null && installed.confirmed.toSet() == setOf("network", "media", "location"))
            fun controlledIdentity(media: Map<String, Any?>): Boolean =
                (media["owner"] as? String)?.matches(Regex(Regex.escape(id) + ":[1-9][0-9]*")) == true &&
                    (media["count"] as? Number)?.toLong() == 1L && (media["index"] as? Number)?.toLong() == 1L &&
                    media["kind"] == "video" && media["title"] == "Silent registry fixture"
            val setup = JSONObject().put("scope", "test_harness_preparation_only_not_public_Stop")
                .put("plugin", id).put("version", installed.version).put("digest", installed.digest)
                .put("explicit_own_fixture_media_stopped", false)
            // Publish failure observations too; do not infer a replaced owner
            // merely from a failed paused/error check during cold preparation.
            result.put("repair_ui_setup", setup)
            // Test-only observation of the existing volatile bootstrap signal.
            // ready follows first-frame registry metadata recovery; the active
            // diagnostic below separately proves the deferred v3 VM committed.
            // No setter, new production API, startup pause or fabricated event.
            val registryReady = host.plugins.javaClass.getDeclaredField("registryReady").apply { isAccessible = true }
            until { main {
                val media = host.media.snapshot()
                setup.put("last_observed", JSONObject(media))
                val empty = media["status"] == "stopped" && media["owner"] in listOf(null, "") &&
                    (media["count"] as? Number)?.toLong() == 0L
                if (empty) false
                else {
                    check(controlledIdentity(media)) { "Unknown media queue; setup Stop refused" }
                    when (media["status"]) {
                        // These exact owned states may be a pending asynchronous
                        // prepare. Wait, but never Stop or label them paused.
                        "buffering", "reconnecting" -> false
                        "paused", "error" -> {
                            val diagnostic = host.plugins.diagnostics().singleOrNull { it.id == id }
                            val ready = registryReady.getBoolean(host.plugins) && diagnostic?.let {
                                it.version == "1.0.2" && !it.disabled && it.error == null && it.bytes > 0
                            } == true
                            if (!ready) false
                            else {
                                quiet()
                                // Snapshot validation and actual Stop are one
                                // Main invocation; no yield/re-read against an
                                // owner captured in another coroutine turn.
                                setup.put("before", JSONObject(media)).put("registry_bootstrap_ready", true)
                                    .put("same_main_snapshot_stop", true)
                                host.media.stop()
                                val after = host.media.snapshot()
                                check(after["status"] == "stopped" && after["owner"] in listOf(null, "") &&
                                    (after["count"] as? Number)?.toLong() == 0L)
                                setup.put("after", JSONObject(after)).put("explicit_own_fixture_media_stopped", true)
                                true
                            }
                        }
                        else -> error("Non-paused fixture media; setup Stop refused")
                    }
                }
            } }
            check(withContext(Dispatchers.IO) { same(stateRaw, bytes(stateFile, 262144)) })
        }
        val originalDeck = main { host.store.current.deck }
        val originalSettings = main { host.settings.snapshot(SettingsStore.SYSTEM) }
        val originals = (listOf("settings/local.json", "settings/local.json.bak", "settings/server.json",
            "settings/server.json.bak", "store/welcome.json", "store/welcome.json.bak", "store/radio.json",
            "store/radio.json.bak", "cast/identity-seed", "cast/airplay-identity", "management-exit")
            .map { File(context.filesDir, it) } + listOf("deck.xml", "deck.xml.bak", "recovery.xml", "recovery.xml.bak")
            .map { File(context.applicationInfo.dataDir, "shared_prefs/$it") }).associateWith(::bytes)
        suspend fun guarded() {
            quiet(); check(Locale.getDefault().language == "zh")
            check(originals.all { (file, old) -> same(old, bytes(file)) }) { "Real private data changed; retain the newer data" }
            main {
                check(!host.store.current.safeMode && !host.router.canGoBack && !host.router.onboardingOpen &&
                    !host.router.settingsOpen && !moe.kiwi.thinkos.shell.KioskState.foreign &&
                    host.store.current.mode != moe.kiwi.thinkos.core.Mode.SLEEP)
                check(host.store.current.deck == originalDeck && host.settings.snapshot(SettingsStore.SYSTEM) == originalSettings)
                val media = host.media.snapshot()
                check(media["status"] == "stopped" && media["owner"] in listOf(null, "") &&
                    (media["count"] as? Number)?.toLong() == 0L)
                check(host.settings.get(SettingsStore.SYSTEM, "update.auto_install") == false)
                val updates = host.updates.status.value
                // An independent same-source fixture carries its actual APK
                // roots. Permit only that UID's entirely idle, source-less OTA
                // controller; never replace roots or relax the formal target.
                val idleFixture = context.packageName == "moe.kiwi.thinkos.regfixture" &&
                    host.system.updatePolicy.sources.isEmpty() && updates.offer == null &&
                    updates.state.phase == "IDLE" && updates.state.catalog_digest == null
                check((!updates.configured || idleFixture) && !updates.busy && !updates.checking)
            }
        }
        guarded()
        check(main { !host.updates.screensSnapshot().settings }) { "Preserve an existing native window" }
        val pending = "{\"schema\":".toByteArray()
        val previous = "owned invalid previous; never an install candidate".toByteArray()
        val failure = UpdateInstallFailure(1_000_000, "1.0.0", 2, true, "1".repeat(32), "crash_loop")
        val history = UpdateModels.writeUpdateInstallHistory(UpdateInstallHistory(1, listOf(failure))).toString().toByteArray()
        val failureRaw = UpdateModels.writeUpdateFailureDocument(UpdateFailureDocument(1, failure)).toString().toByteArray()
        val ledger = File(root, "ledger").apply { check(mkdir()) }
        val sequence = UpdateModels.writeUpdateSequenceDocument(UpdateSequenceDocument(1,
            stable = UpdateSequence(37, "a".repeat(64)))).toString().toByteArray()
        writeBytes(File(root, "pending.json"), pending); writeBytes(File(root, "previous.apk"), previous)
        writeBytes(File(root, "install-history.json"), history)
        writeBytes(File(root, "failure-1000000.json"), failureRaw)
        writeBytes(File(ledger, "sequences.json"), sequence)
        val records = UpdateInstallStore(root)
        val repair = UpdateRepairRuntime(ownedContext, records)
        val initial = UpdateIo.run { records.repairSnapshot() }
        check(initial.corruptPending && !initial.corruptHistory && initial.needed)
        val status = UpdateStatusDocument(1, "unavailable", "stable", BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE, false, false, false, false, 0, 0, emptyList(), error = "unavailable")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var inspection: Job? = null
        var dialog: UpdateDialog? = null
        var repairCalls = 0
        var inspectionCalls = 0
        var ordinaryCalls = 0
        var inFlight = false
        var negativePassed = false
        var aborted = false
        var closed = false
        var generation = 0L
        val orientations = mutableSetOf<String>()
        val observation = JSONObject().put("scope", "Native UpdateDialog and actual UpdateRepairRuntime only")
            .put("production_runtime_controller_cancel_covered", false).put("real_update_files_written", false)
            .put("install_or_rollback_called", false).put("history_clear_called", false)
        result.put("repair_ui", observation)
        fun noIsolation() {
            check(same(pending, bytes(File(root, "pending.json"))) && same(previous, bytes(File(root, "previous.apk"))))
            check(!File(root, UpdateRepairFiles.MARKER).exists() && !File(root, "install-quarantine").exists())
        }
        fun preserved() {
            check(same(history, bytes(File(root, "install-history.json"))))
            check(same(failureRaw, bytes(File(root, "failure-1000000.json"))))
            check(same(sequence, bytes(File(ledger, "sequences.json"))))
        }
        suspend fun isolated() = UpdateIo.run {
            check(repair.state.value.stage == UpdateRepairUi.Stage.DONE && repairCalls == 1)
            val snapshot = records.repairSnapshot()
            val marker = checkNotNull(snapshot.record)
            check(marker.state == "ISOLATED" && marker.bound_transaction == null)
            check(marker.current.version == BuildConfig.VERSION_NAME && marker.current.code == BuildConfig.VERSION_CODE)
            check(marker.pending == initial.token.pending && marker.previous == initial.token.previous && marker.history == initial.token.history)
            check(!File(root, "pending.json").exists() && !File(root, "previous.apk").exists())
            val quarantine = File(root, "install-quarantine")
            check(quarantine.list()?.toSet() == setOf(marker.id))
            val archive = File(quarantine, marker.id)
            check(archive.list()?.toSet() == setOf("pending.json", "previous.apk", "proof.json"))
            check(same(pending, bytes(File(archive, "pending.json"))) && same(previous, bytes(File(archive, "previous.apk"))))
            val proof = UpdateModels.readUpdateRepairRecord(UpdateJson.read(checkNotNull(bytes(File(archive, "proof.json"))), 16384))
            check(proof == marker); preserved()
            check(records.blocked(failure.code) && !snapshot.needed)
        }
        fun checkpoint(): JSONObject {
            val shown = checkNotNull(dialog)
            val decor = checkNotNull(shown.window).decorView
            val actions = mapOf("inspect" to R.string.update_repair_inspect, "isolate" to R.string.update_repair_isolate)
                .mapValues { shown.context.getString(it.value) }
            val readyText = shown.context.getString(R.string.update_repair_ready)
            val buttons = JSONArray()
            var readyVisible = false
            fun walk(view: View) {
                if (view is TextView && view.text.toString() == readyText) {
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    val bounds = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
                    val visible = Rect()
                    readyVisible = view.isShown && view.getGlobalVisibleRect(visible) && visible == bounds
                }
                if (view is TextView) actions.entries.firstOrNull { it.value == view.text.toString() }?.let { action ->
                    val xy = IntArray(2); view.getLocationOnScreen(xy)
                    val bounds = Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
                    val visible = Rect()
                    val full = view.isShown && view.getGlobalVisibleRect(visible) && visible == bounds
                    buttons.put(JSONObject().put("action", action.key).put("text", view.text).put("visible", view.isShown)
                        .put("fully_visible", full).put("enabled", view.isEnabled).put("clickable", view.isClickable)
                        .put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                }
                if (view is ViewGroup) for (index in 0 until view.childCount) walk(view.getChildAt(index))
            }
            walk(decor)
            val orientation = if (decor.width > decor.height) "landscape" else "portrait"
            if (repair.state.value.stage == UpdateRepairUi.Stage.READY && readyVisible && shown.isShowing && decor.hasWindowFocus() &&
                (0 until buttons.length()).any { buttons.getJSONObject(it).let { b ->
                    b.getString("action") == "isolate" && b.getBoolean("fully_visible") && b.getBoolean("enabled")
                } }) orientations += orientation
            return JSONObject().put("showing", shown.isShowing).put("focus", decor.hasWindowFocus())
                .put("stage", repair.state.value.stage.name).put("reason", repair.state.value.reason)
                .put("ready_text", readyText).put("ready_text_fully_visible", readyVisible)
                .put("width", decor.width).put("height", decor.height).put("orientation", orientation)
                .put("buttons", buttons).put("repair_calls", repairCalls).put("inspection_calls", inspectionCalls)
                .put("ordinary_calls", ordinaryCalls)
        }
        try {
            test("real repair evidence accepts only the owned malformed cache journal") {
                main {
                    dialog = UpdateDialog(activity, Palette.forTheme(host.store.current.theme), { status }, repair.state.map { Unit },
                        perform = { _, _ -> ordinaryCalls++; false },
                        readRepair = { repair.state.value }, inspectRepair = {
                            if (inspection?.isActive != true) {
                                inspectionCalls++
                                inspection = scope.launch { repair.inspect() }
                            }
                        }, repair = { tap ->
                            check(UpdaterProtocol.fresh(tap, SystemClock.elapsedRealtime()))
                            check(dialog?.isShowing == true && dialog?.window?.decorView?.hasWindowFocus() == true)
                            check(repair.state.value.stage == UpdateRepairUi.Stage.READY && !inFlight)
                            repairCalls++; inFlight = true
                            try { repair.isolate() } finally { inFlight = false }
                        }, interaction = { host.sensors.touch(); host.router.noteInteraction() }).apply { show() }
                }
                until { repair.state.value.stage !in setOf(UpdateRepairUi.Stage.NONE, UpdateRepairUi.Stage.CHECKING) }
                check(repair.state.value.stage == UpdateRepairUi.Stage.READY) { "Real native evidence refused: ${repair.state.value.reason}" }
                noIsolation(); preserved(); guarded()
            }
            runner.checkedSnapshot()
            atomic(File(area, "ready.json"), main { checkpoint() }.put("ready", true).put("run_id", token)
                .put("pid", Process.myPid()).put("uid", Process.myUid()).put("version", 1)
                .put("control", "repair-ui/control.json")
                .put("actions", JSONArray(listOf("checkpoint", "accessibility_click", "finish", "abort"))))
            var finished = false
            withTimeout(20 * 60 * 1000L) {
                while (!finished) {
                    guarded()
                    val command = File(area, "control.json")
                    if (command.exists()) {
                        val request = JSONObject(String(checkNotNull(bytes(command, 4096)), Charsets.UTF_8))
                        check(command.length() in 1..4096 && request.keys().asSequence().toSet() == setOf("generation", "action"))
                        val value = request.get("generation") as? Number ?: error("generation")
                        check(value.toDouble() == value.toLong().toDouble() && value.toLong() > 0)
                        if (value.toLong() > generation) {
                            generation = value.toLong()
                            val response = JSONObject().put("generation", generation)
                            when (request.getString("action")) {
                                "checkpoint" -> response.put("checkpoint", main { checkpoint() })
                                "accessibility_click" -> {
                                    test("ACTION_CLICK cannot consume native isolation touch credit") {
                                        check(main { repair.state.value.stage == UpdateRepairUi.Stage.READY && !inFlight })
                                        val before = repairCalls
                                        val automation = runner.uiAutomation
                                        try {
                                            val node = checkNotNull(automation.rootInActiveWindow)
                                            try {
                                                val label = main { activity.getString(R.string.update_repair_isolate) }
                                                val nodes = node.findAccessibilityNodeInfosByText(label)
                                                try {
                                                    val button = nodes.single { it.isClickable && it.isEnabled && it.text?.toString() == label }
                                                    response.put("action_returned", button.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                                                } finally { nodes.forEach { it.recycle() } }
                                            } finally { node.recycle() }
                                            delay(300)
                                            check(repairCalls == before && repair.state.value.stage == UpdateRepairUi.Stage.READY)
                                            noIsolation(); preserved(); guarded(); negativePassed = true
                                        } finally {
                                            automation.javaClass.getDeclaredMethod("destroy").apply { isAccessible = true }.invoke(automation)
                                            response.put("automation_destroyed", true)
                                        }
                                    }
                                    runner.checkedSnapshot()
                                }
                                "finish" -> {
                                    test("real touch isolates exact raw evidence without resetting history failure or sequence") {
                                        check(negativePassed && ordinaryCalls == 0 && orientations == setOf("portrait", "landscape"))
                                        isolated(); guarded()
                                    }
                                    runner.checkedSnapshot(); finished = true
                                }
                                "abort" -> { aborted = true; finished = true }
                                else -> error("Unknown fixed repair component action")
                            }
                            response.put("handled", true)
                            atomic(File(area, "response.json"), response)
                        }
                    }
                    if (!finished) delay(100)
                }
            }
        } finally {
            // Only this owned native Dialog and jobs are closed. No old business
            // values, raw production file or navigation/history are written back.
            withContext(NonCancellable) {
                main { dialog?.dismiss(); scope.cancel() }
                closed = runCatching { withTimeout(30_000) {
                    inspection?.join()
                    while (main { inFlight }) delay(25)
                }; true }.getOrDefault(false)
                observation.put("closed", closed).put("aborted", aborted).put("generation", generation)
                    .put("negative_passed", negativePassed).put("orientations", JSONArray(orientations.toList()))
                    .put("repair_calls", repairCalls).put("inspection_calls", inspectionCalls)
                    .put("ordinary_calls", ordinaryCalls)
                atomic(File(area, "closed.json"), JSONObject(observation.toString()).put("run_id", token))
            }
        }
        test("repair component closes its owned window and preserves real user state") {
            check(closed && !aborted); guarded()
        }
    }

    private suspend fun <T> main(block: () -> T): T = withContext(Dispatchers.Main) { block() }
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(30_000) { while (!predicate()) delay(50) }
    private fun same(a: ByteArray?, b: ByteArray?) = if (a == null) b == null else b != null && a.contentEquals(b)
    private fun bytes(file: File): ByteArray? = bytes(file, 8_388_608)
    private fun bytes(file: File, limit: Long): ByteArray? {
        check(!Files.isSymbolicLink(file.toPath()))
        if (!file.exists()) return null
        check(file.isFile && file.length() in 0..limit)
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break
                check(out.size().toLong() + n <= limit); out.write(buffer, 0, n) }
            out.toByteArray()
        }
    }
    private fun writeBytes(file: File, value: ByteArray) = FileOutputStream(file).use { it.write(value); it.fd.sync() }
    private fun atomic(file: File, value: JSONObject) {
        val temporary = File(file.parentFile, file.name + ".new")
        writeBytes(temporary, value.toString(2).toByteArray())
        check(temporary.renameTo(file))
    }
}
