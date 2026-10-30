package moe.kiwi.thinkos.settings

import org.json.JSONObject

/** A group of settings: the system itself (`system`) or one plugin. */
class SettingsScope(val id: String, val name: String, val fields: Map<String, SettingField>, val constraints: List<Pair<String, String>> = emptyList()) {
    /** What the device's settings page lists. Hidden fields are still stored, validated and deliverable by the Server. */
    val visibleFields: List<SettingField> get() = fields.values.filter { !it.hidden }
}

/** Durable bytes by name. The Android implementation writes through AtomicFile. */
interface SettingsPersistence {
    fun read(name: String): ByteArray?
    fun write(name: String, bytes: ByteArray)
}

class RejectedSetting(val scope: String, val key: String, val reason: String)

/**
 * The only holder of settings on the device (ADR-017).
 *
 * Effective value = device override ?? Server-delivered value ?? declared default.
 * A device override is cleared only when the Server later delivers a *different* value for the
 * same key, so an explicit Server change wins over an old local edit while unrelated Server
 * updates never wipe local edits. Plugins read through [snapshot]; only [set] writes.
 *
 * Thread-safe: every public call is synchronised and snapshots are immutable copies.
 */
class SettingsStore(
    private val persistence: SettingsPersistence,
    private val sources: OptionSource = AnyOption,
    /** Runs [task] after roughly [delayMillis]; the default writes through immediately. */
    private val schedule: (delayMillis: Long, task: () -> Unit) -> Unit = { _, task -> task() },
) {
    fun interface Listener { fun onChanged(scope: String, keys: Set<String>) }

    private val scopes = linkedMapOf<String, SettingsScope>()
    private var server = JSONObject()      // scope -> key -> value, as last delivered
    private var local = JSONObject()       // scope -> key -> value, edited on the device
    private val listeners = mutableListOf<Listener>()
    private var rejected = emptyList<RejectedSetting>()
    private var writePending = false

    init {
        server = load("server.json")
        local = load("local.json")
    }

    private fun load(name: String): JSONObject = try {
        persistence.read(name)?.let { JSONObject(String(it, Charsets.UTF_8)) } ?: JSONObject()
    } catch (_: Exception) {
        JSONObject()        // a damaged file must never stop the device from booting
    }

    @Synchronized fun addListener(listener: Listener) { listeners += listener }
    @Synchronized fun removeListener(listener: Listener) { listeners -= listener }

    @Synchronized fun register(scope: SettingsScope) { scopes[scope.id] = scope }
    @Synchronized fun unregister(id: String) { scopes.remove(id) }
    @Synchronized fun scope(id: String): SettingsScope? = scopes[id]
    @Synchronized fun scopes(): List<SettingsScope> = scopes.values.toList()

    // ---- reading -------------------------------------------------------------------------

    @Synchronized fun get(scope: String, key: String): Any {
        val field = field(scope, key)
        return resolve(scope, field).value
    }

    /** Immutable copy of every effective value of [scope]. */
    @Synchronized fun snapshot(scope: String): Map<String, Any> {
        val definition = scopes[scope] ?: return emptyMap()
        return definition.fields.values.associate { it.key to resolve(scope, it).value }
    }

    class Resolved(val value: Any, val overridden: Boolean)

    @Synchronized fun resolved(scope: String, key: String): Resolved = resolve(scope, field(scope, key))

    private fun field(scope: String, key: String): SettingField =
        scopes[scope]?.fields?.get(key) ?: throw SettingError("unknown", message = "unknown setting $scope.$key")

    private fun resolve(scope: String, field: SettingField): Resolved {
        stored(local, scope, field)?.let { return Resolved(it, true) }
        stored(server, scope, field)?.let { return Resolved(it, false) }
        return Resolved(field.default, false)
    }

    /** A stored value is used only if it still passes the current declaration. */
    private fun stored(layer: JSONObject, scope: String, field: SettingField): Any? {
        val raw = layer.optJSONObject(scope)?.opt(field.key) ?: return null
        return try { field.validate(raw, sources) } catch (_: SettingError) { null }
    }

    // ---- writing -------------------------------------------------------------------------

    /** Edits a value on the device. Throws [SettingError] with a user-facing reason. */
    fun set(scope: String, key: String, value: Any?): Any {
        return setMany(scope, mapOf(key to value)).getValue(key)
    }

    /** Validate a compound edit before writing any part of it; listeners see one coherent snapshot. */
    fun setMany(scope: String, values: Map<String, Any?>): Map<String, Any> {
        if (values.isEmpty()) return emptyMap()
        val changed: Map<String, Any>
        synchronized(this) {
            changed = values.mapValues { (key, value) -> field(scope, key).validate(value, sources) }
            val definition = scopes.getValue(scope)
            for ((low, high) in definition.constraints) {
                if (low !in changed && high !in changed) continue
                val a = (changed[low] ?: get(scope, low)) as Number
                val b = (changed[high] ?: get(scope, high)) as Number
                if (a.toDouble() >= b.toDouble()) {
                    val lowChanged = low in changed
                    val other = if (lowChanged) high else low
                    throw SettingError("order", mapOf("key" to other, "below" to lowChanged),
                        if (lowChanged) "must be below $high" else "must be above $low")
                }
            }
            val layer = local.optJSONObject(scope) ?: JSONObject().also { local.put(scope, it) }
            for ((key, value) in changed) {
                val field = field(scope, key)
                val below = stored(server, scope, field) ?: field.default
                if (same(value, below)) layer.remove(key) else layer.put(key, value)
            }
            if (layer.length() == 0) local.remove(scope)
            markDirty()
        }
        notifyChanged(scope, changed.keys)
        return changed
    }

    /** Clears device edits: one key, or a whole scope when [key] is null. */
    fun reset(scope: String, key: String? = null) {
        val keys: Set<String>
        synchronized(this) {
            val layer = local.optJSONObject(scope) ?: return
            keys = if (key == null) layer.keys().asSequence().toSet() else setOf(key).filter { layer.has(it) }.toSet()
            if (key == null) local.remove(scope) else { layer.remove(key); if (layer.length() == 0) local.remove(scope) }
            if (keys.isNotEmpty()) markDirty()
        }
        if (keys.isNotEmpty()) notifyChanged(scope, keys)
    }

    fun resetAll() {
        val before = synchronized(this) {
            val scopesWithEdits = local.keys().asSequence().associateWith { id -> local.getJSONObject(id).keys().asSequence().toSet() }
            local = JSONObject()
            markDirty()
            scopesWithEdits
        }
        before.forEach { (scope, keys) -> notifyChanged(scope, keys) }
    }

    /**
     * Applies the `config` object of a desired-state document: system settings are top-level
     * (nested by their dotted key, `theme.mode` ↔ `{"theme":{"mode":…}}`) and plugin settings live
     * in `plugins.<id>`. Invalid values are ignored and reported through [rejectedByServer].
     */
    fun applyServer(config: JSONObject) {
        val changes = linkedMapOf<String, MutableSet<String>>()
        synchronized(this) {
            val next = JSONObject()
            val problems = mutableListOf<RejectedSetting>()
            val plugins = config.optJSONObject("plugins")
            for (definition in scopes.values) {
                for (field in definition.fields.values) {
                    val raw = if (definition.id == SYSTEM) nested(config, field.key) else plugins?.optJSONObject(definition.id)?.opt(field.key)
                    if (raw == null || raw == JSONObject.NULL) continue
                    try {
                        val value = field.validate(raw, sources)
                        (next.optJSONObject(definition.id) ?: JSONObject().also { next.put(definition.id, it) }).put(field.key, value)
                    } catch (error: SettingError) {
                        problems += RejectedSetting(definition.id, field.key, error.message ?: "invalid")
                    }
                }
            }
            // Values for scopes that are not registered yet (plugin still loading) are kept untouched.
            for (id in server.keys()) if (scopes[id] == null) next.put(id, server.getJSONObject(id))
            for (id in next.keys()) {
                val incoming = next.getJSONObject(id)
                val previous = server.optJSONObject(id)
                for (key in incoming.keys()) {
                    if (!same(previous?.opt(key), incoming.get(key))) {
                        changes.getOrPut(id) { linkedSetOf() } += key
                        // A different Server value for this key replaces an older local edit.
                        local.optJSONObject(id)?.let { layer -> layer.remove(key); if (layer.length() == 0) local.remove(id) }
                    }
                }
                previous?.keys()?.forEach { key -> if (!incoming.has(key)) changes.getOrPut(id) { linkedSetOf() } += key }
            }
            server = next
            rejected = problems
            markDirty()
        }
        changes.forEach { (scope, keys) -> notifyChanged(scope, keys) }
    }

    @Synchronized fun rejectedByServer(): List<RejectedSetting> = rejected

    /** Device edits for the next report. Secret values never leave the device. */
    @Synchronized fun overrides(): JSONObject {
        val out = JSONObject()
        for (id in local.keys()) {
            val definition = scopes[id]
            val layer = local.getJSONObject(id)
            val visible = JSONObject()
            for (key in layer.keys()) if (definition?.fields?.get(key)?.secret != true) visible.put(key, layer.get(key))
            if (visible.length() > 0) out.put(id, visible)
        }
        return out
    }

    private fun checkConstraints(scope: String, key: String, value: Any) {
        val definition = scopes[scope] ?: return
        for ((low, high) in definition.constraints) {
            if (key != low && key != high) continue
            val a = (if (key == low) value else get(scope, low)) as Number
            val b = (if (key == high) value else get(scope, high)) as Number
            if (a.toDouble() >= b.toDouble()) {
                val other = if (key == low) high else low
                throw SettingError("order", mapOf("key" to other, "below" to (key == low)),
                    if (key == low) "must be below $high" else "must be above $low")
            }
        }
    }

    /** JSON round trips turn Long into Integer; numbers compare by value. */
    private fun same(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b

    private fun nested(root: JSONObject, dotted: String): Any? {
        var node: Any? = root
        for (part in dotted.split('.')) node = (node as? JSONObject)?.opt(part)
        return node
    }

    private fun markDirty() {
        if (writePending) return
        writePending = true
        schedule(WRITE_DELAY_MILLIS) { flush() }
    }

    /** Writes both layers now. Called by the debounce timer and on shutdown. */
    fun flush() {
        val serverBytes: ByteArray
        val localBytes: ByteArray
        synchronized(this) {
            writePending = false
            serverBytes = server.toString().toByteArray(Charsets.UTF_8)
            localBytes = local.toString().toByteArray(Charsets.UTF_8)
        }
        persistence.write("server.json", serverBytes)
        persistence.write("local.json", localBytes)
    }

    private fun notifyChanged(scope: String, keys: Set<String>) {
        val copy = synchronized(this) { listeners.toList() }
        copy.forEach { it.onChanged(scope, keys) }
    }

    companion object {
        const val SYSTEM = "system"
        const val WRITE_DELAY_MILLIS = 500L
    }
}
