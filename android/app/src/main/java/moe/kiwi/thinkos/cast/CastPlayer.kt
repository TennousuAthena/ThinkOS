package moe.kiwi.thinkos.cast

import moe.kiwi.thinkos.media.MediaEntry

internal enum class CastStatus { IDLE, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/** What a protocol needs to know about playback. A plain value: it is read on the protocol's threads, not the main thread. */
internal data class CastState(
    /** True while the engine plays what this protocol loaded and nothing else has taken the session since. */
    val owned: Boolean,
    val status: CastStatus,
    val positionMillis: Long = 0,
    val durationMillis: Long = 0,
    /** The session's own gain, 0..1. */
    val volume: Float = 1f,
    val seekable: Boolean = true,
)

/**
 * The only thing a casting protocol may do to the device: drive the one OS media session (ADR-022 / ADR-031). It is an
 * interface so the protocol logic runs in plain JVM tests with a fake.
 */
internal interface CastPlayer {
    /**
     * Replaces whatever is playing and takes the screen. Playback starts at once only when [autoplay] is true. Returns when the
     * engine has accepted the item, so a Play that follows sees it; throws when the engine refuses it.
     */
    fun load(entry: MediaEntry, autoplay: Boolean)
    fun play()
    fun pause()
    /** Ends the cast and gives the screen back. */
    fun stop()
    fun seek(millis: Long)
    fun volume(fraction: Float)
    fun state(): CastState
}
