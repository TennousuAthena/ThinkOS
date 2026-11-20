package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.plugin.LayoutVocabulary
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginWidget
import org.json.JSONArray
import org.json.JSONObject

internal object PreparePolicy {
    private val mediaTypes = setOf("video", "tv", "nowplaying", "controls", "seekbar")
    private val mediaBinding = Regex("""\{\{\s*(?:media(?:[.}\s]|$)|mediaCover\b|coverUrl\b)""")

    fun allowed(page: PluginPage, widgets: List<PluginWidget>): Boolean {
        if (page.live || page.pluginId == "settings" || page.pluginId == "cast" ||
            page.key == "player.library" || "onboarding" in page.roles || "settings" in page.roles ||
            page.view.optString("layout") !in LayoutVocabulary.slots) return false

        val byKey = widgets.associateBy { it.key }
        val checked = hashSetOf<String>()
        fun safe(value: Any?): Boolean = when (value) {
            is JSONObject -> {
                val type = value.optString("type")
                type !in mediaTypes && !(type == "form" && value.has("nav")) &&
                    value.keys().asSequence().all { safe(value.opt(it)) }
            }
            is JSONArray -> (0 until value.length()).all { safe(value.opt(it)) }
            is String -> {
                val widget = byKey[value]
                !mediaBinding.containsMatchIn(value) && (widget == null || !checked.add(value) ||
                    safe(widget.view) && widget.alternates.values.all(::safe))
            }
            else -> true
        }
        return safe(page.view)
    }

    fun imageAllowedWhileWarm(src: String, owner: String): Boolean {
        if (!src.startsWith("https:", ignoreCase = true) && !src.startsWith("http:", ignoreCase = true)) return true
        return owner == "gallery" && src.startsWith("https://www.bing.com/th?id=OHR.")
    }
}
