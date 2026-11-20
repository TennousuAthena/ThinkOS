package moe.kiwi.thinkos.anim

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.core.Attention
import org.json.JSONArray
import org.json.JSONObject

/** The arithmetic of [ClockTicker], free of Android so it runs in JVM tests. */
object ClockSchedule {
    /** A frame is on the glass about one refresh after it is recorded, so one recorded this close to a boundary already shows the new value. */
    const val PRESENT_LEAD_MILLIS = 17L
    /** Frames are polled from this long before a boundary: two refreshes plus room for a late handler. */
    const val POLL_WINDOW_MILLIS = 40L

    fun bucket(wall: Long, period: Long): Long = Math.floorDiv(wall, period)

    /** The first wall time at which a value of [period] shows the bucket after [bucket]. */
    fun boundary(bucket: Long, period: Long): Long = (bucket + 1) * period

    /** What a frame recorded at [frameWall] will show: the time it reaches the glass. */
    fun displayed(frameWall: Long): Long = frameWall + PRESENT_LEAD_MILLIS

    fun acceptsBucket(bucket: Long, previousBucket: Long, force: Boolean): Boolean =
        force || bucket >= previousBucket

    /** How long to sleep, at [now], before polling frames for a value that changes at [boundaryWall] (displayed time). */
    fun armDelay(now: Long, boundaryWall: Long): Long =
        (boundaryWall - PRESENT_LEAD_MILLIS - POLL_WINDOW_MILLIS - now).coerceAtLeast(0)
}

/**
 * The one heartbeat of every clock the OS draws. A face that read the time whenever the stage happened to tick
 * showed it up to a second late, by an amount that drifted; here each face is told at the second (or minute)
 * boundary itself, by a frame recorded just before it, so the new value is on screen as the true time passes.
 *
 * It sleeps until just before the next boundary of the finest face attached, polls the next frames, delivers on the
 * first one that will be displayed past the boundary, and sleeps again. A late frame still gets the time it is really
 * drawn at: lateness can cost a frame, never a stale value. Main thread only.
 */
object ClockTicker {
    interface Listener {
        /** How often the value changes: 1000 for seconds, 60000 for minutes. Read again at every wake. */
        val periodMillis: Long
        /** [wall] is the true time the frame now being recorded will be shown at. */
        fun onTick(wall: Long)
        fun onWallClockAdjusted(wall: Long) = onTick(wall)
    }

    private class Entry(val listener: Listener) { var bucket = Long.MIN_VALUE }

    private val entries = ArrayList<Entry>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }
    private val choreographer by lazy { Choreographer.getInstance() }
    private var polling = false
    private var listening = false
    private val arm = Runnable { poll() }
    private val frame = Choreographer.FrameCallback { onFrame(it) }
    private val adjusted: () -> Unit = {
        handler.post {
            deliver(ClockSchedule.displayed(WallClock.now()), force = true)
            schedule()
        }
    }

    fun add(listener: Listener) {
        if (entries.any { it.listener === listener }) return
        entries += Entry(listener).also { it.bucket = ClockSchedule.bucket(WallClock.now(), listener.periodMillis.coerceAtLeast(1)) }
        if (!listening) { listening = true; WallClock.addListener(adjusted) }
        schedule()
    }

    fun remove(listener: Listener) {
        entries.removeAll { it.listener === listener }
        if (entries.isEmpty() && listening) { listening = false; WallClock.removeListener(adjusted) }
        schedule()
    }

    /** A listener's period changed: plan again. */
    fun rearm() = schedule()

    private fun poll() {
        if (!polling) { polling = true; choreographer.postFrameCallback(frame) }
    }

    private fun onFrame(frameTimeNanos: Long) {
        polling = false
        // frameTimeNanos is on the System.nanoTime() clock: how long ago this frame started is the difference.
        val frameWall = WallClock.now() - (System.nanoTime() - frameTimeNanos) / 1_000_000L
        deliver(ClockSchedule.displayed(frameWall))
        schedule()
    }

    private fun deliver(displayed: Long, force: Boolean = false) {
        for (entry in entries.toList()) {
            val period = entry.listener.periodMillis.coerceAtLeast(1)
            val bucket = ClockSchedule.bucket(displayed, period)
            if (force || bucket != entry.bucket) {
                entry.bucket = bucket
                if (force) entry.listener.onWallClockAdjusted(displayed) else entry.listener.onTick(displayed)
            }
        }
    }

    private fun schedule() {
        handler.removeCallbacks(arm)
        choreographer.removeFrameCallback(frame)
        polling = false
        if (entries.isEmpty()) return
        val now = WallClock.now()
        val displayed = ClockSchedule.displayed(now)
        var next = Long.MAX_VALUE
        for (entry in entries) {
            val period = entry.listener.periodMillis.coerceAtLeast(1)
            val bucket = ClockSchedule.bucket(displayed, period)
            // A face already behind the display timeline (the clock was stepped, or a frame was lost) is told at once.
            if (bucket != entry.bucket) { poll(); return }
            next = minOf(next, ClockSchedule.boundary(bucket, period))
        }
        val delay = ClockSchedule.armDelay(now, next)
        if (delay == 0L) poll() else handler.postDelayed(arm, delay)
    }
}

