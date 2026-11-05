package moe.kiwi.thinkos.ui

import android.content.Context
import org.json.JSONObject

/** What on-screen content may ask of the rest of the OS. Plugins never see views, only these events. */
interface PageEvents {
    val tilesLive: Boolean
    val tilesIntervalMillis: Long
    /** `indigo`, `teal` or `wine`. */
    val tilesBackground: String get() = "indigo"
    /** Delivers a UI event (`form.change`, `form.reset`, …) to the plugin that owns the page. */
    fun send(pluginId: String, name: String, args: List<Any?>)

    /** Leave the settings page and return to where the user was. */
    fun closeSettings()
    /** OS chrome can open the same settings destination as a long press. */
    fun openSettings(owner: String) = Unit
    fun openPage(key: String) = Unit

    /** Native dialogs and IME edits count as activity even outside the page's touch window. */
    fun settingsInteraction()

    /** Native descriptor preview. Null ends the preview; this does not submit a form change. */
    fun previewSetting(pluginId: String, scope: String, key: String, kind: String, value: Double?) = Unit

    /** A translated string from the plugin's own tables (`@key` in a view), or null if it has none. */
    fun text(pluginId: String, key: String): String?

    /** The control sheet that opens from the top edge (ADR-035): whether it may open, how high the edge zone is, how long it waits. */
    val panelEnabled: Boolean get() = false
    val panelEdgeDp: Float get() = 24f
    val panelIdleMillis: Long get() = 15_000L
    /** The sheet started to open or finished closing; while it is open the display stays attentive. */
    fun panelChanged(open: Boolean) = Unit

    /** The back swipe from the left edge (ADR-040): whether it may start, and how wide the strip it starts in is. */
    val backEnabled: Boolean get() = true
    val backEdgeDp: Float get() = 24f
    val upEnabled: Boolean get() = true
    val upEdgeDp: Float get() = 24f

    /** Factory for OS capabilities (media, images); this is not a plugin-provided style API. */
    fun component(context: Context, pluginId: String, definition: JSONObject): RenderComponent? = null
}
