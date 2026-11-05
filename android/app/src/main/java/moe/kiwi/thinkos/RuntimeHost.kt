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
import moe.kiwi.thinkos.ui.DeviceComponents
import moe.kiwi.thinkos.ui.DeviceLevels
import moe.kiwi.thinkos.ui.CoverLoader
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
    /** The Activity uses every topic; smaller hosts implement only the interfaces they need. */
    interface Observer : RendererObserver, DisplayObserver, KioskObserver, NavigationObserver,
        StoragePermissionObserver, DiagnosticObserver

    val store = Store()
    private val settingsWriter = Executors.newSingleThreadScheduledExecutor { Thread(it, "ThinkOS-SettingsIO") }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val hadLocalSettings = File(context.filesDir, "settings/local.json").exists() ||
        File(context.filesDir, "settings/local.json.bak").exists()

    /** The only holder of settings (ADR-017); writes are coalesced and happen off the main thread. */
    val settings = SettingsStore(AndroidSettingsPersistence(File(context.filesDir, "settings")), TimezoneSource) { delay, task ->
        settingsWriter.schedule({ runCatching(task).onFailure { Log.e("ThinkOS", "settings write failed", it) } }, delay, TimeUnit.MILLISECONDS)
    }.also { store ->
        store.register(SystemSettings.parse(context.assets.open("settings/system.json").use { String(it.readBytes(), Charsets.UTF_8) }, TimezoneSource))
        // Completion zero is the default and is intentionally omitted from local overrides.
        // A separate durable marker distinguishes a new device's interrupted setup from legacy data.
        val initialized = store.get(SettingsStore.SYSTEM, SystemSettings.Keys.ONBOARDING_INITIALIZED) as Boolean
        if (moe.kiwi.thinkos.core.OnboardingPolicy.adoptExisting(hadLocalSettings,
                store.hasValue(SettingsStore.SYSTEM, SystemSettings.Keys.ONBOARDING_DONE), initialized))
            store.set(SettingsStore.SYSTEM, SystemSettings.Keys.ONBOARDING_DONE, moe.kiwi.thinkos.core.OnboardingPolicy.VERSION)
        if (!initialized) store.set(SettingsStore.SYSTEM, SystemSettings.Keys.ONBOARDING_INITIALIZED, true)
    }
    val system = SystemValues(settings)
    val timeSync = moe.kiwi.thinkos.time.TimeSync(context, system, settings)
    internal val updates = moe.kiwi.thinkos.update.UpdateRuntime(context, settings, system, store,
        { sensors.touches }, { moe.kiwi.thinkos.update.UpdateScreens(router.settingsOpen, router.onboardingOpen) }) {
        plugins.event(null, "update")
    }
    /** One audio/video session for the whole OS (ADR-022); plugins reach it through `tos.media`. */
    val media: MediaEngine = MediaEngine(context, object : MediaEngine.Listener {
        override fun onState(state: Map<String, Any?>) {
            updates.onMedia(state)
            plugins.mediaState(state)
            cast.onMediaState(state)
            sensors.mediaPlaying(system.keepAwakeVideo && state["kind"] == "video" &&
                state["status"] in setOf("playing", "buffering", "reconnecting"))
        }
        override fun onEvent(owner: String, name: String, payload: Map<String, Any?>) {
            if (name == "media.error" || name == "media.warning") Log.w("ThinkOS", "[media:${owner.substringBefore(':')}] $name $payload")
            plugins.mediaEvent(owner, name, payload)
        }
    }, sleeping = { store.current.mode == moe.kiwi.thinkos.core.Mode.SLEEP }) { system.mediaPolicy }
    val storagePermission = StoragePermission(context, ::requestStoragePermission)
    /** The brightness, volume and Wi-Fi the control sheet changes (ADR-035); `device` events reach the plugin that holds `system.device`. */
    val device = moe.kiwi.thinkos.device.DeviceControl(context, { levels },
        onChange = { plugins.event(null, "device") }, onNetworkChange = { cast.reconfigure() },
        mode = { store.current.mode.name.lowercase(java.util.Locale.ROOT) },
        manualMode = { value -> sensors.setMode(moe.kiwi.thinkos.core.Mode.valueOf(value.uppercase(java.util.Locale.ROOT))) })
    val plugins: PluginRuntime = PluginRuntime(context, this, settings, media, storagePermission, device, castStatus = { cast.status() })
    private val imageLoader = moe.kiwi.thinkos.image.ImageLoader(context, plugins::imageOwner)
    private val images = moe.kiwi.thinkos.ui.ImageComponents(imageLoader, plugins::imageAuthority)
    val router = DeckRouter(context, store, { pluginId, event, args ->
        if (event == "page.leave") onPractice(pluginId, null, "")
        if (event == "variant" || event == "page.enter") {
            if (args.length() > 1) plugins.setVariant(pluginId, args.optString(1))
        }
        plugins.event(pluginId, event, args)
    }, { system.ambientPageAfterMillis }, shouldStartOnboarding = { system.onboardingNeeded },
        onOnboardingPrompt = {
            val attempts = (settings.get(SettingsStore.SYSTEM, SystemSettings.Keys.ONBOARDING_ATTEMPTS) as Number).toInt()
            settings.set(SettingsStore.SYSTEM, SystemSettings.Keys.ONBOARDING_ATTEMPTS, attempts + 1)
        }, onboardingIdleMillis = { system.onboardingIdleMillis })
    /** The sunrise-and-sunset theme asks this once a second; the Sun is recomputed at most every 30 s. */
    private val daylight = DaylightProbe({ runCatching { LocationProvider(settings).get().place }.getOrNull() })
    val modes = moe.kiwi.thinkos.core.ModeRuntime(settings,
        { runCatching { LocationProvider(settings).get().place }.getOrNull() })
    val sensors: SensorController = SensorController(context, store, system, { value ->
        brightness = value
        notifyObservers<DisplayObserver> { it.onBrightness(value) }
    }, { event, args ->
        if (event == "mode") {
            // SensorController publishes Store.mode first. Every admission gate
            // below reads that same state before any observers see the event.
            device.onModeChanged()
            media.onModeChanged()
            cast.onModeChanged()
        }
        plugins.event(null, event, args)
    }, { router.settingsOpen || router.onboardingOpen || panelOpen }, daylight::isDay, modeRuntime = modes)
    /** The control sheet is up: the display stays at full attention while somebody uses it. */
    @Volatile private var panelOpen = false

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
                val target = if (store.current.mode == moe.kiwi.thinkos.core.Mode.SLEEP) 0 else Math.round(value * max).coerceIn(0, max)
                audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, target, 0)
            }
        override fun previewBrightness(value: Float?) {
            if (value != null && !value.isFinite()) return
            sensors.previewBrightness(value?.coerceIn(minimumBrightness, ((cap.max ?: 100.0) / 100.0).toFloat()))
        }
    }

    /** Casts from phones and computers on the local network (ADR-031); off until `cast.enabled`. */
    val cast = moe.kiwi.thinkos.cast.CastHost(context, system, media, router, sensors, mainHandler,
        sleeping = { store.current.mode == moe.kiwi.thinkos.core.Mode.SLEEP },
        onForegroundRequested = {
            if (moe.kiwi.thinkos.shell.KioskState.foreign) moe.kiwi.thinkos.shell.ForegroundTask.restore(context, "cast return")
        }) {
        plugins.event(null, "cast")
    }

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
        override fun previewSetting(pluginId: String, scope: String, key: String, kind: String, value: Double?) {
            if (!plugins.hasPermission(pluginId, "system.settings")) return
            val field = settings.scope(scope)?.fields?.get(key) ?: return
            if (kind != moe.kiwi.thinkos.settings.SettingsDescriber.preview(scope, field)) return
            if (value != null && (!value.isFinite() || !router.settingsOpen)) return
            if (kind == "brightness") levels.previewBrightness(value?.div(100.0)?.toFloat())
        }
        override fun text(pluginId: String, key: String): String? = plugins.text(pluginId, key)
        override val panelEnabled get() = system.panelEnabled
        override val panelEdgeDp get() = system.panelEdgeDp
        override val panelIdleMillis get() = system.panelIdleMillis
        override val backEnabled get() = system.navBackEnabled || foreignPanel.globalNavigation
        override val backEdgeDp get() = system.navBackEdgeDp
        override val upEnabled get() = system.navUpEnabled || foreignPanel.globalNavigation
        override val upEdgeDp get() = system.navUpEdgeDp
        override fun panelChanged(open: Boolean) { panelOpen = open; router.panelChanged(open); if (open) sensors.touch() }
        override fun component(context: Context, pluginId: String, definition: JSONObject): RenderComponent? =
            DeviceComponents.createSlider(context, pluginId, definition, levels, plugins::hasPermission)
                ?: MediaComponents.create(context, pluginId, definition, media, plugins::hasPermission, levels,
                    CoverLoader { id, src, width, height, result -> imageLoader.load(id, src, width, height, result) },
                    emit = { name, args -> plugins.event(pluginId, name, JSONArray(args)) })
                ?: images.create(context, pluginId, definition)
    }

    internal val foreignPanel by lazy { moe.kiwi.thinkos.shell.ForeignPanel(context, this) }

    /** Android's language, 12/24-hour or time zone settings changed (broadcast): refresh everything that shows a time. */
    private val timeReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(ignored: Context, intent: android.content.Intent) {
            if (intent.action == android.content.Intent.ACTION_TIMEZONE_CHANGED) java.util.TimeZone.setDefault(null)
            updates.timeChanged()
        sensors.timeChanged()
            plugins.localeChanged()
            notifyObservers<RendererObserver> { it.onLocaleChanged() }
        }
    }

    /** Long press on content owned by [owner]: that plugin's settings, or the system's if it declares none. */
    fun localeChanged() {
        updates.timeChanged()
        sensors.timeChanged()
        plugins.localeChanged()
        notifyObservers<RendererObserver> { it.onLocaleChanged() }
    }

    fun openSettings(owner: String) {
        val scope = if (settings.scope(owner)?.hasSettingsPage == true) owner else SettingsStore.SYSTEM
        if (!router.openSettings(scope)) notifyObservers<NavigationObserver> { it.onSettingsUnavailable() }
    }

    private var started = false
    private var brightness = 0.4f
    private var pages: List<PluginPage> = emptyList()
    private var widgets: List<PluginWidget> = emptyList()
    private val states = linkedMapOf<String, JSONObject>()
    private val observers = linkedSetOf<RuntimeObserver>()
    private data class PracticeSnapshot(val owner: String, val name: String, val text: String)
    private var practice: PracticeSnapshot? = null

    private fun requestStoragePermission(requestId: Long): Boolean = observerHandles<StoragePermissionObserver> { it.onStoragePermissionRequest(requestId) }
    fun storagePermissionResult(requestId: Long, granted: Boolean) = storagePermission.result(requestId, granted)
    fun storagePermissionHostClosed(requestId: Long) = storagePermission.hostClosed(requestId)

    init {
        plugins.mediaState(media.snapshot())
        updates.onMedia(media.snapshot())
        MotionPrefs.clock = system.clockMotion
        settings.addListener { scope, keys ->
            if (scope == SettingsStore.SYSTEM && keys.any { key ->
                    key.startsWith("redshift.") || key.startsWith("sleep.") || key.startsWith("mode.") ||
                        key.startsWith("theme.") || key.startsWith("location.")
                }) mainHandler.post { sensors.refreshBrightness() }
            if (scope == SettingsStore.SYSTEM && keys.any { it == SystemSettings.Keys.TILES_LIVE || it == SystemSettings.Keys.TILES_INTERVAL || it == SystemSettings.Keys.TILES_BACKGROUND }) mainHandler.post { notifyObservers<RendererObserver> { it.onRenderSettingsChanged() } }
            if (scope == SettingsStore.SYSTEM && keys.any { it.startsWith("cast.") }) cast.reconfigure()
            if (scope == SettingsStore.SYSTEM && SystemSettings.Keys.CLOCK_MOTION in keys) MotionPrefs.clock = system.clockMotion
            if (scope == SettingsStore.SYSTEM && SystemSettings.Keys.ORIENTATION in keys) mainHandler.post { applyOrientationSetting() }
            if (scope == SettingsStore.SYSTEM && (SystemSettings.Keys.KIOSK_LOCK in keys || SystemSettings.Keys.PANEL_ENABLED in keys)) {
                mainHandler.post { notifyObservers<KioskObserver> { it.onKioskPolicy(system.kioskLock, system.panelEnabled) } }
            }
            if (scope == SettingsStore.SYSTEM && SystemSettings.Keys.BRIGHTNESS_MAX in keys) device.changed()
            if (scope == SettingsStore.SYSTEM && keys.any { it.startsWith("panel.") || it == SystemSettings.Keys.BRIGHTNESS_MAX }) {
                mainHandler.post { foreignPanel.sync() }
            }
        }
    }

    /** `display.orientation`: follow the sensor, or stay upright in the mounted landscape position / sensor portrait. */
    private fun orientationCode() = when (system.orientation) {
        "landscape" -> moe.kiwi.thinkos.core.DeviceOrientation.LANDSCAPE
        "portrait" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }
    private fun applyOrientationSetting() { notifyObservers<DisplayObserver> { it.onOrientationRequest(orientationCode()) } }

    fun start() {
        if (Looper.myLooper() != mainHandler.looper) { mainHandler.post { start() }; return }
        if (started) return
        started = true
        warmUpLocaleData()
        context.registerReceiver(timeReceiver, android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_LOCALE_CHANGED)
            addAction(android.content.Intent.ACTION_TIMEZONE_CHANGED)
            addAction(android.content.Intent.ACTION_TIME_CHANGED)       // also sent when the 12/24-hour switch flips
        })
        // Reconcile a trusted sleep schedule before device.start can recover
        // saved audio, or casting/plugin inputs can begin. A posted first tick
        // would briefly expose the default DAY state after a process restart.
        sensors.refreshBrightness()
        plugins.start(CrashRecovery.isSafeMode(context))
        sensors.start()
        timeSync.start()
        updates.start()
        device.start()
        foreignPanel.start()
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

    fun observe(observer: RuntimeObserver) {
        observers += observer
        if (observer is RendererObserver) {
            observer.onCatalog(pages, widgets)
            states.forEach { (id, state) -> observer.onState(id, state) }
        }
        if (observer is DisplayObserver) {
            observer.onBrightness(brightness)
            observer.onOrientationRequest(orientationCode())
        }
        if (observer is KioskObserver) observer.onKioskPolicy(system.kioskLock, system.panelEnabled)
        if (observer is NavigationObserver) practice?.takeIf {
            router.onboardingOpen && router.currentPage?.pluginId == it.owner && router.currentPage?.roles?.contains("onboarding") == true
        }?.let { observer.onPractice(it.owner, it.name, it.text) }
    }

    private inline fun <reified T : RuntimeObserver> notifyObservers(action: (T) -> Unit) {
        observers.toList().forEach { if (it is T) action(it) }
    }

    private inline fun <reified T : RuntimeObserver> observerHandles(action: (T) -> Boolean): Boolean =
        observers.toList().any { it is T && action(it) }

    var debugOrientationCode: Int = 10; private set
    fun debugOrientation(value: Int) {
        if (BuildConfig.DEBUG) {
            debugOrientationCode = value
            notifyObservers<DisplayObserver> { it.onOrientationRequest(moe.kiwi.thinkos.core.DeviceOrientation.forced(value)) }
        }
    }

    /** Only the DUMP-protected debug receiver exposes this UI test entry. */
    fun debugMaintenance() {
        if (BuildConfig.DEBUG) mainHandler.post { notifyObservers<NavigationObserver> { it.onSettingsUnavailable() } }
    }

    fun removeObserver(observer: RuntimeObserver) { observers -= observer }

    override fun onCatalog(pages: List<PluginPage>, widgets: List<PluginWidget>) {
        if (pages.isNotEmpty()) (context.applicationContext as ThinkApplication).updateGuard.catalog()
        this.pages = pages
        this.widgets = widgets
        states.keys.retainAll((pages.map { it.pluginId } + widgets.map { it.pluginId }).toSet())
        router.setPages(pages)
        if (!router.onboardingOpen) practice = null
        notifyObservers<RendererObserver> { it.onCatalog(pages, widgets) }
    }
    override fun onState(pluginId: String, state: JSONObject) {
        states[pluginId] = state
        notifyObservers<RendererObserver> { it.onState(pluginId, state) }
    }
    override fun onTileAnimation(pluginId: String, name: String) {
        notifyObservers<RendererObserver> { it.onTileAnimation(pluginId, name) }
    }
    override fun onPanelClose() { notifyObservers<NavigationObserver> { it.onPanelClose() } }
    internal fun systemHomeGesture() {
        // The system callback can arrive before ThinkStage sees ACTION_UP. Queue
        // the lesson result before observers cancel that touch and before page.leave.
        practiceCompleted("home")
        sensors.touch()
        notifyObservers<NavigationObserver> { it.onSystemHome() }
        foreignPanel.closeImmediately()
        router.homeGesture()
        start()
        moe.kiwi.thinkos.shell.ForegroundTask.restore(context, "edge home", clearAbove = true)
    }
    override fun onOpenAndroid(target: String) {
        notifyObservers<NavigationObserver> { it.onPanelClose() }
        if (!observerHandles<NavigationObserver> { it.onOpenAndroid(target) }) Log.w("ThinkOS", "No Activity to open the Android $target screen")
    }
    override fun onOpenApp(entry: moe.kiwi.thinkos.apps.AppEntry, result: (Boolean) -> Unit) {
        val fromForeignPanel = foreignPanel.showing
        notifyObservers<NavigationObserver> { it.onPanelClose() }
        if (entry.kind == "launcher" && entry.id == "launcher") {
            val opened = router.launcher()
            if (opened && fromForeignPanel) returnFromPanel("panel launcher")
            result(opened); return
        }
        if (entry.kind == "page") {
            if (router.onboardingOpen || pages.none { it.key == entry.id && it.app }) { result(false); return }
            // An explicit public app action leaves the settings sheet before launch.
            // Background focus requests still cannot pull a person out of settings.
            if (router.settingsOpen) router.closeSettings()
            router.open(entry.id)
            if (fromForeignPanel) returnFromPanel("panel app")
            result(router.currentPage?.key == entry.id); return
        }
        if (router.settingsOpen) { result(false); return }
        if (entry.kind != "android") { result(false); return }
        val packageName = entry.id.removePrefix("android:")
        if (!system.appsAndroid || packageName in system.appsHidden || !moe.kiwi.thinkos.apps.AppCatalog.packageName(packageName) ||
            !observerHandles<NavigationObserver> { it.onOpenAndroidApp(packageName, result) }) result(false)
    }

    /** Called only after the foreground Activity has claimed a user-initiated departure. */
    fun beforeForeignLaunch() {
        val snapshot = media.snapshot()
        when (moe.kiwi.thinkos.media.ForeignMediaPolicy.action(snapshot["kind"], snapshot["mirror"], snapshot["status"])) {
            moe.kiwi.thinkos.media.ForeignMediaPolicy.Action.STOP -> media.stop()
            moe.kiwi.thinkos.media.ForeignMediaPolicy.Action.PAUSE -> media.pause()
            moe.kiwi.thinkos.media.ForeignMediaPolicy.Action.KEEP -> Unit
        }
    }
    override fun onOpenSettings(scope: String) {
        val fromForeignPanel = foreignPanel.showing
        notifyObservers<NavigationObserver> { it.onPanelClose() }
        if (router.openSettings(scope)) {
            if (fromForeignPanel) returnFromPanel("panel settings")
        } else notifyObservers<NavigationObserver> { it.onSettingsUnavailable() }
    }
    private fun returnFromPanel(reason: String) {
        sensors.touch()
        moe.kiwi.thinkos.shell.ForegroundTask.restore(context, reason)
    }
    override fun onAbout() { notifyObservers<NavigationObserver> { it.onAbout() } }
    override fun onPluginConsent(request: moe.kiwi.thinkos.plugin.RegistryConsent, result: (Boolean) -> Unit) {
        if (!observerHandles<NavigationObserver> { it.onPluginConsent(request, result) }) result(false)
    }
    override fun registryVisible(id: String): Boolean = router.currentPage?.pluginId == id
    override fun onboardingStatus(): Map<String, Any?> = mapOf("version" to moe.kiwi.thinkos.core.OnboardingPolicy.VERSION, "active" to router.onboardingOpen,
        "updater" to false, "greeting_seconds" to system.onboardingGreetingSeconds)
    override fun onOpenOnboarding(mode: String) {
        notifyObservers<NavigationObserver> { it.onPanelClose() }
        if (router.openOnboarding(mode)) sensors.touch()
    }
    override fun onPractice(pluginId: String, name: String?, text: String) {
        if (name != null && (!router.onboardingOpen || router.currentPage?.pluginId != pluginId ||
                router.currentPage?.roles?.contains("onboarding") != true)) return
        if (name == null) {
            if (practice?.owner == pluginId) practice = null
        } else practice = PracticeSnapshot(pluginId, name, text)
        notifyObservers<NavigationObserver> { it.onPractice(pluginId, name, text) }
    }
    fun practiceCompleted(name: String) {
        val page = router.currentPage ?: return
        if (!moe.kiwi.thinkos.core.OnboardingPolicy.practiceMatches(router.onboardingOpen, "onboarding" in page.roles,
                page.pluginId, practice?.owner, practice?.name, name)) return
        onPractice(page.pluginId, null, "")
        plugins.event(page.pluginId, "practice", JSONArray().put(name))
    }
    fun replayPractice() {
        practice?.takeIf { router.onboardingOpen && router.currentPage?.pluginId == it.owner &&
            router.currentPage?.roles?.contains("onboarding") == true }?.let { value ->
            notifyObservers<NavigationObserver> { it.onPractice(value.owner, value.name, value.text) }
        }
    }
    override fun updateStatus(): Map<String, Any?> = updates.snapshot()
    override fun onOpenUpdates(): Boolean = observerHandles<NavigationObserver> { it.onUpdates() }
    override fun onEnableMiracast(): Boolean {
        // This callback can run under SettingsStore's lock on PluginThread.
        // Only read the existing status and post; no IO or window work here.
        if (cast.status()["miracastModule"] == true) return true
        // The public precommit guard still holds the settings monitor here.
        // Carry this exact intent through the queue; never replace it with a
        // later revision captured after another user's false edit.
        val expectedRevision = settings.revision()
        mainHandler.post { observerHandles<NavigationObserver> { it.onMiracastFeature(expectedRevision) } }
        return false
    }
    override fun onUpdateAction(action: String, tapElapsed: Long, result: (Boolean, String?) -> Unit): (() -> Unit) =
        updates.request(action, tapElapsed, result)
    override fun onBackIntercept(pluginId: String, on: Boolean) { router.interceptBack(pluginId, on) }
    override fun onBackAnswered(pluginId: String) { router.backAnswered(pluginId) }
    override fun onFocus(pageKey: String, live: Boolean, release: Boolean, answersTap: Boolean) {
        router.requestFocus(pageKey, live, release, answersTap)
    }
    override fun onError(pluginId: String, message: String) {
        Log.e("ThinkOS", "[plugin:$pluginId] $message")
        notifyObservers<DiagnosticObserver> { it.onError(pluginId, message) }
    }
}
