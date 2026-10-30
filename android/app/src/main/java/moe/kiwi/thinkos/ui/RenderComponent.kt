package moe.kiwi.thinkos.ui

import android.view.View
import moe.kiwi.thinkos.core.Attention
import org.json.JSONObject

/**
 * OS-internal renderer lifecycle. The declaration is supplied to PageEvents.component at creation;
 * plugins only see the schema's fixed component vocabulary, never this interface or native views.
 */
interface RenderComponent {
    val view: View
    val defaultWeight: Double get() = 1.0
    /** Keeps vertical scrolling and native controls inside this component. */
    val interactive: Boolean get() = false
    /** A component such as a list dispatches its own row taps. */
    val handlesTap: Boolean get() = false
    /** True only while this renderer needs display-rate ticks (for example an image transition). */
    val animating: Boolean get() = false
    fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?)
    fun colors(value: Palette)
    fun tick(monotonic: Long, attention: Attention) {}
    fun localeChanged() {}
}
