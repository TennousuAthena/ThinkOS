package moe.kiwi.thinkos.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import moe.kiwi.thinkos.plugin.JsonDocument
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.core.Store
import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.SystemValues
import moe.kiwi.thinkos.shell.KioskState
import java.io.File
import java.time.Instant
import java.time.ZoneOffset

/** Process-owned adapter. No Server, root calls or startup network.
 * Media snapshots enter only on Main; blocking key/package/file work stays on IO. */
internal class UpdateRuntime(private val context: Context, private val settings: SettingsStore,
                             private val system: SystemValues, private val store: Store,
                             private val touches: () -> StateFlow<Long>, private val screens: () -> UpdateScreens,
                             private val workOverride: (suspend (UpdatePolicy, UpdateRuntime) -> UpdateController.Work)? = null,
                             private val environmentOverride: StateFlow<UpdateSession.Environment>? = null,
                             private val onChanged: () -> Unit = {}) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = MutableStateFlow(system.updatePolicy)
    private val mutableEnvironment = MutableStateFlow(UpdateEnvironment.from(store.current.attention, emptyMap(), false))
    val environment: StateFlow<UpdateSession.Environment> = environmentOverride ?: mutableEnvironment
    private val availability = MutableStateFlow(UpdateController.Availability(false, false))
    internal val readiness: StateFlow<UpdateController.Availability> = availability
    private val automaticInstall = MutableStateFlow(false)
    private val controller = UpdateController(scope, policy, environment, availability, WallClock::elapsed, ::work,
        automaticInstall = automaticInstall)
    val status get() = controller.status
    private var lastMedia: Map<String, Any?> = emptyMap()
    private var started = false
    private var networkRegistered = false
    private val installStore = UpdateInstallContext.store(context)
    // Owned RAM-trust instrumentation runtimes must never reconfigure the
    // process-owned production root coordinator or its durable journal.
    private val modules = if (workOverride == null) ModuleUpdateRuntime.get(context) else null
    private val moduleView: StateFlow<ModuleUpdateRuntime.View> = modules?.view ?: MutableStateFlow(ModuleUpdateRuntime.View())
    private val notices = UpdateNoticeRuntime(context, scope) { UpdateInstallContext.now(context).copy(wall = trustedWall()) }
    private val repairs = UpdateRepairRuntime(context, installStore)
    val repairState: StateFlow<UpdateRepairUi> = repairs.state
    private var repairInspection: Job? = null
    private var repairInProgress = false
    val changes = combine(status, environment, notices.state, moduleView, repairState) { _, _, _, _, _ -> Unit }
    private var nativeWindow = false
    private var inventoryRefresh: Job? = null
    private val mutableFeature = MutableStateFlow(FeatureUiState())
    val featureState: StateFlow<FeatureUiState> = mutableFeature
    @Volatile private var featureScope: FeatureScope? = null
    private val castChangeEpoch = java.util.concurrent.atomic.AtomicLong()
    private var featurePlan: FeaturePreparedPlan? = null
    private var featureInspection: Job? = null
    private class FeatureSuperseded : Exception()
    private val quiet = UpdateQuietRuntime(context, system, store, environment, { status.value }, touches,
        ::screensSnapshot, ::trustedWall, automaticInstall) {
        modules?.silentFor(status.value.state.catalog_digest) == true
    }
    internal val automaticReadiness: StateFlow<Boolean> = automaticInstall
    private val network = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { refreshAvailability() } }
        override fun onLost(network: Network) { handler.post { refreshAvailability() } }
    }
    private val clockChanged: () -> Unit = { handler.post { refreshAvailability(); quiet.changed(); notices.changed() }; Unit }
    private val settingsChanged = SettingsStore.Listener { owner, keys ->
        if (owner == SettingsStore.SYSTEM && moe.kiwi.thinkos.settings.SystemSettings.Keys.CAST_MIRACAST in keys) {
            castChangeEpoch.incrementAndGet()
            // Revoke synchronously on the writer's thread, before a queued IO
            // completion could overwrite a later false intent. Final-confirmed
            // durable transactions own their enable/flush and are not revoked.
            val feature = featureScope
            if (feature != null && !feature.durable) {
                feature.revoke()
                handler.post { if (featureScope === feature && !feature.durable) closeMiracastFeature(feature.token) }
            }
        }
        if (owner == SettingsStore.SYSTEM && keys.any { it.startsWith("update.") }) handler.post {
            featureScope?.token?.let(::closeMiracastFeature)
            policy.value = system.updatePolicy
            refreshAvailability()
            quiet.changed()
            notices.changed()
        }
    }

    fun start() {
        checkMain()
        if (started) return
        started = true
        settings.addListener(settingsChanged)
        WallClock.addListener(clockChanged)
        // No NET_CAPABILITY_VALIDATED requirement: a LAN-only mirror works
        // without Google connectivity checks or a public internet connection.
        networkRegistered = runCatching { network.registerDefaultNetworkCallback(callback); true }.getOrDefault(false)
        refreshAvailability()
        modules?.attach(scope, { system.updateInstallLimits }, {
            ((settings.get(SettingsStore.SYSTEM, "update.module_guard_poll_s") as Number).toDouble() * 1000).toLong()
        }, { if (system.castMiracast) setOf("thinkos-cast") else emptySet() }, admitted = {
            val screen = screensSnapshot()
            environment.value.allowsInstall() && !screen.settings && !screen.onboarding
        }, enableFeature = { feature ->
            if (feature != FeatureScope.FEATURE) false else try {
                settings.set(SettingsStore.SYSTEM, moe.kiwi.thinkos.settings.SystemSettings.Keys.CAST_MIRACAST, true)
                UpdateIo.run { settings.flush() }
                system.castMiracast
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
        })
        notices.start()
        scope.launch {
            combine(store.state, KioskState.foregroundState) { state, _ -> state.attention }.collect {
                publishEnvironment()
            }
        }
        controller.start()
        quiet.start(scope, status)
        scope.launch {
            combine(status, environment, policy, notices.state, moduleView) { _, _, _, _, _ -> Unit }.collect { onChanged() }
        }
    }

    private fun featurePublish(value: FeatureUiState) {
        mutableFeature.value = value
        onChanged()
    }
    private fun featureCurrent(value: FeatureScope) = featureScope === value && value.valid &&
        value.policy == system.updatePolicy.copy(enabled = true) && value.settingEpoch == castChangeEpoch.get()

    /** Opens only a read-only local inspection. No catalog/package request is
     * sent until a separate real native PREPARE touch has been admitted. */
    fun openMiracastFeature(expectedRevision: Long): String {
        checkMain()
        featureScope?.token?.let(::closeMiracastFeature)
        val token = UpdateInstaller.newId()
        val owner = FeatureScope(token, system.updatePolicy.copy(enabled = true), settingEpoch = castChangeEpoch.get())
        featureScope = owner
        // This revision originates inside the original settings precommit lock,
        // before any post to Main. A later false intent must close this request.
        if (settings.revision() != expectedRevision || !featureCurrent(owner) || system.castMiracast) {
            owner.revoke(); featureScope = null
            featurePublish(FeatureUiState(FeatureStage.CLOSED, token))
            return token
        }
        featurePublish(FeatureUiState(FeatureStage.CHECKING, token))
        if (!controller.reserveNative(token)) {
            featurePublish(FeatureUiState(FeatureStage.UNAVAILABLE, token, "busy"))
            return token
        }
        featureInspection = scope.launch {
            try {
                // The cast service can already exist while its switch is off,
                // including devices that have no optional OTA updater.
                val activeCast = UpdateIo.run {
                    moe.kiwi.thinkos.cast.miracast.WfdControl(
                        moe.kiwi.thinkos.cast.miracast.LocalSocketWfdTransport()).hello().ok
                }
                if (!featureCurrent(owner)) return@launch
                if (activeCast) {
                    settings.set(SettingsStore.SYSTEM, moe.kiwi.thinkos.settings.SystemSettings.Keys.CAST_MIRACAST, true) {
                        if (settings.revision() != expectedRevision || !featureCurrent(owner) || system.castMiracast)
                            throw FeatureSuperseded()
                    }
                    featurePublish(FeatureUiState(FeatureStage.INSTALLED, token))
                    controller.releaseNative(token)
                    return@launch
                }
                val source = modules
                if (source == null) { featurePublish(FeatureUiState(FeatureStage.UNAVAILABLE, token, "updater")); return@launch }
                source.refresh()
                if (!featureCurrent(owner)) return@launch
                val view = source.view.value
                val installed = view.installedCodes[FeatureScope.MODULE]
                if (view.inventoryReadable && installed != null && installed > 0) {
                    settings.set(SettingsStore.SYSTEM, moe.kiwi.thinkos.settings.SystemSettings.Keys.CAST_MIRACAST, true) {
                        if (settings.revision() != expectedRevision || !featureCurrent(owner) || system.castMiracast)
                            throw FeatureSuperseded()
                    }
                    featurePublish(FeatureUiState(FeatureStage.INSTALLED, token))
                    controller.releaseNative(token)
                    return@launch
                }
                val reason = when {
                    !view.inventoryReadable || installed == null || installed < 0 -> "inventory"
                    view.active -> "busy"
                    !view.available -> "updater"
                    owner.policy.sources.isEmpty() -> "sources"
                    UpdateKeys.load(context).getOrNull() == null -> "keys"
                    !status.value.configured -> "keys"
                    else -> null
                }
                if (!featureCurrent(owner)) return@launch
                featurePublish(FeatureUiState(if (reason == null) FeatureStage.IDLE else FeatureStage.UNAVAILABLE, token, reason))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: FeatureSuperseded) {
                if (featureScope === owner) closeMiracastFeature(token)
            } catch (_: Exception) {
                if (featureCurrent(owner)) featurePublish(FeatureUiState(FeatureStage.UNAVAILABLE, token, "inventory"))
            }
        }
        return token
    }

    suspend fun prepareMiracastFeature(token: String, tapElapsed: Long): Boolean {
        checkMain()
        val previous = featureScope?.takeIf { it.token == token && featureCurrent(it) } ?: return false
        if (mutableFeature.value.stage !in setOf(FeatureStage.IDLE, FeatureStage.FAILED) ||
            !previous.policy.fresh(tapElapsed, WallClock.elapsed()) || system.castMiracast) return false
        previous.revoke()
        val owner = FeatureScope(token, system.updatePolicy.copy(enabled = true), settingEpoch = castChangeEpoch.get())
        featureScope = owner; featurePlan = null
        return controller.native(token, tapElapsed, installing = false) { work ->
            if (!featureCurrent(owner)) return@native
            featurePublish(FeatureUiState(FeatureStage.PREPARING, token))
            try {
                val native = work as? FeatureModuleWork ?: error("Native module preparation unavailable")
                val plan = native.prepareFeature(owner) { count, size ->
                    if (featureCurrent(owner)) mutableFeature.value = FeatureUiState(FeatureStage.PREPARING, token,
                        downloaded = count.coerceIn(0, size), size = size)
                }
                if (featureCurrent(owner) && !system.castMiracast) {
                    featurePlan = plan
                    featurePublish(FeatureUiState(FeatureStage.READY, token, plan = plan.ui))
                }
            } catch (cancelled: CancellationException) {
                if (featureCurrent(owner)) featurePublish(FeatureUiState(FeatureStage.FAILED, token, "media"))
                throw cancelled
            } catch (_: Exception) {
                if (featureCurrent(owner)) featurePublish(FeatureUiState(FeatureStage.FAILED, token, "package"))
            }
        }
    }

    suspend fun installMiracastFeature(token: String, planFingerprint: String, tapElapsed: Long): Boolean {
        checkMain()
        val plan = featurePlan ?: return false
        val owner = plan.scope
        if (owner.token != token || !featureCurrent(owner) || mutableFeature.value.stage != FeatureStage.READY ||
            plan.ui.fingerprint != planFingerprint || !owner.policy.fresh(tapElapsed, WallClock.elapsed()) || system.castMiracast) return false
        return controller.native(token, tapElapsed, installing = true) { work ->
            if (!featureCurrent(owner) || featurePlan !== plan) return@native
            featurePublish(FeatureUiState(FeatureStage.INSTALLING, token, plan = plan.ui))
            try {
                val native = work as? FeatureModuleWork ?: error("Native module installation unavailable")
                val outcome = native.installFeature(plan) {
                    (featureCurrent(owner) || owner.durable) && environment.value.allowsInstall()
                }
                if (featureScope === owner) {
                    featurePlan = null
                    featurePublish(FeatureUiState(if (outcome == UpdateInstaller.Outcome.SUBMITTED) FeatureStage.SUBMITTED else FeatureStage.FAILED,
                        token, if (outcome == UpdateInstaller.Outcome.SUBMITTED) null else "changed", plan.ui))
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (featureScope === owner) {
                    featurePlan = null
                    featurePublish(FeatureUiState(FeatureStage.FAILED, token, "changed"))
                }
            }
        }
    }

    /** Synchronous revocation fences late IO before cancellation is joined.
     * A committed ModuleUpdateStore transaction is never deleted or rolled back
     * by closing its UI; the existing recovery chain remains its sole owner. */
    fun closeMiracastFeature(token: String) {
        checkMain()
        val owner = featureScope?.takeIf { it.token == token } ?: return
        val returningFromInstall = owner.durable ||
            mutableFeature.value.stage in setOf(FeatureStage.INSTALLING, FeatureStage.SUBMITTED)
        // Revocation still stops an unconsumed plan before prepare. Once final
        // consent is consumed, the noncancellable durable commit and its manual
        // operation must survive Return, including the consume/prepare window.
        owner.revoke(); featurePlan = null; featureScope = null
        featureInspection?.cancel(); featureInspection = null
        val operation = controller.releaseNative(token, cancelOperation = !returningFromInstall)
        if (operation != null) scope.launch { operation.join() }
        featurePublish(FeatureUiState())
    }

    fun onMedia(state: Map<String, Any?>) { checkMain(); lastMedia = state.toMap(); publishEnvironment() }
    fun timeChanged() { checkMain(); if (started) { refreshAvailability(); quiet.changed(); notices.changed() } }
    fun nativeWindow(open: Boolean) {
        checkMain()
        val entering = open && !nativeWindow
        nativeWindow = open; quiet.changed()
        if (entering && workOverride == null) inspectRepair()
        val source = modules
        if (entering && source != null && inventoryRefresh?.isActive != true) inventoryRefresh = scope.launch {
            try { source.refresh() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { android.util.Log.e("ThinkOS", "Component inventory refresh unavailable", error) }
        }
    }
    /** No plugin repair method: only the existing native update window supplies touch credit. */
    fun inspectRepair() {
        checkMain()
        if (workOverride != null || repairInProgress || repairInspection?.isActive == true) return
        repairInspection = scope.launch { repairs.inspect() }
    }
    suspend fun repair(tapElapsed: Long): Boolean {
        checkMain()
        if (workOverride != null || repairInProgress || !nativeWindow || !system.updatePolicy.fresh(tapElapsed, WallClock.elapsed()) ||
            status.value.busy || repairState.value.stage != UpdateRepairUi.Stage.READY) return false
        return runUpdateRepair(controller, scope, { repairInProgress = it },
            isolate = { repairs.isolate().also { if (it) notices.changed() } },
            afterRestart = { callerCancelled ->
                // A window reopened while stop was joining skipped inspection.
                // Cancellation may precede isolate setting CHECKING, so also
                // refresh READY from disk after a cancelled join.
                if (nativeWindow && (callerCancelled || repairState.value.stage == UpdateRepairUi.Stage.CHECKING)) inspectRepair()
            })
    }

    internal fun screensSnapshot() = screens().let { it.copy(settings = it.settings || nativeWindow) }
    internal suspend fun automaticEligibility() = quiet.admitted()
    suspend fun manual(action: UpdateController.Action, userTapElapsed: Long): Boolean {
        val current = publicStatus()
        val allowed = when (action) {
            UpdateController.Action.CHECK -> current.can_check
            UpdateController.Action.DOWNLOAD -> current.can_download
            UpdateController.Action.INSTALL -> current.can_install
        }
        return allowed && controller.manual(action, userTapElapsed)
    }

    fun publicStatus(): UpdateStatusDocument {
        val value = UpdatePublicStatus.read(status.value, environment.value, policy.value,
            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, java.util.Locale.getDefault(), modules?.view?.value).copy(
                result = notices.state.value.notice, silent_install = notices.state.value.silent)
        return if (repairInProgress || File(UpdateInstallContext.directory(context), "pending.json").exists() || moduleView.value.active)
            value.copy(can_check = false, can_download = false, can_install = false)
        else if (featureScope != null) value.copy(can_check = false, can_download = false, can_install = false) else value
    }

    fun snapshot(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return JsonDocument.toHost(UpdateModels.writeUpdateStatusDocument(publicStatus())) as Map<String, Any?>
    }

    /** Admission returns quickly; state/event reports transfer completion. */
    fun request(action: String, tapElapsed: Long, result: (Boolean, String?) -> Unit): () -> Unit {
        val selected = when (action) {
            "check" -> UpdateController.Action.CHECK
            "download" -> UpdateController.Action.DOWNLOAD
            "install" -> UpdateController.Action.INSTALL
            else -> null
        }
        val task = scope.launch {
            val accepted = selected != null && manual(selected, tapElapsed)
            result(accepted, if (accepted) null else "unavailable")
        }
        return { task.cancel() }
    }

    private fun publishEnvironment() {
        mutableEnvironment.value = UpdateEnvironment.from(store.current.attention, lastMedia,
            !KioskState.resumed && KioskState.foregroundTaskId >= 0)
    }

    @Suppress("DEPRECATION")
    private fun refreshAvailability() {
        val connected = networkRegistered && runCatching { network.activeNetworkInfo?.isConnected == true }.getOrDefault(false)
        availability.value = UpdateController.Availability(connected, trustedWall() != null)
    }

    private fun trustedWall(): Long? = WallClock.now().takeIf {
        runCatching { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).year >= policy.value.trustedYear }.getOrDefault(false)
    }

    private suspend fun work(config: UpdatePolicy): UpdateController.Work {
        workOverride?.let { return it(config, this) }
        val keys = UpdateKeys.load(context).getOrElse { throw UpdateController.Unconfigured("keys") }
        return UpdateIo.run {
            val root = File(context.filesDir, "update")
            val sequences = UpdateSequenceStore(File(root, "ledger"))
            val fetcher = Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(config.connectMillis, config.readMillis),
                health = UpdateSourceHealth(File(root, "source-health.json")))
            try {
                val client = UpdateCatalogClient(fetcher, keys, sequences, UpdateCatalogCache(File(root, "metadata"), keys))
                val session = UpdateSession(config.channel, client, UpdateState(File(root, "state"), sequences), File(root, "downloads"),
                    UpdatePackageVerifier.forContext(context), ::installedDevice, {
                        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }?.toLong()
                        UpdateState.Time(boot, WallClock.elapsed(), trustedWall())
                    }, installStore, modules, fetcher)
                val feature = modules?.let { source -> NativeFeaturePreparation(config, client, source, fetcher,
                    File(root, "downloads"), UpdatePackageVerifier.forContext(context), ::installedDevice, environment) { catalog ->
                    UpdateIo.run { sequences.requireCurrent(catalog) }
                } }
                sessionWork(session, fetcher, feature)
            } catch (error: Throwable) { fetcher.close(); throw error }
        }
    }

    internal fun sessionWork(session: UpdateSession, fetcher: Fetcher, feature: FeatureModuleWork? = null) = SessionWork(session, fetcher,
        UpdateInstaller(context, installStore) { UpdateInstallContext.now(context) }, { system.updateInstallLimits }, quiet::admitted, modules, feature)

    /** Owned test instances use the same adapter with RAM-only trust. Production
     * has one process-lifetime instance and never accepts a factory from plugins. */
    internal suspend fun stop() {
        featureScope?.token?.let(::closeMiracastFeature)
        controller.stop()
        settings.removeListener(settingsChanged); WallClock.removeListener(clockChanged)
        if (networkRegistered) runCatching { network.unregisterNetworkCallback(callback) }
        scope.cancel()
    }

    @Suppress("DEPRECATION")
    private fun installedDevice(): UpdateCatalog.Device {
        val installed = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        return UpdateCatalog.Device(Build.DEVICE, Build.VERSION.SDK_INT, installed.versionCode,
            UpdateCatalog.sha256(installed.signatures!!.single().toByteArray()))
    }

    /** Also used by native tests with RAM-only keys and an owned cache root;
     * production never reads test keys from files/settings or another app. */
    internal class SessionWork(private val session: UpdateSession, private val fetcher: Fetcher,
                               private val installer: UpdateInstaller? = null,
                               private val limits: (() -> UpdateInstallStore.Limits)? = null,
                               private val automaticAdmission: suspend () -> Boolean = { false },
                               private val modules: ModuleUpdateRuntime? = null,
                               private val feature: FeatureModuleWork? = null) : UpdateController.Work, FeatureModuleWork {
        override suspend fun prepareFeature(scope: FeatureScope, progress: (Long, Long) -> Unit): FeaturePreparedPlan =
            requireNotNull(feature) { "Native feature updater unavailable" }.prepareFeature(scope, progress)
        override suspend fun installFeature(plan: FeaturePreparedPlan, admitted: suspend () -> Boolean): UpdateInstaller.Outcome =
            requireNotNull(feature) { "Native feature updater unavailable" }.installFeature(plan, admitted)
        override val status get() = session.status
        override val offer get() = session.offer
        override suspend fun restore() { session.restore() }
        override suspend fun check(sources: List<String>) = session.check(sources).origin != UpdateCatalogClient.Origin.OFFLINE_CACHE
        override suspend fun download(manual: Boolean, environment: StateFlow<UpdateSession.Environment>) = session.download(manual, environment) != null
        override val installable get() = installer != null && limits != null
        override suspend fun install(manual: Boolean, environment: StateFlow<UpdateSession.Environment>): Boolean {
            val platform = installer ?: return false
            val config = limits?.invoke() ?: return false
            return session.install(manual, environment) { catalog, file, _ ->
                val admitted: suspend () -> Boolean = { environment.value.allowsInstall() && (manual || automaticAdmission()) }
                if (modules != null) modules.refresh()
                if (modules?.view?.value?.active == true) UpdateInstaller.Outcome.CANCELLED
                else if (modules?.silentFor(catalog.digest) == true)
                    modules.install(catalog, file, automatic = !manual, admitted)
                else platform.install(catalog.apkOnly(), file, automatic = !manual, config, admitted)
            }
        }
        override fun close() = fetcher.close()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) { "Update runtime requires Main" }
}
