package moe.kiwi.thinkos.i18n

import org.json.JSONObject
import java.io.File
import java.text.NumberFormat
import java.util.Locale

/**
 * A plugin's translated strings: `i18n/<table>.json`, flat `{"key": "text"}`.
 *
 *  - lookup falls back through [LocaleChain] to English, so a missing translation never leaves a hole
 *  - `{name}` placeholders are replaced; numbers are formatted for the locale (digits, separators)
 *  - plural forms are `key.one`, `key.few`, … with `key.other` as the required fallback
 */
class StringTable(private val tables: Map<String, Map<String, String>>) {
    val languages: Set<String> get() = tables.keys
    // NumberFormat is expensive on API 27 and is not thread-safe. Keep one per calling
    // thread and replace it when the locale changes; string-only arguments need none.
    private val numberFormat = ThreadLocal<Pair<Locale, NumberFormat>>()
    private fun numbers(locale: Locale): NumberFormat {
        numberFormat.get()?.let { if (it.first == locale) return it.second }
        return NumberFormat.getInstance(locale).apply {
            maximumFractionDigits = 3
            numberFormat.set(locale to this)
        }
    }

    fun raw(key: String, locale: Locale): String? {
        for (name in LocaleChain.candidates(locale)) tables[name]?.get(key)?.let { return it }
        return null
    }

    fun has(key: String): Boolean = tables.values.any { key in it || "$key.other" in it }

    /** Null when no table has the key, so callers can decide what a missing string looks like. */
    fun format(key: String, locale: Locale, params: Map<String, Any?> = emptyMap()): String? {
        val count = (params["count"] as? Number)?.toDouble()
        val template = (if (count != null) raw("$key.${Plurals.category(locale, count)}", locale) ?: raw("$key.other", locale) else null)
            ?: raw(key, locale) ?: return null
        if (params.isEmpty() || '{' !in template) return template
        return buildString {
            var i = 0
            while (i < template.length) {
                val open = template.indexOf('{', i)
                val close = if (open >= 0) template.indexOf('}', open) else -1
                if (open < 0 || close < 0) { append(template, i, template.length); break }
                append(template, i, open)
                val name = template.substring(open + 1, close)
                when (val value = if (name in params) params[name] else null) {
                    null -> append(template, open, close + 1)         // unknown placeholder stays visible
                    is Number -> append(numbers(locale).format(value))
                    else -> append(value.toString())
                }
                i = close + 1
            }
        }
    }

    companion object {
        const val MAX_FILE_BYTES = 64 * 1024
        const val MAX_KEYS = 512
        val EMPTY = StringTable(emptyMap())

        /** Reads every table file under `i18n/` in [pluginDirectory]; a damaged file is skipped, not fatal. */
        fun load(pluginDirectory: File): StringTable {
            val files = File(pluginDirectory, "i18n").listFiles { f -> f.isFile && f.extension == "json" } ?: return EMPTY
            val tables = linkedMapOf<String, Map<String, String>>()
            for (file in files.sortedBy { it.name }.take(32)) {
                if (file.length() > MAX_FILE_BYTES) continue
                val table = parse(file.readText()) ?: continue
                tables[file.nameWithoutExtension] = table
            }
            return StringTable(tables)
        }

        fun parse(text: String): Map<String, String>? = try {
            val json = JSONObject(text)
            if (json.length() > MAX_KEYS) null
            else json.keys().asSequence().associateWith { json.get(it) as? String ?: return null }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * Turns `"@key"` into text. `@@literal` is an escaped literal `@literal`. For the system scope the
 * key `a.b.c` names the Android string resource `a_b_c`; for a plugin it is a key of its own table.
 */
class Labels(
    private val system: (String) -> String?,
    private val plugin: (pluginId: String, key: String, locale: Locale, params: Map<String, Any?>) -> String?,
) {
    fun resolve(scope: String, text: String, locale: Locale = Locale.getDefault(), params: Map<String, Any?> = emptyMap()): String = when {
        text.startsWith("@@") -> text.substring(1)
        text.startsWith("@") -> {
            val key = text.substring(1)
            (if (scope == "system") system(key) else plugin(scope, key, locale, params)) ?: key      // a missing string shows its key
        }
        else -> text
    }

    companion object {
        fun isReference(text: String) = text.startsWith("@") && !text.startsWith("@@")
    }
}
