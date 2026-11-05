package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.core.Attention

/** Immutable projection made on MediaEngine's main-thread callback. Enabling a
 * cast listener is not a cast session. A paused video still reserves the session. */
internal object UpdateEnvironment {
    fun from(attention: Attention, media: Map<String, Any?>, foreign: Boolean): UpdateSession.Environment {
        val active = media["status"] in setOf("playing", "buffering", "reconnecting", "paused")
        return UpdateSession.Environment(attention,
            active && media["kind"] == "video",
            active && (media["mirror"] == true || (media["owner"] as? String)?.startsWith("cast:") == true), foreign,
            (media["owner"] as? String)?.isNotBlank() == true || active)
    }
}
