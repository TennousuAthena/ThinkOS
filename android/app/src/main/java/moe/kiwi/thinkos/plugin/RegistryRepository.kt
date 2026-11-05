package moe.kiwi.thinkos.plugin

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.i18n.LocaleChain
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.update.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.Base64
import java.util.Locale

/** Authenticated directory distribution. No Git, archive extraction, Lua IO or Server dependency. */
internal class RegistryRepository(
    private val environment: Environment,
    private val settings: SettingsStore,
    private val host: Host,
) {
    constructor(context: Context, settings: SettingsStore, host: Host) : this(AndroidEnvironment(context), settings, host)

    /** Native boundary for repository lifecycle tests; production always uses APK roots and AtomicFile. */
    internal interface Environment {
        val filesDir: File
        suspend fun keys(): UpdateKeys?
        fun text(key: String): String
        fun read(file: File, limit: Int): JSONObject?
        fun write(file: File, bytes: ByteArray)
        suspend fun await(millis: Long) = delay(millis)
        fun wallTimeMillis(): Long = System.currentTimeMillis()
    }
    interface Host {
        fun prepare(index: PluginFiles.Index, blobs: Map<String, File>, record: JSONObject): File
        suspend fun activate(record: JSONObject): Boolean
        suspend fun remove(id: String)
        suspend fun open(id: String): Boolean
        suspend fun busy(id: String): Boolean
        suspend fun consent(value: RegistryConsent): Boolean
        fun changed()
        fun ready()
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private var started = false
    private var initialized = false
    private var corrupt = false
    private var keys: UpdateKeys? = null
    @Volatile private var testRoots: UpdateKeys? = null
    @Volatile private var testSources: List<String>? = null
    /** Native instrumentation only: never persisted or exposed through Lua, files, intents or IPC. */
    internal fun overrideForTest(roots: UpdateKeys, sources: List<String>) {
        check(BuildConfig.DEBUG)
        require(sources.size in 1..8)
        testRoots = roots; testSources = sources.toList(); keys = roots
        configured = true; error = null
    }
    private val root = File(environment.filesDir, "registry")
    private val stateFile = File(environment.filesDir, "plugins/registry-state.json")
    @Volatile private var state = emptyState()
    @Volatile private var catalog: JSONObject? = null
    private var revocationsReady = false
    @Volatile private var error: String? = null
    @Volatile private var refreshing = false
    @Volatile private var configured = false
    private var transport: Fetcher? = null
    private val health = mutableMapOf<String, Job>()
    private data class Deferred(val record: JSONObject, val rollbackReason: String? = null)
    private val deferred = mutableMapOf<String, Deferred>()
    private fun setting(key: String) = settings.get(SettingsStore.SYSTEM, key)
    private fun number(key: String) = (setting(key) as Number).toLong()
    private fun bool(key: String) = setting(key) == true
    private fun records() = RegistryPolicy.objects(state.getJSONArray("installed"))
    private fun record(id: String) = records().firstOrNull { it.getString("id") == id }
    fun isTrial(id: String, digest: String): Boolean = record(id)?.let { it.getString("digest") == digest && it.getBoolean("trial") } == true
    private fun copy(value: JSONObject) = JSONObject(value.toString())
    private fun emptyState() = JSONObject().put("schema", 1).put("disabled", false).put("installed", JSONArray()).put("blocked", JSONArray())
    private fun directory(value: JSONObject) = File(environment.filesDir, "plugins/${value.getString("id")}/${value.getString("version")}-${value.getString("digest")}")
    fun keepDirectories(): Set<String> = records().flatMap { value -> listOfNotNull(directory(value), value.optJSONObject("previous")?.let { directory(copy(it).put("id", value.getString("id"))) }) }.map { it.absolutePath }.toSet()
    fun start() {
        if (started) return
        started = true
        scope.launch { lock.withLock { initialize() }
            while (isActive) {
                environment.await(number("plugins.start_batch_ms"))
                lock.withLock { activateDeferred() }
                if (deferred.isEmpty()) environment.await(1000)
            }
        }
        scope.launch {
            environment.await(number("update.initial_min_s") * 1000)
            while (isActive) {
                runCatching { lock.withLock { initialize(); refresh(); automatic() } }
                environment.await(number("update.check_hours") * 3_600_000)
            }
        }
    }
    private suspend fun initialize() {
        if (initialized) return
        initialized = true
        try {
            state = UpdateIo.run { read(stateFile, 256 * 1024)?.also(RegistryModels::readRegistryState) ?: emptyState() }
            val floor = UpdateIo.run { read(File(root, "ledger.json"), 4096)?.also(RegistryModels::readRegistryLedger) }
            keys = testRoots ?: environment.keys()
            configured = keys != null && sources().isNotEmpty()
            if (keys != null) runCatching {
                val cached = UpdateIo.run { read(File(root, "cache.json"), 2 * 1024 * 1024) } ?: return@runCatching
                RegistryModels.readRegistryCache(cached)
                val bytes = Base64.getDecoder().decode(cached.getString("document"))
                val signature = Base64.getDecoder().decode(cached.getString("signature"))
                val known = authenticate(bytes, signature)
                UpdateIo.run { requireFloor(known) }
                catalog = known
            }
            // A durable floor proves that a catalog was accepted, even if its cache write was
            // interrupted. Missing revocation evidence must never mean that nothing is revoked.
            revocationsReady = catalog != null || floor == null
            if (revocationsReady) recoverRecords() else {
                error = "catalog-unavailable"
                suspendRecords()
            }
            host.ready(); host.changed()
        } catch (_: Exception) { corrupt = true; error = "state-corrupt"; host.changed() }
    }
    private fun sources(): List<String> {
        testSources?.let { return it }
        val raw = (setting("plugins.mirror") as String).trim()
        val values = listOf(raw) + (setting("plugins.sources") as String).split(Regex("[\\s,]+"))
        return values.filter(String::isNotBlank).distinct().take(8).map { if (it.substringBefore('?').endsWith(".json")) it else it.trimEnd('/') + "/registry/index.json" }
    }
    private fun fetcher(): Fetcher = transport ?: Fetcher(BuildConfig.VERSION_NAME,
        UpdateHttpClient.Timeouts(number("update.connect_s") * 1000, number("update.read_s") * 1000)).also { transport = it }
    private suspend fun authenticate(bytes: ByteArray, signature: ByteArray): JSONObject = UpdateIo.run {
        val roots = keys ?: error("registry-unconfigured")
        roots.read(UpdateKeys.Role.REGISTRY, SignedDocument.Kind.CATALOG, bytes, signature, RegistryPolicy::parseCatalog).also { originalBytes[it] = bytes.copyOf() }
    }
    private fun requireFloor(doc: JSONObject) {
        val floor = read(File(root, "ledger.json"), 4096) ?: error("registry-floor-missing")
        RegistryModels.readRegistryLedger(floor)
        require(floor.getLong("seq") == doc.getLong("seq") && floor.getString("digest") == RegistryPolicy.hash(docBytes(doc))) { "Registry cache is stale" }
    }
    /** Authenticated original bytes are retained separately from their parsed projection. */
    private val originalBytes = java.util.Collections.synchronizedMap(java.util.WeakHashMap<JSONObject, ByteArray>())
    private fun docBytes(doc: JSONObject) = originalBytes[doc] ?: error("registry-original-missing")
    private suspend fun refresh() {
        configured = keys != null && sources().isNotEmpty()
        if (!configured) { error = "unconfigured"; return }
        refreshing = true; host.changed()
        var best: JSONObject? = null; var signed: ByteArray? = null
        val seen = mutableMapOf<Long, String>(); val forks = mutableSetOf<Long>()
        for (source in sources()) try {
            val bytes = (fetcher().readDocument(listOf(source), Fetcher.DocumentKind.CATALOG) as Fetcher.Document.Body).bytes
            val sig = (fetcher().readDocument(listOf(signatureUrl(source)), Fetcher.DocumentKind.SIGNATURE) as Fetcher.Document.Body).bytes
            val doc = authenticate(bytes, sig); originalBytes[doc] = bytes.copyOf()
            val sequence = doc.getLong("seq"); val digest = RegistryPolicy.hash(bytes)
            seen.put(sequence, digest)?.takeIf { it != digest }?.let { forks.add(sequence) }
            UpdateIo.run {
                val floor = read(File(root, "ledger.json"), 4096)
                if (floor != null) {
                    RegistryModels.readRegistryLedger(floor)
                    val seq = doc.getLong("seq"); val old = floor.getLong("seq")
                    require(seq > old || seq == old && RegistryPolicy.hash(bytes) == floor.getString("digest")) { "Registry rollback/fork" }
                }
            }
            if (best == null || doc.getLong("seq") > best.getLong("seq")) { best = doc; signed = sig }
        } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
        if (best?.let { it.getLong("seq") in forks } == true) best = null
        if (best != null) {
            val accepted = best; val bytes = docBytes(accepted)
            val recovering = !revocationsReady
            revocationsReady = false
            try {
                UpdateIo.run {
                    write(File(root, "ledger.json"), JSONObject().put("schema", 1).put("seq", accepted.getLong("seq")).put("digest", RegistryPolicy.hash(bytes)))
                    write(File(root, "cache.json"), JSONObject().put("schema", 1).put("document", Base64.getEncoder().encodeToString(bytes)).put("signature", Base64.getEncoder().encodeToString(signed!!)))
                }
            } catch (failure: Exception) {
                error = "catalog-unavailable"; refreshing = false
                withContext(NonCancellable) { suspendRecords() }
                host.changed()
                throw failure
            }
            catalog = accepted; revocationsReady = true; error = null
            if (recovering) recoverRecords() else applyRevocations()
        } else error = "catalog-unavailable"
        refreshing = false; host.changed()
    }
    private fun signatureUrl(source: String): String = source.substringBefore('?') + ".sig" + source.substringAfter('?', "").let { if (it.isEmpty()) "" else "?$it" }
    private fun visible(plugin: JSONObject) = plugin.getString("tier") != "community" || bool("plugins.community")
    private fun offer(id: String): Pair<JSONObject, JSONObject>? = RegistryPolicy.objects(catalog?.optJSONArray("plugins")).firstOrNull { it.getString("id") == id && visible(it) }?.let { p -> RegistryPolicy.selected(p)?.let { p to it } }
    private fun revoked(id: String, digest: String): Boolean = RegistryPolicy.objects(catalog?.optJSONArray("revoked")).any { it.getString("id") == id && it.getString("digest") == digest }
    private fun publicError(id: String, digest: String, fallback: String): String? {
        val revocation = RegistryPolicy.objects(catalog?.optJSONArray("revoked")).firstOrNull { it.getString("id") == id && it.getString("digest") == digest }
        val reason = revocation?.getString("reason")
        if (reason != null && !reason.startsWith('@')) return reason
        val key = if (reason != null) reason.removePrefix("@").replace('.', '_') else if (fallback.isNotEmpty()) "plugins_install_failed" else return null
        return environment.text(key)
    }
    private fun blocked(id: String, digest: String): Boolean = RegistryPolicy.objects(state.optJSONArray("blocked")).any { it.getString("id") == id && it.getString("digest") == digest }
    private fun localized(value: JSONObject?): String {
        if (value == null) return ""
        return LocaleChain.candidates(Locale.getDefault()).firstNotNullOfOrNull { tag -> value.optString(tag).takeIf(String::isNotEmpty) }
            ?: value.optString("en").ifEmpty { value.optString("zh-Hans") }.ifEmpty { value.keys().asSequence().firstOrNull()?.let(value::getString).orEmpty() }
    }
    fun installed(): List<Map<String, Any?>> = records().map { value ->
        val available = offer(value.getString("id"))?.second
        val update = available != null && RegistryPolicy.compare(available.getString("version"), value.getString("version")) > 0
        val name = offer(value.getString("id"))?.first?.let { localized(it.getJSONObject("name")) } ?: value.getString("name")
        mapOf("id" to value.getString("id"), "name" to name, "version" to value.getString("version"), "digest" to value.getString("digest"),
            "repo" to value.getString("repo"), "commit" to value.getString("commit"), "tier" to value.getString("tier"), "auto" to value.getBoolean("auto"),
            "enabled" to value.getBoolean("enabled"), "error" to publicError(value.getString("id"), value.getString("digest"), value.optString("error")), "revoked" to revoked(value.getString("id"), value.getString("digest")),
            "update" to update, "available_version" to available?.getString("version"), "apps" to emptyList<Any>())
    }
    suspend fun catalog(options: Map<*, *>): Map<String, Any?> = lock.withLock {
        initialize()
        if (catalog == null || !revocationsReady || options["refresh"] == true) refresh()
        val query = (options["query"] as? String).orEmpty().take(128).lowercase(Locale.ROOT)
        val tag = (options["tag"] as? String).orEmpty().take(40)
        val offset = ((options["offset"] as? Number)?.toInt() ?: 0).coerceIn(0, 256)
        val limit = ((options["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 50)
        val rows = RegistryPolicy.objects(catalog?.optJSONArray("plugins")).filter { p -> visible(p) &&
            (query.isEmpty() || query in (p.getString("id") + " " + localized(p.getJSONObject("name")) + " " + localized(p.getJSONObject("summary"))).lowercase(Locale.ROOT)) &&
            (tag.isEmpty() || tag in RegistryPolicy.strings(p.getJSONArray("tags"))) }.mapNotNull { p ->
            val release = RegistryPolicy.selected(p) ?: return@mapNotNull null
            val installed = record(p.getString("id"))
            mapOf("id" to p.getString("id"), "name" to localized(p.getJSONObject("name")), "summary" to localized(p.getJSONObject("summary")),
                "author" to p.getString("author"), "repo" to p.getString("repo"), "license" to p.getString("license"), "tier" to p.getString("tier"),
                "version" to release.getString("version"), "commit" to release.getString("commit"), "digest" to release.getString("digest"), "size" to release.getLong("size"),
                "permissions" to RegistryPolicy.strings(release.getJSONArray("permissions")).toList(), "why" to release.getJSONObject("why").keys().asSequence().associateWith { localized(release.getJSONObject("why").getJSONObject(it)) },
                "tags" to RegistryPolicy.strings(p.getJSONArray("tags")).toList(), "installed" to (installed != null),
                "update" to (installed != null && RegistryPolicy.compare(release.getString("version"), installed.getString("version")) > 0), "revoked" to revoked(p.getString("id"), release.getString("digest")),
                "error" to publicError(p.getString("id"), release.getString("digest"), ""))
        }
        mapOf("items" to rows.drop(offset).take(limit), "total" to rows.size, "offset" to offset, "configured" to configured, "refreshing" to refreshing, "error" to error,
            "expired" to (catalog?.let { environment.wallTimeMillis() > it.getLong("expires") } ?: false))
    }
    suspend fun mutate(action: String, id: String, automatic: Boolean = false): Boolean = lock.withLock {
        initialize(); check(!corrupt) { "state-corrupt" }
        require(id.matches(Regex("[a-z][a-z0-9-]{0,63}")))
        when (action) {
            "open" -> host.open(id)
            "uninstall" -> {
                health.remove(id)?.cancel(); deferred.remove(id)
                replace(id, null); host.remove(id); true
            }
            "install", "update" -> install(id, automatic)
            else -> error("unknown-action")
        }
    }
    private suspend fun install(id: String, automatic: Boolean): Boolean {
        if (catalog == null || !revocationsReady) refresh()
        check(revocationsReady) { "catalog-unavailable" }
        val (plugin, release) = offer(id) ?: error("plugin-unavailable")
        val old = record(id)
        require(old == null || !old.getBoolean("trial")) { "trial-in-progress" }
        require(!revoked(id, release.getString("digest"))) { "revoked" }
        if (automatic) require(!blocked(id, release.getString("digest"))) { "blocked" }
        require(old != null || records().size < number("plugins.max_installed")) { "plugin-limit" }
        if (old != null) require(RegistryPolicy.compare(release.getString("version"), old.getString("version")) > 0 || !automatic && !old.getBoolean("enabled")) { "not-newer" }
        val requested = RegistryPolicy.strings(release.getJSONArray("permissions"))
        val confirmed = old?.let { RegistryPolicy.strings(it.getJSONArray("confirmed")) }.orEmpty()
        val permissionIncrease = !confirmed.containsAll(requested) || old == null || old.getString("repo") != plugin.getString("repo")
        if (permissionIncrease) {
            require(!automatic) { "confirmation-required" }
            val why = release.getJSONObject("why")
            if (!host.consent(RegistryConsent(id, localized(plugin.getJSONObject("name")), plugin.getString("repo"), release.getString("commit"), plugin.getString("tier"), release.getLong("size"), requested.map { it to localized(why.getJSONObject(it)) }))) error("cancelled")
        }
        val bases = RegistryPolicy.strings(catalog!!.getJSONArray("blob_bases")).toList()
        val raw = (fetcher().readDocument(bases.map { it.trimEnd('/') + "/registry/files/${release.getString("digest")}.json" }, Fetcher.DocumentKind.MANIFEST) as Fetcher.Document.Body).bytes
        val index = UpdateIo.run {
            val json = UpdateJson.read(raw, PluginFiles.MAX_INDEX); RegistryModels.readRegistryFiles(json)
            PluginFiles.parse(json).also { require(it.id == id && it.version == release.getString("version") && it.digest == release.getString("digest") && it.files.sumOf { e -> e.size } == release.getLong("size")) }
        }
        val blobs = mutableMapOf<String, File>()
        for (entry in index.files) {
            val file = File(root, "blobs/${entry.sha256}")
            val usable = UpdateIo.run { file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == entry.size && RegistryPolicy.hash(file.readBytes()) == entry.sha256 }
            if (!usable) {
                val bytes = if (entry.size == 0L) ByteArray(0) else (fetcher().readDocument(bases.map { it.trimEnd('/') + "/blobs/${entry.sha256.take(2)}/${entry.sha256}" }, Fetcher.DocumentKind.CATALOG, entry.size.toInt()) as Fetcher.Document.Body).bytes
                require(RegistryPolicy.hash(bytes) == entry.sha256) { "blob-hash" }
                UpdateIo.run { writeBytes(file, bytes) }
            }
            blobs[entry.path] = file
        }
        val next = JSONObject().put("id", id).put("version", release.getString("version")).put("digest", release.getString("digest")).put("commit", release.getString("commit"))
            .put("repo", plugin.getString("repo")).put("tier", plugin.getString("tier")).put("name", localized(plugin.getJSONObject("name")))
            .put("confirmed", JSONArray(requested.toList())).put("auto", old?.getBoolean("auto") ?: true).put("enabled", true).put("trial", true)
        if (old != null) next.put("previous", previous(old))
        UpdateIo.run { host.prepare(index, blobs, next) }
        // Durable permission/trial journal precedes any execution of downloaded code.
        replace(id, next)
        deferred.remove(id)
        if (host.busy(id)) { deferred[id] = Deferred(next); host.changed(); return true }
        return activateRecord(next)
    }
    private fun previous(value: JSONObject): JSONObject = JSONObject().also { out ->
        listOf("version", "digest", "commit", "repo", "tier", "name", "confirmed").forEach { out.put(it, value.get(it)) }
    }
    private suspend fun activateRecord(value: JSONObject): Boolean {
        val id = value.getString("id")
        val failure = activationFailure(value)
        if (failure != null) {
            if (failure == "catalog-unavailable") suspendRecords()
            else if (failure == "revoked") quarantine(value, failure) else rollback(value, failure)
            return false
        }
        if (value.getBoolean("trial")) {
            health.remove(id)?.cancel()
            health[id] = scope.launch {
                environment.await(number("plugins.trial_s") * 1000)
                lock.withLock {
                    val current = record(id)
                    if (current != null && current.getString("digest") == value.getString("digest") && current.getBoolean("enabled")) {
                        replace(id, copy(current).put("trial", false).apply { remove("previous") })
                    }
                }
            }
        }
        return true
    }
    /** Every execution path, including rollback, checks the current authenticated id + digest. */
    private suspend fun activationFailure(value: JSONObject): String? {
        if (!revocationsReady) return "catalog-unavailable"
        if (revoked(value.getString("id"), value.getString("digest"))) return "revoked"
        return try { if (host.activate(value)) null else "startup-failed" }
        catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) { "startup-failed" }
    }
    private suspend fun quarantine(value: JSONObject, reason: String) {
        val id = value.getString("id"); health.remove(id)?.cancel(); deferred.remove(id)
        // Keep both the failed candidate and previous metadata for diagnosis and directory retention.
        try { replace(id, copy(value).put("enabled", false).put("trial", false).put("error", reason)) }
        finally { host.remove(id) }
    }
    private suspend fun activateDeferred() {
        if (!revocationsReady || state.getBoolean("disabled")) return
        val value = deferred.values.firstOrNull { !host.busy(it.record.getString("id")) } ?: return
        deferred.remove(value.record.getString("id"))
        if (value.rollbackReason == null) activateRecord(value.record) else rollback(value.record, value.rollbackReason)
    }
    private suspend fun rollback(value: JSONObject, reason: String, activate: Boolean = true) {
        if (!revocationsReady) { suspendRecords(); return }
        val id = value.getString("id"); health.remove(id)?.cancel(); deferred.remove(id)
        val nextState = copy(state); val blocked = nextState.getJSONArray("blocked")
        val remaining = RegistryPolicy.objects(blocked).filterNot { it.getString("id") == id && it.getString("digest") == value.getString("digest") }.takeLast(255)
        nextState.put("blocked", JSONArray(remaining + JSONObject().put("id", id).put("digest", value.getString("digest")).put("reason", reason)))
        state = nextState
        val prior = value.optJSONObject("previous")?.let { copy(it).put("id", id).put("auto", value.getBoolean("auto")).put("enabled", true).put("trial", false) }
        if (prior == null) { quarantine(value, reason); return }
        if (revoked(id, prior.getString("digest"))) { quarantine(value, "previous-revoked"); return }
        // Retain the interrupted trial until its previous version actually starts. Another restart
        // before the first startup batch can therefore retry recovery without losing either record.
        replace(id, copy(value).put("enabled", false).put("error", reason))
        if (!activate) { deferred[id] = Deferred(copy(value), reason); return }
        val failure = activationFailure(prior)
        if (failure == null) replace(id, prior)
        else quarantine(value, if (failure == "revoked") "previous-revoked" else "previous-unavailable")
    }
    fun failed(id: String, digest: String) {
        scope.launch { lock.withLock { val value = record(id); if (value?.getString("digest") == digest && value.getBoolean("trial")) rollback(value, "trial-failed") } }
    }
    private suspend fun suspendRecords() {
        deferred.clear(); health.values.forEach { it.cancel() }; health.clear()
        // Keep the durable trial/previous journal intact so a matching authenticated catalog
        // can resume recovery. Stop any old instance while its revocation status is unknown.
        for (value in records()) host.remove(value.getString("id"))
    }
    private suspend fun recoverRecords() {
        records().filter { it.getBoolean("trial") }.forEach { rollback(it, "trial_interrupted", activate = false) }
        if (!state.getBoolean("disabled")) records().filter { it.getBoolean("enabled") }.forEach { deferred[it.getString("id")] = Deferred(copy(it)) }
        applyRevocations()
    }
    private suspend fun applyRevocations() {
        for (value in records()) if (revoked(value.getString("id"), value.getString("digest"))) {
            quarantine(value, "revoked")
        }
    }
    private suspend fun automatic() {
        val mode = setting("plugins.auto_update") as String
        if (!revocationsReady || mode == "off" || state.getBoolean("disabled")) return
        for (value in records()) {
            val id = value.getString("id"); val candidate = offer(id) ?: continue
            if (!value.getBoolean("auto") || !value.getBoolean("enabled") || value.getBoolean("trial") || mode == "official" && candidate.first.getString("tier") != "official") continue
            if (RegistryPolicy.compare(candidate.second.getString("version"), value.getString("version")) <= 0) continue
            runCatching { install(id, automatic = true) }
        }
    }
    fun setAuto(id: String, enabled: Boolean): Boolean {
        if (record(id) == null || corrupt) return false
        scope.launch { lock.withLock { record(id)?.let { replace(id, copy(it).put("auto", enabled)) } } }
        return true
    }
    fun restoreFactory() {
        scope.launch { lock.withLock {
            deferred.clear(); health.values.forEach { it.cancel() }; health.clear()
            val next = copy(state).put("disabled", true).put("installed", JSONArray())
            persist(next)
        } }
    }
    private suspend fun replace(id: String, value: JSONObject?) {
        val next = copy(state)
        next.put("installed", JSONArray(records().filterNot { it.getString("id") == id } + listOfNotNull(value)))
        if (value != null) next.put("disabled", false)
        persist(next)
    }
    private suspend fun persist(next: JSONObject) {
        val checked = UpdateIo.run { UpdateJson.read(next.toString().toByteArray(), 256 * 1024).also { RegistryModels.readRegistryState(it); write(stateFile, it) } }
        state = checked; host.changed()
    }
    private fun read(file: File, limit: Int): JSONObject? = environment.read(file, limit)
    private fun write(file: File, value: JSONObject) = writeBytes(file, value.toString().toByteArray())
    private fun writeBytes(file: File, bytes: ByteArray) = environment.write(file, bytes)

    private class AndroidEnvironment(private val context: Context) : Environment {
        override val filesDir: File get() = context.filesDir
        override suspend fun keys(): UpdateKeys? = UpdateKeys.load(context).getOrNull()
        override fun text(key: String): String {
            val resource = context.resources.getIdentifier(key, "string", context.packageName)
            val generic = context.resources.getIdentifier("plugins_disabled", "string", context.packageName)
            return if (resource != 0) context.getString(resource) else if (generic != 0) context.getString(generic) else ""
        }
        override fun read(file: File, limit: Int): JSONObject? {
            val backup = File(file.path + ".bak")
            if (!file.exists() && !backup.exists()) return null
            require(!Files.isSymbolicLink(file.toPath()) && !Files.isSymbolicLink(backup.toPath()))
            require(!file.exists() || file.isFile)
            val bytes = AtomicFile(file).openRead().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer, 0, minOf(buffer.size, limit - out.size() + 1)); if (n < 0) break; require(n <= limit - out.size()); out.write(buffer, 0, n) }
                out.toByteArray()
            }
            return UpdateJson.read(bytes, limit)
        }
        override fun write(file: File, bytes: ByteArray) {
            val parent = file.parentFile!!
            if (!parent.isDirectory) check(parent.mkdirs())
            require(!Files.isSymbolicLink(parent.toPath()) && !Files.isSymbolicLink(file.toPath()))
            val atomic = AtomicFile(file); val stream = atomic.startWrite()
            try { stream.write(bytes); atomic.finishWrite(stream); FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) } }
            catch (error: Exception) { atomic.failWrite(stream); throw error }
        }
    }
    fun close() { scope.cancel(); transport?.close() }
}
