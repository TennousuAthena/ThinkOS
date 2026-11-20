package moe.kiwi.thinkos.perf

import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.os.Trace
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import moe.kiwi.thinkos.BuildConfig

internal object InputTrace {
    const val NEW_PAGE = 1
    const val PREPARE = 2
    const val RENDER = 3
    const val TAKE = 4
    const val PRESS_AT = 5
    const val PRESS = 6
    const val QUEUE = 7
    const val LUA = 8
    const val PUBLISH = 9
    const val DELIVER = 10
    const val DIFF = 11
    const val BIND = 12
    const val TOUCH = 13
    const val WAKE = 14
    const val LAUNCH = 15
    const val UP_DISPATCH = 16
    const val TAB_CLICK = 17
    const val DECLARATIVE_TAP = 18

    const val HORIZONTAL = 1
    const val VERTICAL = 2
    const val STACK = 3
    const val PANEL = 4
    const val BACK = 5
    const val UP = 6
    const val SLIDER = 7
    const val SEEK = 8

    private val names = arrayOf("", "newPage", "prepareNeighbor", "renderDeck", "takePrepared", "pressAt", "press",
        "tapQueue", "luaDispatch", "publish", "stateDeliver", "diff", "bind", "touch", "wake", "launch",
        "upDispatch", "tabClick", "declarativeTap")
    private val sections = Array(names.size) { "TOS:" + names[it] }

    @Volatile var enabled = false
        private set
    var pageDetail = false
        private set
    var pagePss = false
        private set
    var pageEpoch = 0
        private set
    private var pageKey: String? = null
    private val ring by lazy { TraceRing(2048) }
    private var window: Window? = null
    private var listener: Window.OnFrameMetricsAvailableListener? = null
    @Volatile private var frameThread: HandlerThread? = null
    private var gesture = 0
    private var touchDownTime = -1L
    private var action = -1
    private var dispatching = false
    private var eventMs = 0L
    private var lastUp = 0L
    private var delivering = 0
    private var wakeTouch = 0L
    private var sequence = 0

    fun window(value: Window?) {
        if (!BuildConfig.DEBUG || window === value) return
        detachFrames()
        window = value
        attachFrames()
    }

    fun enable(value: Boolean, detail: Boolean = false, pss: Boolean = false) {
        if (!BuildConfig.DEBUG) return
        pageDetail = value && detail
        pagePss = pageDetail && pss
        if (enabled == value) return
        if (value) reset()
        enabled = value
        if (value) attachFrames() else detachFrames()
    }

    private fun attachFrames() {
        if (!enabled) return
        val target = window ?: return
        val thread = HandlerThread("ThinkOS-FrameMetrics", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        frameThread = thread
        var verified = false
        val callback = Window.OnFrameMetricsAvailableListener { _, metrics, dropped ->
            if (!enabled || frameThread !== thread) return@OnFrameMetricsAvailableListener
            val intended = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            val vsync = metrics.getMetric(FrameMetrics.VSYNC_TIMESTAMP)
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
            val swap = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION)
            val now = System.nanoTime()
            if (intended <= 0L || vsync < intended || total <= 0L || swap < 0L || total > now - intended) {
                ring.drop(dropped + 1)
                return@OnFrameMetricsAvailableListener
            }
            if (!verified) {
                val uptime = SystemClock.uptimeMillis()
                Log.i("ThinkOS", "DEBUG frame metrics clockDeltaMs=" + (uptime - now / 1_000_000) +
                    " completionMs=" + ((intended + total) / 1_000_000))
                verified = true
            }
            ring.drop(dropped)
            ring.add(TraceRing.FRAME, 0, intended, vsync, total, swap)
        }
        listener = callback
        target.addOnFrameMetricsAvailableListener(callback, Handler(thread.looper))
    }

    private fun detachFrames() {
        listener?.let { window?.removeOnFrameMetricsAvailableListener(it) }
        listener = null
        frameThread?.quitSafely()
        frameThread = null
    }

