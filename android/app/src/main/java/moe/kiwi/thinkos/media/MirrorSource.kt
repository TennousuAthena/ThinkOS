package moe.kiwi.thinkos.media

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import java.io.IOException
import java.io.InterruptedIOException
import java.util.ArrayDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A live MPEG-TS byte stream that the OS itself produces (screen mirroring from Miracast), played by the same engine as
 * everything else (ADR-022 / ADR-031). Only code inside the OS can build a [MediaEntry] that carries one: the Lua side has no
 * way to name it, and its URL scheme is refused everywhere a plugin supplies an address.
 *
 * The producer [write]s whole TS packets as they arrive; the player reads them. The queue is short on purpose: a mirror
 * is only worth watching if it is live, so when the player cannot keep up the oldest bytes are dropped (the TS demuxer
 * resynchronises on the next packet) rather than letting the picture fall behind.
 */
class MirrorSource(
    /** Shown in logs and state. */
    val label: String,
    private val capacityBytes: Int = DEFAULT_CAPACITY_BYTES,
) {
    private val lock = ReentrantLock()
    private val ready = lock.newCondition()
    private val chunks = ArrayDeque<ByteArray>()
    private var queued = 0
    private var closed = false

    @Volatile var droppedBytes = 0L
        private set
    @Volatile var receivedBytes = 0L
        private set

    init { require(capacityBytes >= 16 * 1024) { "A mirror queue needs at least 16 KiB" } }

    /** Never blocks. Returns false once the source is closed. */
    fun write(chunk: ByteArray): Boolean = lock.withLock {
        if (closed) return false
        chunks.addLast(chunk)
        queued += chunk.size
        receivedBytes += chunk.size
        while (queued > capacityBytes && chunks.size > 1) {
            val old = chunks.removeFirst()
            queued -= old.size
            droppedBytes += old.size
        }
        ready.signal()
        true
    }

    /** Ends the stream: readers get what is queued, then end-of-input. */
    fun close() = lock.withLock { closed = true; ready.signalAll() }

    val isClosed: Boolean get() = lock.withLock { closed }

    /** Bytes waiting for the player. */
    val queuedBytes: Int get() = lock.withLock { queued }

    /** Waits up to [timeoutMillis] for data. Returns the byte count, 0 on timeout, -1 when closed and drained. */
    internal fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMillis: Long): Int {
        var left = TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        lock.lockInterruptibly()
        try {
            while (chunks.isEmpty()) {
                if (closed) return -1
                if (left <= 0) return 0
                left = ready.awaitNanos(left)
            }
            var copied = 0
            while (copied < length && chunks.isNotEmpty()) {
                val head = chunks.peekFirst()!!
                val take = minOf(length - copied, head.size)
                System.arraycopy(head, 0, buffer, offset + copied, take)
                copied += take
                queued -= take
                if (take == head.size) chunks.removeFirst()
                else { chunks.removeFirst(); chunks.addFirst(head.copyOfRange(take, head.size)) }
            }
            return copied
        } finally { lock.unlock() }
    }

    companion object {
        const val SCHEME = "thinkos-mirror"
        const val DEFAULT_CAPACITY_BYTES = 384 * 1024
    }
}

/** Gives ExoPlayer the bytes of one [MirrorSource]. Opening it twice is a mistake, not a retry: a mirror cannot rewind. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MirrorDataSource(private val source: MirrorSource) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        if (dataSpec.position != 0L) throw IOException("A mirror cannot seek")
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (true) {
            val count = try { source.read(buffer, offset, length, POLL_MILLIS) } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("Mirror read cancelled")
            }
            when {
                count > 0 -> { bytesTransferred(count); return count }
                count < 0 -> return C.RESULT_END_OF_INPUT
            }
            // Timed out with nothing queued: the sender may simply have nothing to say. Keep waiting; ExoPlayer cancels the load by interrupting this thread.
        }
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        if (opened) { opened = false; transferEnded() }
    }

    private companion object { const val POLL_MILLIS = 100L }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MirrorDataSourceFactory(private val source: MirrorSource) : DataSource.Factory {
    override fun createDataSource(): DataSource = MirrorDataSource(source)
}

/**
 * Brings a mirror back to the live edge. A shared screen arrives in real time, so whatever the player holds ahead of the playhead
 * (while a decoder was starting, after a network stall) is delay that never drains at normal speed. Playing a little faster until
 * the surplus is gone is how that delay is paid back: the pitch is kept by the audio processor and the picture only looks brisk.
 *
 * What the player holds is never below about 270 ms (its renderers read that far ahead of the playhead; below it the player runs dry and
 * buffers), so "caught up" has to be above that floor. With the old 150 ms it was out of reach: the speed-up never ended, and the player ran
 * dry and refilled over and over, which is a wheel that keeps turning and a picture that stutters.
 */
internal class MirrorPace(
    private val engageAboveMillis: Long = 500,
    private val settleBelowMillis: Long = 330,
) {
    var speed = 1f
        private set

    /** [aheadMillis] is how much media the player holds beyond the playhead. Returns the speed to play at. */
    fun update(aheadMillis: Long): Float {
        speed = when {
            aheadMillis > engageAboveMillis -> 1.06f + 0.24f * ((aheadMillis - engageAboveMillis) / 1100f).coerceIn(0f, 1f)
            aheadMillis < settleBelowMillis -> 1f
            else -> speed                                   // in between: keep going, so the speed does not flutter around the threshold
        }
        return speed
    }

    fun reset() { speed = 1f }
}
