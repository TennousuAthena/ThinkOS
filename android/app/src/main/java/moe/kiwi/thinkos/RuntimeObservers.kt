package moe.kiwi.thinkos

import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginWidget
import org.json.JSONObject

/** Main-thread runtime notifications. A host may subscribe to just the topics it uses. */
interface RuntimeObserver

interface RendererObserver : RuntimeObserver {
    fun onCatalog(pages: List<PluginPage>, widgets: List<PluginWidget>)
    fun onState(pluginId: String, state: JSONObject)
    fun onRenderSettingsChanged() {}
    /** A plugin asked its visible tiles to play a named animation. */
    fun onTileAnimation(pluginId: String, name: String) {}
    /** Language, 12/24-hour setting or time zone changed in Android's settings. */
    fun onLocaleChanged() {}
}

interface DisplayObserver : RuntimeObserver {
    fun onBrightness(value: Float)
    fun onOrientationRequest(value: Int) {}
}

interface KioskObserver : RuntimeObserver {
    /** `kiosk.lock` or `panel.enabled` changed (or the observer attached): lock or release the system UI. */
    fun onKioskPolicy(locked: Boolean, panel: Boolean) {}
}

interface NavigationObserver : RuntimeObserver {
    /** Returns true only when a resumed host owns the native installation prompt. */
    fun onPluginConsent(request: moe.kiwi.thinkos.plugin.RegistryConsent, result: (Boolean) -> Unit): Boolean = false
    /** The OS draws and recognizes a named onboarding exercise; null ends it. */
    fun onPractice(pluginId: String, name: String?, text: String) {}
    /** Explicit global Home closes OS-owned dialogs and in-flight gestures before clearing navigation. */
    fun onSystemHome() {}
    /** A plugin asked the control sheet to close (`tos.panel.close`), or something else made it pointless. */
    fun onPanelClose() {}
    /** Leave for one of Android's own screens (`wifi`, `settings`); true if this Activity did. */
    fun onOpenAndroid(target: String): Boolean = false
    /** True means this observer claimed the request; result arrives after Android accepted the launch. */
    fun onOpenAndroidApp(packageName: String, result: (Boolean) -> Unit): Boolean = false
    /** A long press asked for settings but the settings plugin is not running: show the recovery menu. */
    fun onSettingsUnavailable() {}
    /** Native legal notices, available independently of plugins. */
    fun onAbout() {}
    /** True when the foreground Activity opened the plugin-independent update view. */
    fun onUpdates(): Boolean = false
    /** Missing optional casting component: only native controls can prepare/approve. */
    fun onMiracastFeature(expectedRevision: Long): Boolean = false
}

interface StoragePermissionObserver : RuntimeObserver {
    /** Return true only when this Activity has opened Android's native permission screen. */
    fun onStoragePermissionRequest(requestId: Long): Boolean = false
}

interface DiagnosticObserver : RuntimeObserver {
    fun onError(pluginId: String, message: String)
}
