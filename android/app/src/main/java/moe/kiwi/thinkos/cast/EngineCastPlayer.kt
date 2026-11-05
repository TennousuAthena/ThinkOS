package moe.kiwi.thinkos.cast

import android.os.Handler
import android.os.Looper
import moe.kiwi.thinkos.media.MediaEngine
import moe.kiwi.thinkos.media.MediaEntry
import moe.kiwi.thinkos.media.MediaOptions
import moe.kiwi.thinkos.media.MediaSourceType
import moe.kiwi.thinkos.media.MirrorSource
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** How the engine's snapshot reads as a [CastState] for the session [owner]. Pure, so it is tested without the engine. */
internal object CastStates {
    fun from(snapshot: Map<String, Any?>, owner: String, position: Long? = null): CastState {
        val owned = snapshot["owner"] == owner
        val status = when (snapshot["status"]) {
            "playing" -> CastStatus.PLAYING
            "buffering", "reconnecting" -> CastStatus.BUFFERING
            "paused" -> CastStatus.PAUSED
            "ended" -> CastStatus.ENDED
            "error" -> CastStatus.ERROR
            else -> CastStatus.IDLE
        }
        return CastState(
            owned = owned, status = if (owned) status else CastStatus.IDLE,
            positionMillis = position ?: (snapshot["position"] as? Number)?.toLong() ?: 0L,
            durationMillis = (snapshot["duration"] as? Number)?.toLong() ?: 0L,
            volume = (snapshot["volume"] as? Number)?.toFloat() ?: 1f,
            seekable = snapshot["seekable"] as? Boolean ?: false,
        )
    }
}

/**
 * A protocol's view of the one OS media session (ADR-022). Its calls come from network threads; the engine belongs to the
 * main thread, so each call is handed over and waited for, never run on the caller's thread.
 */
internal class EngineCastPlayer(
    private val media: MediaEngine,
    private val main: Handler,
    private val owner: String,
    /** The session started or changed: show the cast page for this kind (`video` / `audio`). Runs on the main thread. */
    private val shown: (String) -> Unit,
    /** The cast ended by the sender's request: give the screen back. Runs on the main thread. */
    private val ended: () -> Unit,
) : CastPlayer {
    @Volatile private var last = CastState(owned = false, status = CastStatus.IDLE)

    private fun <T> onMain(timeoutMillis: Long = 4000, block: () -> T): T {
        if (Looper.myLooper() === Looper.getMainLooper()) return block()
        val task = FutureTask(block)
        if (!main.post(task)) throw IllegalStateException("The UI thread is gone")
        try { return task.get(timeoutMillis, TimeUnit.MILLISECONDS) } catch (error: TimeoutException) {
            task.cancel(false)
            throw IllegalStateException("The UI thread is busy")
        } catch (error: java.util.concurrent.ExecutionException) {
            throw (error.cause as? RuntimeException) ?: IllegalStateException(error.cause)
        }
    }

    override fun load(entry: MediaEntry, autoplay: Boolean) {
        val ticket = media.playbackGeneration()
        onMain {
            media.requireAwake(ticket)
            media.load(owner, listOf(entry), MediaOptions(autoplay = autoplay))
            shown(entry.kind)
        }
    }

    /** A shared screen (Miracast): the OS's own live stream, played in the engine's low-latency profile. */
    fun loadMirror(source: MirrorSource, title: String) {
        val ticket = media.playbackGeneration()
        onMain {
            media.requireAwake(ticket)
            val entry = MediaEntry("${MirrorSource.SCHEME}://${owner.substringAfter(':')}", title = title, kind = "video", type = MediaSourceType.PROGRESSIVE, mirror = source)
            media.load(owner, listOf(entry), MediaOptions(autoplay = true))
            shown("video")
        }
    }

    private fun mine() = media.snapshot()["owner"] == owner

    /** Play after the viewer swiped away from the cast page also brings the page back. */
    override fun play() = onMain {
        if (mine()) {
            media.play()
            (media.snapshot()["kind"] as? String)?.takeIf { it.isNotEmpty() }?.let(shown)
        }
    }
    override fun pause() = onMain { if (mine()) media.pause() }
    override fun seek(millis: Long) = onMain { if (mine()) media.seek(millis) }
    override fun volume(fraction: Float) = onMain { if (mine()) media.volume(fraction.coerceIn(0f, 1f)) }

    override fun stop() = onMain {
        // Never end somebody else's playback: the session may have been taken over since the controller last looked.
        if (mine()) { media.stop(); ended() }
    }

    /** The protocol's session is over for a reason of its own (the sender went away): give the screen back, whoever plays now. */
    fun releaseScreen() = onMain { ended() }

    override fun state(): CastState = try {
        onMain(500) { CastStates.from(media.snapshot(), owner, media.positionNow()) }.also { last = it }
    } catch (error: IllegalStateException) { last }
}