/** Debug builds only: how late each clock face really showed its new value. Never read in production. */
object ClockStats {
    enum class Source { TICKER, STAGE, ATTACH }

    private class Face {
        var bucket = Long.MIN_VALUE
        var period = 0L
    }
    private val faces = HashMap<Int, Face>()
    private val lateness = ArrayList<Long>()
    private val awakeLateness = ArrayList<Long>()
    private val lateDetails = ArrayList<JSONObject>()
    private var wholePeriodsLate = 0
    private var skipped = 0
    private var frames = 0

    /** A face recorded a frame showing [bucket] of [period]. Counted once per bucket. */
    fun drawn(face: Int, period: Long, bucket: Long, attention: Attention, view: android.view.View,
              attachedMs: Long, source: Source, updatedWall: Long) {
        val state = faces.getOrPut(face) { Face() }
        if (bucket == state.bucket) return
        // How far past the boundary this frame was recorded; the glass shows it about one refresh later.
        val late = WallClock.now() - bucket * period + ClockSchedule.PRESENT_LEAD_MILLIS
        if (state.bucket != Long.MIN_VALUE) {
            if (bucket - state.bucket > 1) skipped += (bucket - state.bucket - 1).toInt()
            if (lateness.size < 20_000) lateness += late
            if ((attention == Attention.AWAKE || attention == Attention.ENGAGED) && awakeLateness.size < 20_000) {
                awakeLateness += late
            }
            if (late >= 1_000) wholePeriodsLate++
            if (late > 100 && lateDetails.size < 32) {
                var ancestor = view.parent
                while (ancestor != null && ancestor !is moe.kiwi.thinkos.ui.PageView) ancestor = ancestor.parent
                lateDetails += JSONObject().put("face", face).put("page", (ancestor as? moe.kiwi.thinkos.ui.PageView)?.page?.key)
                    .put("lateMs", late).put("periodMillis", period).put("previousPeriodMillis", state.period)
                    .put("bucket", bucket).put("previousBucket", state.bucket).put("attention", attention.name)
                    .put("isShown", view.isShown).put("windowVisibility", view.windowVisibility)
                    .put("attachedMs", attachedMs).put("source", source.name).put("updatedWall", updatedWall)
            }
            frames++
        }
        state.bucket = bucket
        state.period = period
    }

    fun reset() {
        faces.clear()
        lateness.clear()
        awakeLateness.clear()
        lateDetails.clear()
        wholePeriodsLate = 0
        skipped = 0
        frames = 0
    }

    fun details(): String = JSONObject().put("late", JSONArray(lateDetails))
        .put("awakeLatenessMs", JSONArray(awakeLateness.takeLast(1024)))
        .put("awakeCount", awakeLateness.size).put("wholePeriodsLate", wholePeriodsLate).put("skipped", skipped).toString()

    fun report(): String {
        if (lateness.isEmpty()) return "n=0"
        val sorted = lateness.sorted()
        fun at(p: Double) = sorted[((sorted.size - 1) * p).toInt()]
        return "n=${sorted.size} p50=${at(.5)}ms p95=${at(.95)}ms p99=${at(.99)}ms min=${sorted.first()}ms max=${sorted.last()}ms skipped=$skipped"
    }
}
