package moe.kiwi.thinkos

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.kiwi.thinkos.core.DeckRouter
import moe.kiwi.thinkos.core.Store
import moe.kiwi.thinkos.location.DaylightProbe
import moe.kiwi.thinkos.location.LocationProvider
import moe.kiwi.thinkos.media.MediaEngine
import moe.kiwi.thinkos.media.StoragePermission
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginRuntime
import moe.kiwi.thinkos.plugin.PluginWidget
import moe.kiwi.thinkos.anim.MotionPrefs
import moe.kiwi.thinkos.settings.AndroidSettingsPersistence
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.SystemSettings
import moe.kiwi.thinkos.settings.SystemValues
import moe.kiwi.thinkos.settings.TimezoneSource
import moe.kiwi.thinkos.shell.CrashRecovery
import moe.kiwi.thinkos.ui.DeviceLevels
import moe.kiwi.thinkos.ui.MediaComponents
import moe.kiwi.thinkos.ui.PageEvents
import moe.kiwi.thinkos.ui.RenderComponent
import moe.kiwi.thinkos.vision.SensorController
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One runtime per process, shared by the service and all Activity instances. */
class RuntimeHost(private val context: Context) : PluginRuntime.Callbacks {
    interface Observer {
        fun onCatalog(pages: List<PluginPage>, widgets: List<PluginWidget>)
        fun onState(pluginId: String, state: JSONObject)
        fun onBrightness(value: Float)
        fun onError(pluginId: String, message: String)
        fun onOrientationRequest(value: Int) {}
        /** `kiosk.lock` changed (or the observer attached): lock or release the system UI. */
        fun onKioskLock(locked: Boolean) {}
        /** A long press asked for settings but the settings plugin is not running: show the recovery menu. */
        fun onSettingsUnavailable() {}
        fun onRenderSettingsChanged() {}
        /** A plugin asked its visible tiles to play a named animation. */
        fun onTileAnimation(pluginId: String, name: String) {}
        /** Language, 12/24-hour setting or time zone changed in Android's settings. */
        fun onLocaleChanged() {}
        /** Return true only when this Activity has opened Android's native permission screen. */
        fun onStoragePermissionRequest(requestId: Long): Boolean = false
    }

    val store = Store()
    private val settingsWriter = Executors.newSingleThreadScheduledExecutor { Thread(it, "ThinkOS-SettingsIO") }
    private val mainHandler = Handler(Looper.getMainLooper())

    /** The only holder of settings (ADR-017); writes are coalesced and happen off the main thread. */
    val settings = SettingsStore(AndroidSettingsPersistence(File(context.filesDir, "settings")), TimezoneSource) { delay, task ->
        settingsWriter.schedule({ runCatching(task).onFailure { Log.e("ThinkOS", "settings write failed", it) } }, delay, TimeUnit.MILLISECONDS)
    }.also { store ->
        store.register(SystemSettings.parse(context.assets.open("settings/system.json").use { String(it.readBytes(), Charsets.UTF_8) }, TimezoneSource))
    }
    val system = SystemValues(settings)
    val timeSync = moe.kiwi.thinkos.time.TimeSync(context, system, settings)
    /** One audio/video session for the whole OS (ADR-022); plugins reach it through `tos.media`. */
    val media: MediaEngine = MediaEngine(context, object : MediaEngine.Listener {
        override fun onState(state: Map<String, Any?>) {
            plugins.mediaState(state)
            cast.onMediaState(state)
            sensors.mediaPlaying(system.keepAwakeVideo && state["kind"] == "video" &&
                state["status"] in setOf("playing", "buffering", "reconnecting"))
        }
        override fun onEvent(owner: String, name: String, payload: Map<String, Any?>) {
            if (name == "media.error" || name == "media.warning") Log.w("ThinkOS", "[media:${owner.substringBefore(':')}] $name $payload")
            plugins.mediaEvent(owner, name, payload)
        }
    }) { system.mediaPolicy }
    val storagePermission = StoragePermission(context, ::requestStoragePermission)
    val plugins: PluginRuntime = PluginRuntime(context, this, settings, media, storagePermission)
    private val images = moe.kiwi.thinkos.ui.ImageComponents(moe.kiwi.thinkos.image.ImageLoader(context, plugins::imageOwner), plugins::imageAuthority)
    val router = DeckRouter(context, store, { pluginId, event, args ->
        if (event == "variant" || event == "page.enter") {
            if (args.length() > 1) plugins.setVariant(pluginId, args.optString(1))
        }
        plugins.event(pluginId, event, args)
    }, { system.ambientPageAfterMillis })
    /** The sunrise-and-sunset theme asks this once a second; the Sun is recomputed at most every 30 s. */
    private val daylight = DaylightProbe({ runCatching { LocationProvider(settings).get().place }.getOrNull() })
    val sensors: SensorController = SensorController(context, store, system, { value ->
        brightness = value
        observers.toList().forEach { it.onBrightness(value) }
    }, { event, args -> plugins.event(null, event, args) }, { router.settingsOpen }, daylight::isDay)

