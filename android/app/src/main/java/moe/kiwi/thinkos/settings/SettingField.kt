package moe.kiwi.thinkos.settings

import org.json.JSONArray
import org.json.JSONObject

enum class FieldType(val wire: String) {
    BOOL("bool"), CHOICE("choice"), NUMBER("number"), TEXT("text"), TIME("time");
    companion object { fun of(wire: String): FieldType? = values().firstOrNull { it.wire == wire } }
}

data class Choice(val value: String, val label: String)

enum class FieldLevel(val wire: String) {
    NORMAL("normal"), ADVANCED("advanced"), HIDDEN("hidden");
    companion object { fun of(wire: String): FieldLevel? = values().firstOrNull { it.wire == wire } }
}

/** A display condition only: changing visibility never changes the stored value. */
data class SettingDependency(val key: String, val values: List<Any>, val multiple: Boolean = false, val negated: Boolean = false) {
    fun matches(value: Any): Boolean = values.any { equal(it, value) }.let { if (negated) !it else it }
    fun toJson(): JSONObject = JSONObject().put("key", key).apply {
        if (negated) put("not", values.single()) else if (multiple) put("in", JSONArray(values)) else put("value", values.single())
    }
    companion object {
        fun equal(a: Any, b: Any): Boolean = if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
    }
}

/**
 * Why a value was refused. [code] and [args] let the settings page say so in the user's language
 * (`type`, `min`, `max`, `length`, `chars`, `required`, `option`, `order`); [message] is English for logs.
 */
class SettingError(val code: String, val args: Map<String, Any> = emptyMap(), message: String) : IllegalArgumentException(message)

/** Dynamic option lists supplied by the OS (`timezone`, `place` and API10 `region`). */
interface OptionSource {
    /** The normalised value (e.g. an alias resolved to its canonical id), or null if it is not an option. */
    fun normalize(source: String, value: String): String?
}

object AnyOption : OptionSource {
    override fun normalize(source: String, value: String): String = value
}

/**
 * One declared setting (ADR-017). API10 adds a local HH:mm time in five-minute steps.
 * Declarations are parsed fail-closed: unknown properties and ill-typed defaults are errors.
 */
