package moe.kiwi.thinkos

import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Process
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.plugin.JsonDocument
import moe.kiwi.thinkos.settings.SettingsStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.lang.reflect.Method
import java.math.BigDecimal
import java.nio.file.Files
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** AndroidTest-only semantic checkpoint for factory welcome or one fixed DLNA switch.
 * Private originals stay in this process RAM; control files contain no originals.
 * No arbitrary plugin, key, Lua, path, setting value or snapshot input exists.
 */
internal object WelcomeFlowProbe {
    private val keys = listOf("onboarding.done", "onboarding.attempts", "units.system", "update.auto_install")
    private val progressKeys = setOf("schema", "step", "skipped", "gesture", "resumeOnly", "started", "region")
    private val steps = setOf("hello", "language", "region", "timezone", "wifi", "privacy", "updates", "location", "appearance", "gestures", "welcome", "done")
    private data class Override(val present: Boolean, val value: Any?)
    private data class Snapshot(val progress: Any?, val otherStore: JSONObject,
                                val edits: Map<String, Override>, val resolved: Map<String, Any>,
                                val otherLocal: JSONObject, val server: JSONObject)

    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit, dlnaSleep: Boolean = false) {
        check(BuildConfig.DEBUG && token.matches(Regex("[a-f0-9]{32}")))
        val phase = if (dlnaSleep) "dlna_sleep" else "welcome_flow"
        val controlledKeys = if (dlnaSleep) listOf("cast.enabled") else keys
        val area = File(directory, if (dlnaSleep) "dlna-sleep" else "welcome-flow")
        check(!area.exists() && area.mkdir())
        var restored = false
        var aborted = false
        var generation = 0L
        var requestedFinish = false
        var error: String? = null
        try {
            runner.startActivitySync(Intent(runner.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            val host = withContext(Dispatchers.Main) { (runner.targetContext.applicationContext as ThinkApplication).runtime }
            // Two fixed test-only cases share this one existing control loop.
            // The DLNA branch never constructs or touches the welcome checkpoint.
            val checkpoint = if (dlnaSleep) null else Checkpoint(host, runner)
            val dlna = if (dlnaSleep) DlnaCheckpoint(host, runner) else null
            withTimeout(30_000) { while (!(dlna?.loaded() ?: checkpoint!!.loaded())) delay(100) }
            if (dlnaSleep) dlna!!.capture() else checkpoint!!.capture()
            test(if (dlnaSleep) "dlna_sleep captured only the cast.enabled local override"
                 else "welcome_flow captured one factory welcome and four restorable local overrides") {
                check(dlna?.captured ?: checkpoint!!.captured)
            }
            val locale = Locale.getDefault()
            write(File(area, "ready.json"), JSONObject().put("ready", true).put("run_id", token)
                .put("uid", Process.myUid()).put("pid", Process.myPid()).put("version", 1)
                .put("locale_tag", locale.toLanguageTag()).put("region", locale.country)
                .put("settings_keys", JSONArray(controlledKeys)).put("semantic_restore", true))
            withTimeout(30 * 60 * 1000L) {
                while (true) {
                    val file = File(area, "control.json")
                    if (file.exists()) {
                        val command = read(file, 4096)
                        check(command.keys().asSequence().toSet() - setOf("generation", "action", "external_input") == emptySet<String>())
                        val number = command.get("generation") as? Number ?: kotlin.error("control")
                        check(number.toDouble() == number.toLong().toDouble() && number.toLong() > 0)
                        if (number.toLong() > generation) {
                            generation = number.toLong()
                            when (command.getString("action")) {
                                "finish" -> {
                                    check(!command.optBoolean("external_input"))
                                    requestedFinish = true
                                    try { withTimeout(30_000) {
                                        if (dlnaSleep) dlna!!.restore() else checkpoint!!.restore()
                                    }; restored = true }
                                    catch (_: Exception) { error = "semantic-restore-unconfirmed" }
                                }
                                "abort" -> { aborted = true } // no old-value restore, no Lua reload
                                else -> kotlin.error("control")
                            }
                            break
                        }
                    }
                    delay(100)
                }
            }
        } catch (_: Exception) {
            // Timeout, corrupt channel or unexpected lifecycle never authorizes
            // an old-value write. Do not retry a timed-out mutation in finally.
            error = if (requestedFinish) "semantic-restore-unconfirmed" else "capture-or-control-unavailable"
        } finally {
            val response = JSONObject().put("generation", generation).put("restored", restored)
                .put("closed", true).put("semantic_only", true).put("aborted", aborted)
            error?.let { response.put("error", it) }
            write(File(area, "response.json"), response)
            result.put(phase, JSONObject(response.toString()).put("run_id", token)
                .put("private_snapshot_persisted", false).put("raw_byte_restore", false))
        }
        test("$phase semantic restoration is explicitly confirmed, or abort retains current business state") {
            check(error == null && (restored || aborted)) { "welcome_flow closed without confirmed restoration" }
        }
    }

    /** AndroidTest-only fixed DLNA/SLEEP checkpoint. Originals remain in RAM.
     * The outer fixed driver owns touch/foreground, histories, STOP/DAY/clear,
     * listener shutdown and the existing ready/control protocol. */
    private class DlnaCheckpoint(val host: RuntimeHost, val runner: UpdateInstrumentation) {
        var captured = false
            private set
        private val key = "cast.enabled"
        private val switches = listOf("cast.enabled", "cast.airplay", "cast.miracast")
        private val guardedNames = listOf("store/radio.json", "store/radio.json.bak",
            "store/welcome.json", "store/welcome.json.bak", "cast/identity-seed",
            "cast/airplay-identity", "management-exit")
        private val absentNames = listOf("state/sleep-audio", "state/sleep-audio.bak",
            "settings/local.json.bak", "settings/server.json.bak")
        private val settingsIO = field(host, "settingsWriter") as ExecutorService
        private val context get() = runner.targetContext
        private var originalPresent = false
        private var originalValue: Boolean? = null
        private lateinit var originalSwitches: Map<String, Boolean>
        private lateinit var otherLocal: JSONObject
        private lateinit var originalServer: JSONObject
        private lateinit var originals: Map<String, ByteArray?>
        private lateinit var system: List<String>

        private fun local() = field(host.settings, "local") as JSONObject
        private fun server() = field(host.settings, "server") as JSONObject
        private fun copy(value: JSONObject) = JSONObject(value.toString())
        private fun scope(value: JSONObject): JSONObject? {
            check(!value.has(SettingsStore.SYSTEM) || value.opt(SettingsStore.SYSTEM) is JSONObject)
            return value.optJSONObject(SettingsStore.SYSTEM)
        }
        private fun withoutCast(value: JSONObject) = copy(value).apply {
            scope(this)?.let { it.remove(key); if (it.length() == 0) remove(SettingsStore.SYSTEM) }
        }
        private fun edit(value: JSONObject): Pair<Boolean, Boolean?> {
            val values = scope(value)
            val present = values?.has(key) == true
            check(!present || values!!.get(key) is Boolean)
            return present to if (present) values!!.getBoolean(key) else null
        }
        private fun settingsMatch(checkOriginal: Boolean) {
            check(same(withoutCast(local()), otherLocal) && same(server(), originalServer))
            check(switches.all { host.settings.get(SettingsStore.SYSTEM, it) == originalSwitches.getValue(it) })
            val current = edit(local())
            if (checkOriginal) check(current == (originalPresent to originalValue))
        }
        private fun systemNow() = listOf(context.resources.configuration.locales.toLanguageTags(),
            TimeZone.getDefault().id,
            Settings.Global.getString(context.contentResolver, Settings.Global.WIFI_ON).orEmpty())
        /** Main-thread public snapshots only; no reflection into mode/media/cast. */
        private fun idle() {
            val page = checkNotNull(host.router.currentPage)
            check(!host.store.current.safeMode && !host.router.onboardingOpen && !host.router.settingsOpen &&
                !host.router.canGoBack && !host.router.ownsSideways &&
                page.roles.none { it in setOf("onboarding", "settings", "panel", "cast") } &&
                !moe.kiwi.thinkos.shell.KioskState.foreign)
            val decision = checkNotNull(host.modes.decision)
            check(decision.manual == null && decision.mode != moe.kiwi.thinkos.core.Mode.SLEEP &&
                host.store.current.mode != moe.kiwi.thinkos.core.Mode.SLEEP)
            val audio = context.getSystemService(AudioManager::class.java)
            check((1..5).all { audio.getStreamVolume(it) == 0 })
            check(switches.all { host.settings.get(SettingsStore.SYSTEM, it) == originalSwitches.getValue(it) })
            val cast = host.cast.status()
            check(listOf("dlna", "airplay", "miracast").zip(switches).all { (status, setting) ->
                cast[status] == originalSwitches.getValue(setting)
            })
            val media = host.media.snapshot()
            check(media["status"] == "stopped" && media["owner"] in listOf(null, "") &&
                media["kind"] in listOf(null, "") && (media["count"] as? Number)?.toLong() == 0L)
        }
        /** Only fixed leaves, bounded and opened nonblocking/no-follow before any read. */
        private fun bytes(name: String): ByteArray? {
            check(name in guardedNames || name in absentNames)
            val file = File(context.filesDir, name)
            check(!Files.isSymbolicLink(context.filesDir.toPath()) &&
                !Files.isSymbolicLink(file.parentFile!!.toPath()))
            val fd = try {
                android.system.Os.open(file.path, android.system.OsConstants.O_RDONLY or
                    android.system.OsConstants.O_CLOEXEC or android.system.OsConstants.O_NONBLOCK or
                    android.system.OsConstants.O_NOFOLLOW, 0)
            } catch (error: android.system.ErrnoException) {
                if (error.errno == android.system.OsConstants.ENOENT) return null
                throw error
            }
            try {
                val before = android.system.Os.fstat(fd)
                check(android.system.OsConstants.S_ISREG(before.st_mode) &&
                    before.st_uid == Process.myUid() && before.st_nlink == 1L && before.st_size in 0..8_388_608L)
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(4096)
                while (true) {
                    val n = try { android.system.Os.read(fd, buffer, 0, buffer.size) }
                    catch (error: android.system.ErrnoException) {
                        if (error.errno == android.system.OsConstants.EINTR) continue
                        throw error
                    }
                    check(n >= 0 && out.size().toLong() + n <= 8_388_608L)
                    if (n == 0) break
                    out.write(buffer, 0, n)
                }
                val after = android.system.Os.fstat(fd)
                check(before.st_dev == after.st_dev && before.st_ino == after.st_ino &&
                    before.st_size == after.st_size && before.st_mtime == after.st_mtime &&
                    before.st_ctime == after.st_ctime && out.size().toLong() == before.st_size)
                return out.toByteArray()
            } finally { android.system.Os.close(fd) }
        }
        private fun noRecovery() { check(absentNames.all { bytes(it) == null }) }
        private fun filesMatch() {
            noRecovery()
            check(originals.all { (name, before) -> bytes(name).let { after ->
                if (before == null) after == null else after != null && before.contentEquals(after)
            } })
            check(systemNow() == system)
        }
        private fun persisted(name: String): JSONObject {
            val file = File(context.filesDir, "settings/$name.json")
            check(name == "local" || name == "server")
            check(!Files.isSymbolicLink(file.parentFile!!.toPath()) && !Files.isSymbolicLink(file.toPath()))
            return if (file.exists()) read(file, 1024 * 1024) else JSONObject()
        }
        /** An immediate FIFO task on the same executor as every production settings write.
         * A timed-out task is never retried and no finally block restores values. */
        private suspend fun <T> settingsBarrier(block: () -> T): T = withContext(Dispatchers.IO) {
            val future = settingsIO.submit<T> { block() }
            try { future.get(5, TimeUnit.SECONDS) }
            finally { if (!future.isDone) future.cancel(false) }
        }
        suspend fun loaded(): Boolean = withContext(Dispatchers.Main) {
            host.settings.scope(SettingsStore.SYSTEM) != null && host.router.currentPage != null && host.modes.decision != null
        }
        suspend fun capture() {
            check(!captured)
            val capturedLocal = withContext(Dispatchers.Main) {
                synchronized(host.settings) {
                    originalSwitches = switches.associateWith {
                        host.settings.get(SettingsStore.SYSTEM, it) as? Boolean ?: error("cast configuration must be Boolean")
                    }
                    check(originalSwitches.getValue(key)) // This fixed variant uses the already-enabled DLNA receiver.
                    idle()
                    val l = copy(local()); val s = copy(server())
                    val before = edit(l)
                    originalPresent = before.first; originalValue = before.second
                    originalServer = s; otherLocal = withoutCast(l)
                    l
                }
            }
            system = withContext(Dispatchers.IO) { systemNow() }
            originals = withContext(Dispatchers.IO) {
                noRecovery()
                guardedNames.associateWith(::bytes).also { saved ->
                    check(saved.getValue("cast/identity-seed")?.size == 32)
                    check(saved.values.sumOf { it?.size?.toLong() ?: 0L } <= 32L * 1024 * 1024)
                }
            }
            settingsBarrier {
                filesMatch()
                check(same(persisted("local"), capturedLocal) && same(persisted("server"), originalServer))
            }
            withContext(Dispatchers.Main) { idle(); synchronized(host.settings) { settingsMatch(true) } }
            captured = true
        }
        suspend fun restore() {
            check(captured)
            withContext(Dispatchers.Main) { idle(); synchronized(host.settings) { settingsMatch(true) } }
            withContext(Dispatchers.IO) { filesMatch() }
            // Receiver settings are read-only in this fixed variant. No set/reset
            // is needed, so even redundant original override presence is preserved.
            settingsBarrier {
                noRecovery()
                host.settings.flush() // Public persistence, never a raw original-byte write.
                val saved = persisted("local")
                check(same(withoutCast(saved), otherLocal) && edit(saved) == (originalPresent to originalValue) &&
                    same(persisted("server"), originalServer))
                filesMatch()
            }
            withContext(Dispatchers.Main) { idle(); synchronized(host.settings) { settingsMatch(true) } }
        }
    }

    private class Checkpoint(val host: RuntimeHost, val runner: UpdateInstrumentation) {
        val runtime = host.plugins
        val worker = field(runtime, "worker") as Handler
        val storeIO = field(runtime, "storeIO") as ExecutorService
        val settingsIO = field(host, "settingsWriter") as ExecutorService
        lateinit var bundle: Any
        lateinit var store: Any
        lateinit var snapshot: Snapshot
        var captured = false
        private lateinit var system: List<String>
        private val context get() = runner.targetContext
        private fun quiet() = (1..5).all { context.getSystemService(AudioManager::class.java).getStreamVolume(it) == 0 }
        private fun systemNow() = listOf(context.resources.configuration.locales.toLanguageTags(),
            TimeZone.getDefault().id, Settings.Global.getString(context.contentResolver, Settings.Global.WIFI_ON).orEmpty())
        @Suppress("UNCHECKED_CAST") private fun active() = (field(runtime, "active") as Map<String, Any>)["welcome"]
        @Suppress("UNCHECKED_CAST") private fun factory() = (field(runtime, "factory") as Map<String, Any>)["welcome"]
        private fun document() = (field(store, "document") as JsonDocument).snapshot()
        private fun clone(value: Any?) = JsonDocument.copy(value)
        private fun nonProgress(doc: JSONObject) = JSONObject(doc.toString()).apply { remove("progress") }
        private fun local() = field(host.settings, "local") as JSONObject
        private fun server() = field(host.settings, "server") as JSONObject
        private fun withoutKeys(doc: JSONObject) = JSONObject(doc.toString()).apply {
            optJSONObject("system")?.let { scope -> keys.forEach(scope::remove); if (scope.length() == 0) remove("system") }
        }
        private fun edits(): Map<String, Override> = synchronized(host.settings) {
            val scope = local().optJSONObject("system")
            keys.associateWith { key -> Override(scope?.has(key) == true, clone(scope?.opt(key))) }
        }
        suspend fun loaded() = onWorker { active()?.let { field(it, "lua") != null && field(it, "disabled") == false } == true }
        suspend fun capture() {
            withContext(Dispatchers.Main) {
                check(!host.router.onboardingOpen && !host.store.current.safeMode && quiet())
                check((host.settings.get("system", "onboarding.done") as Number).toInt() == 1)
                val media = host.media.snapshot()
                check(media["status"] == "stopped" && media["owner"] == "" && media["kind"] == "" &&
                    (media["count"] as Number).toInt() == 0)
            }
            system = systemNow()
            val defaults = withContext(Dispatchers.IO) { context.assets.open("settings/system.json").use { JSONObject(it.bufferedReader().readText()).getJSONObject("fields") } }
            snapshot = onWorker {
                val instance = checkNotNull(active())
                bundle = checkNotNull(factory()); check(field(instance, "bundle") === bundle)
                store = checkNotNull(field(instance, "store"))
                invoke(store, "flush")
                val doc = document()
                synchronized(host.settings) {
                    val before = edits()
                    // Public setters normalize redundant defaults. Refuse such a
                    // baseline before UI starts instead of silently losing presence.
                    before.forEach { (key, value) -> if (value.present) {
                        val below = server().optJSONObject("system")?.opt(key) ?: defaults.getJSONObject(key).get("default")
                        check(!same(value.value, below))
                    } }
                    Snapshot(clone(doc.opt("progress")), nonProgress(doc), before,
                        keys.associateWith { host.settings.get("system", it) }, withoutKeys(local()), JSONObject(server().toString()))
                }
            }
            onIO(storeIO) { Unit }
            onWorker { check(same(document().opt("progress"), snapshot.progress) && editsSame(edits(), snapshot.edits)); check(field(store, "dirty") == false) }
            captured = true
        }
        private fun editsSame(a: Map<String, Override>, b: Map<String, Override>) = keys.all {
            a.getValue(it).present == b.getValue(it).present && same(a.getValue(it).value, b.getValue(it).value)
        }
        private fun guard() {
            check(quiet() && systemNow() == system)
            check(active()?.let { field(it, "bundle") === bundle && field(it, "store") === store } == true && factory() === bundle)
            check(same(nonProgress(document()), snapshot.otherStore))
            synchronized(host.settings) { check(same(withoutKeys(local()), snapshot.otherLocal) && same(server(), snapshot.server)) }
        }
        suspend fun restore() {
            check(captured)
            withContext(Dispatchers.Main) { check(!host.router.onboardingOpen && !host.store.current.safeMode) }
            var stopped = false
            try {
                val last = onWorker {
                    guard()
                    val progress = document().optJSONObject("progress") ?: error("progress")
                    check(progress.keys().asSequence().toSet().all { it in progressKeys } && progress.optString("step") in steps)
                    check(progress.optInt("gesture") in 1..7 && progress.optInt("schema") == 1)
                    val current = edits()
                    val instance = checkNotNull(active()); stopped = true
                    invoke(instance, "terminate", true)
                    invoke(store, "set", "progress", null) // clear merge semantics first
                    if (snapshot.progress != null && snapshot.progress !== JSONObject.NULL) invoke(store, "set", "progress", clone(snapshot.progress))
                    invoke(store, "flush")
                    current
                }
                onIO(storeIO) { check(same(readStore().opt("progress"), snapshot.progress) && same(nonProgress(readStore()), snapshot.otherStore)) }
                onWorker { check(field(store, "dirty") == false) }
                withContext(Dispatchers.Main) {
                    synchronized(host.settings) {
                        check(editsSame(edits(), last) && same(withoutKeys(local()), snapshot.otherLocal) && same(server(), snapshot.server))
                        keys.filter { !snapshot.edits.getValue(it).present }.forEach { host.settings.reset(SettingsStore.SYSTEM, it) }
                        val present = snapshot.edits.filterValues { it.present }.mapValues { it.value.value }
                        if (present.isNotEmpty()) host.settings.setMany(SettingsStore.SYSTEM, present)
                        check(editsSame(edits(), snapshot.edits))
                    }
                }
                onIO(settingsIO) {
                    host.settings.flush()
                    val persisted = readSettings()
                    check(same(withoutKeys(persisted), snapshot.otherLocal))
                    val scope = persisted.optJSONObject("system")
                    check(keys.all { key ->
                        val original = snapshot.edits.getValue(key)
                        (scope?.has(key) == true) == original.present && (!original.present || same(scope?.opt(key), original.value))
                    })
                }
                onWorker {
                    guard()
                    check(same(document().opt("progress"), snapshot.progress))
                    check(invoke(runtime, "activate", bundle, active()) == true)
                    stopped = false
                }
                onWorker {
                    check(field(checkNotNull(active()), "lua") != null && editsSame(edits(), snapshot.edits))
                    check(keys.all { same(host.settings.get("system", it), snapshot.resolved.getValue(it)) })
                    check(same(document().opt("progress"), snapshot.progress))
                }
                withContext(Dispatchers.Main) { check(!host.router.onboardingOpen && quiet() && systemNow() == system) }
            } finally {
                // After an authorized finish partially stopped this one plugin,
                // resume only the same factory bundle. Never mutate more state.
                if (stopped) withContext(NonCancellable) {
                    // onWorker retains its own five-second bound; never retry old-value writes.
                    runCatching { onWorker { if (factory() === bundle && active()?.let { field(it, "bundle") === bundle } == true) invoke(runtime, "activate", bundle, active()) } }
                }
            }
        }
        private fun readStore(): JSONObject = read(File(context.filesDir, "store/welcome.json"), 256 * 1024)
        private fun readSettings(): JSONObject = read(File(context.filesDir, "settings/local.json"), 1024 * 1024)
        private suspend fun <T> onWorker(block: () -> T): T = withTimeout(5000) {
            suspendCancellableCoroutine { continuation ->
                val task = Runnable { if (continuation.isActive) try { val value = block(); if (continuation.isActive) continuation.resume(value) }
                    catch (error: Exception) { if (continuation.isActive) continuation.resumeWithException(error) } }
                if (!worker.post(task)) continuation.resumeWithException(IllegalStateException("worker unavailable"))
                continuation.invokeOnCancellation { worker.removeCallbacks(task) }
            }
        }
        private suspend fun <T> onIO(executor: ExecutorService, block: () -> T): T = withContext(Dispatchers.IO) {
            val future = executor.submit<T> { block() }
            try { future.get(5, TimeUnit.SECONDS) } finally { if (!future.isDone) future.cancel(false) }
        }
    }

    private fun field(owner: Any, name: String): Any? = owner.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(owner)
    private fun invoke(owner: Any, name: String, vararg args: Any?): Any? {
        val method: Method = owner.javaClass.declaredMethods.single { it.name == name && it.parameterTypes.size == args.size }.apply { isAccessible = true }
        return method.invoke(owner, *args)
    }
    private fun same(a: Any?, b: Any?, depth: Int = 0): Boolean {
        check(depth <= 32)
        if (a == null || a === JSONObject.NULL) return b == null || b === JSONObject.NULL
        if (a is JSONObject && b is JSONObject) return a.keys().asSequence().toSet() == b.keys().asSequence().toSet() && a.keys().asSequence().toSet().all { same(a.opt(it), b.opt(it), depth + 1) }
        if (a is JSONArray && b is JSONArray) return a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it), depth + 1) }
        if (a is Number && b is Number) return BigDecimal(a.toString()).compareTo(BigDecimal(b.toString())) == 0
        return a == b
    }
    private fun read(file: File, limit: Int): JSONObject {
        check(!Files.isSymbolicLink(file.toPath()) && file.isFile && file.length() in 1..limit.toLong())
        return file.inputStream().use { input ->
            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
            while (true) { val n = input.read(buffer); if (n < 0) break; check(out.size() + n <= limit); out.write(buffer, 0, n) }
            JSONObject(out.toString("UTF-8"))
        }
    }
    private fun write(file: File, value: JSONObject) {
        val bytes = value.toString().toByteArray(Charsets.UTF_8); check(bytes.size <= 65536)
        val next = File(file.path + ".new")
        check(!Files.isSymbolicLink(file.toPath()) && !next.exists())
        FileOutputStream(next).use { it.write(bytes); it.fd.sync() }
        check(next.renameTo(file))
    }
}
