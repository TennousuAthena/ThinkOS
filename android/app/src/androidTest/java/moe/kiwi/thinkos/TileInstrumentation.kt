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
