package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject

internal data class PublicExport(val path: String, val shape: String, val requires: Set<String>)
internal data class PublicUse(val provider: String, val export: String, val shape: String)
internal class ExportValue(val value: Any, val text: String)
internal enum class UseStatus(val code: String) {
    AVAILABLE("available"), MISSING("missing"), SHAPE("shape_mismatch"), PERMISSION("permission_required"),
}
internal class UseResolution(val status: UseStatus, val provider: String, val value: ExportValue?)

/** Plugin state is private unless a manifest exports a path; a consumer declares what it reads (ADR-027). */
internal object PublicData {
    private const val MAX_LUA_READ = 64 * 1024
    private const val MAX_LUA_CONTAINERS = 4096
    const val MAX_COMMAND_ARGS = 4 * 1024
    const val DATA_EVENT_INTERVAL = 1_000L
    const val CONSUMER_PUBLISH_DELAY = 250L
    private const val DEFAULT_COOLDOWN = 1.0
    private val WHOLE_BINDING = Regex("""^\{\{\s*uses\.([a-z][a-z0-9_]*)(?:\.[A-Za-z0-9_.-]+)?\s*\}\}$""")
    private val WHOLE_PATH = Regex("""^uses\.([a-z][a-z0-9_]*)(?:\.[A-Za-z0-9_.-]+)?$""")

    fun exports(manifest: JSONObject): Map<String, PublicExport> {
        val declared = manifest.optJSONObject("exports") ?: return emptyMap()
        return declared.keys().asSequence().associateWith { name ->
            val entry = declared.getJSONObject(name)
            val requires = entry.optJSONArray("requires")
            PublicExport(entry.getString("path"), entry.getString("shape"),
                (0 until (requires?.length() ?: 0)).map { requires!!.getString(it) }.toSet())
        }
    }
    fun uses(manifest: JSONObject): Map<String, PublicUse> {
        val declared = manifest.optJSONObject("uses") ?: return emptyMap()
        return declared.keys().asSequence().associateWith { alias ->
            val entry = declared.getJSONObject(alias)
            val target = entry.getString("export")
            PublicUse(target.substringBefore('.'), target.substringAfter('.'), entry.getString("shape"))
        }
    }
    fun calls(manifest: JSONObject): Set<String> {
        val declared = manifest.optJSONArray("calls") ?: return emptySet()
        return (0 until declared.length()).map { declared.getString(it) }.toSet()
    }
    /** Public command id to its cooldown in milliseconds. A command stays private unless it says otherwise. */
    fun publicCommands(manifest: JSONObject): Map<String, Long> {
        val declared = manifest.optJSONArray("commands") ?: return emptyMap()
        return (0 until declared.length()).map(declared::getJSONObject).filter { it.optBoolean("public") }
            .associate { it.getString("id") to (it.optDouble("cooldown", DEFAULT_COOLDOWN) * 1000).toLong() }
    }
    fun read(state: JSONObject, path: String): Any? {
        var current: Any? = state
        path.split('.').forEach { current = (current as? JSONObject)?.opt(it) }
        return current.takeUnless { it == JSONObject.NULL }
    }
    fun encode(value: Any): String = when (value) {
        is JSONObject, is JSONArray -> value.toString()
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    /** Key order is not content: a restarted Lua state may encode the same table differently. */
    fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && same(a.opt(it), b.opt(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.opt(it), b.opt(it)) }
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }
    /** A provider's value must not exhaust its consumer's Lua memory: both text and table count are bounded. */
    fun fitsLua(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size <= MAX_LUA_READ &&
        text.count { it == '[' || it == '{' } <= MAX_LUA_CONTAINERS
    fun status(use: PublicUse, export: PublicExport?, permissions: Set<String>): UseStatus = when {
        export == null -> UseStatus.MISSING
        export.shape != use.shape -> UseStatus.SHAPE
        !permissions.containsAll(export.requires) -> UseStatus.PERMISSION
        else -> UseStatus.AVAILABLE
    }
    /**
     * The alias whose provider vouches for an image. Only a whole-value binding qualifies: anything a consumer
     * composes or formats (`https://me/{{uses.x}}`) is the consumer's own request and needs its own permission.
     */
    fun imageAlias(definition: JSONObject): String? = when (definition.optString("type")) {
        "image" -> WHOLE_BINDING.find(definition.optString("src"))?.groupValues?.get(1)
        "carousel" -> definition.optString("items").let { items ->
            (WHOLE_BINDING.find(items) ?: WHOLE_PATH.find(items))?.groupValues?.get(1)
        }
        else -> null
    }
}

/** A public command's cooldown protects its provider, whichever plugin is calling. */
internal class CommandGate {
    private val last = HashMap<String, Long>()
    fun allow(command: String, now: Long, cooldown: Long): Boolean {
        val previous = last[command]
        if (previous != null && now - previous < cooldown) return false
        last[command] = now
        return true
    }
}
