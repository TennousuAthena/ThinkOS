package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.i18n.Formats
import moe.kiwi.thinkos.tz.ZoneFormat
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Templates are parsed once at view compilation, never on a state update.
 *
 *  - `{{path | format}}` reads plugin state; formats are an allow-list, not expressions
 *  - `@key` (the whole text) or `{{@key}}` reads the plugin's own translated strings
 *  - `time` and `date` formats accept named formats (`@time`, `@dateFull`) so output follows the
 *    system locale and 12/24-hour setting, and render in the device's own time zone
 */
object Binding {
    private val NO_STRINGS: (String) -> String? = { null }

    class Template internal constructor(private val parts: List<Part>) {
        val dependencies: Set<String> = parts.mapNotNull { it.path }.toSet()
        fun affected(paths: Set<String>): Boolean = dependencies.any { path ->
            paths.any { changed -> changed.isEmpty() || path == changed || path.startsWith("$changed.") || changed.startsWith("$path.") }
        }
        fun render(state: JSONObject, variant: String, strings: (String) -> String? = NO_STRINGS, item: JSONObject? = null): String = buildString {
            parts.forEach { append(it.render(state, variant, strings, item)) }
        }
    }

    internal class Part(val literal: String?, val path: String?, val formats: List<(Any?) -> Any?>, val reference: String? = null) {
        fun render(state: JSONObject, variant: String, strings: (String) -> String?, item: JSONObject?): String {
            literal?.let { return it }
            reference?.let { return strings(it) ?: it }          // a missing translation shows its key
            var value = when {
                path == "variant" -> variant
                path?.startsWith('.') == true -> item?.let { lookup(it, path.substring(1)) }
                else -> item?.let { lookup(it, path.orEmpty()) } ?: lookup(state, path.orEmpty())
            }
            formats.forEach { value = it(value) }
            return if (value == null || value == JSONObject.NULL) "" else value.toString()
        }
    }

    fun compile(template: String): Template {
        if (template.startsWith("@") && !template.startsWith("@@") && "{{" !in template) {
            return Template(listOf(Part(null, null, emptyList(), reference = template.substring(1))))
        }
        val parts = mutableListOf<Part>()
        var offset = 0
        while (offset < template.length) {
            val start = template.indexOf("{{", offset)
            val end = if (start >= 0) template.indexOf("}}", start + 2) else -1
            if (start < 0 || end < 0) { parts += Part(literalText(template.substring(offset), offset == 0), null, emptyList()); break }
            if (start > offset) parts += Part(literalText(template.substring(offset, start), offset == 0), null, emptyList())
            val expression = template.substring(start + 2, end).split('|').map(String::trim)
            val head = expression.first()
            parts += if (head.startsWith("@")) Part(null, null, emptyList(), reference = head.substring(1))
                     else Part(null, head, expression.drop(1).map(::formatter))
            offset = end + 2
        }
        return Template(parts)
    }

    /** `@@x` is the escaped literal `@x`, but only at the very start of a template. */
    private fun literalText(text: String, atStart: Boolean) = if (atStart && text.startsWith("@@")) text.substring(1) else text

    private fun formatter(spec: String): (Any?) -> Any? {
        val op = spec.substringBefore(' ')
        val arg = spec.substringAfter(' ', "").trim().trim('"', '\'')
        val precision = arg.toIntOrNull()?.coerceIn(0, 6) ?: 0
        val pattern = if (op in setOf("time", "date")) arg.ifBlank { if (op == "time") "@time" else "@date" } else ""
        return { value ->
            when (op) {
                "default" -> if (value == null || value == JSONObject.NULL || value.toString().isEmpty()) arg else value
                "upper" -> value?.toString()?.uppercase(Locale.getDefault())
                "lower" -> value?.toString()?.lowercase(Locale.getDefault())
                "round" -> value?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite)?.roundToLong()
                "fixed" -> value?.toString()?.toDoubleOrNull()?.takeIf(Double::isFinite)?.let { String.format(Locale.getDefault(), "%.${precision}f", it) }
                "time", "date" -> value?.toString()?.toDoubleOrNull()?.let { millis ->
                    runCatching { ZoneFormat.format(pattern, millis.toLong(), Zones.home()) }.getOrNull()
                }
                else -> value
            }
        }
    }

    fun render(template: String, state: JSONObject, variant: String): String = compile(template).render(state, variant)

    fun lookup(state: JSONObject, path: String): Any? {
        var current: Any? = state
        path.split('.').forEach { part ->
            current = when (val value = current) {
                is JSONObject -> value.opt(part)
                is JSONArray -> part.toIntOrNull()?.let(value::opt)
                else -> null
            }
        }
        return current.takeUnless { it == JSONObject.NULL }
    }

    fun changed(old: JSONObject?, next: JSONObject): Set<String> {
        if (old == null) return setOf("")
        val paths = mutableSetOf<String>()
        fun visit(a: Any?, b: Any?, path: String) {
            if (a === b) return
            if (a is JSONObject && b is JSONObject) {
                (a.keys().asSequence().toSet() + b.keys().asSequence().toSet()).forEach { key ->
                    visit(a.opt(key), b.opt(key), if (path.isEmpty()) key else "$path.$key")
                }
            } else if (a is JSONArray && b is JSONArray && a.length() == b.length()) {
                for (i in 0 until a.length()) visit(a.opt(i), b.opt(i), "$path.$i")
            } else if (a != b) paths += path
        }
        visit(old, next, "")
        return paths
    }

    /** For tests and diagnostics: which named formats exist. */
    val namedFormats: Set<String> get() = Formats.names
}