class SettingField(
    val key: String,
    val type: FieldType,
    val label: String,
    val default: Any,
    val help: String? = null,
    val group: String? = null,
    val choices: List<Choice> = emptyList(),
    val source: String? = null,
    val allowEmpty: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val maxLength: Int = DEFAULT_TEXT_LENGTH,
    val secret: Boolean = false,
    /** Kept, validated, persisted and deliverable by the Server, but not listed on the device's settings page. */
    hidden: Boolean = false,
    val warn: String? = null,
    val placeholder: String? = null,
    val level: FieldLevel = if (hidden) FieldLevel.HIDDEN else FieldLevel.NORMAL,
    val section: String? = null,
    val depends: SettingDependency? = null,
    val format: String? = null,
    val zero: String? = null,
    val presets: List<Number> = emptyList(),
) {
    val hidden: Boolean get() = level == FieldLevel.HIDDEN
    /** Returns the normalised value or throws [SettingError]. Numbers come back as Long when integral. */
    fun validate(value: Any?, sources: OptionSource = AnyOption): Any = when (type) {
        FieldType.BOOL -> value as? Boolean ?: throw SettingError("type", message = "expected true or false")
        FieldType.TIME -> {
            val text = value as? String ?: throw SettingError("type", message = "expected HH:mm")
            if (SettingTime.minutes(text) == null) throw SettingError("time", message = "expected HH:mm in five-minute steps")
            text
        }
        FieldType.NUMBER -> {
            val number = (value as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: throw SettingError("type", message = "expected a number")
            if (min != null && number < min) throw SettingError("min", mapOf("min" to canonical(min)), "must be at least ${format(min)}")
            if (max != null && number > max) throw SettingError("max", mapOf("max" to canonical(max)), "must be at most ${format(max)}")
            canonical(number)
        }
        FieldType.TEXT -> {
            val text = value as? String ?: throw SettingError("type", message = "expected text")
            if (text.length > maxLength) throw SettingError("length", mapOf("max" to maxLength), "must be at most $maxLength characters")
            if (text.any { it < ' ' && it != '\t' }) throw SettingError("chars", message = "control characters are not allowed")
            text
        }
        FieldType.CHOICE -> {
            val text = value as? String ?: throw SettingError("type", message = "expected one of the listed options")
            when {
                text.isEmpty() -> if (allowEmpty) "" else throw SettingError("required", message = "a choice is required")
                source == "region" -> {
                    if (!REGION_CODE.matches(text)) throw SettingError("option", message = "unknown region")
                    sources.normalize(source, text.uppercase(java.util.Locale.ROOT))
                        ?: throw SettingError("option", message = "unknown region")
                }
                source != null -> sources.normalize(source, text) ?: throw SettingError("option", message = "unknown option: $text")
                choices.any { it.value == text } -> text
                else -> throw SettingError("option", message = "unknown option: $text")
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().put("type", type.wire).put("label", label).put("default", default).apply {
        help?.let { put("help", it) }
        warn?.let { put("warn", it) }
        placeholder?.let { put("placeholder", it) }
        group?.let { put("group", it) }
        if (choices.isNotEmpty()) put("choices", JSONArray().also { array -> choices.forEach { array.put(JSONObject().put("value", it.value).put("label", it.label)) } })
        source?.let { put("source", it) }
        if (allowEmpty) put("allowEmpty", true)
        min?.let { put("min", canonical(it)) }
        max?.let { put("max", canonical(it)) }
        step?.let { put("step", canonical(it)) }
        unit?.let { put("unit", it) }
        if (type == FieldType.TEXT && maxLength != DEFAULT_TEXT_LENGTH) put("maxLength", maxLength)
        if (secret) put("secret", true)
        if (level != FieldLevel.NORMAL) put("level", level.wire)
        section?.let { put("section", it) }
        depends?.let { put("depends", it.toJson()) }
        format?.let { put("format", it) }
        zero?.let { put("zero", it) }
        if (presets.isNotEmpty()) put("presets", JSONArray(presets))
    }

    companion object {
        const val DEFAULT_TEXT_LENGTH = 1024
        private val PLUGIN_KEY = Regex("[a-z][a-z0-9_]{0,63}")
        private val SYSTEM_KEY = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
        private val CHOICE_VALUE = Regex("[A-Za-z0-9_.-]{1,64}")
        private val COMMON = setOf("type", "label", "default", "help", "group", "hidden", "level", "depends")
        private val ALLOWED = mapOf(
            FieldType.BOOL to COMMON + "warn",
            FieldType.CHOICE to COMMON + setOf("choices", "source", "allowEmpty"),
            FieldType.NUMBER to COMMON + setOf("min", "max", "step", "unit", "format", "zero", "presets"),
            FieldType.TEXT to COMMON + setOf("maxLength", "secret", "placeholder"),
            FieldType.TIME to COMMON,
        )
        private val REGION_CODE = Regex("[A-Za-z]{2}")
        val SOURCES = setOf("timezone", "place", "region")

        /** A whole number stays a Long so `{{config.x}}` prints `5`, not `5.0`. */
        fun canonical(number: Double): Any =
            if (number == Math.rint(number) && Math.abs(number) < 9.0e15) number.toLong() else number

        private fun format(number: Double) = canonical(number).toString()

        /** [system] keys are dotted groups (`theme.mode`); plugin keys are plain identifiers. */
        fun parse(key: String, json: JSONObject, system: Boolean, sources: OptionSource = AnyOption): SettingField {
            fun fail(message: String): Nothing = throw IllegalArgumentException("settings.$key: $message")
            if (!(if (system) SYSTEM_KEY else PLUGIN_KEY).matches(key)) fail("invalid key")
            val type = FieldType.of(json.opt("type") as? String ?: "") ?: fail("type must be bool, choice, number, text or time")
            json.keys().forEach { if (it !in ALLOWED.getValue(type) && !(system && it == "section")) fail("unknown property '$it' for type ${type.wire}") }
            val label = (json.opt("label") as? String)?.takeIf { it.isNotBlank() && it.length <= 80 } ?: fail("label is required (1-80 characters)")
            fun prompt(name: String, limit: Int = 200): String? {
                if (!json.has(name)) return null
                val text = json.get(name) as? String ?: fail("$name must be text")
                if (text.length > limit) fail("$name is limited to $limit characters")
                return text.takeIf { it.isNotEmpty() }
            }
            fun boolean(name: String): Boolean = if (json.has(name)) json.get(name) as? Boolean ?: fail("$name must be true or false") else false
            val help = prompt("help")
            val warn = prompt("warn")
            val placeholder = prompt("placeholder")
            val group = prompt("group", 80)
            if (json.has("hidden") && json.get("hidden") !is Boolean) fail("hidden must be true or false")
            val hidden = json.optBoolean("hidden", false)
            if (json.has("hidden") && json.has("level")) fail("hidden and level cannot be combined")
            val level = if (json.has("level")) FieldLevel.of(json.get("level") as? String ?: fail("level must be text"))
                ?: fail("level must be normal, advanced or hidden") else if (hidden) FieldLevel.HIDDEN else FieldLevel.NORMAL
            val section = if (json.has("section")) (json.get("section") as? String)?.takeIf { it.matches(Regex("[a-z][a-z0-9_-]{0,63}")) }
                ?: fail("invalid section") else null
            val depends = if (json.has("depends")) {
                val condition = json.optJSONObject("depends") ?: fail("depends must be an object")
                val target = condition.opt("key") as? String ?: fail("depends needs a key")
                if (!(if (system) SYSTEM_KEY else PLUGIN_KEY).matches(target)) fail("invalid dependency key")
                val operation = listOf("value", "in", "not").singleOrNull { condition.has(it) }
                    ?: fail("depends needs exactly one of value, in or not")
                val multiple = operation == "in"
                if (condition.length() != 2) fail("depends needs exactly key and one of value, in or not")
                val rawValues = if (multiple) {
                    val array = condition.optJSONArray("in") ?: fail("depends.in must be an array")
                    if (array.length() !in 1..64) fail("depends.in needs 1-64 values")
                    List(array.length()) { array.get(it) }
                } else listOf(condition.get(operation))
                val values = rawValues.map { value ->
                    when (value) {
                        is String, is Boolean -> value
                        is Number -> if (value.toDouble().isFinite()) canonical(value.toDouble()) else fail("dependency values must be finite")
                        else -> fail("dependency values must be strings, numbers or booleans")
                    }
                }
                if (values.indices.any { i -> values.take(i).any { SettingDependency.equal(it, values[i]) } }) fail("dependency values must be unique")
                SettingDependency(target, values, multiple, operation == "not")
            } else null
            if (!json.has("default")) fail("default is required")
            val rawDefault = json.get("default")

            var choices = emptyList<Choice>()
            var source: String? = null
            val allowEmpty = boolean("allowEmpty")
            var min: Double? = null
            var max: Double? = null
            var step: Double? = null
            var unit: String? = null
            var maxLength = DEFAULT_TEXT_LENGTH
            val secret = boolean("secret")
            var numberFormat: String? = null
            var zero: String? = null
            var presets = emptyList<Number>()

            when (type) {
                FieldType.CHOICE -> {
                    source = if (json.has("source")) json.get("source") as? String ?: fail("source must be text") else null
                    if (source == "") fail("unknown source")
                    val array = json.optJSONArray("choices")
                    if (json.has("choices") && array == null) fail("choices must be an array")
                    if ((source == null) == (array == null)) fail("a choice needs exactly one of 'choices' or 'source'")
                    if (source != null && source !in SOURCES) fail("unknown source '$source'")
                    if (array != null) {
                        if (array.length() !in 2..64) fail("choices must list 2-64 options")
                        choices = List(array.length()) { index ->
                            val item = array.optJSONObject(index) ?: fail("choices[$index] must be {value,label}")
                            item.keys().forEach { if (it != "value" && it != "label") fail("choices[$index]: unknown property '$it'") }
                            val value = item.opt("value") as? String ?: fail("choices[$index].value must be text")
                            val text = item.opt("label") as? String ?: fail("choices[$index].label must be text")
                            if (!CHOICE_VALUE.matches(value)) fail("choices[$index].value is invalid")
                            if (text.isBlank() || text.length > 80) fail("choices[$index].label is required (1-80 characters)")
                            Choice(value, text)
                        }
                        if (choices.map { it.value }.toSet().size != choices.size) fail("choice values must be unique")
                        if (allowEmpty) fail("allowEmpty only applies to dynamic sources")
                    }
                }
                FieldType.NUMBER -> {
                    fun number(name: String): Double? {
                        if (!json.has(name)) return null
                        return (json.get(name) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fail("$name must be a finite number")
                    }
                    min = number("min")
                    max = number("max")
                    step = number("step")
                    if (min == null || max == null) fail("number needs min and max")
                    if (!(min < max)) fail("min must be below max")
                    if (step != null && !(step > 0 && step.isFinite())) fail("step must be positive")
                    unit = prompt("unit", 16)
                    if (json.has("format")) {
                        if (json.get("format") != "duration") fail("format must be duration")
                        if (min < 0) fail("duration cannot be negative")
                        numberFormat = "duration"
                    }
                    if (json.has("zero")) {
                        zero = (json.get("zero") as? String)?.takeIf { it.isNotBlank() && it.length <= 80 } ?: fail("zero needs 1-80 characters")
                        if (numberFormat == null || min > 0) fail("zero requires duration with zero in range")
                    }
                    if (json.has("presets")) {
                        val array = json.optJSONArray("presets") ?: fail("presets must be an array")
                        if (array.length() !in 2..64) fail("presets needs 2-64 values")
                        presets = List(array.length()) { i ->
                            val value = (array.get(i) as? Number)?.toDouble()?.takeIf { it.isFinite() } ?: fail("presets must be numbers")
                            if (value < min || value > max) fail("preset outside range")
                            canonical(value) as Number
                        }
                        if (presets.map { it.toDouble() }.toSet().size != presets.size) fail("presets must be unique")
                    }
                }
                FieldType.TEXT -> {
                    if (json.has("maxLength")) {
                        val value = (json.get("maxLength") as? Number)?.toDouble() ?: fail("maxLength must be an integer")
                        if (!value.isFinite() || value != Math.rint(value) || value !in 1.0..DEFAULT_TEXT_LENGTH.toDouble()) fail("maxLength must be 1-$DEFAULT_TEXT_LENGTH")
                        maxLength = value.toInt()
                    }
                    if (maxLength !in 1..DEFAULT_TEXT_LENGTH) fail("maxLength must be 1-$DEFAULT_TEXT_LENGTH")
                }
                FieldType.BOOL, FieldType.TIME -> Unit
            }
            fun field(value: Any) = SettingField(key, type, label, value, help, group, choices, source, allowEmpty, min, max, step, unit, maxLength, secret, hidden, warn, placeholder,
                level, section, depends, numberFormat, zero, presets)
            val checkedDefault = try {
                field("").validate(if (rawDefault == JSONObject.NULL) null else rawDefault, sources)
            } catch (error: SettingError) {
                fail("default ${error.message}")
            }
            return field(checkedDefault)
        }
    }
}
