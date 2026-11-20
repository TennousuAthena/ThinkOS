package moe.kiwi.thinkos.perf

import android.view.MotionEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InputTraceTest {
    private val millis = 1_000_000L
    private val names = Array(16) { "mark" + it }

    @Test fun ringOverwritesOldestAndResetClearsLossCount() {
        val ring = TraceRing(2)
        (1..3).forEach { ring.add(TraceRing.INPUT, it, it.toLong()) }
        val snapshot = ring.snapshot()
        assertEquals(listOf(2, 3), snapshot.records.map { it.id })
        assertEquals(1, snapshot.dropped)
        ring.reset()
        assertEquals(TraceSnapshot(emptyList(), 0), ring.snapshot())
    }

    @Test fun firstAppliedMovePairsWithFirstEligibleVsyncAndUsesIntendedCompletion() {
        val ring = TraceRing(16)
        ring.add(TraceRing.INPUT, 1, 105 * millis, 104, MotionEvent.ACTION_DOWN.toLong())
        ring.add(TraceRing.INPUT, 1, 115 * millis, 110, MotionEvent.ACTION_MOVE.toLong())
        ring.add(TraceRing.APPLIED, 1, 119 * millis, 110, InputTrace.HORIZONTAL.toLong())
        ring.add(TraceRing.FRAME, 0, 108 * millis, 116 * millis, 13 * millis)
        ring.add(TraceRing.FRAME, 0, 120 * millis, 124 * millis, 12 * millis)
        ring.add(TraceRing.INPUT, 1, 135 * millis, 130, MotionEvent.ACTION_UP.toLong())
        val gesture = JSONObject(TraceReport.build(ring.snapshot(), names)).getJSONArray("gestures").getJSONObject(0)
        assertEquals(110L, gesture.getLong("firstMove"))
        assertEquals(119.0, gesture.getDouble("firstApplied"), 0.0)
        assertEquals(132.0, gesture.getDouble("firstSwap"), 0.0)
        assertEquals(22.0, gesture.getJSONArray("followLagMs").getDouble(0), 0.0)
    }

    @Test fun boundsReportAndCountsTruncatedSamples() {
        val ring = TraceRing(2048)
        repeat(65) { index ->
            ring.add(TraceRing.INPUT, index + 1, millis, 1, MotionEvent.ACTION_DOWN.toLong())
        }
        repeat(257) { ring.add(TraceRing.APPLIED, 1, 2 * millis, 1, InputTrace.HORIZONTAL.toLong()) }
        ring.add(TraceRing.FRAME, 0, 3 * millis, 3 * millis, millis)
        val report = TraceReport.build(ring.snapshot(), names)
        val parsed = JSONObject(report)
        val gestures = parsed.getJSONArray("gestures")
        assertEquals(64, gestures.length())
        assertEquals(256, gestures.getJSONObject(0).getJSONArray("followLagMs").length())
        assertEquals(2, parsed.getInt("dropped"))
        assertTrue(report.toByteArray().size <= 64 * 1024)
    }

    @Test fun disabledProbeDoesNotRecordAndRunsWrappedCode() {
        assertTrue(!InputTrace.enabled)
        InputTrace.applied(InputTrace.HORIZONTAL, 10f)
        InputTrace.launched(300)
        assertEquals(3, InputTrace.marked(InputTrace.BIND) { 3 })
        assertEquals(0, JSONObject(InputTrace.report()).getJSONArray("gestures").length())
    }
}