    /**
     * What a drag on a film changes: the brightness cap that is a system setting (so the settings page shows the same number)
     * and the media volume (so the volume keys agree). Both are the device's, never a plugin's.
     */
    val levels: DeviceLevels = object : DeviceLevels {
        private val audio by lazy { context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager }
        private val cap: moe.kiwi.thinkos.settings.SettingField get() = settings.scope(SettingsStore.SYSTEM)!!.fields.getValue(SystemSettings.Keys.BRIGHTNESS_MAX)
        override val minimumBrightness: Float get() = ((cap.min ?: 20.0) / 100.0).toFloat()
        override var brightness: Float
            get() = system.brightnessMax
            set(value) {
                val step = cap.step ?: 5.0
                val percent = (Math.round(value * 100.0 / step) * step).coerceIn(cap.min ?: 20.0, cap.max ?: 100.0)
                if (Math.round(system.brightnessMax * 100.0).toDouble() == percent) return
                runCatching { settings.set(SettingsStore.SYSTEM, SystemSettings.Keys.BRIGHTNESS_MAX, percent) }
                sensors.refreshBrightness()
            }
        override var volume: Float
            get() = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() / audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            set(value) {
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, Math.round(value * max).coerceIn(0, max), 0)
            }
    }

    /** Casts from phones and computers on the local network (ADR-031); off until `cast.enabled`. */
    val cast = moe.kiwi.thinkos.cast.CastHost(context, system, media, router, sensors, mainHandler)

    /** UI → plugin events and the way out of the settings page. */
    val pageEvents = object : PageEvents {
        override val tilesLive get() = system.tilesLive
        override val tilesIntervalMillis get() = system.tilesIntervalMillis
        override val tilesBackground get() = system.tilesBackground
        override fun send(pluginId: String, name: String, args: List<Any?>) = plugins.event(pluginId, name, JSONArray(args))
        override fun closeSettings() = router.closeSettings()
        override fun openSettings(owner: String) = this@RuntimeHost.openSettings(owner)
        override fun openPage(key: String) = router.open(key)
        override fun settingsInteraction() { router.noteInteraction(); sensors.touch() }
        override fun text(pluginId: String, key: String): String? = plugins.text(pluginId, key)
        override fun component(context: Context, pluginId: String, definition: JSONObject): RenderComponent? =
            MediaComponents.create(context, pluginId, definition, media, plugins::hasPermission, levels)
                ?: images.create(context, pluginId, definition)
    }

    /** Android's language, 12/24-hour or time zone settings changed (broadcast): refresh everything that shows a time. */
    private val timeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ignored: Context, intent: android.content.Intent) {
            if (intent.action == android.content.Intent.ACTION_TIMEZONE_CHANGED) java.util.TimeZone.setDefault(null)
            plugins.localeChanged()
            observers.toList().forEach { it.onLocaleChanged() }
        }
    }

    /** Long press on content owned by [owner]: that plugin's settings, or the system's if it declares none. */
    fun localeChanged() {
        plugins.localeChanged()
        observers.toList().forEach { it.onLocaleChanged() }
    }

    fun openSettings(owner: String) {
        val scope = if (settings.scope(owner)?.visibleFields?.isNotEmpty() == true) owner else SettingsStore.SYSTEM
        if (!router.openSettings(scope)) observers.toList().forEach { it.onSettingsUnavailable() }
    }

    private var started = false
    private var brightness = 0.4f
    private var pages: List<PluginPage> = emptyList()
    private var widgets: List<PluginWidget> = emptyList()
    private val states = linkedMapOf<String, JSONObject>()
    private val observers = linkedSetOf<Observer>()

    private fun requestStoragePermission(requestId: Long): Boolean = observers.toList().any { it.onStoragePermissionRequest(requestId) }
    fun storagePermissionResult(requestId: Long, granted: Boolean) = storagePermission.result(requestId, granted)
    fun storagePermissionHostClosed(requestId: Long) = storagePermission.hostClosed(requestId)

    init {
        plugins.mediaState(media.snapshot())
        MotionPrefs.clock = system.clockMotion
        settings.addListener { scope, keys ->
            if (scope == SettingsStore.SYSTEM && keys.any { it == SystemSettings.Keys.TILES_LIVE || it == SystemSettings.Keys.TILES_INTERVAL || it == SystemSettings.Keys.TILES_BACKGROUND }) mainHandler.post { observers.toList().forEach { it.onRenderSettingsChanged() } }
            if (scope == SettingsStore.SYSTEM && keys.any { it.startsWith("cast.") }) cast.reconfigure()
            if (scope == SettingsStore.SYSTEM && SystemSettings.Keys.CLOCK_MOTION in keys) MotionPrefs.clock = system.clockMotion
            if (scope == SettingsStore.SYSTEM && SystemSettings.Keys.ORIENTATION in keys) mainHandler.post { applyOrientationSetting() }
            if (scope == SettingsStore.SYSTEM && SystemSettings.Keys.KIOSK_LOCK in keys) mainHandler.post { observers.toList().forEach { it.onKioskLock(system.kioskLock) } }
        }
    }

    /** `display.orientation`: follow the sensor, or stay upright in the mounted landscape position / sensor portrait. */
    private fun orientationCode() = when (system.orientation) {
        "landscape" -> moe.kiwi.thinkos.core.DeviceOrientation.LANDSCAPE
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
    private fun applyOrientationSetting() { observers.toList().forEach { it.onOrientationRequest(orientationCode()) } }

    fun start() {
        if (started) return
        started = true
        warmUpLocaleData()
        context.registerReceiver(timeReceiver, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_LOCALE_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)       // also sent when the 12/24-hour switch flips
        })
        plugins.start(CrashRecovery.isSafeMode(context))
        sensors.start()
        timeSync.start()
        cast.reconfigure()
        Log.i("ThinkOS", "Runtime started; local mode, safe=${CrashRecovery.isSafeMode(context)}")
    }

    /** ICU loads its data lazily (tens of ms on this CPU); do it once, off the threads that draw and run plugins. */
    private fun warmUpLocaleData() {
        Thread({
            val locale = java.util.Locale.getDefault()
            runCatching {
                java.text.NumberFormat.getInstance(locale).format(1234.5)
                java.text.Collator.getInstance(locale)
                moe.kiwi.thinkos.tz.ZoneNames.city("Asia/Hong_Kong", locale)
                moe.kiwi.thinkos.tz.ZoneNames.country("HK", locale)
                java.text.SimpleDateFormat("EEEE d MMMM y", locale).format(java.util.Date())
            }
        }, "ThinkOS-Warmup").apply { priority = Thread.MIN_PRIORITY; start() }
    }

    fun observe(observer: Observer) {
        observers += observer
        observer.onCatalog(pages, widgets)
        states.forEach { (id, state) -> observer.onState(id, state) }
        observer.onBrightness(brightness)
        observer.onOrientationRequest(orientationCode())
        observer.onKioskLock(system.kioskLock)
    }

    fun debugOrientation(value: Int) {
        if (BuildConfig.DEBUG) observers.toList().forEach { it.onOrientationRequest(moe.kiwi.thinkos.core.DeviceOrientation.forced(value)) }
    }

    /** Only the DUMP-protected debug receiver exposes this UI test entry. */
    fun debugMaintenance() {
        if (BuildConfig.DEBUG) mainHandler.post { observers.toList().forEach { it.onSettingsUnavailable() } }
    }

    fun removeObserver(observer: Observer) { observers -= observer }

    override fun onCatalog(pages: List<PluginPage>, widgets: List<PluginWidget>) {
        this.pages = pages
        this.widgets = widgets
        states.keys.retainAll((pages.map { it.pluginId } + widgets.map { it.pluginId }).toSet())
        router.setPages(pages)
        observers.toList().forEach { it.onCatalog(pages, widgets) }
    }
    override fun onState(pluginId: String, state: JSONObject) {
        states[pluginId] = state
        observers.toList().forEach { it.onState(pluginId, state) }
    }
    override fun onTileAnimation(pluginId: String, name: String) {
        observers.toList().forEach { it.onTileAnimation(pluginId, name) }
    }
    override fun onFocus(pageKey: String, live: Boolean, release: Boolean, answersTap: Boolean) {
        router.requestFocus(pageKey, live, release, answersTap)
    }
    override fun onError(pluginId: String, message: String) {
        Log.e("ThinkOS", "[plugin:$pluginId] $message")
        observers.toList().forEach { it.onError(pluginId, message) }
    }
}
