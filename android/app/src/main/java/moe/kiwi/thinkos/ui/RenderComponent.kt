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
    /**
     * A drag that starts at ([x], [y]) in this component's own coordinates belongs to the component (a seek bar):
     * the stage must not turn it into a page swipe.
     */
    fun capturesHorizontalAt(x: Float, y: Float): Boolean = false
    /** Move stationary photo labels without moving the edge-to-edge image viewport. */
    fun burnInOffset(x: Int, y: Int) {}
    fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?)
    fun colors(value: Palette)
    /** Only the selected page/card is active; drag previews may be attached but remain paused. */
    fun active(value: Boolean) {}
    /** Native capability tap without taking ownership of the OS's swipe/long-press gestures. */
    fun tap(): Boolean = false
    /** A finger went down at ([x], [y]) in this component's own coordinates, before it is known to be a tap or a drag. */
    fun touchDown(x: Float, y: Float) {}
    /** [tap] for a component that cares where: the tap landed at ([x], [y]) in its own coordinates. */
    fun tapAt(x: Float, y: Float): Boolean = tap()
    /** OS-owned vertical content browsing; horizontal navigation and long press stay with the stage. */
    fun dragVertical(offset: Float): Boolean = false
    /**
     * [dragVertical] for the call that begins a drag: it began at ([x], [y]) in this component's own coordinates. Once a
     * component has taken the drag, later moves arrive through [dragVertical] alone.
     */
    fun dragVerticalFrom(x: Float, y: Float, offset: Float): Boolean = dragVertical(offset)
    fun releaseVertical(offset: Float, velocity: Float) {}
    fun tick(monotonic: Long, attention: Attention) {}
    fun localeChanged() {}
}
