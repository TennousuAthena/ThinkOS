package moe.kiwi.thinkos.core

/**
 * The screens layered over the swipe sequence (ADR-040): an app the user opened, a page a plugin took over the screen with, the
 * settings page. Back peels one layer off. Nothing here knows about views or Android; [DeckRouter] applies what it answers.
 *
 * Below the first layer is the page of the sequence the user came from ([base]); it is remembered when the first layer goes on
 * and answered again when the last one comes off.
 */
class NavStack(private val maxLayers: Int = MAX_LAYERS) {
    enum class Kind { APP, TAKEOVER, SETTINGS, ONBOARDING }
    /** One page on screen, with the variant it was showing. */
    data class Frame(val page: String, val variant: String)
    data class Layer(val kind: Kind, val frame: Frame)

    /** What a change did to the screen. */
    sealed class Change {
        /** The page on screen stays the same. */
        object None : Change()
        /** The screen now shows [frame]. */
        data class Show(val frame: Frame) : Change()
        /** The page the stack was built over is gone too: start over from home. */
        object Home : Change()
    }

    private var base: Frame? = null
    private val layers = ArrayList<Layer>()

    val depth: Int get() = layers.size
    val isEmpty: Boolean get() = layers.isEmpty()
    val top: Layer? get() = layers.lastOrNull()
    fun has(kind: Kind): Boolean = layers.any { it.kind == kind }

    /** The page on screen if the top layer went away, or null when there is no layer. */
    val below: Frame? get() = when (layers.size) {
        0 -> null
        1 -> base
        else -> layers[layers.size - 2].frame
    }

    /** Puts [kind] on top, showing [to]. [from] is what is on screen now. False when the stack is full. */
    fun push(from: Frame, kind: Kind, to: Frame): Boolean {
        if (layers.size >= maxLayers) return false
        syncTop(from)
        layers += Layer(kind, to)
        return true
    }

    /** The top layer shows another page (a plugin's takeover moved to its next page): the way back stays where it was. */
    fun replaceTop(to: Frame): Boolean {
        val last = layers.lastOrNull() ?: return false
        layers[layers.size - 1] = last.copy(frame = to)
        return true
    }

    /** The page on screen changed variant (a vertical swipe). */
    fun variantChanged(variant: String) {
        val last = layers.lastOrNull() ?: return
        layers[layers.size - 1] = last.copy(frame = last.frame.copy(variant = variant))
    }

    /** Peels the top layer; answers the frame that is on screen now, or null if there was nothing to peel. */
    fun pop(): Frame? {
        if (layers.isEmpty()) return null
        val shown = below
        layers.removeAt(layers.size - 1)
        if (layers.isEmpty()) base = null
        return shown
    }

    /** Drops every layer; answers the page they were built over. */
    fun clear(): Frame? {
        val shown = base
        layers.clear()
        base = null
        return shown
    }

    /** A plugin closed its own page (or it was taken away): remove its layers wherever they sit. */
    fun remove(page: String): Change {
        val wasTop = layers.lastOrNull()?.frame?.page == page
        if (!layers.removeAll { it.frame.page == page }) return Change.None
        if (layers.isEmpty()) { val shown = base; base = null; return shown?.let { Change.Show(it) } ?: Change.Home }
        return if (wasTop) Change.Show(layers.last().frame) else Change.None
    }

    /** Removes every layer of [kind] (the settings page, whose plugin may already be gone). */
    fun removeKind(kind: Kind): Change {
        val wasTop = layers.lastOrNull()?.kind == kind
        if (!layers.removeAll { it.kind == kind }) return Change.None
        if (layers.isEmpty()) { val shown = base; base = null; return shown?.let { Change.Show(it) } ?: Change.Home }
        return if (wasTop) Change.Show(layers.last().frame) else Change.None
    }

    /** [page] is already a layer: peel everything above it instead of stacking it twice. */
    fun unwindTo(page: String): Change {
        val index = layers.indexOfLast { it.frame.page == page }
        if (index < 0) return Change.None
        if (index == layers.size - 1) return Change.None
        while (layers.size > index + 1) layers.removeAt(layers.size - 1)
        return Change.Show(layers.last().frame)
    }

    /** The catalog changed: drop every layer whose page no longer exists. */
    fun prune(exists: (String) -> Boolean): Change {
        val wasTop = layers.lastOrNull()?.frame?.page
        val before = layers.size
        layers.removeAll { !exists(it.frame.page) }
        if (layers.isEmpty()) {
            val shown = base
            base = null
            if (before == 0) return Change.None
            return if (shown != null && exists(shown.page)) Change.Show(shown) else Change.Home
        }
        // A base that is gone is found out when the layers above it are peeled off; the router falls back to home then.
        return if (layers.last().frame.page != wasTop) Change.Show(layers.last().frame) else Change.None
    }

    private fun syncTop(from: Frame) {
        if (layers.isEmpty()) base = from
        else if (layers.last().frame.page == from.page) layers[layers.size - 1] = layers.last().copy(frame = from)
    }

    companion object {
        /** Deep enough for an app, a takeover over it and the settings page, with room to spare. */
        const val MAX_LAYERS = 8
    }
}
