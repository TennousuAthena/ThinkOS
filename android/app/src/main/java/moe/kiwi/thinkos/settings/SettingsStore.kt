package moe.kiwi.thinkos.settings

import org.json.JSONObject

/** A group of settings: the system itself (`system`) or one plugin. */
class SettingsScope(
    val id: String,
    val name: String,
    val fields: Map<String, SettingField>,
    val constraints: List<Pair<String, String>> = emptyList(),
    val sections: List<SettingSection> = emptyList(),
    val related: List<String> = emptyList(),
) {
    init {
        SettingDeclarations.validate(fields)
        for ((first, second) in constraints) {
            val a = requireNotNull(fields[first]); val b = requireNotNull(fields[second])
            require(a.type == b.type && a.type in setOf(FieldType.NUMBER, FieldType.TIME)) { "constraint needs two numbers or two times" }
            require(if (a.type == FieldType.TIME) a.default != b.default else (a.default as Number).toDouble() < (b.default as Number).toDouble()) { "constraint defaults conflict" }
        }
        require(sections.size <= 32 && sections.map { it.id }.toSet().size == sections.size) { "settings sections must be unique (at most 32)" }
        require(id == SettingsStore.SYSTEM || sections.isEmpty()) { "sections belong to the system scope" }
        for (field in fields.values) field.section?.let { section ->
            require(id == SettingsStore.SYSTEM && sections.any { it.id == section }) { "settings.${field.key}: unknown section $section" }
        }
    }
    /** What the device's settings page lists. Hidden fields are still stored, validated and deliverable by the Server. */
    val visibleFields: List<SettingField> get() = fields.values.filter { !it.hidden }
    val hasSettingsPage: Boolean get() = visibleFields.isNotEmpty() || related.isNotEmpty()
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
    private var revisionValue = 0L

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

    @Synchronized fun register(scope: SettingsScope) {
        if (scope.related.isNotEmpty()) SettingDeclarations.related(scope.related,
            scopes[SYSTEM]?.visibleFields.orEmpty().filter { !it.secret }.map { it.key }.toSet())
        advanceRevision()
        scopes[scope.id] = scope
        if (scope.id == SYSTEM && hasModeSettings()) migrateNightSetting()
    }
    @Synchronized fun unregister(id: String) {
        if (scopes.containsKey(id)) { advanceRevision(); scopes.remove(id) }
    }
    @Synchronized fun scope(id: String): SettingsScope? = scopes[id]
    @Synchronized fun scopes(): List<SettingsScope> = scopes.values.toList()

    /** Process-local intent revision, observed under the same lock as writes.
     * A successful same-value edit is a newer intent too. Never persisted. */
    @Synchronized fun revision(): Long = revisionValue

    /** All callers hold this store's monitor. Rejected validation/guards do not advance it. */
    private fun advanceRevision() { revisionValue = Math.addExact(revisionValue, 1L) }

    // ---- reading -------------------------------------------------------------------------

    @Synchronized fun get(scope: String, key: String): Any {
        val field = field(scope, key)
        return resolve(scope, field).value
    }

    /** Migration checks the presence of a delivered or local value, not whether its default is zero. */
    @Synchronized fun hasValue(scope: String, key: String): Boolean =
        if (scope == SYSTEM && key == SystemSettings.Keys.THEME_NIGHT && hasModeSettings())
            hasValue(scope, SystemSettings.Keys.REDSHIFT_TRIGGER)
        else local.optJSONObject(scope)?.has(key) == true || server.optJSONObject(scope)?.has(key) == true

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
        if (scope == SYSTEM && field.key == SystemSettings.Keys.THEME_NIGHT && hasModeSettings()) {
            val current = resolve(scope, this.field(scope, SystemSettings.Keys.REDSHIFT_TRIGGER))
            return Resolved(current.value != "off", current.overridden)
        }
        stored(local, scope, field)?.let { return Resolved(it, true) }
        stored(server, scope, field)?.let { return Resolved(it, false) }
        return Resolved(field.default, false)
    }

    /** A stored value is used only if it still passes the current declaration. */
    private fun stored(layer: JSONObject, scope: String, field: SettingField): Any? {
        val raw = layer.optJSONObject(scope)?.opt(field.key) ?: return null
        return try { field.validate(raw, sources) } catch (_: SettingError) { null }
    }

    private fun hasModeSettings(): Boolean = scopes[SYSTEM]?.fields?.let {
        SystemSettings.Keys.THEME_NIGHT in it && SystemSettings.Keys.REDSHIFT_TRIGGER in it
    } == true

    /** Consume each layer's old bool without changing local-over-Server precedence.
     * A new value wins in its own layer; resetting it cannot resurrect the old bool. */
    private fun migrateNightSetting() {
        val old = field(SYSTEM, SystemSettings.Keys.THEME_NIGHT)
        val trigger = field(SYSTEM, SystemSettings.Keys.REDSHIFT_TRIGGER)
        var changed = false
        for (layer in listOf(local, server)) layer.optJSONObject(SYSTEM)?.let { values ->
            val legacy = stored(layer, SYSTEM, old) as? Boolean
            if (legacy != null && stored(layer, SYSTEM, trigger) == null) {
                values.put(trigger.key, trigger.validate(if (legacy) "sun" else "off", sources))
                changed = true
            }
            if (values.has(old.key)) { values.remove(old.key); changed = true }
            if (values.length() == 0) layer.remove(SYSTEM)
        }
        if (changed) markDirty()
    }

    // ---- writing -------------------------------------------------------------------------

    /** Edits a value on the device. Throws [SettingError] with a user-facing reason. */
    fun set(scope: String, key: String, value: Any?, beforeWrite: ((Map<String, Any>) -> Unit)? = null): Any {
        return setMany(scope, mapOf(key to value), beforeWrite).getValue(key)
    }

    /** Validate a compound edit before writing any part of it; listeners see one coherent snapshot. */
    fun setMany(scope: String, values: Map<String, Any?>, beforeWrite: ((Map<String, Any>) -> Unit)? = null): Map<String, Any> {
        if (values.isEmpty()) return emptyMap()
        if (scope == SYSTEM && SystemSettings.Keys.THEME_NIGHT in values && synchronized(this) { hasModeSettings() }) {
            val legacy = synchronized(this) { field(scope, SystemSettings.Keys.THEME_NIGHT).validate(values[SystemSettings.Keys.THEME_NIGHT], sources) as Boolean }
            val translated = values.toMutableMap().apply {
                remove(SystemSettings.Keys.THEME_NIGHT)
                if (!containsKey(SystemSettings.Keys.REDSHIFT_TRIGGER)) put(SystemSettings.Keys.REDSHIFT_TRIGGER, if (legacy) "sun" else "off")
            }
            val result = setMany(scope, translated, beforeWrite)
            return values.keys.associateWith { if (it == SystemSettings.Keys.THEME_NIGHT) get(scope, it) else result.getValue(it) }
        }
        val changed: Map<String, Any>
        synchronized(this) {
            changed = values.mapValues { (key, value) -> field(scope, key).validate(value, sources) }
            val definition = scopes.getValue(scope)
            for ((low, high) in definition.constraints) {
                if (low !in changed && high !in changed) continue
                if (definition.fields.getValue(low).type == FieldType.TIME) {
                    if ((changed[low] ?: get(scope, low)) == (changed[high] ?: get(scope, high)))
                        throw SettingError("time_window", mapOf("key" to if (low in changed) high else low), "start and end must differ")
                    continue
                }
                val a = (changed[low] ?: get(scope, low)) as Number
                val b = (changed[high] ?: get(scope, high)) as Number
                if (a.toDouble() >= b.toDouble()) {
                    val lowChanged = low in changed
                    val other = if (lowChanged) high else low
                    throw SettingError("order", mapOf("key" to other, "below" to lowChanged),
                        if (lowChanged) "must be below $high" else "must be above $low")
                }
            }
            // A public UI write may require native confirmation. Validation and
            // compound constraints finish first; refusal leaves every key unchanged.
            beforeWrite?.invoke(changed)
            advanceRevision()
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
    fun reset(scope: String, key: String? = null, beforeWrite: ((Map<String, Any>) -> Unit)? = null) {
        if (scope == SYSTEM && key == SystemSettings.Keys.THEME_NIGHT && synchronized(this) { hasModeSettings() }) {
            reset(scope, SystemSettings.Keys.REDSHIFT_TRIGGER, beforeWrite)
            return
        }
        val keys: Set<String>
        synchronized(this) {
            val layer = local.optJSONObject(scope) ?: return
            for ((first, second) in scopes.getValue(scope).constraints) {
                if (field(scope, first).type != FieldType.TIME || (key != null && key != first && key != second)) continue
                fun afterReset(name: String): Any = if (key == null || key == name) stored(server, scope, field(scope, name)) ?: field(scope, name).default else get(scope, name)
                if (afterReset(first) == afterReset(second)) throw SettingError("time_window", message = "start and end must differ")
            }
            keys = if (key == null) layer.keys().asSequence().toSet() else setOf(key).filter { layer.has(it) }.toSet()
            // Clearing a local false can reveal a delivered true. Inspect the
            // actual post-reset values before removing any part of this scope.
            // Retired local keys still clear/notify normally; they have no current
            // effective value and must not block a whole-scope reset.
            if (keys.isNotEmpty()) beforeWrite?.invoke(keys.filter { it in scopes.getValue(scope).fields }.associateWith { name ->
                stored(server, scope, field(scope, name)) ?: field(scope, name).default
            })
            if (keys.isNotEmpty()) advanceRevision()
            if (key == null) local.remove(scope) else { layer.remove(key); if (layer.length() == 0) local.remove(scope) }
            if (keys.isNotEmpty()) markDirty()
        }
        if (keys.isNotEmpty()) notifyChanged(scope, keys)
    }

    fun resetAll() {
        val before = synchronized(this) {
            val scopesWithEdits = local.keys().asSequence().associateWith { id -> local.getJSONObject(id).keys().asSequence().toSet() }
            advanceRevision()
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
            // Old Server documents keep working, but never reintroduce the consumed bool.
            if (hasModeSettings()) next.optJSONObject(SYSTEM)?.let { incoming ->
                val legacy = incoming.opt(SystemSettings.Keys.THEME_NIGHT) as? Boolean
                if (legacy != null && !incoming.has(SystemSettings.Keys.REDSHIFT_TRIGGER))
                    incoming.put(SystemSettings.Keys.REDSHIFT_TRIGGER, if (legacy) "sun" else "off")
                incoming.remove(SystemSettings.Keys.THEME_NIGHT)
                if (incoming.length() == 0) next.remove(SYSTEM)
            }
            // A time window may cross midnight; only equal endpoints are invalid. Reject
            // the pair together, including an update that would clear a local override.
            for (definition in scopes.values) for ((first, second) in definition.constraints) {
                if (definition.fields.getValue(first).type != FieldType.TIME) continue
                val incoming = next.optJSONObject(definition.id)
                val previous = server.optJSONObject(definition.id)
                fun base(name: String): Any = incoming?.opt(name) ?: definition.fields.getValue(name).default
                fun effective(name: String): Any {
                    val replaced = incoming?.has(name) == true && !same(previous?.opt(name), incoming.get(name))
                    return (if (replaced) null else stored(local, definition.id, definition.fields.getValue(name))) ?: base(name)
                }
                if (base(first) == base(second) || effective(first) == effective(second)) {
                    val restored = incoming ?: JSONObject().also { next.put(definition.id, it) }
                    for (name in listOf(first, second)) {
                        restored.remove(name)
                        previous?.opt(name)?.let { restored.put(name, it) }
                        problems += RejectedSetting(definition.id, name, "start and end must differ")
                    }
                    if (restored.length() == 0) next.remove(definition.id)
                }
            }
            // Values for scopes that are not registered yet (plugin still loading) are kept untouched.
            for (id in server.keys()) if (scopes[id] == null) next.put(id, server.getJSONObject(id))
            advanceRevision()
            val changedScopes = server.keys().asSequence().toSet() + next.keys().asSequence().toSet()
            for (id in changedScopes) {
                val incoming = next.optJSONObject(id) ?: JSONObject()
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
