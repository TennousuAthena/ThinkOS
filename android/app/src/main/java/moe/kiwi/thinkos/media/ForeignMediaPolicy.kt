package moe.kiwi.thinkos.media

/** Android apps share decoders and audio focus with the OS session (ADR-035). */
internal object ForeignMediaPolicy {
    enum class Action { KEEP, PAUSE, STOP }
    fun action(kind: Any?, mirror: Any?, status: Any?): Action = when {
        mirror == true -> Action.STOP
        kind == "video" && status in setOf("playing", "buffering", "reconnecting") -> Action.PAUSE
        else -> Action.KEEP
    }
}
