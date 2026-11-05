package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.util.IdentityHashMap

/** Mutable on PluginThread. Cache subtree sizes; only changed branches are visited. */
internal class JsonDocument(val value: JSONObject = JSONObject(), private val limit: Int = 256 * 1024) {
    private val sizes = IdentityHashMap<Any, Int>()
    var bytes = size(value)
        private set

    fun merge(patch: JSONObject) {
        data class Edit(val target: JSONObject, val key: String, val next: Any)
        val edits = mutableListOf<Edit>()
        val updatedSizes = IdentityHashMap<Any, Int>()
        fun plan(target: JSONObject, source: JSONObject): Int {
            var result = size(target)
            var count = target.length()
            source.keys().forEach { key ->
                val next = source.get(key)
                val old = target.opt(key)
                if (old is JSONObject && next is JSONObject) {
                    result += plan(old, next) - size(old)
                } else if (old != next) {
                    result += size(next) - if (target.has(key)) size(old) else 0
                    if (!target.has(key)) {
                        result += stringSize(key) + 1 + if (count++ > 0) 1 else 0
                    }
                    edits += Edit(target, key, next)
                }
            }
            updatedSizes[target] = result
            return result
        }
        val total = plan(value, patch)
        if (total > limit) {
            sizes.clear()
            size(value)
            error("JSON document exceeds ${limit / 1024}KB")
        }
        edits.forEach { edit ->
            forget(edit.target.opt(edit.key))
            edit.target.put(edit.key, edit.next)
        }
        sizes.putAll(updatedSizes)
        bytes = total
    }

    fun snapshot(): JSONObject = copy(value) as JSONObject
    fun set(key: String, next: Any?) = merge(JSONObject().put(key, next ?: JSONObject.NULL))

    private fun forget(value: Any?) {
        when (value) {
            is JSONObject -> {
                sizes.remove(value)
                value.keys().forEach { forget(value.opt(it)) }
            }
            is JSONArray -> {
                sizes.remove(value)
                for (i in 0 until value.length()) forget(value.opt(i))
            }
        }
    }
    private fun size(value: Any?): Int {
        return when (value) {
            null, JSONObject.NULL -> 4
            is String -> stringSize(value)
            is Boolean -> value.toString().length
            is Number -> JSONObject.numberToString(value).length
            is JSONObject -> sizes[value] ?: run {
                var bytes = 2 + (value.length() - 1).coerceAtLeast(0)
                val keys = value.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    bytes += stringSize(key) + 1 + size(value.opt(key))
                }
                sizes[value] = bytes
                bytes
            }
            is JSONArray -> sizes[value] ?: run {
                var bytes = 2 + (value.length() - 1).coerceAtLeast(0)
                for (i in 0 until value.length()) bytes += size(value.opt(i))
                sizes[value] = bytes
                bytes
            }
            else -> error("Unsupported JSON type")
        }
    }
    companion object {
        private fun stringSize(value: String): Int {
            // Count unescaped characters without allocating a quoted string and
            // byte array per list cell. Android and the JVM reference serializer
            // escape some ranges differently; let the local serializer handle
            // controls, slashes, U+2000..20FF and all surrogate code units.
            var bytes = 2
            for (char in value) {
                bytes += when {
                    char in ' '..'~' && char != '"' && char != '\\' && char != '/' -> 1
                    char in '\u00a0'..'\u07ff' -> 2
                    char in '\u0800'..'\u1fff' || char in '\u2100'..'\ud7ff' || char in '\ue000'..'\uffff' -> 3
                    else -> return JSONObject.quote(value).toByteArray(Charsets.UTF_8).size
                }
            }
            return bytes
        }
        fun copy(value: Any?): Any? = when (value) {
            is JSONObject -> JSONObject().also { out -> value.keys().forEach { out.put(it, copy(value.opt(it))) } }
            is JSONArray -> JSONArray().also { out -> for (i in 0 until value.length()) out.put(copy(value.opt(i))) }
            else -> value
        }
        fun toHost(value: Any?): Any? = when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> linkedMapOf<String, Any?>().also { out -> value.keys().forEach { out[it] = toHost(value.opt(it)) } }
            is JSONArray -> (0 until value.length()).map { toHost(value.opt(it)) }
            else -> value
        }
        fun fromHost(value: Any?): Any = when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> JSONObject().also { out -> value.forEach { (k, v) -> out.put(k as String, fromHost(v)) } }
            is List<*> -> JSONArray().also { out -> value.forEach { out.put(fromHost(it)) } }
            else -> value
        }
    }
}
