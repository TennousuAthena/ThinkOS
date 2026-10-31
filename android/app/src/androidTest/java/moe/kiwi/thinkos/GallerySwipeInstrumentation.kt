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
import moe.kiwi.thinkos.anim.MotionClock
import moe.kiwi.thinkos.ui.ClockView
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
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch(t: Throwable) { failure=t } }
        failure?.let { throw it }
    }
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
    /** Real photo stage: exercise all exposed edges, and the independent foreground drift. */
    private fun checkViewport(host: RuntimeHost, result: JSONObject) {
        val clock=ThinkStage::class.java.getDeclaredField("clock").also { it.isAccessible=true }.get(stage) as MotionClock
        val dx=ThinkStage::class.java.getDeclaredField("driftX").also { it.isAccessible=true }
        val dy=ThinkStage::class.java.getDeclaredField("driftY").also { it.isAccessible=true }
        val apply=ThinkStage::class.java.getDeclaredMethod("applyDrift").also { it.isAccessible=true }
        val samples=JSONArray()
        val originalOrientation=activity.requestedOrientation
        fun offset(x: Int, y: Int) = main { dx.setFloat(stage,x.toFloat());dy.setFloat(stage,y.toFloat());apply.invoke(stage) }
        fun clockView(v: View): View? {
            if(v is ClockView) return v
            if(v is ViewGroup) for(i in 0 until v.childCount) clockView(v.getChildAt(i))?.let { return it }
            return null
        }
        fun location(v: View) = IntArray(2).also(v::getLocationOnScreen)
        fun coversScreen() = main {
            val root=checkNotNull(photo()) { "Photo disappeared" }
            val expected=location(stage)
            val actual=location(root)
            check(actual.contentEquals(expected) && root.width==stage.width && root.height==stage.height) {
                "Photo viewport ${actual.toList()} ${root.width}x${root.height} != stage ${expected.toList()} ${stage.width}x${stage.height}"
            }
            val visible=Rect();root.getGlobalVisibleRect(visible)
            check(visible==Rect(expected[0],expected[1],expected[0]+stage.width,expected[1]+stage.height)) { "Photo viewport clipped: $visible" }
            check((root.parent as View).alpha==1f) { "Attention faded the photo into its backdrop" }
            check((stage.getChildAt(0) as ViewGroup).getChildAt(0).visibility==View.INVISIBLE) {
                "Covered ambient animation remained visible"
            }
        }
        try {
            for(orientation in listOf(0,1)) {
              main { host.debugOrientation(orientation) }
              waitFor("Photo unavailable after rotation") {
                  var ready=false
                  main { ready=(stage.width>stage.height)==(orientation==0) && photo()!=null }
                  ready
              }
              for(theme in listOf(Theme.LIGHT,Theme.DARK)) { // Bing's ambient page is intentionally absent at NIGHT.
                main { clock.start();host.sensors.debugHoldTheme(theme) }
                SystemClock.sleep(2300)
                main { clock.stop() } // Hold deterministic drift extremes; native photo pan stays live.
                for(attention in listOf(Attention.AMBIENT,Attention.AWAKE)) {
                    main { host.sensors.debugHoldAttention(attention) }
                    for(delay in listOf(30L,150L,500L)) { SystemClock.sleep(delay);coversScreen() }
                    offset(0,0)
                    var baseClock=IntArray(2)
                    main { baseClock=location(checkNotNull(clockView(stage))) }
                    for((x,y) in listOf(-8 to -6,8 to -6,-8 to 6,8 to 6)) {
                        offset(x,y);SystemClock.sleep(50);coversScreen()
                        main {
                            val root=checkNotNull(photo())
                            val caption=(0 until root.childCount).map(root::getChildAt).filterIsInstance<LinearLayout>().single()
                            check(caption.translationX==x.toFloat() && caption.translationY==y.toFloat()) { "Caption lost burn-in drift" }
                            val position=location(checkNotNull(clockView(stage)))
                            check(position[0]==baseClock[0]+x && position[1]==baseClock[1]+y) { "Clock lost burn-in drift" }
                            // A caption relayout must preserve both the viewport and foreground offset.
                            root.requestLayout()
                        }
                        SystemClock.sleep(50);coversScreen()
                        samples.put(JSONObject().put("orientation",if(orientation==0)"landscape" else "portrait")
                            .put("theme",theme.name).put("attention",attention.name).put("x",x).put("y",y))
                    }
                    if(attention==Attention.AWAKE) {
                        val bitmap=uiAutomation.takeScreenshot()
                        File(targetContext.filesDir,"gallery-viewport-$orientation-${theme.name.lowercase()}.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
                        }
                        bitmap.recycle()
                    }
                }
              }
            }
            result.put("viewportCases",samples).put("fullBleedWithForegroundDrift",true)
        } finally { main { host.debugOrientation(originalOrientation);clock.start() } }
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
            if(args.getString("viewport")=="true") {
                checkViewport(host,result);success=true;return
            }
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
                host.sensors.debugHoldAttention(null);host.sensors.debugHoldTheme(null);host.sensors.debugInjectLux(null)
            }
            result.put("complete",success)
            File(targetContext.filesDir,"swipe-instrumentation.json").writeText(result.toString(2))
            finish(if(success)0 else 1,Bundle().apply { putString("result",result.toString()) })
        }
    }
}
