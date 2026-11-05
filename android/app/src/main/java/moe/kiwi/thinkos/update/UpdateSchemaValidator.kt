package moe.kiwi.thinkos.update

import org.json.JSONArray
import org.json.JSONObject

/** Schema-driven vocabulary limited to shared/schema/update. Models and documents are generated. */
internal object UpdateSchemaValidator {
    fun validate(value: Any, schema: JSONObject, document: String, documents: Map<String, JSONObject>, depth: Int = 0) {
        require(depth <= 16) { "Update schema nesting exceeded" }
        if (schema.has("\$ref")) {
            val parts = schema.getString("\$ref").split('#', limit = 2)
            val doc = parts[0].ifEmpty { document }
            var target = documents.getValue(doc)
            for (key in parts.getOrElse(1) { "" }.removePrefix("/").split('/').filter(String::isNotEmpty)) target = target.getJSONObject(key)
            validate(value, target, doc, documents, depth + 1)
            return
        }
        when (schema.getString("type")) {
            "object" -> {
                require(value is JSONObject) { "Expected update object" }
                require(value.length() >= schema.optInt("minProperties", 0) && value.length() <= schema.optInt("maxProperties", Int.MAX_VALUE)) { "Update object size rejected" }
                val properties = schema.getJSONObject("properties")
                schema.optJSONArray("required")?.let { required ->
                    for (i in 0 until required.length()) require(value.has(required.getString(i))) { "Missing update field" }
                }
                for (key in value.keys()) {
                    require(properties.has(key)) { "Unknown update field" }
                    validate(value.get(key), properties.getJSONObject(key), document, documents, depth + 1)
                }
            }
            "array" -> {
                require(value is JSONArray) { "Expected update array" }
                require(value.length() in schema.optInt("minItems", 0)..schema.optInt("maxItems", Int.MAX_VALUE)) { "Update array size rejected" }
                val unique = HashSet<Any>()
                for (i in 0 until value.length()) {
                    val item = value.get(i)
                    if (schema.optBoolean("uniqueItems")) require(unique.add(item)) { "Duplicate update array item" }
                    validate(item, schema.getJSONObject("items"), document, documents, depth + 1)
                }
            }
            "string" -> {
                require(value is String) { "Expected update string" }
                val length = value.codePointCount(0, value.length)
                require(length in schema.optInt("minLength", 0)..schema.optInt("maxLength", Int.MAX_VALUE)) { "Update string size rejected" }
                if (schema.has("pattern")) require(Regex(schema.getString("pattern")).containsMatchIn(value)) { "Update string pattern rejected" }
            }
            "integer" -> {
                require(value is Long) { "Expected exact update integer" }
                require(value in schema.optLong("minimum", Long.MIN_VALUE)..schema.optLong("maximum", Long.MAX_VALUE)) { "Update integer bound rejected" }
            }
            "boolean" -> require(value is Boolean) { "Expected update boolean" }
            else -> error("Unsupported generated update schema type")
        }
        fun same(expected: Any): Boolean = if (value is Long && expected is Number) value == expected.toLong() else value == expected
        if (schema.has("const")) require(same(schema.get("const"))) { "Update constant rejected" }
        schema.optJSONArray("enum")?.let { allowed ->
            require((0 until allowed.length()).any { same(allowed.get(it)) }) { "Update enum rejected" }
        }
    }
}
