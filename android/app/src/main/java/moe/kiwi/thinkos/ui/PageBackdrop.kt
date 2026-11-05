package moe.kiwi.thinkos.ui

import org.json.JSONArray
import org.json.JSONObject

/** Page content, not the widgets displayed inside home cards, decides whether the ambient field is covered. */
internal object PageBackdrop {
    fun quiet(value: Any?): Boolean = when (value) {
        is JSONObject -> value.optString("type") in setOf("form", "list", "grid", "tabs") ||
            value.keys().asSequence().any { quiet(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { quiet(value.opt(it)) }
        else -> false
    }
}
