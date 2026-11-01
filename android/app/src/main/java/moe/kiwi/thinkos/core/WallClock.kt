package moe.kiwi.thinkos.core

import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The time the OS shows. The device has no RTC and an app cannot set the system clock on API 27, so once
 * `time/TimeSync` has a reading from a time server, the time is that reading plus the monotonic time elapsed
 * since. Neither a ROM's own NTP step nor a person changing the system clock moves it, and a missed sync
 * only lets it drift, it never snaps back to the (here about 350 ms slow) system clock.
 *
 * Until the first reading it is the system clock, and follows it when that changes.
 */
object WallClock {
    /** A moment known on both clocks: true time was [wall] when the monotonic clock read [elapsed]. */
    data class Anchor(val wall: Long, val elapsed: Long)

    private var realtime: () -> Long = System::currentTimeMillis
    private var monotonic: () -> Long = SystemClock::elapsedRealtime

    @Volatile private var anchor: Anchor? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    /** True time in epoch milliseconds. Safe from any thread. */
    fun now(): Long {
        val a = anchor ?: return realtime()
        return a.wall + (monotonic() - a.elapsed)
    }

    /** The monotonic clock WallClock counts with (milliseconds since boot, including sleep). */
    fun elapsed(): Long = monotonic()

    /** True once a time server has set the time; false while the system clock is trusted as is. */
    val synced: Boolean get() = anchor != null

    /** The reading the time is built on, for saving. Null while unsynced. */
    fun anchor(): Anchor? = anchor

    /** Take [wall] as the true time at monotonic [elapsed]. */
    fun adopt(wall: Long, elapsed: Long) {
        anchor = Anchor(wall, elapsed)
        listeners.forEach { it() }
    }

    /** The system clock moved. It only matters while it is all we have. */
    fun systemClockChanged() {
        if (anchor == null) listeners.forEach { it() }
    }

    /** Called from the thread that changed the time; hop to your own thread inside. */
    fun addListener(listener: () -> Unit) { listeners.addIfAbsent(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    /** For tests: replace the two clocks and forget any reading. */
    internal fun reset(realtime: () -> Long = System::currentTimeMillis, monotonic: () -> Long = SystemClock::elapsedRealtime) {
        this.realtime = realtime
        this.monotonic = monotonic
        anchor = null
        listeners.clear()
    }
}
