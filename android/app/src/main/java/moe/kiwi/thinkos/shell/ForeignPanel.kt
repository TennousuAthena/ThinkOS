package moe.kiwi.thinkos.shell

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.database.ContentObserver
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import moe.kiwi.thinkos.DisplayObserver
import moe.kiwi.thinkos.NavigationObserver
import moe.kiwi.thinkos.RendererObserver
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.RuntimeHost
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.BackGesture
import moe.kiwi.thinkos.core.Theme
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginWidget
import moe.kiwi.thinkos.ui.Binding
import moe.kiwi.thinkos.ui.PageEvents
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.PanelHost
import org.json.JSONObject

/** Non-focusable OS edges above Android apps (ADR-046). No Activity, player or input injection. */
internal class ForeignPanel(private val context: Context, private val host: RuntimeHost) : RendererObserver, DisplayObserver, NavigationObserver {
    private val main = Handler(Looper.getMainLooper())
    private val windows = context.getSystemService(WindowManager::class.java)
    private val states = mutableMapOf<String, JSONObject>()
    private var widgets = emptyList<PluginWidget>()
    private var attached = false
    private var removing = false
    private var started = false
    private var expanded = false
    private var leftAttached = false
    private var leftTouches = 0
    private var backDown: MotionEvent? = null
    private var backVelocity: VelocityTracker? = null
    private var backChosen = false
    private var backRejected = false
    private var displayRotation = windows.defaultDisplay.rotation
    private val events: PageEvents = object : PageEvents by host.pageEvents {
        override fun panelChanged(open: Boolean) {
            host.pageEvents.panelChanged(open)
            main.removeCallbacks(tick)
            if (open) { resize(true); tick.run() } else if (!removing) resize(false)
        }
    }
    private val panel: PanelHost = PanelHost(context, events, { states[it] ?: JSONObject() }) { host.sensors.touch() }
    private val root: EdgeWindow = EdgeWindow(context)
    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT, edgeHeight(), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT; title = "ThinkOS Panel"; screenBrightness = host.levels.brightness }
    // Reserve the physical edge from app drawers. system_server still observes
    // the pointer stream and emits the fixed Back; this View never injects keys.
    private val left = object : View(context) {
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) { leftTouches++; host.sensors.touch() }
            panelBackGesture(event)
            return true
        }
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            displayChanged()
        }
    }
    private val leftParams = WindowManager.LayoutParams(
        leftWidth(), WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.LEFT; title = "ThinkOS Back Edge"; screenBrightness = host.levels.brightness }
    private val propertyGet by lazy { runCatching { Class.forName("android.os.SystemProperties").getMethod("get", String::class.java, String::class.java) }.getOrNull() }
    private val policyObserver = object : ContentObserver(main) { override fun onChange(selfChange: Boolean) = sync() }
    private val appOpObserver = AppOpsManager.OnOpChangedListener { _, packageName -> if (packageName == context.packageName) main.post { sync() } }
    private val tick = object : Runnable {
        override fun run() {
            if (!panel.showing || removing) return
            panel.tick(SystemClock.uptimeMillis(), Attention.ENGAGED)
            main.postDelayed(this, 1_000)
        }
    }

    init { root.addView(panel, FrameLayout.LayoutParams(-1, -1)) }
    val showing: Boolean get() = attached && panel.showing
    val globalNavigation: Boolean get() = !ManagementExit.isReleased(context) && ForeignPanelPolicy.globalNavigation(protocol(), Settings.canDrawOverlays(context),
        Settings.Global.getString(context.contentResolver, "policy_control"))
    fun start() {
        if (started) return
        started = true
        host.observe(this)
        runCatching { context.contentResolver.registerContentObserver(Settings.Global.getUriFor("policy_control"), false, policyObserver) }
            .onFailure { Log.w("ThinkOS", "Unable to watch shell policy", it) }
        runCatching { context.getSystemService(AppOpsManager::class.java).startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, context.packageName, appOpObserver) }
            .onFailure { Log.w("ThinkOS", "Unable to watch overlay permission", it) }
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            host.store.state.map { it.theme to it.safeMode }.distinctUntilChanged().collect { (theme, _) -> colors(theme); sync() }
        }
    }
    private fun protocol(): String = runCatching { propertyGet?.invoke(null, ForeignPanelPolicy.PROPERTY, "") as? String }.getOrNull().orEmpty()
    private fun edgeHeight() = (host.system.panelEdgeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun leftWidth() = (host.system.navBackEdgeDp * context.resources.displayMetrics.density).toInt().coerceAtLeast(1)
    private fun allowed() = !ManagementExit.isReleased(context) && ForeignPanelPolicy.available(KioskState.foreign, KioskState.resumed, host.system.panelEnabled,
        host.router.panelPage != null, host.store.current.safeMode, Settings.canDrawOverlays(context), protocol(),
        Settings.Global.getString(context.contentResolver, "policy_control"))

    /** Called for lifecycle, catalog, settings and system policy changes; no idle polling. */
    fun sync() {
        if (!started) return
        syncLeft()
        if (!allowed()) { remove(); return }
        if (!attached) {
            expanded = false; params.height = edgeHeight(); params.screenBrightness = host.levels.brightness
            try { windows.addView(root, params); attached = true }
            catch (error: RuntimeException) { Log.w("ThinkOS", "Foreign control edge unavailable", error); return }
            colors(host.store.current.theme)
        } else {
            params.screenBrightness = host.levels.brightness
            resize(expanded)
        }
    }
    private fun syncLeft() {
        if (KioskState.resumed || !globalNavigation) {
            if (leftAttached) {
                cancelPanelBack()
                runCatching { windows.removeViewImmediate(left) }.onFailure { Log.w("ThinkOS", "Unable to remove foreign Back edge", it) }
                leftAttached = false
            }
            return
        }
        leftParams.width = leftWidth(); leftParams.screenBrightness = host.levels.brightness
        try {
            if (leftAttached) windows.updateViewLayout(left, leftParams)
            else { windows.addView(left, leftParams); leftAttached = true }
        } catch (error: RuntimeException) { Log.w("ThinkOS", "Foreign Back edge unavailable", error) }
    }
    private fun remove() {
        if (!attached) return
        removing = true
        root.cancelEdge(); cancelPanelBack(); panel.close(false); main.removeCallbacks(tick)
        runCatching { windows.removeViewImmediate(root) }.onFailure { Log.w("ThinkOS", "Unable to remove foreign control edge", it) }
        attached = false; expanded = false; removing = false
    }
    private fun resize(full: Boolean) {
        if (!attached || removing) return
        expanded = full
        params.height = if (full) WindowManager.LayoutParams.MATCH_PARENT else edgeHeight()
        params.screenBrightness = host.levels.brightness
        runCatching { windows.updateViewLayout(root, params) }.onFailure { Log.w("ThinkOS", "Unable to resize foreign control edge", it); remove() }
    }
    private fun colors(theme: Theme) {
        panel.colors(Palette.forTheme(theme)); panel.night(if (theme == Theme.NIGHT) 1f else 0f)
        root.layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault())
    }
    override fun onCatalog(pages: List<PluginPage>, widgets: List<PluginWidget>) {
        this.widgets = widgets; panel.refresh(host.router.panelPage, widgets); sync()
    }
    override fun onState(pluginId: String, state: JSONObject) {
        val changed = Binding.changed(states.put(pluginId, state), state)
        if (changed.isNotEmpty()) panel.bind(pluginId, changed)
    }
    override fun onLocaleChanged() { colors(host.store.current.theme); panel.localeChanged() }
    override fun onRenderSettingsChanged() { panel.refresh(host.router.panelPage, widgets); sync() }
    override fun onBrightness(value: Float) {
        // External activities keep Android's own idle/brightness policy. This
        // window applies only the user's cap, not ThinkOS's hidden-page attention.
        if (attached && params.screenBrightness != host.levels.brightness) resize(expanded)
        if (leftAttached && leftParams.screenBrightness != host.levels.brightness) syncLeft()
    }
    fun closeImmediately() { root.cancelEdge(); cancelPanelBack(); panel.close(false) }
    private fun displayChanged() {
        // Overlay relayouts also receive configuration callbacks. Expanding the
        // sheet must not cancel its own pull; reset only for a real rotation.
        val rotation = windows.defaultDisplay.rotation
        if (rotation != displayRotation) { displayRotation = rotation; closeImmediately() }
        sync()
    }
    override fun onPanelClose() = panel.close()
    override fun onOpenAndroid(target: String): Boolean {
        if (!attached || KioskState.resumed || (!KioskState.foreign && !globalNavigation)) return false
        panel.close(false)
        return try {
            host.beforeForeignLaunch()
            context.startActivity(Intent(if (target == "wifi") Settings.ACTION_WIFI_SETTINGS else Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            KioskState.enterForeign(host.system.appsReturnAfterMillis)
            true
        } catch (error: RuntimeException) {
            Log.w("ThinkOS", "Unable to open Android from the foreign panel", error)
            Toast.makeText(context, context.getString(R.string.apps_open_failed, "Android"), Toast.LENGTH_LONG).show()
            ForegroundTask.restore(context, "panel launch failure"); true
        }
    }
    fun debugState(): JSONObject = JSONObject().put("attached", attached).put("showing", showing).put("expanded", expanded)
        .put("height", params.height).put("brightness", params.screenBrightness).put("protocol", protocol())
        .put("leftAttached", leftAttached).put("leftWidth", leftParams.width).put("leftTouches", leftTouches)
        .put("overlayPermission", Settings.canDrawOverlays(context)).put("allowed", allowed()).put("progress", panel.progress).put("textMetrics", panel.debugTextMetrics())

    private fun cancelPanelBack() {
        backDown?.recycle(); backDown = null
        backVelocity?.recycle(); backVelocity = null
        backChosen = false; backRejected = false
    }
    /** An expanded panel owns Back first. Close on release, after the native
     * callback has observed the expanded window and left the external app alone. */
    private fun panelBackGesture(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancelPanelBack()
            if (!panel.showing || !BackGesture.startsAtEdge(event.rawX, leftWidth().toFloat())) return false
            backDown = MotionEvent.obtain(event)
            backVelocity = VelocityTracker.obtain()
            host.sensors.touch()
        }
        val first = backDown ?: return false
        backVelocity?.addMovement(event)
        val dx = event.rawX - first.rawX; val dy = event.rawY - first.rawY
        if (!backChosen && !backRejected) when (BackGesture.classify(dx, dy, ViewConfiguration.get(context).scaledTouchSlop.toFloat(), true, false)) {
            BackGesture.Verdict.BACK -> backChosen = true
            BackGesture.Verdict.PAGE -> backRejected = true
            else -> Unit
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_UP -> {
                backVelocity?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                val size = Point(); windows.defaultDisplay.getRealSize(size)
                val commit = backChosen && !backRejected && BackGesture.commits(dx, backVelocity?.xVelocity ?: 0f, size.x.toFloat(), context.resources.displayMetrics.density)
                cancelPanelBack()
                if (commit) panel.close()
            }
            MotionEvent.ACTION_CANCEL -> cancelPanelBack()
            MotionEvent.ACTION_POINTER_DOWN -> backRejected = true
        }
        return true
    }

    private inner class EdgeWindow(context: Context) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
        private var down: MotionEvent? = null
        private var pulling = false
        private var swallow = false
        fun cancelEdge() { down?.recycle(); down = null; pulling = false; swallow = false }
        override fun onConfigurationChanged(newConfig: Configuration) {
            super.onConfigurationChanged(newConfig)
            displayChanged()
        }
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (panelBackGesture(event)) return true
            if (event.actionMasked == MotionEvent.ACTION_DOWN && !panel.showing) {
                cancelEdge(); down = MotionEvent.obtain(event); host.sensors.touch(); return true
            }
            val first = down ?: return super.dispatchTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - first.x; val dy = event.y - first.y
                    if (!pulling && !swallow) when (BackGesture.classify(dx, dy, slop, false, true, false)) {
                        BackGesture.Verdict.PULL -> {
                            if (!panel.prepare(host.router.panelPage, widgets)) { swallow = true; return true }
                            val size = Point(); windows.defaultDisplay.getRealSize(size)
                            pulling = true; resize(true); panel.beginPull(size.x, size.y); panel.pullTo(0f, first)
                        }
                        BackGesture.Verdict.PAGE -> swallow = true
                        else -> Unit
                    }
                    if (pulling) panel.pullTo(dy, event)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (pulling) { panel.pullTo(event.y - first.y, event); panel.release(event.actionMasked == MotionEvent.ACTION_CANCEL) }
                    cancelEdge()
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (pulling) panel.release(true)
                    pulling = false; swallow = true
                }
            }
            return true
        }
    }
}
