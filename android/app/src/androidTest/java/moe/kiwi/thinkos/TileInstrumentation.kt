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
import moe.kiwi.thinkos.anim.MotionClock
import moe.kiwi.thinkos.ui.TileView
import moe.kiwi.thinkos.ui.ThinkStage
import org.json.JSONArray
import moe.kiwi.thinkos.settings.SettingsStore
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
    private fun main(block: () -> Unit) {
        var failure: Throwable? = null
        runOnMainSync { try { block() } catch(t: Throwable) { failure=t } }
        failure?.let { throw it }
    }
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
    /** ADR-028: press tilt, page entrance and the animations a plugin can request by name. */
    private fun motionChecks() {
        page("tiles.main")
        val clock=read { tiles().first { it.owner=="clock" } }
        main { clock.press(clock.width*.92f,clock.height/2f) };SystemClock.sleep(220)
        check(read { clock.rotationY>3f && clock.scaleX<.99f }) { "Press did not tilt away from the touch: ${clock.rotationY}" }
        main { clock.release() }
        waitFor("Pressed tile did not return") { read { clock.rotationY==0f && clock.rotationX==0f && clock.scaleX==1f } }
        pass("press tilts the tile away from the touch point and release returns it")
        // The stage drives it from a real touch: only the touched tile moves, a cancel releases it without a tap.
        val point=read { floatArrayOf(clock.left+clock.width*.1f,clock.top+clock.height*.5f) }
        val start=SystemClock.uptimeMillis()
        fun event(action: Int) { main { MotionEvent.obtain(start,SystemClock.uptimeMillis(),action,point[0],point[1],0).also { stage.dispatchTouchEvent(it); it.recycle() } } }
        event(MotionEvent.ACTION_DOWN);SystemClock.sleep(220)
        check(read { clock.rotationY<-3f && tiles().filter { it !== clock }.all { it.rotationY==0f && it.scaleX==1f } }) { "Stage press moved the wrong tiles" }
        event(MotionEvent.ACTION_CANCEL)
        waitFor("Cancelled press did not release") { read { clock.rotationY==0f && clock.scaleX==1f } }
        check(read { host.store.current.deck.page=="tiles.main" }) { "A cancelled press must not open a page" }
        pass("a stage touch tilts only the touched tile; cancel releases it without opening anything")
        page("clock.face")
        main { host.router.open("tiles.main") };SystemClock.sleep(90)
        check(read { tiles().minOf { it.alpha }<.9f }) { "Tiles did not start hidden for their entrance" }
        waitFor("Tiles did not finish entering") { read { tiles().all { it.alpha==1f && it.translationY==0f && it.scaleX==1f } } }
        pass("opening the page raises its tiles in, then leaves them untouched")
        page("tile-motion.main")
        val mine=read { tiles().filter { it.owner=="tile-motion" } }
        check(mine.size==2) { "Motion fixture tiles: ${mine.size}" }
        fun send(name: String) { host.plugins.event("tile-motion","command",JSONArray().put(name)) }
        @Suppress("UNCHECKED_CAST") fun result() = read { (field(host,"states") as Map<String,JSONObject>)["tile-motion"]?.optString("lastResult") }
        fun sample(duration: Long, probe: () -> Float): Float {
            var peak=0f;val until=SystemClock.uptimeMillis()+duration
            while(SystemClock.uptimeMillis()<until) { peak=maxOf(peak,kotlin.math.abs(read(probe)));SystemClock.sleep(12) }
            return peak
        }
        send("pulse")
        check(sample(500) { mine[0].scaleX-1f }>.05f) { "pulse never grew the tile" }
        waitFor("pulse did not settle") { read { mine.all { it.scaleX==1f && it.scaleY==1f } } }
        check(result()=="ok") { "pulse result ${result()}" }
        send("pulse");waitFor("a repeated pulse was not refused") { result()=="cooldown" }
        send("bogus");waitFor("an unknown animation was not refused") { result()=="unknown" }
        send("nudge")
        check(sample(500) { mine[0].translationX }>4f) { "nudge never moved the tile" }
        waitFor("nudge did not settle") { read { mine.all { it.translationX==0f } } }
        val face=read { field(mine[0],"faceIndex") }
        send("flip");waitFor("flip did not change the face") { read { field(mine[0],"faceIndex")!=face } }
        pass("plugin animations pulse, nudge and flip run on the plugin's own tiles; repeats and unknown names are refused")
        // The user's "animate tile content" setting also silences what a plugin asks for.
        try {
            main { host.settings.set(SettingsStore.SYSTEM,"tiles.live",false) }
            SystemClock.sleep(1100);send("pulse")
            check(sample(500) { mine[0].scaleX-1f }<.01f) { "pulse ignored tiles.live=false" }
        } finally { main { host.settings.reset(SettingsStore.SYSTEM,"tiles.live") } }
        pass("turning off animated tile content stops plugin-requested animations")
        // The backdrop is a system setting: each choice has to reach the visible page, then the default returns.
        try {
            page("tiles.main")
            for (variant in listOf("teal","wine","indigo")) {
                main { host.settings.set(SettingsStore.SYSTEM,"tiles.background",variant) }
                waitFor("Backdrop did not become $variant") { read { val current=field(stage,"current") ?: return@read false; field(current,"backdrop")?.let { field(it,"variant") }?.toString()==variant.uppercase() } }
                SystemClock.sleep(300);screenshot("start-backdrop-$variant")
            }
        } finally { main { host.settings.reset(SettingsStore.SYSTEM,"tiles.background") } }
        pass("tiles.background switches the visible Start backdrop between indigo, teal and wine")
    }
    private fun liveChecks() {
        val live=read { host.settings.resolved("system","tiles.live") }
        val interval=read { host.settings.resolved("system","tiles.interval") }
        try {
            main { host.settings.setMany("system",mapOf("tiles.live" to true,"tiles.interval" to 5));activity.requestedOrientation=moe.kiwi.thinkos.core.DeviceOrientation.LANDSCAPE }
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
            page("tile-probe.behavior");check(read { !tiles()[0].canFlip && (field(tiles()[0],"label") as TextView).text=="Label only" });check(read { (field(tiles().last(),"value") as TextView).text=="Label only" });screenshot("all-empty-label")
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
    private fun backdropChecks() {
        val clock=field(stage,"clock") as MotionClock
        val dx=ThinkStage::class.java.getDeclaredField("driftX").also { it.isAccessible=true }
        val dy=ThinkStage::class.java.getDeclaredField("driftY").also { it.isAccessible=true }
        val apply=ThinkStage::class.java.getDeclaredMethod("applyDrift").also { it.isAccessible=true }
        val samples=JSONArray()
        fun offset(x: Int,y: Int) = main { dx.setFloat(stage,x.toFloat());dy.setFloat(stage,y.toFloat());apply.invoke(stage) }
        fun position(v: View) = IntArray(2).also(v::getLocationOnScreen)
        fun checkBackdrop(desktop: View,wallpaper: View,bar: View) = main {
            val origin=position(stage)
            check(position(desktop).contentEquals(origin) && desktop.width==stage.width && desktop.height==stage.height) { "Aero desktop moved off viewport" }
            check(position(wallpaper).contentEquals(origin) && wallpaper.width==stage.width && wallpaper.height==stage.height) { "Aero wallpaper exposed an edge" }
            val visible=android.graphics.Rect();wallpaper.getGlobalVisibleRect(visible)
            check(visible==android.graphics.Rect(origin[0],origin[1],origin[0]+stage.width,origin[1]+stage.height)) { "Aero wallpaper clipped: $visible" }
            val barOrigin=position(bar)
            check(barOrigin[0]==origin[0] && barOrigin[1]==origin[1]+stage.height-bar.height && bar.width==stage.width) { "Taskbar background exposed an edge" }
        }
        try {
            for(orientation in listOf(0,1)) {
                main { activity.requestedOrientation=moe.kiwi.thinkos.core.DeviceOrientation.forced(orientation) }
                waitFor("Orientation unavailable") { read { (stage.width>stage.height)==(orientation==0) } }
                for(theme in listOf(Theme.LIGHT,Theme.DARK,Theme.NIGHT)) {
                    main { clock.start() };debugTheme(theme);page("aero.main")
                    val desktop=read { all().first { it.javaClass.simpleName=="AeroHomeView" } }
                    val wallpaper=field(desktop,"wallpaper") as View
                    val bar=field(desktop,"taskbar") as View
                    val window=read { all().first { it.javaClass.simpleName=="AeroWindow" } }
                    main { clock.stop() }
                    for(attention in listOf(Attention.AMBIENT,Attention.AWAKE)) {
                        main { host.sensors.debugHoldAttention(attention) };SystemClock.sleep(650)
                        offset(0,0);val base=read { position(window) }
                        for((x,y) in listOf(-8 to -6,8 to -6,-8 to 6,8 to 6)) {
                            offset(x,y);SystemClock.sleep(50);checkBackdrop(desktop,wallpaper,bar)
                            main {
                                val actual=position(window)
                                check(actual[0]==base[0]+x && actual[1]==base[1]+y) { "Foreground window lost drift" }
                                desktop.requestLayout()
                            }
                            SystemClock.sleep(50);checkBackdrop(desktop,wallpaper,bar)
                            main {
                                val actual=position(window)
                                check(actual[0]==base[0]+x && actual[1]==base[1]+y) { "Relayout lost foreground drift" }
                            }
                            samples.put(JSONObject().put("orientation",orientation).put("theme",theme.name).put("attention",attention.name).put("x",x).put("y",y))
                        }
                    }
                    screenshot("aero-drift-${if(orientation==0)"landscape" else "portrait"}-${theme.name.lowercase()}")
                }
            }
            result.put("backdropCases",samples)
            pass("48 Aero drift extremes; fixed wallpaper/taskbar; live foreground retained across relayout")
            main { clock.start();activity.requestedOrientation=moe.kiwi.thinkos.core.DeviceOrientation.LANDSCAPE }
            debugTheme(Theme.DARK);page("tiles.main");main { clock.stop() }
            val first=read { tiles().first() }
            offset(0,0);val base=read { position(first) }
            for((x,y) in listOf(-8 to -6,8 to -6,-8 to 6,8 to 6)) {
                offset(x,y)
                main {
                    val page=field(stage,"current") as View
                    check(position(page).contentEquals(position(stage))) { "Tile backdrop moved off viewport" }
                    val actual=position(first)
                    check(actual[0]==base[0]+x && actual[1]==base[1]+y) { "Tiles lost foreground drift" }
                    page.requestLayout()
                }
                SystemClock.sleep(50)
                main { val actual=position(first);check(actual[0]==base[0]+x && actual[1]==base[1]+y) { "Tile relayout lost drift" } }
            }
            pass("Tile backdrop pinned at all four extremes; tile positions and relayout retain drift")
        } finally { main { clock.start() } }
    }
    private fun aeroChecks() {
        for (orientation in listOf(0,1)) {
            main { activity.requestedOrientation=moe.kiwi.thinkos.core.DeviceOrientation.forced(orientation) };SystemClock.sleep(1000)
            for(theme in listOf(Theme.LIGHT,Theme.DARK,Theme.NIGHT)) {
                debugTheme(theme);page("aero.main")
                check(read { all().count { it.javaClass.simpleName=="AeroWindow" }==3 })
                check(read { all().none { it.javaClass.name.contains("WebView") } })
                screenshot("aero-${if(orientation==0) "landscape" else "portrait"}-${theme.name.lowercase()}")
            }
        }
        pass("Aero native desktop; three widget windows; six orientation/theme scenes; no WebView")
        main { activity.requestedOrientation=moe.kiwi.thinkos.core.DeviceOrientation.LANDSCAPE };SystemClock.sleep(800);debugTheme(Theme.DARK)
        for(l in listOf("zh-Hans","en","ar","hi")) { locale(l);page("aero.main");screenshot("aero-locale-$l") }
        locale("en");page("aero.main")
        val clock=read { all().first { it.javaClass.simpleName=="AeroClockView" } }
        val prior=read { field(clock,"lastSecond") }
        waitFor("Aero clock did not tick") { read { field(clock,"lastSecond")!=prior } }
        val bucket=read { field(clock,"lastSecond") }
        val clockRect=read { android.graphics.Rect().also { clock.getGlobalVisibleRect(it) } }
        fun clockPixels():IntArray {
            val image=uiAutomation.takeScreenshot()
            return IntArray(clockRect.width()*clockRect.height()).also {
                image.getPixels(it,0,clockRect.width(),clockRect.left,clockRect.top,clockRect.width(),clockRect.height());image.recycle()
            }
        }
        val firstClock=clockPixels();SystemClock.sleep(180);val nextClock=clockPixels()
        check(read { field(clock,"lastSecond")==bucket }) { "Clock tick crossed the sampling window" }
        check(firstClock.indices.count { firstClock[it]!=nextClock[it] }>5) { "Static cache froze the analogue clock" }
        pass("Aero visible hands move between UI clock ticks; static cache retains live native content")
        val desktop=read { all().first { it.javaClass.simpleName=="AeroHomeView" } }
        val start=read { field(desktop,"start") as View }
        val point=read { IntArray(2).also { start.getLocationOnScreen(it) }.let { it[0]+start.width/2f to it[1]+start.height/2f } }
        touch(point.first,point.second);check(read { (field(desktop,"menu") as View).visibility==View.VISIBLE });screenshot("aero-start-menu")
        val menu=read { field(desktop,"menu") as ViewGroup }
        val setting=read { menu.getChildAt(menu.childCount-1) }
        val settingPoint=read { IntArray(2).also { setting.getLocationOnScreen(it) }.let { it[0]+setting.width/2f to it[1]+setting.height/2f } }
        touch(settingPoint.first,settingPoint.second);check(read { host.router.settingsOpen });main { host.router.closeSettings() }
        page("aero.main")
        val frames=read { all().filter { it.javaClass.simpleName=="AeroWindow" } }
        val weather=frames[1];val xy=read { IntArray(2).also { weather.getLocationOnScreen(it) }.let { it[0]+weather.width/2f to it[1]+weather.height/2f } }
        touch(xy.first,xy.second,true);check(read { host.router.settingsOpen });main { host.router.closeSettings() };page("aero.main")
        touch(xy.first,xy.second);check(read { host.store.current.deck.page=="weather.detail" });page("aero.main")
        touch(read { stage.width*.8f },read { stage.height*.6f },dx=read { -stage.width*.65f })
        check(read { host.store.current.deck.page!="aero.main" })
        pass("Aero native start menu opens settings; widget click/owner long press and horizontal page swipe")
    }
    private fun pluginChecks() {
        page("tiles.main")
        check(read { tiles().size==10 && tiles().all { it.owner in setOf("clock","weather","gallery") } })
        screenshot("builtin-tiles");pass("pure declaration Start page resolves ten tiles from the clock, weather and gallery plugins")
        val point=read { val t=tiles().first { it.owner=="weather" };floatArrayOf(t.left+t.width/2f,t.top+t.height/2f) }
        touch(point[0],point[1],true)
        check(read { host.router.settingsOpen && all().filterIsInstance<TextView>().any { it.text.toString()==host.plugins.text("weather","setting.interval") } })
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
            waitFor("Missing weather did not become empty") { read { tiles().size==10 && tiles().none { it.owner=="weather" } } }
            screenshot("weather-removed");pass("weather removal publishes empty placeholder without crashing")
        } finally {
            workerCall {
                @Suppress("UNCHECKED_CAST") val active=field(runtime,"active") as MutableMap<String,Any>
                removed?.let { active["weather"]=it }
                runtime.javaClass.getDeclaredMethod("publishCatalog").also { it.isAccessible=true }.invoke(runtime)
                removed?.let { instance -> runtime.javaClass.declaredMethods.first { it.name=="publishState" }.also { it.isAccessible=true }.invoke(runtime,instance) }
            }
        }
        waitFor("Weather did not recover") { read { tiles().any { it.owner=="weather" } } }
        screenshot("weather-restored");pass("weather catalog reinstall restores tile")
        page("legacy-three.main")
        waitFor("API 3 image did not decode") { read { all().filterIsInstance<android.widget.ImageView>().any { it.drawable!=null } } }
        check(read { all().filterIsInstance<TextView>().any { it.text.toString()=="API 3 loaded" } })
        screenshot("legacy-api-three");pass("API 3 plugin runs Lua and decodes its local image through public vocabulary")
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
            main { host.sensors.debugHoldAttention(Attention.AWAKE); host.sensors.stop() }
            if(arguments.getString("backdrop")=="true") {
                waitFor("Aero catalog missing") { read { host.router.pages.any { it.key=="aero.main" } } }
                backdropChecks();success=true;return
            }
            if(arguments.getString("motion")=="true") {
                waitFor("Motion fixture missing") { read { host.router.pages.any { it.key=="tile-motion.main" } } }
                motionChecks();success=true;return
            }
            waitFor("Fixture missing") { read { host.router.pages.any { it.key=="tile-probe.grid" } } }
            if(arguments.getString("aero")=="true") { aeroChecks();success=true;return }
            if(arguments.getString("plugins")=="true") { pluginChecks();success=true;return }
            if(arguments.getString("live")=="true") { liveChecks();success=true;return }
            page("tile-probe.grid")
            for (orientation in listOf(0,1)) {
                main { activity.requestedOrientation=moe.kiwi.thinkos.core.DeviceOrientation.forced(orientation) };SystemClock.sleep(1000)
                for (theme in listOf(Theme.LIGHT,Theme.DARK,Theme.NIGHT)) {
                    debugTheme(theme);check(read { host.store.current.theme==theme }) { "Theme overridden" };page("tile-probe.grid");screenshot("${if(orientation==0)"landscape" else "portrait"}-${theme.name.lowercase()}")
                    page("tile-probe.large");screenshot("${if(orientation==0)"landscape" else "portrait"}-${theme.name.lowercase()}-large")
                }
            }
            pass("six orientation/theme groups; four sizes; square corners; glyph bounds")
            main { activity.requestedOrientation=1 };SystemClock.sleep(800);debugTheme(Theme.LIGHT)
            for (l in listOf("zh-Hans","en","ar","hi")) { locale(l);page("tile-probe.grid");screenshot("locale-$l") }
            pass("zh-Hans/en/ar/hi; native RTL and no clipped glyphs")
            locale("en");main { activity.requestedOrientation=moe.kiwi.thinkos.core.DeviceOrientation.LANDSCAPE };SystemClock.sleep(800);page("tile-probe.grid")
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
            result.put("colorChanged",changed).put("colorAnimationObserved",intermediate).put("animatorsEnabled",read { android.animation.ValueAnimator.areAnimatorsEnabled() }).put("tilesActive",read { JSONArray(tiles().map { field(it,"active") }) })
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
            page("tile-probe.icons");screenshot("twelve-icons");check(read { tiles().size==12 && tiles().all { tile -> (field(tile,"icon") as View).let { it.isShown && it.width>0 && it.height>0 } } });pass("all twelve additional native icons render")
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
