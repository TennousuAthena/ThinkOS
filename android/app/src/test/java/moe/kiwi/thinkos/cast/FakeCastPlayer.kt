package moe.kiwi.thinkos.cast

import moe.kiwi.thinkos.media.MediaEntry

/** Plays the part of the engine: records what a protocol asked and answers with a believable state. */
internal class FakeCastPlayer : CastPlayer {
    val calls = mutableListOf<String>()
    var loaded: MediaEntry? = null
    var refuse = false
    var state = CastState(owned = false, status = CastStatus.IDLE)

    @Synchronized override fun load(entry: MediaEntry, autoplay: Boolean) {
        if (refuse) throw IllegalStateException("engine busy")
        calls += "load autoplay=$autoplay"; loaded = entry
        state = CastState(true, if (autoplay) CastStatus.PLAYING else CastStatus.PAUSED, 0, 120_000, 1f)
    }
    @Synchronized override fun play() { calls += "play"; state = state.copy(status = CastStatus.PLAYING) }
    @Synchronized override fun pause() { calls += "pause"; state = state.copy(status = CastStatus.PAUSED) }
    @Synchronized override fun stop() { calls += "stop"; state = CastState(false, CastStatus.IDLE) }
    @Synchronized override fun seek(millis: Long) { calls += "seek $millis"; state = state.copy(positionMillis = millis) }
    @Synchronized override fun volume(fraction: Float) { calls += "volume $fraction"; state = state.copy(volume = fraction) }
    @Synchronized override fun state() = state
}
