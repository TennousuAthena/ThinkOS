package moe.kiwi.thinkos.settings

import org.json.JSONArray
import org.json.JSONObject

enum class FieldType(val wire: String) {
    BOOL("bool"), CHOICE("choice"), NUMBER("number"), TEXT("text");
    companion object { fun of(wire: String): FieldType? = values().firstOrNull { it.wire == wire } }
}

data class Choice(val value: String, val label: String)

/**
 * Why a value was refused. [code] and [args] let the settings page say so in the user's language
 * (`type`, `min`, `max`, `length`, `chars`, `required`, `option`, `order`); [message] is English for logs.
 */
class SettingError(val code: String, val args: Map<String, Any> = emptyMap(), message: String) : IllegalArgumentException(message)

/** Dynamic option lists supplied by the OS (`timezone` and `place`). */
interface OptionSource {
    /** The normalised value (e.g. an alias resolved to its canonical id), or null if it is not an option. */
    fun normalize(source: String, value: String): String?
}

object AnyOption : OptionSource {
    override fun normalize(source: String, value: String): String = value
}

/**
 * One declared setting (ADR-017). Four types only: bool, choice, number, text.
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
    val hidden: Boolean = false,
) {
    /** Returns the normalised value or throws [SettingError]. Numbers come back as Long when integral. */
    fun validate(value: Any?, sources: OptionSource = AnyOption): Any = when (type) {
        FieldType.BOOL -> value as? Boolean ?: throw SettingError("type", message = "expected true or false")
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
                source != null -> sources.normalize(source, text) ?: throw SettingError("option", message = "unknown option: $text")
                choices.any { it.value == text } -> text
                else -> throw SettingError("option", message = "unknown option: $text")
            }
        }
    }

    fun toJson(): JSONObject = JSONObject().put("type", type.wire).put("label", label).put("default", default).apply {
        help?.let { put("help", it) }
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
        if (hidden) put("hidden", true)
    }

    companion object {
        const val DEFAULT_TEXT_LENGTH = 1024
        private val PLUGIN_KEY = Regex("[a-z][a-z0-9_]{0,63}")
        private val SYSTEM_KEY = Regex("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)+")
        private val CHOICE_VALUE = Regex("[A-Za-z0-9_.-]{1,64}")
        private val COMMON = setOf("type", "label", "default", "help", "group", "hidden")
        private val ALLOWED = mapOf(
            FieldType.BOOL to COMMON,
            FieldType.CHOICE to COMMON + setOf("choices", "source", "allowEmpty"),
            FieldType.NUMBER to COMMON + setOf("min", "max", "step", "unit"),
            FieldType.TEXT to COMMON + setOf("maxLength", "secret"),
        )
        val SOURCES = setOf("timezone", "place")

        /** A whole number stays a Long so `{{config.x}}` prints `5`, not `5.0`. */
        fun canonical(number: Double): Any =
            if (number == Math.rint(number) && Math.abs(number) < 9.0e15) number.toLong() else number

        private fun format(number: Double) = canonical(number).toString()

        /** [system] keys are dotted groups (`theme.mode`); plugin keys are plain identifiers. */
        fun parse(key: String, json: JSONObject, system: Boolean, sources: OptionSource = AnyOption): SettingField {
            fun fail(message: String): Nothing = throw IllegalArgumentException("settings.$key: $message")
            if (!(if (system) SYSTEM_KEY else PLUGIN_KEY).matches(key)) fail("invalid key")
            val type = FieldType.of(json.optString("type")) ?: fail("type must be bool, choice, number or text")
            json.keys().forEach { if (it !in ALLOWED.getValue(type)) fail("unknown property '$it' for type ${type.wire}") }
            val label = json.optString("label").also { if (it.isBlank() || it.length > 80) fail("label is required (1-80 characters)") }
            val help = json.optString("help").takeIf { it.isNotEmpty() }?.also { if (it.length > 200) fail("help is limited to 200 characters") }
            val group = json.optString("group").takeIf { it.isNotEmpty() }?.also { if (it.length > 80) fail("group is limited to 80 characters") }
            if (json.has("hidden") && json.get("hidden") !is Boolean) fail("hidden must be true or false")
            val hidden = json.optBoolean("hidden", false)
            if (!json.has("default")) fail("default is required")
            val rawDefault = json.get("default")

            var choices = emptyList<Choice>()
            var source: String? = null
            val allowEmpty = json.optBoolean("allowEmpty", false)
            var min: Double? = null
            var max: Double? = null
            var step: Double? = null
            var unit: String? = null
            var maxLength = DEFAULT_TEXT_LENGTH
            val secret = json.optBoolean("secret", false)

            when (type) {
                FieldType.CHOICE -> {
                    source = json.optString("source").takeIf { it.isNotEmpty() }
                    val array = json.optJSONArray("choices")
                    if ((source == null) == (array == null)) fail("a choice needs exactly one of 'choices' or 'source'")
                    if (source != null && source !in SOURCES) fail("unknown source '$source'")
                    if (array != null) {
                        if (array.length() !in 2..64) fail("choices must list 2-64 options")
                        choices = List(array.length()) { index ->
                            val item = array.optJSONObject(index) ?: fail("choices[$index] must be {value,label}")
                            item.keys().forEach { if (it != "value" && it != "label") fail("choices[$index]: unknown property '$it'") }
                            val value = item.optString("value")
                            val text = item.optString("label")
                            if (!CHOICE_VALUE.matches(value)) fail("choices[$index].value is invalid")
                            if (text.isBlank() || text.length > 80) fail("choices[$index].label is required (1-80 characters)")
                            Choice(value, text)
                        }
                        if (choices.map { it.value }.toSet().size != choices.size) fail("choice values must be unique")
                        if (allowEmpty) fail("allowEmpty only applies to dynamic sources")
                    }
                }
                FieldType.NUMBER -> {
                    min = json.optDouble("min", Double.NaN).takeUnless { it.isNaN() }
                    max = json.optDouble("max", Double.NaN).takeUnless { it.isNaN() }
                    step = json.optDouble("step", Double.NaN).takeUnless { it.isNaN() }
                    if (min == null || max == null) fail("number needs min and max")
                    if (!(min < max)) fail("min must be below max")
                    if (step != null && !(step > 0 && step.isFinite())) fail("step must be positive")
                    unit = json.optString("unit").takeIf { it.isNotEmpty() }?.also { if (it.length > 16) fail("unit is limited to 16 characters") }
                }
                FieldType.TEXT -> {
                    maxLength = json.optInt("maxLength", DEFAULT_TEXT_LENGTH)
                    if (maxLength !in 1..DEFAULT_TEXT_LENGTH) fail("maxLength must be 1-$DEFAULT_TEXT_LENGTH")
                }
                FieldType.BOOL -> Unit
            }
            val field = SettingField(key, type, label, "", help, group, choices, source, allowEmpty, min, max, step, unit, maxLength, secret, hidden)
            val checkedDefault = try {
                field.validate(if (rawDefault == JSONObject.NULL) null else rawDefault, sources)
            } catch (error: SettingError) {
                fail("default ${error.message}")
            }
            return SettingField(key, type, label, checkedDefault, help, group, choices, source, allowEmpty, min, max, step, unit, maxLength, secret, hidden)
        }
    }
}
