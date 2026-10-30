package moe.kiwi.thinkos

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.Theme
import moe.kiwi.thinkos.ui.ThinkStage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Timed touch streams inside one process; separate adb input invocations cannot produce fast repeats. */
class GallerySwipeInstrumentation : Instrumentation() {
    private var args = Bundle()
    private lateinit var activity: MainActivity
    private lateinit var stage: ThinkStage
    private val events = JSONArray()
    private var largestJump = 0f
    private var violations = 0
    private var firstMove = false
    private var previousDelta = 0f
    private var traceStarted = 0L
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); args = arguments ?: Bundle(); start() }
    private fun main(block: () -> Unit) = runOnMainSync(block)
    private fun photo(): FrameLayout? {
        fun search(v: View): FrameLayout? {
            if (v is FrameLayout && (0 until v.childCount).any { v.getChildAt(it) is LinearLayout } &&
                (0 until v.childCount).any { (v.getChildAt(it) as? FrameLayout)?.getChildAt(0) is ImageView }) return v
            if (v is ViewGroup) for (i in 0 until v.childCount) search(v.getChildAt(i))?.let { return it }
            return null
        }
        return search(stage)
    }
    private fun layers(): Map<Int, Float> {
        val root = photo() ?: return emptyMap()
        return (0 until root.childCount).mapNotNull { i ->
            val v = root.getChildAt(i) as? FrameLayout ?: return@mapNotNull null
            if (v.getChildAt(0) !is ImageView) return@mapNotNull null
            System.identityHashCode(v) to v.translationY
        }.toMap()
    }
    private fun selected(): String { var s = ""; main { s = photo()?.contentDescription?.toString().orEmpty() }; return s }
    private fun waitFor(message: String, check: () -> Boolean) {
        val end = SystemClock.uptimeMillis()+15000
        while (SystemClock.uptimeMillis()<end) { if (check()) return; SystemClock.sleep(10) }
        error(message)
    }
    private fun event(action: Int, down: Long, at: Long, delta: Float, inspect: Boolean) {
        val delay = at-SystemClock.uptimeMillis(); if (delay>0) SystemClock.sleep(delay)
        main {
            val before = layers()
            val e = MotionEvent.obtain(down,at,action,stage.width*.5f,stage.height*.5f+delta,0)
            stage.dispatchTouchEvent(e); e.recycle()
            val after = layers()
            var jump = 0f
            for ((id,y) in before) after[id]?.let { jump = maxOf(jump,abs(it-y)) }
            if (inspect && firstMove) {
                largestJump = maxOf(largestJump,jump)
                val limit = abs(delta-previousDelta)+ViewConfiguration.get(activity).scaledPagingTouchSlop+2f
                if (jump>limit) violations++
            }
            events.put(JSONObject().put("t",SystemClock.uptimeMillis()-traceStarted).put("action",action)
                .put("dy",delta).put("before",JSONObject(before.mapKeys { it.key.toString() }))
                .put("after",JSONObject(after.mapKeys { it.key.toString() })).put("selected",photo()?.contentDescription))
            if (action==MotionEvent.ACTION_MOVE) firstMove=false
            previousDelta=delta
        }
    }
    private fun swipe(delta: Float, millis: Long, inspect: Boolean=false, move: Boolean=true, cancel: Boolean=false) {
        val start=SystemClock.uptimeMillis(); firstMove=true; previousDelta=0f
        event(MotionEvent.ACTION_DOWN,start,start,0f,false)
        if (move) event(MotionEvent.ACTION_MOVE,start,start+millis/2,delta*.6f,inspect)
        event(if(cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP,start,start+millis,delta,false)
    }
    private fun resetFixture() {
        if (args.getString("page","gallery-history-probe.history")!="gallery-history-probe.history") return
        repeat(3) { if(selected()!="A") { swipe(stage.height*.65f,180); SystemClock.sleep(650) } }
        check(selected()=="A") { "Cannot reset fixture to A" }
    }
    override fun onStart() {
        val result=JSONObject(); var success=false
        try {
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            stage=MainActivity::class.java.getDeclaredField("stage").also { it.isAccessible=true }.get(activity) as ThinkStage
            val host=(activity.application as ThinkApplication).runtime
            main { host.sensors.debugHoldAttention(Attention.AWAKE); host.sensors.debugInjectLux(20f); host.store.update { it.copy(theme=Theme.DARK) } }
            val page=args.getString("page","gallery-history-probe.history")
            waitFor("Page unavailable") { var ready=false;main { ready=host.router.pages.any { it.key==page } };ready }
            main { host.router.open(page) }
            waitFor("Photo not ready") { selected().isNotEmpty() };SystemClock.sleep(900);resetFixture()
            val config=ViewConfiguration.get(activity);val baseline=selected()
            traceStarted=SystemClock.uptimeMillis()
            repeat(4) { swipe(-(config.scaledTouchSlop+3f),8);SystemClock.sleep(60) }
            SystemClock.sleep(650)
            val tinyChanged=selected()!=baseline
            result.put("tinyHighVelocityChangedSelection",tinyChanged)
            resetFixture()
            val short=-config.scaledPagingTouchSlop*3f
            // Catch both a committed turn and a cancelled return before they have finished.
            swipe(short,30);SystemClock.sleep(35);swipe(short,30,true);SystemClock.sleep(35)
            swipe(-short,30,true);SystemClock.sleep(650)
            swipe(-config.scaledPagingTouchSlop*.7f,180);SystemClock.sleep(20);swipe(short,30,true);SystemClock.sleep(650)
            repeat(6) { i -> swipe(if(i%2==0)short else -short,30,true);SystemClock.sleep(25) }
            SystemClock.sleep(900)
            result.put("takeoverMaxJumpPx",largestJump).put("takeoverDiscontinuities",violations)
            resetFixture();val beforeCancel=selected()
            swipe(stage.height*-.4f,120,cancel=true);SystemClock.sleep(700)
            result.put("cancelPreservesSelection",selected()==beforeCancel)
            var settled=false
            main { settled=layers().size==1 && layers().values.all { abs(it)<.5f } }
            result.put("oneSettledLayer",settled)
            result.put("events",events)
            if(args.getString("expect","after")=="after") {
                check(!tinyChanged) { "Tiny fast motion changed the image" }
                check(violations==0) { "A takeover reset the presented position" }
                check(selected()==beforeCancel && settled) { "Cancellation left a stale preview or displacement" }
            }
            success=true
        } catch(t:Throwable) { result.put("error",t.stackTraceToString()) }
        finally {
            if(::activity.isInitialized) main {
                val host=(activity.application as ThinkApplication).runtime
                host.sensors.debugHoldAttention(null);host.sensors.debugInjectLux(null)
            }
            result.put("complete",success)
            File(targetContext.filesDir,"swipe-instrumentation.json").writeText(result.toString(2))
            finish(if(success)0 else 1,Bundle().apply { putString("result",result.toString()) })
        }
    }
}
