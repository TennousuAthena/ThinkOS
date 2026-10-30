package moe.kiwi.thinkos.plugin

import moe.kiwi.thinkos.core.WallClock
import android.content.Context
import android.os.FileObserver
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.i18n.Formats
import moe.kiwi.thinkos.i18n.Labels
import moe.kiwi.thinkos.i18n.LocaleChain
import moe.kiwi.thinkos.i18n.StringTable
import moe.kiwi.thinkos.i18n.SystemStrings
import moe.kiwi.thinkos.net.PluginHttp
import moe.kiwi.thinkos.settings.SettingError
import moe.kiwi.thinkos.settings.SettingField
import moe.kiwi.thinkos.settings.SettingsDescriber
import moe.kiwi.thinkos.settings.SettingsScope
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.TimezoneSource
import moe.kiwi.thinkos.tz.ZoneFormat
import moe.kiwi.thinkos.tz.ZoneNames
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Lua is confined to PluginThread. Network and durable writes never block that thread. */
class PluginRuntime(context: Context, private val callbacks: Callbacks, private val settings: SettingsStore) {
    interface Callbacks {
        fun onCatalog(pages: List<PluginPage>, widgets: List<PluginWidget>)
        fun onState(pluginId: String, state: JSONObject)
        fun onFocus(pageKey: String, live: Boolean, release: Boolean)
        fun onError(pluginId: String, message: String)
    }
    private val app = context.applicationContext
    private val thread = HandlerThread("ThinkOS-Plugin").apply { start() }
    private val worker = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4) { Thread(it, "ThinkOS-PluginIO") }
    private val astroWorker = Executors.newSingleThreadExecutor { Thread(it, "ThinkOS-Astro") }
    private val storeIO = Executors.newSingleThreadExecutor { Thread(it, "ThinkOS-StoreIO") }
    private val root = File(app.filesDir, "plugins")
    private val dev = File(app.filesDir, "dev-plugins")
    private val active = linkedMapOf<String, Instance>()
    private val pending = linkedMapOf<String, Instance>()
    private val factory = linkedMapOf<String, Bundle>()
    private val stores = mutableMapOf<String, LocalStore>()
    private val devDigests = mutableMapOf<String, String>()
    private val devFiles = mutableMapOf<String, String>()
    private val previousDirectories = mutableMapOf<String, File>()
    private var observer: FileObserver? = null
    private var crashTracker: NativeCrashTracker? = null
    private var loading = false
    private var currentAttention: Array<Any?> = arrayOf("AWAKE")
    @Volatile private var closed = false
    @Volatile private var safe = false
    @Volatile private var diagnosticSnapshot = emptyList<PluginDiagnostic>()
    /** Immutable copy of every active plugin's translated strings, safe to read from the UI thread. */
    @Volatile private var stringTables = emptyMap<String, StringTable>()
    fun text(pluginId: String, key: String): String? = stringTables[pluginId]?.format(key, Locale.getDefault())
    @Volatile private var imageOwners = emptyMap<String, moe.kiwi.thinkos.image.ImageOwner>()
    internal fun imageOwner(pluginId: String) = imageOwners[pluginId]
    private val refreshDev = Runnable { scanDev() }
    private val systemStrings = SystemStrings(app.resources, app.packageName)
    private val labels = Labels(
        system = systemStrings::get,
        plugin = { id, key, locale, params -> (active[id] ?: pending[id])?.strings?.format(key, locale, params) },
    )
    private val describer = SettingsDescriber(settings, labels) { scope, locale ->
        if (scope == SettingsStore.SYSTEM) systemStrings.get("setting.system.name") ?: "ThinkOS"
        else (active[scope] ?: pending[scope])?.let { labels.resolve(scope, it.bundle.manifest.optString("name", scope), locale) } ?: scope
    }
    private val location = moe.kiwi.thinkos.location.LocationProvider(settings)
    private val astronomy = AstroHost { location.get().toMap() }
    /** Settings changed (Server delivery or the device's own settings page): tell the plugin that owns them. */
    private val settingsListener = SettingsStore.Listener { scope, keys ->
        if (scope != SettingsStore.SYSTEM) worker.post { if (!closed) active[scope]?.let(::configChanged) }
        else if (keys.any { it.startsWith("location.") }) {
            event(null, "location", JSONArray())
        }
    }
    init { settings.addListener(settingsListener) }

    private data class Bundle(val directory: File, val manifest: JSONObject, val digest: String) {
        val id: String get() = manifest.getString("id")
        val version: String get() = manifest.getString("version")
    }
    private inner class LocalStore(val id: String) {
        val file = File(app.filesDir, "store/$id.json")
        val document = JsonDocument(loadStore(file))
        var dirty = false
        private val delayed = Runnable { flush() }
        fun set(key: String, value: Any?) {
            require(key.length in 1..128)
            document.set(key, JsonDocument.fromHost(value))
            dirty = true
            worker.removeCallbacks(delayed)
            worker.postDelayed(delayed, 2_000)
        }
        fun flush() {
            worker.removeCallbacks(delayed)
            if (!dirty) return
            val snapshot = document.snapshot()
            dirty = false
            storeIO.execute {
                runCatching {
                    writeAtomic(file, snapshot.toString().toByteArray(Charsets.UTF_8))
                    if (BuildConfig.DEBUG) Log.d(TAG, "[store:$id] persisted coalesced snapshot")
                }
                    .onFailure { error -> worker.post { dirty = true; if (!closed) worker.postDelayed(delayed, 5_000); report(id, Exception("store write failed", error)) } }
            }
        }
    }
    private fun loadStore(file: File): JSONObject = try {
        // AtomicFile.openRead restores .bak after an interrupted fsync/write.
        // Reading the base File directly can parse a partially written document.
        val bytes = AtomicFile(file).openRead().use { it.readBytesLimited(MAX_STATE.toLong()) }
        parseObject(String(bytes, Charsets.UTF_8))
    } catch (_: java.io.FileNotFoundException) {
        JSONObject()
    } catch (error: Exception) {
        report(file.nameWithoutExtension, Exception("store recovery failed", error))
        JSONObject()
    }
    private inner class Instance(val bundle: Bundle, previous: Instance? = null) {
        val imageOwner = moe.kiwi.thinkos.image.ImageOwner(bundle.directory,
            arrayValues(bundle.manifest.optJSONArray("permissions")).map(Any::toString).toSet())
        var lua: NativeLua? = null
        val state: JsonDocument = previous?.state ?: JsonDocument()
        val strings: StringTable = StringTable.load(bundle.directory)
        /** A manifest string that may be an `@key` reference into this plugin's own tables. */
        fun label(text: String): String = when {
            text.startsWith("@@") -> text.substring(1)
            text.startsWith("@") -> strings.format(text.substring(1), Locale.getDefault()) ?: text.substring(1)
            else -> text
        }
        val scope: SettingsScope? = bundle.manifest.optJSONObject("settings")?.let { declared ->
            SettingsScope(bundle.id, bundle.manifest.optString("name", bundle.id), linkedMapOf<String, SettingField>().also { fields ->
                declared.keys().forEach { key -> fields[key] = SettingField.parse(key, declared.getJSONObject(key), false, TimezoneSource) }
            })
        }
        val health: PluginHealth = previous?.health ?: PluginHealth()
        val store = stores.getOrPut(bundle.id) { LocalStore(bundle.id) }
        var publishPending = false
        var error: String? = null
        var disabled = false
        var startTask = 0
        val timers = mutableMapOf<Int, Runnable>()
        val awaits = mutableMapOf<Int, Future<*>?>()
        val pages = arrayObjects(bundle.manifest.optJSONArray("pages")).map { page ->
            val view = readObject(resolve(bundle.directory, page.getString("view")))
            val variants = page.optJSONArray("variants") ?: view.optJSONArray("variants")
            PluginPage(bundle.id, page.getString("id"), label(page.optString("title", page.getString("id"))), view,
                arrayValues(variants).map { if (it is JSONObject) it.getString("id") else it.toString() },
                arrayValues(page.optJSONArray("roles")).map(Any::toString), page.optInt("priority"), page.optBoolean("live"))
        }
        val widgets = arrayObjects(bundle.manifest.optJSONArray("widgets")).map { widget ->
            PluginWidget(bundle.id, widget.getString("id"), readObject(resolve(bundle.directory, widget.getString("view"))),
                widget.optString("opens").takeIf(String::isNotBlank))
        }
        fun terminate(sendStop: Boolean) {
            if (sendStop) runCatching { slice(this) { lua?.dispatch("stop") } }
            timers.values.forEach(worker::removeCallbacks)
            timers.clear()
            awaits.values.forEach { it?.cancel(true) }
            awaits.clear()
            slice(this) { lua?.close() }
            lua = null
            store.flush()
        }
    }

    fun start(safeMode: Boolean = false) {
        safe = safeMode
        worker.post {
            root.mkdirs()
            dev.mkdirs()
            // The old debug transport is retired; repush source directories with plugin-push.sh.
            dev.listFiles()?.filter { it.isFile && it.extension == "tpk" }?.forEach(File::delete)
            // Recover an interrupted debug directory replacement before catalog discovery.
            dev.listFiles()?.filter { it.name.startsWith(".previous-") }?.forEach { backup ->
                val id = backup.name.removePrefix(".previous-")
                if (id.matches(Regex("[a-z][a-z0-9-]{0,63}"))) runCatching {
                    val published = File(dev, id)
                    if (published.exists()) PluginFiles.deleteTree(backup)
                    else {
                        require(java.nio.file.Files.isDirectory(backup.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS))
                        check(backup.renameTo(published))
                    }
                }.onFailure { report(id, Exception("development directory recovery failed", it)) }
            }
            crashTracker = runCatching { NativeCrashTracker(app) }.getOrElse { report("runtime", it as Exception); null }
            crashTracker?.recoveredPlugin?.let { Log.w(TAG, "Unclean native execution attributed to plugin $it") }
            loading = true
            try {
                val catalog = app.assets.open("plugins/index.json").use {
                    parseObject(String(it.readBytesLimited((256 * 1024).toLong()), Charsets.UTF_8))
                }
                PluginSchema.validateCatalog(catalog)
                val builtins = catalog.getJSONArray("plugins")
                require(builtins.length() <= 32) { "too many bundled plugins" }
                val builtinIds = mutableSetOf<String>()
                arrayObjects(builtins).forEach { entry ->
                    val id = entry.optString("id", "invalid")
                    try {
                        val index = PluginFiles.parse(entry)
                        require(builtinIds.add(index.id)) { "duplicate bundled plugin id" }
                        val bundle = install(index) { path -> app.assets.open("plugins/${index.id}/$path") }
                        factory[bundle.id] = bundle
                        if (!safe || bundle.id == "clock") activate(bundle)
                    } catch (e: Exception) { report(id, e) }
                }
                loading = false
                persistInstalled(); cleanupVersions()
                publishCatalog()
                if (BuildConfig.DEBUG && !safe) {
                    @Suppress("DEPRECATION")
                    val watcher = object : FileObserver(dev.absolutePath, MOVED_TO or DELETE) {
                        override fun onEvent(event: Int, path: String?) {
                            if (path != null && !path.startsWith(".") && !closed) {
                                worker.removeCallbacks(refreshDev)
                                worker.postDelayed(refreshDev, 150)
                            }
                        }
                    }
                    observer = watcher
                    watcher.startWatching()
                    scanDev()
                }
            } catch (e: Exception) { loading = false; report("runtime", e); publishCatalog() }
        }
    }
    fun event(pluginId: String?, name: String, args: JSONArray = JSONArray()) {
        val values = (JsonDocument.toHost(args) as List<*>).toTypedArray()
        worker.post {
            if (pluginId == null && name == "attention") currentAttention = values
            val targets = if (pluginId == null) active.values.toList() else listOfNotNull(active[pluginId])
            targets.forEach { instance ->
                if (name in setOf("presence", "gesture") && !arrayValues(instance.bundle.manifest.optJSONArray("permissions")).contains("presence")) return@forEach
                if (name == "location" && !arrayValues(instance.bundle.manifest.optJSONArray("permissions")).contains("location")) return@forEach
                execute(instance) { it.dispatch(name, values) }
            }
        }
    }
    fun setVariant(pluginId: String, variant: String) {
        worker.post { active[pluginId]?.let { it.state.set("variant", variant); publishState(it) } }
    }
    fun diagnostics(): List<PluginDiagnostic> = diagnosticSnapshot
    /** Language, 12/24-hour setting or time zone changed: tell every plugin so it can recompute its text. */
    fun localeChanged() { Formats.invalidate(); event(null, "locale", JSONArray()) }
    fun restoreFactory() {
        worker.post {
            dev.listFiles()?.forEach(PluginFiles::deleteTree)
            devDigests.clear(); devFiles.clear(); previousDirectories.clear()
            crashTracker?.reset()
            pending.values.toList().forEach { it.terminate(false) }; pending.clear()
            active.values.toList().forEach { it.terminate(true); settings.unregister(it.bundle.id) }; active.clear()
            factory.values.filter { !safe || it.id == "clock" }.forEach { activate(it) }
            publishCatalog()
        }
    }
    fun close() {
        closed = true
        imageOwners = emptyMap()
        settings.removeListener(settingsListener)
        observer?.stopWatching()
        worker.post {
            pending.values.toList().forEach { it.terminate(false) }; pending.clear()
            active.values.toList().forEach { it.terminate(true) }; active.clear()
            stores.values.forEach { it.flush() }
            io.shutdownNow()
            astroWorker.shutdownNow()
            storeIO.shutdown()
            crashTracker?.close()
            worker.removeCallbacksAndMessages(null)
            thread.quitSafely()
        }
    }
    private fun owned(instance: Instance) = !closed && (active[instance.bundle.id] === instance || pending[instance.bundle.id] === instance)
    private inline fun <T> slice(instance: Instance, body: () -> T): T {
        crashTracker?.enter(instance.bundle.id, instance.bundle.digest)
        return try { body() } finally { crashTracker?.clear() }
    }
    private fun activate(bundle: Bundle, previous: Instance? = null): Boolean {
        pending.remove(bundle.id)?.terminate(false)
        var candidate: Instance? = null
        try {
            val instance = Instance(bundle, previous)
            candidate = instance
            registerScope(instance)
            if (crashTracker?.blocked(bundle.id, bundle.digest) == true) {
                instance.disabled = true
                instance.error = "Quarantined after repeated unclean native exits; retry in one hour"
                if (active[bundle.id] == null) active[bundle.id] = instance
                worker.postDelayed({ if (active[bundle.id] === instance && !closed) activate(bundle, instance) }, PluginHealth.HOUR)
                publishCatalog()
                return false
            }
            pending[bundle.id] = instance
            if (!instance.state.value.has("variant")) instance.pages.firstOrNull()?.variants?.firstOrNull()?.let { instance.state.set("variant", it) }
            if (bundle.manifest.has("main")) {
                val lua = slice(instance) { NativeLua { method, args, taskId -> host(instance, method, args, taskId) } }
                instance.lua = lua
                slice(instance) { lua.run(LuaBootstrap.code, "@runtime/bootstrap") }
                slice(instance) { lua.bindEntries() }
                slice(instance) { lua.run(resolve(bundle.directory, bundle.manifest.getString("main")).readText(), "@${bundle.id}/main.lua") }
                val step = slice(instance) { lua.dispatch("start") }
                instance.startTask = step.taskId
                handleStep(instance, step, startup = true)
            } else commit(instance)
            return active[bundle.id] === instance || pending[bundle.id] === instance
        } catch (error: Exception) {
            candidate?.let { failCandidate(it, error) } ?: report(bundle.id, error)
            return false
        }
    }
    private fun commit(instance: Instance) {
        persistInstalled(instance)
        pending.remove(instance.bundle.id)
        instance.startTask = 0
        val previous = active.put(instance.bundle.id, instance)
        if (previous != null && previous.bundle.directory != instance.bundle.directory) previousDirectories[instance.bundle.id] = previous.bundle.directory
        previous?.terminate(true)
        // A plugin installed/replaced while already asleep must learn that state before
        // its deferred work starts, even when the sensors have no new transition to emit.
        worker.post {
            if (active[instance.bundle.id] === instance) execute(instance) { it.dispatch("attention", currentAttention) }
        }
        publishCatalog()
        publishState(instance)
        cleanupVersions()
        Log.i(TAG, "[plugin:${instance.bundle.id}] loaded ${instance.bundle.version}; Lua ${instance.lua?.bytes() ?: 0} bytes")
    }
    /** A plugin's declared settings exist from the moment its code starts, so `tos.config` is right in `start`. */
    private fun registerScope(instance: Instance) {
        instance.scope?.let(settings::register) ?: settings.unregister(instance.bundle.id)
    }
    private fun failCandidate(instance: Instance, error: Exception) {
        if (pending[instance.bundle.id] === instance) pending.remove(instance.bundle.id)
        // The old version keeps running with its own declarations.
        active[instance.bundle.id]?.let(::registerScope) ?: settings.unregister(instance.bundle.id)
        instance.terminate(false)
        report(instance.bundle.id, error)
        val old = active[instance.bundle.id]
        if (old == null || old.lua == null) {
            val retained = old ?: instance.also { active[instance.bundle.id] = it }
            retained.error = error.message
            recover(retained, true)
        } else if (factory[instance.bundle.id]?.directory != instance.bundle.directory && old.bundle.directory != instance.bundle.directory) instance.bundle.directory.deleteRecursively()
    }
    private fun execute(instance: Instance, call: (NativeLua) -> NativeLua.Step) {
        if (!owned(instance) || instance.disabled) return
        val lua = instance.lua ?: return
        try { handleStep(instance, slice(instance) { call(lua) }) }
        catch (error: Exception) { instance.error = error.message; report(instance.bundle.id, error); recover(instance, true) }
    }
    private fun handleStep(instance: Instance, step: NativeLua.Step, startup: Boolean = false) {
        if (step.status != NativeLua.WAITING) instance.awaits.remove(step.taskId)?.cancel(false)
        if (step.failed) {
            val failure = LuaFailure(step)
            if (startup) failCandidate(instance, failure)
            else {
                instance.error = step.message
                report(instance.bundle.id, failure)
                recover(instance, step.resourceFailure)
            }
        } else if (startup && step.status == NativeLua.DONE) commit(instance)
    }
    private fun recover(instance: Instance, resource: Boolean) {
        val action = instance.health.failure(SystemClock.elapsedRealtime(), resource)
        if (action != PluginHealth.Action.KEEP) {
            instance.terminate(false)
            instance.disabled = action == PluginHealth.Action.COOL_DOWN
            val delay = (instance.health.retryAt - SystemClock.elapsedRealtime()).coerceAtLeast(1)
            worker.postDelayed({
                if (active[instance.bundle.id] === instance && !closed) {
                    if (instance.disabled) instance.health.cooledDown()
                    activate(instance.bundle, instance)
                }
            }, delay)
        }
        if (action == PluginHealth.Action.KEEP) updateDiagnostics() else publishCatalog()
    }
    private fun resume(instance: Instance, taskId: Int, values: Array<Any?>) {
        if (!owned(instance) || instance.lua == null || instance.disabled || !instance.awaits.containsKey(taskId)) return
        instance.awaits.remove(taskId)
        val startup = pending[instance.bundle.id] === instance && instance.startTask == taskId
        try {
            val step = slice(instance) { instance.lua!!.resume(taskId, values) }
            handleStep(instance, step, startup)
        } catch (error: Exception) {
            if (startup) failCandidate(instance, error)
            else {
                instance.error = error.message
                report(instance.bundle.id, error)
                recover(instance, true)
            }
        }
    }
    private fun host(instance: Instance, method: String, args: Array<Any?>, taskId: Int): Any? {
        val bundle = instance.bundle
        fun arg(index: Int) = args.getOrNull(index)
        fun number(index: Int) = (arg(index) as Number).toDouble()
        return when (method) {
            "config" -> LinkedHashMap(settings.snapshot(bundle.id))
            "state.set" -> {
                val patch = statePatch(arg(0))
                instance.state.merge(patch); publishState(instance); null
            }
            "settings.list" -> { requireSettingsAdmin(instance); describer.list(Locale.getDefault()) }
            "settings.get" -> { requireSettingsAdmin(instance); describer.describe(arg(0) as String, Locale.getDefault()) }
            "settings.set" -> {
                requireSettingsAdmin(instance)
                try {
                    mapOf("value" to settings.set(arg(0) as String, arg(1) as String, arg(2)))
                } catch (error: SettingError) {
                    // An ordering error names the other setting; hand over its label, not its key.
                    val args = LinkedHashMap(error.args)
                    (args["key"] as? String)?.let { other ->
                        settings.scope(arg(0) as String)?.fields?.get(other)?.let { args["label"] = labels.resolve(arg(0) as String, it.label, Locale.getDefault()) }
                    }
                    mapOf("error" to mapOf("code" to error.code, "args" to args, "message" to error.message))
                }
            }
            "settings.setMany" -> {
                requireSettingsAdmin(instance)
                val values = (arg(1) as? Map<*, *>)?.entries?.associate { (key, value) ->
                    require(key is String) { "setting key must be text" }
                    key to value
                } ?: error("settings values must be a table")
                try {
                    mapOf("value" to settings.setMany(arg(0) as String, values))
                } catch (error: SettingError) {
                    mapOf("error" to mapOf("code" to error.code, "args" to error.args, "message" to error.message))
                }
            }
            "settings.reset" -> { requireSettingsAdmin(instance); settings.reset(arg(0) as String, arg(1) as? String); null }
            "i18n.t" -> {
                val params = (arg(1) as? Map<*, *>)?.entries?.associate { (k, v) -> k.toString() to v }.orEmpty()
                instance.strings.format(arg(0) as String, Locale.getDefault(), params) ?: arg(0)
            }
            "state.get" -> JsonDocument.toHost(path(instance.state.value, arg(0) as? String ?: ""))
            "store.get" -> JsonDocument.toHost(instance.store.document.value.opt(arg(0) as String))
            "store.set" -> { instance.store.set(arg(0) as String, arg(1)); null }
            "time.now" -> WallClock.now().takeIf { it >= 1_577_836_800_000L }
            "time.format" -> {
                // Named formats (@time, @dateFull, …) follow the system locale and 12/24-hour setting.
                val pattern = arg(1) as? String ?: "@time"
                require(pattern.length <= 128)
                ZoneFormat.format(pattern, number(0).toLong(), Zones.clock(arg(2) as? String))
            }
            "tz.home" -> Zones.home().id
            "tz.info" -> zoneInfo(arg(0) as String)
            "location.get" -> {
                require(arrayValues(bundle.manifest.optJSONArray("permissions")).contains("location")) { "location permission required" }
                location.get().toMap()
            }
            "astro.sun", "astro.moon" -> {
                require(arrayValues(bundle.manifest.optJSONArray("permissions")).contains("location")) { "location permission required" }
                require(taskId != 0 && instance.awaits.size < 8 && (active.values + pending.values).sumOf { it.awaits.size } < 32)
                instance.awaits[taskId] = null
                instance.awaits[taskId] = astroWorker.submit {
                    val response = runCatching { astronomy.call(method, args) }
                    worker.post { resume(instance, taskId, if (response.isSuccess) arrayOf(response.getOrThrow(), null)
                        else arrayOf(null, response.exceptionOrNull()?.message ?: "astronomy error")) }
                }
                null
            }
            "astro.seasons" -> astronomy.call(method, args)
            "locale" -> {
                val locale = Locale.getDefault()
                mapOf("tag" to locale.toLanguageTag(), "language" to locale.language, "region" to locale.country,
                    "script" to LocaleChain.script(locale), "rtl" to LocaleChain.isRtl(locale), "hour24" to Formats.env.hour24)
            }
            "timer.add" -> {
                val id = number(0).toInt(); val seconds = number(1)
                require(seconds.isFinite() && seconds in 0.05..2_592_000.0)
                require(instance.timers.size < 64)
                val every = arg(2) == true; val align = arg(3) == true
                val delay = (seconds * 1000).toLong()
                val timer = object : Runnable {
                    override fun run() {
                        if (!owned(instance) || instance.disabled || instance.lua == null) return
                        if (pending[bundle.id] === instance) { worker.postDelayed(this, 50); return }
                        if (!every) instance.timers.remove(id)
                        execute(instance) { it.timer(id, every) }
                        if (every && instance.timers[id] === this && instance.lua != null) worker.postDelayed(this, if (align) delay - WallClock.now().mod(delay) else delay)
                    }
                }
                instance.timers.put(id, timer)?.let(worker::removeCallbacks)
                worker.postDelayed(timer, if (align) delay - WallClock.now().mod(delay) else delay)
                null
            }
            "timer.cancel" -> { instance.timers.remove(number(0).toInt())?.let(worker::removeCallbacks); null }
            "sleep" -> {
                require(taskId != 0 && number(0).isFinite() && number(0) in 0.001..3600.0)
                require(instance.awaits.size < 8)
                instance.awaits[taskId] = null
                worker.postDelayed({ resume(instance, taskId, emptyArray()) }, (number(0) * 1000).toLong())
                null
            }
            "library.list" -> {
                require(arrayValues(bundle.manifest.optJSONArray("permissions")).contains("library")) { "library permission required" }
                require(taskId != 0 && instance.awaits.size < 8 && (active.values + pending.values).sumOf { it.awaits.size } < 32)
                val options = arg(0) as? Map<*, *> ?: error("Library options must be a table")
                instance.awaits[taskId] = null
                instance.awaits[taskId] = io.submit {
                    // Large metadata arrays cross JNI once as text; the budgeted C codec
                    // reconstructs the same public table without thousands of JNI calls.
                    val result = runCatching {
                        val value = moe.kiwi.thinkos.media.MediaLibrary(app).list(options).toHost()
                        JsonDocument.fromHost(value).toString().also {
                            require(it.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Library response exceeds 1 MiB" }
                        }
                    }
                    worker.post { resume(instance, taskId, if (result.isSuccess) arrayOf(result.getOrThrow(), null)
                        else arrayOf(null, result.exceptionOrNull()?.message ?: "Library scan failed")) }
                }
                null
            }
            "http.get", "http.post" -> {
                require(arrayValues(bundle.manifest.optJSONArray("permissions")).contains("network")) { "network permission required" }
                require(taskId != 0 && instance.awaits.size < 8 && (active.values + pending.values).sumOf { it.awaits.size } < 32)
                val url = arg(0) as String
                val body = if (method == "http.post") arg(1) as? String else null
                val options = arg(if (method == "http.post") 2 else 1) as? Map<*, *>
                instance.awaits[taskId] = null
                instance.awaits[taskId] = io.submit {
                    val response = runCatching { PluginHttp.request(if (method == "http.get") "GET" else "POST", url, body, options) }
                    worker.post { resume(instance, taskId, if (response.isSuccess) arrayOf(response.getOrThrow(), null) else arrayOf(null, response.exceptionOrNull()?.message ?: "network error")) }
                }
                null
            }
            "page.focus", "page.release" -> {
                val id = (arg(0) as String).removePrefix("${bundle.id}.")
                val page = instance.pages.firstOrNull { it.id == id } ?: error("page does not belong to plugin")
                val live = arg(1) == true; require(!live || page.live)
                main.post { if (!closed) callbacks.onFocus(page.key, live, method == "page.release") }
                null
            }
            "log" -> {
                val level = when (arg(0)) { "error" -> Log.ERROR; "warn" -> Log.WARN; else -> Log.INFO }
                Log.println(level, TAG, "[plugin:${bundle.id}] ${(arg(1) as? String).orEmpty().take(4096)}")
                null
            }
            "module" -> {
                val name = arg(0) as String
                require(name.length <= 128 && name.matches(Regex("[A-Za-z0-9_.-]+")) && !name.contains(".."))
                val module = resolve(bundle.directory, name.replace('.', '/') + ".lua")
                require(module.isFile && module.length() <= MAX_STATE)
                module.readText()
            }
            else -> error("Unavailable API: $method")
        }
    }
    private fun scanDev() {
        if (closed || safe || !BuildConfig.DEBUG) return
        // A scan already queued by an earlier publication can run between the two replacement
        // renames. Treat the previous directory as still published until its replacement exists.
        val present = PluginFiles.developmentDirectories(dev, devFiles.keys)
        val desired = mutableSetOf<String>()
        present.take(32).forEach { (name, file) ->
            // Retain the last working version when a published directory is malformed.
            devFiles[name]?.let(desired::add)
            try {
                val index = PluginFiles.readDirectory(file)
                require(name == index.id) { "development directory must match plugin id" }
                val digest = index.digest
                val oldId = devFiles[name]
                if (oldId != null && devDigests[oldId] == digest) desired.add(oldId)
                else {
                    val bundle = install(index) { path -> PluginFiles.openDirectory(file, path) }
                    require(oldId == null || oldId == bundle.id) { "a development directory cannot change its id" }
                    desired.add(bundle.id)
                    activate(bundle)
                    // Track validated overrides even if startup fails or the digest is quarantined.
                    // Removal must also cancel a candidate that is still suspended in start.
                    devDigests[bundle.id] = digest
                    devFiles[name] = bundle.id
                }
            } catch (e: Exception) { report(name, e) }
        }
        devFiles.keys.toList().filter { name -> present.none { it.first == name } }.forEach(devFiles::remove)
        devDigests.keys.toList().filter { it !in desired }.forEach { id ->
            devDigests.remove(id)
            pending.remove(id)?.terminate(false)
            factory[id]?.let { activate(it) } ?: active.remove(id)?.let { it.terminate(true); settings.unregister(id) }
            previousDirectories.remove(id)
        }
        persistInstalled(); cleanupVersions(); publishCatalog()
    }

    private fun install(index: PluginFiles.Index, open: (String) -> InputStream): Bundle {
        val digest = index.digest
        val staging = File(root, ".stage-$digest")
        PluginFiles.deleteTree(staging)
        try {
            PluginFiles.copy(index, staging, open)
            val manifest = readObject(resolve(staging, "manifest.json"))
            PluginSchema.validateManifest(manifest)
            require(manifest.getInt("api") <= PluginSchema.API)
            val id = manifest.getString("id"); val version = manifest.getString("version")
            require(id == index.id && version == index.version) { "manifest disagrees with file index" }
            require(id.matches(Regex("[a-z][a-z0-9-]{0,63}")))
            require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+(?:-[A-Za-z0-9.-]+)?")))
            require(!manifest.has("sources")) { "sources are supported from M1" }
            if (manifest.has("main")) require(resolve(staging, manifest.getString("main")).let { it.isFile && it.length() <= MAX_STATE })
            for (collection in listOf("pages", "widgets")) {
                val ids = mutableSetOf<String>()
                arrayObjects(manifest.optJSONArray(collection)).forEach { item ->
                    require(ids.add(item.getString("id"))) { "duplicate $collection id" }
                    val view = readObject(resolve(staging, item.getString("view")))
                    PluginSchema.validateView(view, manifest.getInt("api"))
                    require(view.has(if (collection == "pages") "layout" else "template")) { "wrong view kind for $collection" }
                    if (item.has("variants") && view.has("variants")) {
                        fun ids(a: JSONArray) = arrayValues(a).map { if (it is JSONObject) it.getString("id") else it.toString() }
                        require(ids(item.getJSONArray("variants")) == ids(view.getJSONArray("variants"))) { "manifest/view variants disagree" }
                    }
                }
            }
            val destination = File(root, "$id/$version-$digest")
            destination.parentFile!!.mkdirs()
            if (destination.exists()) PluginFiles.deleteTree(staging) else check(staging.renameTo(destination))
            return Bundle(destination, manifest, digest)
        } catch (e: Exception) { PluginFiles.deleteTree(staging); throw e }
    }

    private fun publishCatalog() {
        if (loading) return
        val pages = active.values.flatMap {
            pagesForCatalog(it.pages, hasScript = it.bundle.manifest.has("main"), scriptRunning = it.lua != null, disabled = it.disabled)
        }.sortedWith(compareBy<PluginPage> { if ("home" in it.roles) 0 else if ("sleep" in it.roles) 1 else 2 }.thenBy { it.key })
        val widgets = active.values.flatMap { it.widgets }
        updateDiagnostics()
        stringTables = active.values.associate { it.bundle.id to it.strings }
        imageOwners = active.values.filter { !it.disabled && (!it.bundle.manifest.has("main") || it.lua != null) }
            .associate { it.bundle.id to it.imageOwner }
        main.post { if (!closed) callbacks.onCatalog(pages, widgets) }
    }
    private fun publishState(instance: Instance) {
        if (instance.publishPending) return
        instance.publishPending = true
        worker.postDelayed({
            instance.publishPending = false
            if (active[instance.bundle.id] !== instance || closed) return@postDelayed
            val snapshot = instance.state.snapshot()
            // `config` is reserved: the effective settings, so views can bind {{config.city}}.
            snapshot.put("config", JSONObject(settings.snapshot(instance.bundle.id)))
            main.post { if (!closed) callbacks.onState(instance.bundle.id, snapshot) }
            updateDiagnostics()
        }, 50)
    }
    /** City, country, coordinates and today's offset for an IANA id or alias; null if unknown. */
    private fun zoneInfo(id: String): Map<String, Any?>? {
        val canonical = Zones.canonical(id) ?: return null
        val info = Zones.database?.info(canonical) ?: return null
        val locale = Locale.getDefault()
        val type = Zones.clock(canonical).typeAt(WallClock.now())
        return mapOf(
            "id" to canonical, "city" to ZoneNames.city(canonical, locale), "country" to info.country,
            "countryName" to ZoneNames.country(info.country, locale), "area" to ZoneNames.area(canonical),
            "latitude" to info.latitude, "longitude" to info.longitude,
            "offset" to type.offset.toLong(), "dst" to type.dst, "abbreviation" to type.abbreviation,
        )
    }
    private fun configChanged(instance: Instance) {
        execute(instance) { it.dispatch("config", arrayOf(LinkedHashMap(settings.snapshot(instance.bundle.id)))) }
        publishState(instance)
    }
    /** Only the factory-installed settings plugin may manage settings; a Git copy never gets the permission. */
    private fun requireSettingsAdmin(instance: Instance) {
        require(arrayValues(instance.bundle.manifest.optJSONArray("permissions")).contains("system.settings")) { "system.settings permission required" }
        require(factory[instance.bundle.id]?.digest == instance.bundle.digest) { "system.settings is only granted to built-in plugins" }
    }
    private fun updateDiagnostics() {
        diagnosticSnapshot = active.values.map { PluginDiagnostic(it.bundle.id, it.bundle.version, it.lua?.bytes() ?: 0, it.disabled, it.error) }
    }
    private fun persistInstalled(candidate: Instance? = null) {
        if (loading) return // Initial catalog is committed once, after all bundled candidates.
        val doc = JSONObject()
        (if (candidate == null) active else active + (candidate.bundle.id to candidate)).forEach { (id, instance) -> doc.put(id, JSONObject().put("version", instance.bundle.version).put("directory", instance.bundle.directory.name).put("sha256", instance.bundle.digest)) }
        writeAtomic(File(root, "installed.json"), doc.toString().toByteArray())
    }
    private fun cleanupVersions() {
        if (loading) return
        val keep = (factory.values.map { it.directory } + pending.values.map { it.bundle.directory } + active.values.map { it.bundle.directory } + previousDirectories.values).map { it.absolutePath }.toSet()
        root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }?.forEach { plugin ->
            plugin.listFiles()?.filter { it.isDirectory && it.absolutePath !in keep }?.forEach(PluginFiles::deleteTree)
        }
    }
    private fun report(id: String, error: Exception) {
        val message = error.message ?: error.javaClass.simpleName
        Log.e(TAG, "[plugin:$id] $message")
        if (error is LuaFailure && error.step.resourceFailure) {
            Log.e(TAG, "[plugin:$id] slice cpu=${error.step.cpuMicros}us wall=${error.step.wallMicros}us")
        }
        main.post { if (!closed) callbacks.onError(id, message) }
    }
    companion object {
        private const val TAG = "ThinkOS"
        private const val MAX_STATE = 256 * 1024
        private fun arrayValues(array: JSONArray?): List<Any> = if (array == null) emptyList() else (0 until array.length()).map(array::get)
        private fun arrayObjects(array: JSONArray?): List<JSONObject> = arrayValues(array).map { it as JSONObject }
        private fun path(state: JSONObject, path: String): Any? {
            if (path.isEmpty()) return state
            var value: Any? = state
            path.split('.').forEach { value = (value as? JSONObject)?.opt(it) }
            return value
        }
        private fun merge(target: JSONObject, source: JSONObject) {
            source.keys().forEach { key ->
                val v = source.get(key); val old = target.optJSONObject(key)
                if (v is JSONObject && old != null) merge(old, v) else target.put(key, v)
            }
        }
        private fun resolve(root: File, relative: String): File = PluginFiles.resolve(root, relative)
        private fun readObject(file: File): JSONObject {
            require(file.isFile && file.length() <= MAX_STATE) { "missing or oversized JSON: ${file.name}" }
            return parseObject(file.readText())
        }
        /** API 3 also accepts an encoded object to avoid large typed-JNI patches on armv7. */
        internal fun statePatch(value: Any?): JSONObject {
            val patch = if (value is String) {
                require(value.toByteArray(Charsets.UTF_8).size <= MAX_STATE) { "State patch exceeds 256 KiB" }
                parseObject(value)
            } else JsonDocument.fromHost(value) as JSONObject
            require(!patch.has("config")) { "'config' is reserved for the plugin's settings" }
            return patch
        }
        private fun parseObject(text: String): JSONObject {
            var depth = 0; var quoted = false; var escaped = false
            for (c in text) {
                if (quoted) {
                    if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                } else when (c) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 32) { "JSON nesting exceeds 32" } }
                    '}', ']' -> depth--
                }
            }
            return JSONObject(text)
        }
        private fun InputStream.readBytesLimited(limit: Long): ByteArray {
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = read(buffer); if (n < 0) break; require(output.size().toLong() + n <= limit); output.write(buffer, 0, n) }
            return output.toByteArray()
        }
        private fun writeAtomic(file: File, bytes: ByteArray) {
            file.parentFile!!.mkdirs()
            val atomic = AtomicFile(file); val output = atomic.startWrite()
            try { output.write(bytes); atomic.finishWrite(output) } catch (e: Exception) { atomic.failWrite(output); throw e }
        }
    }
}
