package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject

/**
 * Views that move the device's own levels or draw the control sheet. Only a plugin that holds `system.device` (which only
 * built-in plugins are ever given) may declare them (ADR-035).
 */
internal object DeviceViews {
    /** A slider anywhere in the view, or the `panel` layout itself. */
    fun used(value: Any?): Boolean = when (value) {
        is JSONObject -> value.optString("type") == "slider" || value.optString("layout") == "panel" || value.keys().asSequence().any { used(value.opt(it)) }
        is JSONArray -> (0 until value.length()).any { used(value.opt(it)) }
        else -> false
    }

    fun isPanelLayout(view: JSONObject): Boolean = view.optString("layout") == "panel"
}