    fun reset() {
        if (!BuildConfig.DEBUG) return
        ring.reset()
        pageEpoch++
        gesture = 0
        touchDownTime = -1L
        dispatching = false
        wakeTouch = 0
        lastUp = 0
    }

    fun input(event: MotionEvent) {
        if (!enabled) return
        action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            gesture = ++sequence
            touchDownTime = event.downTime
            wakeTouch = 0
            lastUp = 0
        }
        if (gesture == 0 || event.downTime != touchDownTime) return
        dispatching = true
        eventMs = event.eventTime
        val now = System.nanoTime()
        if (action == MotionEvent.ACTION_UP) lastUp = now
        ring.add(TraceRing.INPUT, gesture, now, eventMs, action.toLong(),
            event.x.toRawBits().toLong(), event.y.toRawBits().toLong())
        if (action == MotionEvent.ACTION_DOWN) gc()
    }

    fun inputFinished() {
        if (!enabled || !dispatching) return
        dispatching = false
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            gc()
            touchDownTime = -1L
        }
    }

    private fun gc() {
        val blocking = Debug.getRuntimeStat("art.gc.blocking-gc-count")?.toLongOrNull() ?: -1
        val total = Debug.getRuntimeStat("art.gc.gc-count")?.toLongOrNull() ?: -1
        ring.add(TraceRing.GC, gesture, blocking, total)
    }

    fun applied(kind: Int, position: Float) {
        if (!enabled || !dispatching || action != MotionEvent.ACTION_MOVE) return
        ring.add(TraceRing.APPLIED, gesture, System.nanoTime(), eventMs, kind.toLong(), position.toRawBits().toLong())
    }

    fun touchEntry() {
        if (!enabled || Looper.myLooper() != Looper.getMainLooper() || !dispatching) return
        wakeTouch = System.nanoTime()
        ring.add(TraceRing.MARK, gesture, wakeTouch, wakeTouch, TOUCH.toLong())
    }

    fun awake() {
        if (!enabled || wakeTouch == 0L) return
        val now = System.nanoTime()
        if (now - wakeTouch <= 2_000_000_000L) ring.add(TraceRing.MARK, gesture, wakeTouch, now, WAKE.toLong())
        wakeTouch = 0
    }

    fun enqueue(plugin: String?, event: String): Int {
        if (!enabled || plugin == null) return 0
        if (event != "tap" && event != "form.change" && event != "back" && event != "variant" && event != "page.enter") return 0
        val id = ring.nextEvent()
        val now = System.nanoTime()
        val owner = if (Looper.myLooper() == Looper.getMainLooper() && lastUp != 0L &&
            now - lastUp <= 250_000_000L && (event == "tap" || event == "form.change")) gesture else 0
        val duringUp = owner != 0 && dispatching && action == MotionEvent.ACTION_UP
        ring.add(TraceRing.EVENT, id, now, owner.toLong(), if (duringUp) 1L else 0L, label = plugin)
        return id
    }

    fun workerStart(id: Int) {
        if (!enabled || id == 0) return
        val now = System.nanoTime()
        ring.add(TraceRing.MARK, id, now, now, QUEUE.toLong())
    }

    fun publishing(): Long = if (enabled) System.nanoTime() else 0

    fun launched(duration: Long) {
        if (!enabled) return
        val now = System.nanoTime()
        ring.add(TraceRing.MARK, gesture, now, now, LAUNCH.toLong(), duration)
    }

    fun published(id: Int, enqueued: Long) {
        if (!enabled || id == 0 || enqueued == 0L) return
        ring.add(TraceRing.MARK, id, enqueued, System.nanoTime(), PUBLISH.toLong())
    }

    inline fun deliver(id: Int, posted: Long, body: () -> Unit) {
        if (!enabled || id == 0) { body(); return }
        beginDelivery(id, posted)
        try { body() } finally { endDelivery() }
    }

    @PublishedApi internal fun beginDelivery(id: Int, posted: Long) {
        ring.add(TraceRing.MARK, id, posted, System.nanoTime(), DELIVER.toLong())
        delivering = id
    }

    @PublishedApi internal fun endDelivery() { delivering = 0 }

    inline fun <T> marked(mark: Int, body: () -> T): T = marked(mark, 0, body)

    inline fun <T> marked(mark: Int, id: Int, body: () -> T): T {
        if (!enabled || (mark == LUA && id == 0)) return body()
        val start = begin(mark)
        try { return body() } finally { end(mark, id, start) }
    }

    @PublishedApi internal fun begin(mark: Int): Long {
        Trace.beginSection(sections[mark])
        return System.nanoTime()
    }

    @PublishedApi internal fun end(mark: Int, id: Int, start: Long) {
        val owner = if (id != 0) id else if (delivering != 0) delivering else gesture
        ring.add(TraceRing.MARK, owner, start, System.nanoTime(), mark.toLong())
        Trace.endSection()
    }

    inline fun <T> pageSpan(key: String, name: String, body: () -> T): T {
        if (!enabled || !pageDetail) return body()
        val previous = pageKey
        pageKey = key
        val pss = if (pagePss && name == "build") Debug.getPss() else -1L
        val start = System.nanoTime()
        try { return body() } finally {
            val end = System.nanoTime()
            recordPageSpan(key, name, start, end, if (pss >= 0) Debug.getPss() - pss else null)
            pageKey = previous
        }
    }

    inline fun <T> componentSpan(type: String, body: () -> T): T {
        val key = pageKey ?: return body()
        return pageSpan(key, "comp:$type", body)
    }

    private fun recordPageSpan(key: String, name: String, start: Long, end: Long, pssKiB: Long?) {
        ring.add(TraceRing.PAGE, 0, start, end, pssKiB ?: Long.MIN_VALUE, label = "$key\n$name")
    }

    fun report() = TraceReport.build(ring.snapshot(), names)
}

