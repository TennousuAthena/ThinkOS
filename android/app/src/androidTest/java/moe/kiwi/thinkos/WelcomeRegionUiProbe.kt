package moe.kiwi.thinkos

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.TextView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.i18n.LocaleChain
import moe.kiwi.thinkos.i18n.StringTable
import moe.kiwi.thinkos.plugin.JsonDocument
import moe.kiwi.thinkos.plugin.LuaBootstrap
import moe.kiwi.thinkos.plugin.NativeLua
import moe.kiwi.thinkos.settings.AndroidSettingsPersistence
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.SystemSettings
import moe.kiwi.thinkos.settings.TimezoneSource
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.form.FormHost
import moe.kiwi.thinkos.ui.form.FormView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Test APK only. Real bundled Lua/bootstrap and native FormView/RegionPicker, with a
 * separate cache SettingsStore and progress. This is a component fixture, NOT the
 * ordinary RuntimeHost, public permission/touch gate, capability or whole-guide path.
 * Existing carrier RuntimeHost is never replaced, reset, stopped or used by this VM.
 */
internal object WelcomeRegionUiProbe {
    private const val SCOPE = "cache_lua_native_form_only"
    private val immutable = listOf("settings/local.json", "settings/local.json.bak", "settings/server.json",
        "store/welcome.json", "store/welcome.json.bak", "cast/identity-seed", "cast/airplay-identity", "management-exit")

    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit) {
        val context = runner.targetContext
        check(context.packageName == "moe.kiwi.thinkos.regfixture" &&
            runner.context.packageName == "moe.kiwi.thinkos.regfixture.test") { "Independent fixture UID only" }
        check(token.matches(Regex("[a-f0-9]{32}")))
        check(Locale.getDefault().language == "zh" && LocaleChain.script(Locale.getDefault()) == "Hans")
        val area = File(directory, "welcome-region")
        check(!area.exists() && area.mkdirs()) { "Use a fresh owned run_id" }
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun quiet() = check((1..5).all { audio.getStreamVolume(it) == 0 })
        fun hashes() = immutable.associateWith { path -> File(context.filesDir, path).let {
            if (!it.exists()) null else { check(it.isFile && it.length() <= 8 * 1024 * 1024); sha(it.readBytes()) }
        } }
        val before = hashes()
        fun untouched() { quiet(); check(hashes() == before) { "Real settings/welcome/identity changed; do not restore over it" } }
        val source = asset(context, "plugins/welcome/main.lua", 64 * 1024)
        val bootstrap = LuaBootstrap.code
        val sourceSha = sha(source)
        val bootstrapSha = sha(bootstrap.toByteArray(Charsets.UTF_8))
        val strings = StringTable(listOf("zh-Hans", "en").associateWith { language ->
            StringTable.parse(String(asset(context, "plugins/welcome/i18n/$language.json", 64 * 1024), Charsets.UTF_8))
                ?: error("Invalid actual welcome strings")
        })
        val settings = SettingsStore(AndroidSettingsPersistence(File(area, "settings")), TimezoneSource)
        settings.register(SystemSettings.parse(String(asset(context, "settings/system.json", 256 * 1024), Charsets.UTF_8), TimezoneSource))
        settings.set(SettingsStore.SYSTEM, "units.system", "imperial")
        settings.flush()
        val progress = JsonDocument(JSONObject().put("progress", JSONObject().put("schema", 1).put("step", "region")
            .put("region", "US").put("started", true).put("gesture", 1).put("resumeOnly", false).put("skipped", JSONObject())))
        atomic(File(area, "progress.json"), progress.snapshot())
        val state = AtomicReference(JSONObject())
        val failure = AtomicReference<Throwable?>(null)
        val unitsWrites = AtomicInteger()
        val regionEvents = AtomicInteger()
        val mutators = AtomicInteger()
        val nativeEvents = JSONArray()
        val stopped = AtomicBoolean(false)
        val thread = HandlerThread("WelcomeRegionFixture", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val worker = Handler(thread.looper)
        val mainHandler = Handler(android.os.Looper.getMainLooper())
        lateinit var vm: NativeLua
        var vmCreated = false // Accessed only on the dedicated Lua worker.
        var form: FormView? = null
        var dialog: Dialog? = null
        var finished = false
        var aborted = false
        val observation = JSONObject().put("scope", SCOPE).put("source_sha256", sourceSha).put("bootstrap_sha256", bootstrapSha)
            .put("real_settings_written", false).put("ordinary_runtime_authorization_qualified", false)
            .put("capability_success_qualified", false).put("whole_guide_qualified", false)
            .put("capability_stubs", JSONArray(listOf("device.canSetLocale=false", "device.canSetTimeZone=false", "device.get=offline", "tz.zones=[]")))
        result.put("welcome_region_ui", observation)
        suspend fun <T> onWorker(block: () -> T): T = withTimeout(5000) {
            suspendCancellableCoroutine { continuation ->
                val task = Runnable { if (continuation.isActive) try { continuation.resume(block()) }
                    catch (error: Throwable) { continuation.resumeWithException(error) } }
                check(worker.post(task)); continuation.invokeOnCancellation { worker.removeCallbacks(task) }
            }
        }
        fun checked(step: NativeLua.Step) { check(!step.failed) { step.message ?: "Actual native Lua failed" } }
        fun applyState(value: JSONObject) {
            state.set(JSONObject(value.toString()))
            mainHandler.post {
                if (!stopped.get()) runCatching {
                    form?.apply {
                        setHeader(value.optString("title"), "")
                        setSetup(true, "setup", value.optString("subtitle"), value.optString("primary"), value.optString("secondary"), value.optString("back"))
                        setFields(value.optJSONArray("fields") ?: JSONArray())
                    }
                }.onFailure { failure.compareAndSet(null, it) }
            }
        }
        suspend fun checkpoint(): JSONObject {
            failure.get()?.let { throw it }
            val saved = onWorker { progress.snapshot().getJSONObject("progress") }
            return withContext(Dispatchers.Main) {
                val root = form ?: error("No owned native form")
                val texts = JSONArray()
                fun collect(view: View, clickableAncestor: Boolean) {
                    val clickable = clickableAncestor || view.isClickable
                    if (view is TextView && view.text.isNotEmpty()) {
                        val position = IntArray(2); view.getLocationOnScreen(position)
                        val bounds = Rect(position[0], position[1], position[0] + view.width, position[1] + view.height)
                        val clip = Rect(); val visible = view.isShown && view.getGlobalVisibleRect(clip) && clip == bounds
                        texts.put(JSONObject().put("text", view.text.toString().take(256)).put("bounds", JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                            .put("clickable", clickable).put("visible", visible))
                    }
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i), clickable)
                }
                collect(root, false)
                val fields = state.get().optJSONArray("fields") ?: JSONArray()
                val allowsAll = (0 until fields.length()).any { fields.optJSONObject(it)?.let { field ->
                    field.optString("source") == "region" && field.optBoolean("allowEmpty", false)
                } == true }
                JSONObject().put("scope", SCOPE).put("source_sha256", sourceSha).put("bootstrap_sha256", bootstrapSha)
                    .put("showing", dialog?.isShowing == true && root.isShown).put("focus", root.hasWindowFocus())
                    .put("width", root.width).put("height", root.height).put("step", state.get().optString("step"))
                    .put("region", saved.optString("region")).put("units", settings.get(SettingsStore.SYSTEM, "units.system"))
                    .put("units_writes", unitsWrites.get()).put("region_events", regionEvents.get()).put("device_mutator_calls", mutators.get())
                    .put("all_regions_allowed", allowsAll).put("texts", texts)
            }
        }
        suspend fun proof(): JSONObject = checkpoint().also { value ->
            check(value.getString("step") == "wifi" && value.getString("region") == "CN")
            check(value.getString("units") == "imperial" && value.getInt("units_writes") == 0) { "Non-US/LR/MM region replaced imperial preference" }
            check(value.getInt("region_events") == 1 && value.getInt("device_mutator_calls") == 0)
            check(value.getBoolean("showing") && value.getBoolean("focus") && value.getInt("width") > 0 && value.getInt("height") > 0)
        }
        suspend fun checkedTest(name: String, block: suspend () -> Unit) {
            test(name) { untouched(); block(); untouched() }; runner.checkedSnapshot()
        }
        try {
            checkedTest("isolated actual welcome Lua starts at region with an existing imperial preference") {
                val activity = runner.startActivitySync(Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) as MainActivity
                withContext(Dispatchers.Main) {
                    form = FormView(activity, object : FormHost {
                        override fun emit(name: String, args: List<Any?>) {
                            if (stopped.get()) return
                            if (name != "form.change" || args != listOf("region", "CN")) {
                                failure.compareAndSet(null, IllegalStateException("Unexpected component UI event")); return
                            }
                            regionEvents.incrementAndGet()
                            synchronized(nativeEvents) { nativeEvents.put(JSONObject().put("name", name).put("key", "region").put("value", "CN")) }
                            worker.post { if (!stopped.get()) runCatching { checked(vm.dispatch(name, args.toTypedArray())) }.onFailure { failure.compareAndSet(null, it) } }
                        }
                        override fun done() { failure.compareAndSet(null, IllegalStateException("Unexpected form done")) }
                        override fun interacted() = Unit
                        override fun preview(scope: String, key: String, kind: String, value: Double?) { failure.compareAndSet(null, IllegalStateException("Unexpected preview")) }
                    }).apply { colors(Palette.DARK) }
                    dialog = Dialog(activity).apply {
                        requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(form!!)
                        setCancelable(false); setCanceledOnTouchOutside(false)
                        setOnKeyListener { _, key, event -> key == KeyEvent.KEYCODE_BACK && if (event.action == KeyEvent.ACTION_UP) { form!!.back(); true } else true }
                        show(); window!!.setBackgroundDrawable(ColorDrawable(Palette.DARK.bg))
                        window!!.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        window!!.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    }
                }
                onWorker {
                    vm = NativeLua { method, args, task ->
                        when (method) {
                            "config" -> emptyMap<String, Any?>()
                            "store.get" -> { check(args.single() == "progress"); JsonDocument.toHost(progress.value.opt("progress")) }
                            "store.set" -> { check(args[0] == "progress"); progress.set("progress", JsonDocument.fromHost(args[1])); atomic(File(area, "progress.json"), progress.snapshot()); null }
                            "state.set" -> { applyState(JsonDocument.fromHost(args.single()) as JSONObject); null }
                            "state.get" -> JsonDocument.toHost(state.get())
                            "settings.set" -> {
                                check(args[0] == "system" && args[1] == "units.system") { "Unexpected cache settings write" }
                                unitsWrites.incrementAndGet()
                                mapOf("value" to settings.set(SettingsStore.SYSTEM, "units.system", args[2]))
                            }
                            "locale" -> Locale.getDefault().let { mapOf("tag" to it.toLanguageTag(), "language" to it.language, "script" to LocaleChain.script(it), "region" to it.country, "rtl" to LocaleChain.isRtl(it)) }
                            "i18n.t" -> strings.format(args[0] as String, Locale.getDefault(), (args.getOrNull(1) as? Map<*, *>)?.entries?.associate { it.key as String to it.value } ?: emptyMap()) ?: error("Missing actual welcome string")
                            "device.canSetLocale", "device.canSetTimeZone" -> {
                                check(task > 0)
                                worker.post { if (!stopped.get()) runCatching { checked(vm.resume(task, arrayOf(false))) }.onFailure { failure.compareAndSet(null, it) } }
                                null
                            }
                            "device.get" -> mapOf("online" to false, "wifi" to mapOf("connected" to false))
                            "tz.zones" -> emptyList<Any>()
                            "back.intercept" -> { check(args.single() is Boolean); null }
                            else -> { if (method.startsWith("device.")) mutators.incrementAndGet(); error("Unsupported fixture capability: $method") }
                        }
                    }
                    vmCreated = true
                    vm.run(bootstrap, "actual-lua-bootstrap")
                    vm.run(String(source, Charsets.UTF_8), "@plugins/welcome/main.lua")
                    vm.bindEntries()
                    checked(vm.dispatch("page.enter", arrayOf("setup", "", mapOf("mode" to "resume"))))
                }
                withTimeout(5000) { while (checkpoint().let { !it.getBoolean("showing") || !it.getBoolean("focus") || it.getInt("width") <= 0 }) delay(50) }
                checkpoint().also {
                    check(it.getString("step") == "region" && it.getString("region") == "US" && it.getString("units") == "imperial")
                    check(it.getInt("units_writes") == 0 && it.getInt("region_events") == 0)
                    observation.put("initial", it)
                }
            }
            atomic(File(area, "ready.json"), JSONObject().put("ready", true).put("run_id", token).put("pid", Process.myPid()).put("uid", Process.myUid())
                .put("version", 1).put("scope", SCOPE).put("source_sha256", sourceSha).put("bootstrap_sha256", bootstrapSha))
            var generation = 0L
            withTimeout(3 * 60 * 1000L) {
                while (!finished && !aborted) {
                    untouched(); failure.get()?.let { throw it }
                    val file = File(area, "control.json")
                    val command = if (file.isFile && file.length() in 1..4096) runCatching { JSONObject(file.readText()) }.getOrNull() else null
                    if (command != null && command.optLong("generation") > generation) {
                        generation = command.getLong("generation")
                        val response = JSONObject().put("generation", generation)
                        try {
                            when (command.getString("action")) {
                                "checkpoint" -> response.put("checkpoint", checkpoint())
                                "finish" -> { checkedTest("real native CN selection keeps imperial units and advances actual Lua to Wi-Fi without privileged capability calls") {
                                    observation.put("proof", proof()).put("events", JSONArray(nativeEvents.toString()))
                                }; finished = true }
                                "abort" -> aborted = true
                                else -> error("Unknown fixed control")
                            }
                            response.put("handled", true)
                        } catch (error: Throwable) { response.put("error", error.toString()); throw error }
                        finally { atomic(File(area, "response.json"), response) }
                    }
                    delay(100)
                }
            }
            check(!aborted) { "Owned component run aborted" }
            checkedTest("cache-only region fixture leaves real settings and welcome state untouched") { settings.flush() }
        } finally {
            withContext(NonCancellable) {
                stopped.set(true)
                withContext(Dispatchers.Main) { dialog?.dismiss(); dialog = null; form = null }
                val cleanup = runCatching {
                    onWorker {
                        if (vmCreated) {
                            try { checked(vm.dispatch("page.leave")); checked(vm.dispatch("stop")) } finally { vm.close() }
                        }
                        settings.flush()
                    }
                }
                worker.removeCallbacksAndMessages(null)
                thread.quitSafely()
                withContext(Dispatchers.IO) { thread.join(5000) }
                val closed = !thread.isAlive && cleanup.isSuccess
                observation.put("closed", closed).put("aborted", aborted).put("private_files_unchanged", hashes() == before)
                atomic(File(area, "closed.json"), JSONObject().put("run_id", token).put("pid", Process.myPid()).put("closed", closed)
                    .put("aborted", aborted).put("scope", SCOPE).put("semantic_restore_needed", false))
                check(closed) { "Owned native Lua worker did not close cleanly" }
                cleanup.getOrThrow()
            }
        }
    }

    private fun asset(context: Context, path: String, limit: Int): ByteArray = context.assets.open(path).use { input ->
        val buffer = ByteArray(limit + 1)
        var size = 0
        while (size < buffer.size) {
            val count = input.read(buffer, size, buffer.size - size)
            if (count < 0) break
            check(count > 0)
            size += count
        }
        check(size in 1..limit)
        buffer.copyOf(size)
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun atomic(file: File, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8); check(bytes.size <= 256 * 1024)
        val pending = File(file.parentFile, file.name + ".next")
        FileOutputStream(pending).use { it.write(bytes); it.fd.sync() }
        check(pending.renameTo(file))
    }
}
