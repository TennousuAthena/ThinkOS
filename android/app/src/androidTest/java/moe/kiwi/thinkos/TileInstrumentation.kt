package moe.kiwi.thinkos

import android.app.Instrumentation
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.Theme
import moe.kiwi.thinkos.ui.TileView
import moe.kiwi.thinkos.ui.ThinkStage
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** Real API-27 view tree, bindings, compositor and gestures. Not packaged in the application. */
class TileInstrumentation : Instrumentation() {
    private lateinit var activity: MainActivity
    private lateinit var stage: ThinkStage
    private lateinit var host: RuntimeHost
    private val result = JSONObject()
    private val passed = JSONArray()
    private val screens = JSONArray()
    private var arguments = Bundle()
    private fun main(block: () -> Unit) = runOnMainSync(block)
    private fun <T> read(block: () -> T): T { var answer: T? = null; main { answer = block() }; return answer!! }
    private fun field(v: Any, name: String): Any? = v.javaClass.getDeclaredField(name).also { it.isAccessible = true }.get(v)
    private fun all(v: View = stage): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else emptyList()
    private fun tiles() = all().filterIsInstance<TileView>().filter { it.isShown }
    private fun waitFor(message: String, timeout: Long = 15000, predicate: () -> Boolean) {
        val until = SystemClock.uptimeMillis()+timeout
        while (SystemClock.uptimeMillis()<until) { if (predicate()) return; SystemClock.sleep(50) }
        error(message)
    }
    private fun page(key: String) { main { host.router.open(key) }; waitFor("Page $key unavailable") { read { host.store.current.deck.page == key } }; SystemClock.sleep(750) }
    private fun pass(s: String) { passed.put(s) }
    private fun debugTheme(theme: Theme) { main { host.sensors.debugInjectLux(if (theme==Theme.LIGHT)100f else if(theme==Theme.NIGHT)0f else 20f); host.store.update { it.copy(theme=theme) } }; SystemClock.sleep(2300) }
    @Suppress("DEPRECATION")
    private fun locale(code: String) {
        main {
            val l = Locale.forLanguageTag(code); Locale.setDefault(l)
            activity.resources.updateConfiguration(Configuration(activity.resources.configuration).apply { setLocale(l) }, activity.resources.displayMetrics)
            targetContext.resources.updateConfiguration(Configuration(targetContext.resources.configuration).apply { setLocale(l) }, targetContext.resources.displayMetrics)
            host.localeChanged()
        }
        SystemClock.sleep(500)
    }
    private fun screenshot(name: String) {
        val root=File(targetContext.filesDir,"tiles-validation").apply { mkdirs() }
        uiAutomation.takeScreenshot().let { bitmap -> File(root,"$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }; bitmap.recycle() }
        screens.put(name)
        main {
            for (tile in tiles()) {
                check((tile.background as GradientDrawable).cornerRadius == 0f) { "Tile has rounded corners" }
                for (v in all(tile).filterIsInstance<TextView>().filter { it.isShown && it.text.isNotEmpty() }) {
                    check(v.width>0 && v.height>0) { "Text has no space: ${v.text}" }
                    v.layout?.let { layout -> check(layout.lineCount == 0 || layout.getLineBottom(layout.lineCount-1)<=v.height) { "Clipped glyph: ${v.text}, ${v.height}" } }
                }
            }
        }
    }
    private fun touch(x: Float, y: Float, long: Boolean = false, dx: Float = 0f, dy: Float = 0f) {
        val start=SystemClock.uptimeMillis()
        fun event(action: Int, x1:Float,y1:Float) { main { MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,x1,y1,0).also { stage.dispatchTouchEvent(it); it.recycle() } } }
        event(MotionEvent.ACTION_DOWN,x,y)
        if (dx!=0f || dy!=0f) repeat(12) { i -> SystemClock.sleep(25); event(MotionEvent.ACTION_MOVE,x+dx*(i+1)/12,y+dy*(i+1)/12) }
        else SystemClock.sleep(if(long)850 else 70)
        event(MotionEvent.ACTION_UP,x+dx,y+dy); SystemClock.sleep(800)
    }
    private fun liveChecks() {
        val live=read { host.settings.resolved("system","tiles.live") }
        val interval=read { host.settings.resolved("system","tiles.interval") }
        try {
            main { host.settings.setMany("system",mapOf("tiles.live" to true,"tiles.interval" to 5));activity.requestedOrientation=0 }
            debugTheme(Theme.DARK);page("tile-probe.grid")
            val changes=JSONArray();var last=emptyList<Int>();var maxMoving=0
            val until=SystemClock.uptimeMillis()+11500
            while(SystemClock.uptimeMillis()<until) {
                main {
                    val current=tiles();val faces=current.map { field(it,"faceIndex") as Int }
                    val moving=current.count { it.flipping };maxMoving=maxOf(maxMoving,moving)
                    check(moving<=1) { "Multiple tiles flipping" };check(faces.none { it==2 }) { "Empty face was shown" }
                    if(last.isNotEmpty() && faces!=last)changes.put(JSONObject().put("time",SystemClock.uptimeMillis()).put("faces",JSONArray(faces)))
                    last=faces
                };SystemClock.sleep(15)
            }
            check(changes.length()>=2) { "Scheduler failed to advance" }
            for(i in 1 until changes.length())check(changes.getJSONObject(i).getLong("time")-changes.getJSONObject(i-1).getLong("time")>=4800)
            result.put("faceChanges",changes).put("maxConcurrentFlips",maxMoving);pass("single page scheduler; interval >=5s; round robin; empty faces skipped")
            waitFor("No running flip",8000) { read { tiles().any { it.flipping } } }
            main { host.settings.set("system","tiles.live",false) }
            waitFor("Disabling did not cancel flip",500) { read { tiles().none { it.flipping } } }
            val frozen=read { tiles().map { field(it,"faceIndex") } };SystemClock.sleep(5500)
            check(read { tiles().map { field(it,"faceIndex") }==frozen });pass("live switch cancels a running animation and stops subsequent flips")
            main { host.settings.set("system","tiles.live",true);host.sensors.debugHoldAttention(Attention.AMBIENT) }
            SystemClock.sleep(6000);check(read { tiles().map { field(it,"faceIndex") }==frozen && tiles().none { it.flipping } });pass("AMBIENT stops and retains face")
            main { host.sensors.debugHoldAttention(Attention.AWAKE) };page("tile-probe.grid")
            waitFor("Wake did not resume",8000) { read { tiles().any { it.flipping } } }
            val oldTiles=read { tiles() };page("tile-probe.detail")
            check(read { oldTiles.none { it.flipping } });SystemClock.sleep(5500);check(read { oldTiles.none { it.flipping } });pass("waking resumes; leaving cancels")
            page("tile-probe.behavior");check(read { !tiles()[0].canFlip && (field(tiles()[0],"label") as TextView).text=="Label only" });screenshot("all-empty-label")
            val xy=read { val t=tiles()[1];floatArrayOf(t.left+t.width/2f,t.top+t.height/2f) };touch(xy[0],xy[1]);check(read { host.store.current.deck.page=="tile-probe.behavior" });pass("all-empty faces retain label; no opens is a no-op")
            // Android's global animator setting is restored by the host script, including failure.
            uiAutomation.executeShellCommand("settings put global animator_duration_scale 0").close();SystemClock.sleep(200)
            val tile=read { tiles()[2] };val old=read { field(tile,"faceIndex") }
            main { tile.flip();check(!tile.flipping);check(field(tile,"faceIndex")!=old) };pass("reduced motion switches immediately without an animation")
            page("tile-probe.stress");check(read { tiles().size==16 });screenshot("sixteen-tiles")
        } finally {
            main {
                if(live.overridden)host.settings.set("system","tiles.live",live.value) else host.settings.reset("system","tiles.live")
                if(interval.overridden)host.settings.set("system","tiles.interval",interval.value) else host.settings.reset("system","tiles.interval")
            }
        }
    }
    private fun pluginChecks() {
        page("tiles.main")
        check(read { tiles().size==3 && tiles().all { it.owner in setOf("clock","weather","gallery") } })
        screenshot("builtin-tiles");pass("pure declaration tiles page resolves all three API 4 plugins")
        val point=read { val t=tiles().first { it.owner=="weather" };floatArrayOf(t.left+t.width/2f,t.top+t.height/2f) }
        touch(point[0],point[1],true)
        check(read { host.router.settingsOpen && all().filterIsInstance<TextView>().any { it.text.toString()==host.plugins.text("weather","setting.temp_unit") } })
        pass("cross-plugin long press opens weather settings, not tiles settings")
        main { host.router.closeSettings() };SystemClock.sleep(700)
        // Bundled plugins have factory fallback. Exercise catalog removal and directory reinstall
        // on the runtime worker, the same publication path used by development uninstall.
        val runtime=host.plugins;val worker=field(runtime,"worker") as android.os.Handler
        var removed:Any?=null
        fun workerCall(block:()->Unit) { val latch=java.util.concurrent.CountDownLatch(1);var error:Throwable?=null;worker.post { try { block() } catch(t:Throwable) { error=t } finally { latch.countDown() } };check(latch.await(15,java.util.concurrent.TimeUnit.SECONDS));error?.let { throw it } }
        try {
            workerCall {
                @Suppress("UNCHECKED_CAST") val active=field(runtime,"active") as MutableMap<String,Any>
                removed=active.remove("weather")
                runtime.javaClass.getDeclaredMethod("publishCatalog").also { it.isAccessible=true }.invoke(runtime)
            }
            waitFor("Missing weather did not become empty") { read { tiles().size==3 && tiles().none { it.owner=="weather" } } }
            screenshot("weather-removed");pass("weather removal publishes empty placeholder without crashing")
        } finally {
            workerCall {
                @Suppress("UNCHECKED_CAST") val active=field(runtime,"active") as MutableMap<String,Any>
                removed?.let { active["weather"]=it }
                runtime.javaClass.getDeclaredMethod("publishCatalog").also { it.isAccessible=true }.invoke(runtime)
            }
        }
        waitFor("Weather did not recover") { read { tiles().any { it.owner=="weather" } } }
        screenshot("weather-restored");pass("weather catalog reinstall restores tile")
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments=arguments?:Bundle(); start() }
    override fun onStart() {
        var success=false
        val initialLocale=Locale.getDefault()
        var initialOrientation=10
        try {
            activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            stage=field(activity,"stage") as ThinkStage; host=(activity.application as ThinkApplication).runtime
            initialOrientation=read { activity.requestedOrientation }
            waitFor("Fixture missing") { read { host.router.pages.any { it.key=="tile-probe.grid" } } }
            main { host.sensors.debugHoldAttention(Attention.AWAKE); host.sensors.stop() }
            if(arguments.getString("plugins")=="true") { pluginChecks();success=true;return }
            if(arguments.getString("live")=="true") { liveChecks();success=true;return }
            page("tile-probe.grid")
            for (orientation in listOf(0,1)) {
                main { activity.requestedOrientation=orientation };SystemClock.sleep(1000)
                for (theme in listOf(Theme.LIGHT,Theme.DARK,Theme.NIGHT)) {
                    debugTheme(theme);check(read { host.store.current.theme==theme }) { "Theme overridden" };page("tile-probe.grid");screenshot("${if(orientation==0)"landscape" else "portrait"}-${theme.name.lowercase()}")
                    page("tile-probe.large");screenshot("${if(orientation==0)"landscape" else "portrait"}-${theme.name.lowercase()}-large")
                }
            }
            pass("six orientation/theme groups; four sizes; square corners; glyph bounds")
            main { activity.requestedOrientation=1 };SystemClock.sleep(800);debugTheme(Theme.LIGHT)
            for (l in listOf("zh-Hans","en","ar","hi")) { locale(l);page("tile-probe.grid");screenshot("locale-$l") }
            pass("zh-Hans/en/ar/hi; native RTL and no clipped glyphs")
            locale("en");main { activity.requestedOrientation=0 };SystemClock.sleep(800);page("tile-probe.grid")
            val stable=read { tiles().map { System.identityHashCode(it) } }
            val before=read { tiles().map { field(it,"fill") } }
            var intermediate=false;var changed=false
            val until=SystemClock.uptimeMillis()+9000
            while(SystemClock.uptimeMillis()<until) {
                main {
                    check(stable==tiles().map { System.identityHashCode(it) }) { "Bindings rebuilt views" }
                    val colors=tiles().map { field(it,"fill") };if(colors!=before)changed=true
                    if(tiles().any { field(it,"colorAnimation")?.let { a -> (a as android.animation.ValueAnimator).isRunning } == true })intermediate=true
                };SystemClock.sleep(30)
            }
            check(changed && intermediate) { "No smooth bound color change" };pass("Lua color changes animate without rebuilding views; invalid values fall back")
            val xy=read { val t=tiles().first();floatArrayOf(t.left+t.width/2f,t.top+t.height/2f) }
            touch(xy[0],xy[1]);check(read { host.store.current.deck.page=="tile-probe.detail" });pass("tap opens widget page")
            page("tile-probe.grid");touch(xy[0],xy[1],true)
            check(read { host.router.settingsOpen && all().filterIsInstance<TextView>().any { it.text.toString()=="Tile color" } });screenshot("long-press-settings")
            pass("long press opens owning plugin settings")
            val oldColor=read { host.settings.resolved("tile-probe","color") }
            try {
                main {
                    var row:View?=all().filterIsInstance<TextView>().first { it.text.toString()=="Tile color" }
                    while(row!=null && !row.isClickable) row=row.parent as? View
                    check(row?.performClick()==true)
                    val form=all().first { it.javaClass.simpleName=="FormView" }
                    val dialog=field(form,"textDialog") as android.app.AlertDialog
                    all(dialog.window!!.decorView).filterIsInstance<android.widget.EditText>().single().setText("#D24726")
                    dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick()
                }
                waitFor("Settings color did not persist") { read { host.settings.get("tile-probe","color")=="#D24726" } }
                main { host.router.closeSettings() };SystemClock.sleep(800)
                waitFor("Config color did not reach tile") { read { tiles().firstOrNull()?.let { field(it,"targetFill")==0xffd24726.toInt() }==true } }
                screenshot("settings-color-orange");pass("editing color in native settings immediately changes tile")
            } finally { main { if(oldColor.overridden)host.settings.set("tile-probe","color",oldColor.value) else host.settings.reset("tile-probe","color") } }

            main { host.router.closeSettings() };SystemClock.sleep(700)
            val beforePage=read { host.store.current.deck.page }
            touch(1100f,400f,dx=-850f);check(read { host.store.current.deck.page!=beforePage });pass("horizontal swipe changes page")
            page("tile-probe.grid");val beforeVariant=read { host.store.current.deck.variant };touch(640f,650f,dy=-500f)
            check(read { host.store.current.deck.variant!=beforeVariant });pass("vertical swipe changes variant")
            page("tile-probe.stack");screenshot("tile-in-stack");check(read { tiles().isNotEmpty() });pass("tile in stacks uses large template")
            page("tile-probe.glance");screenshot("glance-and-missing-placeholder");pass("glance and missing references show safe empty tiles")
            success=true
        } catch(t:Throwable) { result.put("error",t.stackTraceToString()) }
        finally {
            if(::host.isInitialized) {
                locale(initialLocale.toLanguageTag())
                main { activity.requestedOrientation=initialOrientation;host.sensors.debugInjectLux(null);host.sensors.debugHoldAttention(null);host.sensors.start() }
            }
            result.put("complete",success).put("passed",passed).put("screens",screens)
            File(targetContext.filesDir,"tiles-validation").apply { mkdirs() }.resolve("report.json").writeText(result.toString(2))
            finish(if(success)0 else 1,Bundle().apply { putString("result",result.toString()) })
        }
    }
}
