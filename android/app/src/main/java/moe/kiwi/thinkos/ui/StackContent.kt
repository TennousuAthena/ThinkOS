package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.plugin.PluginWidget
import org.json.JSONArray
import org.json.JSONObject

/** Widget references can be temporarily unresolved while the catalog loads or changes. */
internal fun resolveStackItem(owner: String, item: Any?, widgets: List<PluginWidget>, prefer: String? = null): Triple<String, JSONObject, String?> {
    val reference = if (item is String) item else (item as? JSONObject)?.optString("widget")
    val widget = widgets.firstOrNull { it.key == reference }
    // A widget can offer one presentation per template, so each container shows it properly.
    if (widget != null) return Triple(widget.pluginId, prefer?.let(widget.alternates::get) ?: widget.view,
        widget.opens?.let { if ('.' in it) it else "${widget.pluginId}.$it" })

    // A missing reference is an empty slot, never an inline component with no type.
    val inline = (item as? JSONObject)?.takeUnless { it.has("widget") }
    return Triple(owner, inline ?: JSONObject().put("children", JSONArray()), null)
}
