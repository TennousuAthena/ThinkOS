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
    /** Measured native photo captions reserve the bottom band from other stage overlays. */
    val bottomOverlayInset: Int get() = 0
    /** Move stationary photo labels without moving the edge-to-edge image viewport. */
    fun burnInOffset(x: Int, y: Int) {}
    fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?)
    fun colors(value: Palette)
    /** Only the selected page/card is active; drag previews may be attached but remain paused. */
    fun active(value: Boolean) {}
    /** Native capability tap without taking ownership of the OS's swipe/long-press gestures. */
    fun tap(): Boolean = false
    /** OS-owned vertical content browsing; horizontal navigation and long press stay with the stage. */
    fun dragVertical(offset: Float): Boolean = false
    fun releaseVertical(offset: Float, velocity: Float) {}
    fun tick(monotonic: Long, attention: Attention) {}
    fun localeChanged() {}
}
