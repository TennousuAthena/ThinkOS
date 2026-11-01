package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject

/** Views that attach to the OS media session. A plugin without the `media` permission cannot declare them. */
internal object MediaViews {
    private val TYPES = setOf("controls", "video", "seekbar")

    fun used(value: Any?): Boolean = when (value) {
        is JSONObject -> value.optString("type") in TYPES || value.keys().asSequence().any { used(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { used(value.opt(it)) }
        else -> false
    }
}
