package moe.kiwi.thinkos.perf

import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

internal object TraceReport {
    private const val NS_PER_MS = 1_000_000L
    private const val MAX_SAMPLES = 256
    private const val MAX_ROWS = 64
    private const val MAX_BYTES = 64 * 1024
    private const val MAX_PAIR_NS = 2_000 * NS_PER_MS
    private val dragKinds = listOf("", "drag-h", "drag-v", "stack", "panel", "back", "up", "slider", "seek")

    private fun ms(nanos: Long) = (nanos / 10_000.0).roundToLong() / 100.0
    private fun swap(frame: TraceRecord) = frame.time + frame.extra

    fun build(snapshot: TraceSnapshot, names: Array<String>): String {
        val frames = snapshot.records.filter { it.type == TraceRing.FRAME }.sortedBy { it.value }
        val groups = snapshot.records.filter { it.id != 0 }.groupBy { it.id }
        val gestures = JSONArray()
        val taps = JSONArray()
        var dropped = snapshot.dropped
        var bytesLeft = MAX_BYTES - 512
        fun addRow(array: JSONArray, row: JSONObject) {
            val bytes = row.toString().toByteArray(Charsets.UTF_8).size + 1
            if (array.length() >= MAX_ROWS || bytes > bytesLeft) { dropped++; return }
            array.put(row)
            bytesLeft -= bytes
        }
        fun pair(applied: Long): TraceRecord? {
            val index = frames.binarySearchBy(applied) { it.value }.let { if (it < 0) -it - 1 else it }
            return frames.getOrNull(index)?.takeIf { swap(it) in applied..(applied + MAX_PAIR_NS) }
        }
        fun samples(values: List<Double>): JSONArray {
            dropped += (values.size - MAX_SAMPLES).coerceAtLeast(0)
            return JSONArray(values.take(MAX_SAMPLES))
        }
        for ((id, records) in groups) {
            val marks = records.filter { it.type == TraceRing.MARK }.groupBy { it.extra.toInt() }
            val lastBind = marks[InputTrace.BIND]?.lastOrNull()
            if (id < 0) {
                val event = records.firstOrNull { it.type == TraceRing.EVENT } ?: continue
                val up = groups[event.value.toInt()]?.firstOrNull { it.type == TraceRing.INPUT && it.extra == MotionEvent.ACTION_UP.toLong() }
                val row = JSONObject().put("seq", -id).put("plugin", event.label).put("gestureId", event.value)
                up?.let {
                    row.put("up", it.value).put("upToEventMs", ms(event.time - it.value * NS_PER_MS))
                        .put("upToDispatchMs", ms(it.time - it.value * NS_PER_MS))
                        .put("upDispatchToEventMs", ms(event.time - it.time))
                        .put("duringUpDispatch", event.extra == 1L)
                }
                marks[InputTrace.QUEUE]?.firstOrNull()?.let { row.put("queueMs", ms(it.time - event.time)) }
                val lua = marks[InputTrace.LUA]?.firstOrNull()
                lua?.let { row.put("luaMs", ms(it.value - it.time)) }
                marks[InputTrace.PUBLISH]?.firstOrNull()?.let { publish ->
                    row.put("publishWaitMs", ms(publish.value - publish.time))
                    if (lua != null) {
                        val afterLua = (publish.value - maxOf(publish.time, lua.value)).coerceAtLeast(0L)
                        row.put("publishAfterLuaMs", ms(afterLua))
                    }
                }
                marks[InputTrace.DELIVER]?.firstOrNull()?.let { row.put("deliverMs", ms(it.value - it.time)) }
                marks[InputTrace.DIFF]?.firstOrNull()?.let { row.put("diffMs", ms(it.value - it.time)) }
                lastBind?.let { bind ->
                    row.put("bindMs", ms(bind.value - bind.time))
                    if (up != null) pair(bind.value)?.let {
                        row.put("swap", ms(swap(it))).put("bindToSwapMs", ms(swap(it) - bind.value))
                    }
                }
                addRow(taps, row)
                continue
            }
            val down = records.firstOrNull { it.type == TraceRing.INPUT && it.extra == MotionEvent.ACTION_DOWN.toLong() } ?: continue
            val end = records.lastOrNull { it.type == TraceRing.INPUT &&
                (it.extra == MotionEvent.ACTION_UP.toLong() || it.extra == MotionEvent.ACTION_CANCEL.toLong()) }
            val moves = records.filter { it.type == TraceRing.APPLIED }
            val first = moves.firstOrNull()
            val row = JSONObject().put("id", id).put("kind", first?.let { dragKinds[it.extra.toInt()] } ?: "tap")
                .put("down", down.value).put("downDispatchMs", ms(down.time - down.value * NS_PER_MS)).put("complete", end != null)
            end?.let { row.put("end", it.value) }
            val gc = records.filter { it.type == TraceRing.GC }
            if (gc.size == 2) {
                if (gc[0].time in 0..gc[1].time) row.put("gcBlocking", gc[1].time - gc[0].time)
                if (gc[0].value in 0..gc[1].value) row.put("gcTotal", gc[1].value - gc[0].value)
            }
            if (first != null) {
                row.put("firstMove", first.value).put("firstApplied", ms(first.time))
                pair(first.time)?.let { row.put("firstSwap", ms(swap(it))) }
                val lag = moves.mapNotNull { move -> pair(move.time)?.let { ms(swap(it) - move.value * NS_PER_MS) } }
                val inDrag = frames.filter { it.value in first.time..(end?.time ?: moves.last().time) }
                row.put("followLagMs", samples(lag)).put("unpairedMoves", moves.size - lag.size)
                if (first.extra.toInt() == InputTrace.HORIZONTAL || first.extra.toInt() == InputTrace.VERTICAL) {
                    val vertical = first.extra.toInt() == InputTrace.VERTICAL
                    fun coordinate(record: TraceRecord) = Float.fromBits((if (vertical) record.y else record.x).toInt())
                    val finger = records.filter {
                        it.type == TraceRing.INPUT && (it.extra == MotionEvent.ACTION_MOVE.toLong() || it === end)
                    }
                    val errors = moves.mapNotNull { move ->
                        val completed = pair(move.time)?.let(::swap) ?: return@mapNotNull null
                        val next = finger.indexOfFirst { it.value * NS_PER_MS >= completed }
                        if (next <= 0) return@mapNotNull null
                        val after = finger[next]
                        val before = finger[next - 1]
                        if (after.value <= before.value || move.extra != first.extra) return@mapNotNull null
                        val progress = (completed - before.value * NS_PER_MS).toDouble() / ((after.value - before.value) * NS_PER_MS)
                        val raw = coordinate(before) + progress * (coordinate(after) - coordinate(before)) - coordinate(down)
                        val displayed = Float.fromBits(move.x.toInt())
                        (abs(displayed - raw) * 100.0).roundToInt() / 100.0
                    }
                    row.put("followPositionErrorPx", samples(errors))
                }
                row.put("frameIntervalsMs", samples(inDrag.zipWithNext { before, after -> ms(after.value - before.value) }))
            }
            marks[InputTrace.PRESS]?.firstOrNull()?.let { mark -> pair(mark.value)?.let { row.put("pressSwap", ms(swap(it))) } }
            marks[InputTrace.WAKE]?.firstOrNull()?.let { mark -> pair(mark.value)?.let { row.put("wakeSwap", ms(swap(it))) } }
            if (end != null && end.extra == MotionEvent.ACTION_UP.toLong()) {
                marks[InputTrace.LAUNCH]?.firstOrNull { it.time >= end.time }?.let { mark ->
                    pair(mark.value)?.let { row.put("launchSwap", ms(swap(it))) }
                    val inLaunch = frames.filter { it.value in mark.value..(mark.value + mark.x * NS_PER_MS) }
                    row.put("launchIntervalsMs", samples(inLaunch.zipWithNext { before, after -> ms(after.value - before.value) }))
                }
            }
            val durations = JSONObject()
            for ((mark, spans) in marks) {
                val inGesture = spans.filter { it.time >= down.time && it.time <= (end?.time ?: down.time) + MAX_PAIR_NS }
                if (inGesture.isNotEmpty()) durations.put(names[mark], samples(inGesture.map { ms(it.value - it.time) }))
            }
            addRow(gestures, row.put("marks", durations))
        }
        val report = JSONObject().put("gestures", gestures).put("taps", taps).put("frames", frames.size)
        val pages = JSONArray()
        val spans = snapshot.records.filter { it.type == TraceRing.PAGE }.groupBy { it.label!!.substringBefore('\n') }
        for ((key, records) in spans) {
            val kinds = records.groupBy { it.label!!.substringAfter('\n') }
            val builds = kinds["build"].orEmpty()
            val parts = JSONObject()
            kinds.filterKeys { it != "build" && it != "page.update" && it != "page.slots" }.entries.sortedByDescending { (_, spans) ->
                spans.sumOf { it.value - it.time }
            }.take(10).forEach { (name, spans) ->
                parts.put(name, ms(spans.sumOf { it.value - it.time }))
            }
            val row = JSONObject().put("key", key).put("buildMs", samples(builds.map { ms(it.value - it.time) }))
                .put("topMs", parts).put("updateMs", samples(kinds["page.update"].orEmpty().map { ms(it.value - it.time) }))
                .put("slotsMs", samples(kinds["page.slots"].orEmpty().map { ms(it.value - it.time) }))
            val pss = builds.filter { it.extra != Long.MIN_VALUE }.map { it.extra }
            if (pss.isNotEmpty()) row.put("buildPssKiB", JSONArray(pss))
            addRow(pages, row)
        }
        if (pages.length() > 0) report.put("pages", pages)
        return report.put("dropped", dropped).toString()
    }
}
