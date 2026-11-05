package moe.kiwi.thinkos.core

/**
 * Who gets a Back press (ADR-040). An app that has its own levels declares `tos.back.intercept(true)` and is told first.
 * One that was told and did nothing for [graceMillis] — hung, or no handler at all — is not allowed to trap the user:
 * the next press leaves anyway.
 *
 * Time comes in as an argument so the rules can be tested without waiting.
 */
class BackGate(private val graceMillis: Long = GRACE_MILLIS) {
    enum class Verdict {
        /** The plugin is told with a `back` event and the screen stays. */
        DELIVER,
        /** The layer is peeled off. */
        LEAVE,
    }

    private class Pending(val plugin: String, val since: Long) { var answered = false }

    private val intercepting = HashSet<String>()
    private var pending: Pending? = null

    fun intercepts(plugin: String): Boolean = plugin in intercepting

    fun intercept(plugin: String, on: Boolean) {
        if (on) intercepting += plugin else forget(plugin)
        // Giving the interception back is an answer too.
        if (!on) answered(plugin)
    }

    /** The plugin reacted to the `back` it was sent (it changed what it shows, closed itself, gave the interception back). */
    fun answered(plugin: String) { pending?.takeIf { it.plugin == plugin }?.answered = true }

    /** The plugin's page left the screen: whatever it declared for that page ends with it. */
    fun forget(plugin: String) {
        intercepting -= plugin
        if (pending?.plugin == plugin) pending = null
    }

    /** A Back press while the layer on top belongs to [plugin] (null if it belongs to nobody who can intercept). */
    fun back(plugin: String?, now: Long): Verdict {
        if (plugin == null || plugin !in intercepting) { pending = null; return Verdict.LEAVE }
        val waiting = pending?.takeIf { it.plugin == plugin && !it.answered }
        if (waiting != null && now - waiting.since >= graceMillis) {
            // Told, and silent for too long: it forfeits the interception.
            forget(plugin)
            return Verdict.LEAVE
        }
        // Keep counting from the first press that went unanswered, so pressing again and again cannot postpone the way out.
        if (waiting == null) pending = Pending(plugin, now)
        return Verdict.DELIVER
    }

    companion object {
        const val GRACE_MILLIS = 2_000L
    }
}