internal data class TraceRecord(val type: Int, val id: Int, val time: Long, val value: Long,
    val extra: Long, val x: Long, val y: Long, val label: String?)

internal data class TraceSnapshot(val records: List<TraceRecord>, val dropped: Int)

internal class TraceRing(private val capacity: Int) {
    companion object {
        const val INPUT = 1
        const val APPLIED = 2
        const val FRAME = 3
        const val MARK = 4
        const val GC = 5
        const val EVENT = 6
        const val PAGE = 7
    }

    private val types = IntArray(capacity)
    private val ids = IntArray(capacity)
    private val times = LongArray(capacity)
    private val values = LongArray(capacity)
    private val extras = LongArray(capacity)
    private val xs = LongArray(capacity)
    private val ys = LongArray(capacity)
    private val labels = arrayOfNulls<String>(capacity)
    private var next = 0
    private var count = 0
    private var dropped = 0
    private var event = 0

    @Synchronized fun nextEvent(): Int = --event

    @Synchronized fun drop(count: Int) { dropped += count }

    @Synchronized fun add(type: Int, id: Int, time: Long, value: Long = 0, extra: Long = 0,
        x: Long = 0, y: Long = 0, label: String? = null) {
        types[next] = type
        ids[next] = id
        times[next] = time
        values[next] = value
        extras[next] = extra
        xs[next] = x
        ys[next] = y
        labels[next] = label
        next = (next + 1) % capacity
        if (count < capacity) count++ else dropped++
    }

    @Synchronized fun reset() {
        count = 0
        next = 0
        dropped = 0
        labels.fill(null)
    }

    @Synchronized fun snapshot(): TraceSnapshot = TraceSnapshot(List(count) { offset ->
        val index = (next - count + capacity + offset) % capacity
        TraceRecord(types[index], ids[index], times[index], values[index], extras[index], xs[index], ys[index], labels[index])
    }, dropped)
}
