package moe.kiwi.thinkos.ui

import android.content.Context
import org.json.JSONObject

/** What on-screen content may ask of the rest of the OS. Plugins never see views, only these events. */
interface PageEvents {
    /** Delivers a UI event (`form.change`, `form.reset`, …) to the plugin that owns the page. */
    fun send(pluginId: String, name: String, args: List<Any?>)

    /** Leave the settings page and return to where the user was. */
    fun closeSettings()

    /** Native dialogs and IME edits count as activity even outside the page's touch window. */
    fun settingsInteraction()

    /** A translated string from the plugin's own tables (`@key` in a view), or null if it has none. */
    fun text(pluginId: String, key: String): String?

    /** Factory for OS capabilities (media, images); this is not a plugin-provided style API. */
    fun component(context: Context, pluginId: String, definition: JSONObject): RenderComponent? = null
}
