package moe.kiwi.thinkos

import android.app.Activity
import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import moe.kiwi.thinkos.core.Theme
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginWidget
import moe.kiwi.thinkos.service.KioskService
import moe.kiwi.thinkos.shell.CrashRecovery
import moe.kiwi.thinkos.shell.KioskPolicy
import moe.kiwi.thinkos.shell.KioskState
import moe.kiwi.thinkos.ui.ThinkStage
import moe.kiwi.thinkos.ui.LegalDialog
import moe.kiwi.thinkos.ui.UpdateDialog
import moe.kiwi.thinkos.ui.MiracastModuleDialog
import moe.kiwi.thinkos.ui.PluginConsentDialog
import moe.kiwi.thinkos.ui.NativeTouchConfirmation
import moe.kiwi.thinkos.ui.Palette
import moe.kiwi.thinkos.ui.readableButtons
import org.json.JSONObject

class MainActivity : Activity(), RuntimeHost.Observer {
    private lateinit var host: RuntimeHost
    private lateinit var stage: ThinkStage
    private var maintenance: AlertDialog? = null
    private var diagnosticDialog: AlertDialog? = null
    private var legal: LegalDialog? = null
    private var updates: UpdateDialog? = null
    private var miracastModule: MiracastModuleDialog? = null
    private var miracastRevision: Long? = null
    private var pluginConsent: PluginConsentDialog? = null
    private lateinit var kiosk: KioskPolicy
    private var reportedDrawn = false
    private var lastLocale: java.util.Locale = java.util.Locale.getDefault()
    private var storageRequest: Long? = null
    private var foreignLaunchPending = false

    override fun onCreate(savedInstanceState: Bundle?) {
        if (BuildConfig.DEBUG) {
            TouchInjection.sink = { event -> dispatchTouchEvent(event) }
            TouchInjection.legalState = { legal?.debugState() ?: "{}" }
            TouchInjection.updateState = { updates?.debugState() ?: "{}" }
        }
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        if (!(application as ThinkApplication).updateGuard.allowsRuntime) {
            setContentView(View(this).apply { setBackgroundColor(android.graphics.Color.BLACK) })
            return
        }
        host = (application as ThinkApplication).runtime
        kiosk = KioskPolicy(this)
        // Reading /sdcard/Music needs a runtime permission; as Device Owner we grant it without a dialog.
        kiosk.grantStorageRead()
        host.store.update { it.copy(safeMode = CrashRecovery.isSafeMode(this)) }
        stage = ThinkStage(this, host.store, host.router, { host.sensors.touch() }, { showMaintenance() }, host.pageEvents, host::openSettings)
        stage.onPractice = host::practiceCompleted
        setContentView(stage)
        stage.viewTreeObserver.addOnDrawListener(object : android.view.ViewTreeObserver.OnDrawListener {
            private var drawn = false
            override fun onDraw() {
                if (drawn) return
                drawn = true
                stage.post {
                    if (stage.viewTreeObserver.isAlive) stage.viewTreeObserver.removeOnDrawListener(this)
                    (application as ThinkApplication).updateGuard.frame()
                    host.plugins.registryAfterFirstFrame()
                }
            }
        })
        host.observe(this)
        host.start()
        if (intent.hasCategory(Intent.CATEGORY_HOME)) host.router.homeKey()
        startForegroundService(Intent(this, KioskService::class.java))
        Log.i("ThinkOS", "Activity created; orientation=${resources.configuration.orientation}")
    }

    override fun onResume() {
        super.onResume()
        if (!::host.isInitialized) return
        KioskState.noteResumed(taskId)
        if (BuildConfig.DEBUG) TouchInjection.stageState = { stage.debugState().toString() }
        if (KioskState.returningBeforeTimeout()) host.sensors.touch()
        if (storageRequest == null) KioskState.maintenanceUntil = 0 else KioskState.allowSystemSettings()
        immersive()
        KioskState.leaveForeign()
        kiosk.apply(this, host.system.kioskLock, host.system.panelEnabled)   // coming back from Android's settings locks again
        stage.start()
        host.replayPractice()
        host.foreignPanel.sync()
    }
    override fun onPause() {
        if (!::host.isInitialized) { super.onPause(); return }
        pluginConsent?.dismiss()
        miracastModule?.dismiss()
        if (BuildConfig.DEBUG && KioskState.ownsForegroundTask(taskId)) TouchInjection.stageState = null
        KioskState.notePaused(taskId)
        stage.stop()
        host.foreignPanel.sync()
        super.onPause()
    }
    override fun onDestroy() {
        if (BuildConfig.DEBUG) { TouchInjection.sink = null; TouchInjection.legalState = null; TouchInjection.updateState = null }
        if (!::host.isInitialized) { super.onDestroy(); return }
        // An Activity cannot leave Lua suspended on a permission dialog it is no longer able to show.
        storageRequest?.let(host::storagePermissionHostClosed)
        storageRequest = null
        host.removeObserver(this)
        maintenance?.dismiss()
        diagnosticDialog?.dismiss()
        legal?.dismiss()
        updates?.dismiss()
        miracastModule?.dismiss()
        pluginConsent?.dismiss()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (!::host.isInitialized) return
        miracastModule?.dismiss()
        stage.requestLayout()
        if (newConfig.locales[0] != lastLocale) { lastLocale = newConfig.locales[0]; host.localeChanged() }
        Log.i("ThinkOS", "Configuration changed without Activity recreation: ${newConfig.orientation}")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!::host.isInitialized) return
        // The Home key always means "take me home": it also leaves the settings page, an app's page and the control sheet.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) { onSystemHome(); host.router.homeKey() }
        if (BuildConfig.DEBUG && intent.hasExtra("debug.orientation")) {
            requestedOrientation = moe.kiwi.thinkos.core.DeviceOrientation.forced(intent.getIntExtra("debug.orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR))
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!::host.isInitialized) return
        if (hasFocus) {
            immersive()
            if (KioskState.mayRelock()) kiosk.relock(this, host.system.kioskLock)
        }
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::host.isInitialized && event.actionMasked == MotionEvent.ACTION_DOWN) host.sensors.touch()
        return super.dispatchTouchEvent(event)
    }
    /** Back peels one layer off what is over the page sequence; on the sequence itself it does nothing (ADR-040). */
    @Deprecated("Kiosk keeps the device on its home page")
    override fun onBackPressed() { if (::stage.isInitialized) stage.back() }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
    }
    override fun onCatalog(pages: List<PluginPage>, widgets: List<PluginWidget>) {
        stage.updateCatalog(pages, widgets)
        if (!reportedDrawn && pages.isNotEmpty()) {
            reportedDrawn = true
            stage.post { reportFullyDrawn() }
        }
    }
    override fun onState(pluginId: String, state: JSONObject) { stage.updatePluginState(pluginId, state) }
    override fun onOrientationRequest(value: Int) {
        requestedOrientation = value
    }
    override fun onKioskPolicy(locked: Boolean, panel: Boolean) { kiosk.apply(this, locked, panel) }
    override fun onPanelClose() { stage.closePanel() }
    override fun onPractice(pluginId: String, name: String?, text: String) {
        if (name == null || host.router.onboardingOpen && host.router.currentPage?.pluginId == pluginId) {
            stage.setPractice(name, text)
        }
    }
    override fun onSystemHome() {
        maintenance?.dismiss(); diagnosticDialog?.dismiss(); legal?.dismiss(); updates?.dismiss(); miracastModule?.dismiss(); pluginConsent?.dismiss()
        stage.prepareSystemHome()
    }
    override fun onOpenAndroid(target: String): Boolean {
        val action = when (target) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "locale" -> Settings.ACTION_LOCALE_SETTINGS
            "date" -> Settings.ACTION_DATE_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            "settings" -> Settings.ACTION_SETTINGS
            else -> return false
        }
        return launchOutside(Intent(action))
    }
    override fun onOpenAndroidApp(packageName: String, result: (Boolean) -> Unit): Boolean {
        if (!KioskState.ownsForegroundTask(taskId) || foreignLaunchPending) return false
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        if (intent == null) { showOpenFailure(packageName); result(false); return true }
        return launchOutside(intent, result)
    }

    /**
     * Hands the screen to one of Android's own screens or apps at the user's request (ADR-035). The lock ends first (it would
     * refuse to start another app), ThinkOS's watchdog is told to leave the foreground alone for the configured time, and
     * coming back, by Home or by that timeout, locks again.
     */
    private fun launchOutside(intent: Intent, result: (Boolean) -> Unit = {}): Boolean {
        if (!KioskState.ownsForegroundTask(taskId) || foreignLaunchPending) return false
        foreignLaunchPending = true
        try { host.beforeForeignLaunch() }
        catch (error: RuntimeException) {
            foreignLaunchPending = false
            Log.w("ThinkOS", "Unable to prepare the media session for an external app", error)
            showOpenFailure(intent.component?.packageName ?: "Android")
            result(false); return true
        }
        KioskState.enterForeign(host.system.appsReturnAfterMillis)
        kiosk.leaveLock(this)
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed || !KioskState.ownsForegroundTask(taskId)) {
                if (KioskState.foregroundTaskId == taskId) KioskState.leaveForeign()
                foreignLaunchPending = false; result(false); return@postDelayed
            }
            var opened = false
            try { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); opened = true }
            catch (error: Exception) {
                Log.w("ThinkOS", "Unable to open $intent", error)
                KioskState.leaveForeign()
                kiosk.apply(this, host.system.kioskLock, host.system.panelEnabled)
                showOpenFailure(intent.component?.packageName ?: "Android")
            }
            finally { foreignLaunchPending = false }
            result(opened)
        }, 250)
        return true
    }
    private fun showOpenFailure(packageName: String) {
        val title = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0)).toString() }.getOrDefault(packageName)
        android.widget.Toast.makeText(this, getString(R.string.apps_open_failed, title), android.widget.Toast.LENGTH_SHORT).show()
    }
    override fun onSettingsUnavailable() { showMaintenance() }
    override fun onAbout() {
        if (isFinishing || isDestroyed || legal?.isShowing == true || miracastModule != null) return
        legal = LegalDialog(this, Palette.forTheme(host.store.current.theme)) {
            host.sensors.touch()
            host.router.noteInteraction()
        }.apply {
            setOnDismissListener { legal = null; nativeWindowsChanged(); immersive() }
            show()
        }
        nativeWindowsChanged()
    }
    override fun onRenderSettingsChanged() { stage.refreshSettings() }
    override fun onPluginConsent(request: moe.kiwi.thinkos.plugin.RegistryConsent, result: (Boolean) -> Unit): Boolean {
        if (isFinishing || isDestroyed || !KioskState.ownsForegroundTask(taskId) ||
            !window.decorView.hasWindowFocus() || pluginConsent != null || miracastModule != null ||
            listOf(maintenance, diagnosticDialog, legal, updates).any { it?.isShowing == true }) return false
        val prompt = PluginConsentDialog(this, request, Palette.forTheme(host.store.current.theme)) { accepted ->
            pluginConsent = null
            nativeWindowsChanged()
            immersive()
            if (accepted) { host.sensors.touch(); host.router.noteInteraction() }
            result(accepted)
        }
        pluginConsent = prompt
        return try { prompt.show(); nativeWindowsChanged(); true }
        catch (error: RuntimeException) {
            pluginConsent = null
            nativeWindowsChanged()
            Log.w("ThinkOS", "Cannot show plugin confirmation", error)
            false
        }
    }
    override fun onUpdates(): Boolean {
        if (isFinishing || isDestroyed || !KioskState.ownsForegroundTask(taskId) || miracastModule != null) return false
        if (updates?.isShowing == true) return true
        updates = UpdateDialog(this, Palette.forTheme(host.store.current.theme), host.updates::publicStatus,
            host.updates.changes, host.updates::manual,
            readRepair = { host.updates.repairState.value }, inspectRepair = host.updates::inspectRepair,
            repair = host.updates::repair) {
            host.sensors.touch(); host.router.noteInteraction()
        }.apply { setOnDismissListener { updates = null; nativeWindowsChanged(); immersive() }; show() }
        nativeWindowsChanged()
        return true
    }
    override fun onMiracastFeature(expectedRevision: Long): Boolean {
        if (host.settings.revision() != expectedRevision) return false
        if (miracastModule != null) {
            if (miracastRevision == expectedRevision) return true
            miracastModule?.dismiss()
        }
        if (isFinishing || isDestroyed || !KioskState.ownsForegroundTask(taskId) ||
            !window.decorView.hasWindowFocus() || pluginConsent != null ||
            listOf(maintenance, diagnosticDialog, legal, updates).any { it?.isShowing == true }) return false
        val token = host.updates.openMiracastFeature(expectedRevision)
        lateinit var prompt: MiracastModuleDialog
        prompt = MiracastModuleDialog(this, Palette.forTheme(host.store.current.theme), token,
            host.updates.featureState,
            prepare = { tap -> host.updates.prepareMiracastFeature(token, tap) },
            install = { fingerprint, tap -> host.updates.installMiracastFeature(token, fingerprint, tap) },
            revoke = { host.updates.closeMiracastFeature(token) },
            ownsForeground = {
                !isFinishing && !isDestroyed && KioskState.ownsForegroundTask(taskId) &&
                    (miracastModule?.isShowing == true || window.decorView.hasWindowFocus()) &&
                    pluginConsent == null && listOf(maintenance, diagnosticDialog, legal, updates).none { it?.isShowing == true }
            }, changed = { nativeWindowsChanged() },
            interaction = { host.sensors.touch(); host.router.noteInteraction() },
            closed = {
                if (miracastModule === prompt) { miracastModule = null; miracastRevision = null }
                nativeWindowsChanged(); immersive()
            })
        miracastModule = prompt
        miracastRevision = expectedRevision
        return try { prompt.open(); true }
        catch (error: RuntimeException) {
            prompt.dismiss(); Log.w("ThinkOS", "Cannot show casting component prompt", error); false
        }
    }
    override fun onTileAnimation(pluginId: String, name: String) { stage.playTileAnimation(pluginId, name) }
    override fun onLocaleChanged() {
        // A debug locale is process-local. The Activity may own a separate Resources
        // instance from the Application; native dialogs must follow the plugin UI too.
        val locale = java.util.Locale.getDefault()
        if (resources.configuration.locales[0] != locale) {
            @Suppress("DEPRECATION")
            resources.updateConfiguration(android.content.res.Configuration(resources.configuration).apply {
                setLocale(locale)
            }, resources.displayMetrics)
        }
        stage.layoutDirection = if (android.text.TextUtils.getLayoutDirectionFromLocale(locale) == 1)
            android.view.View.LAYOUT_DIRECTION_RTL else android.view.View.LAYOUT_DIRECTION_LTR
        stage.localeChanged()
        legal?.refresh()
        updates?.refresh()
    }
    override fun onStoragePermissionRequest(requestId: Long): Boolean {
        if (isFinishing || isDestroyed || storageRequest != null) return false
        storageRequest = requestId
        KioskState.allowSystemSettings() // The watchdog must not cover Android's permission dialog.
        return try {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), STORAGE_PERMISSION_REQUEST)
            true
        } catch (error: RuntimeException) {
            storageRequest = null
            false
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != STORAGE_PERMISSION_REQUEST) return
        val request = storageRequest ?: return
        storageRequest = null
        val index = permissions.indexOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        host.storagePermissionResult(request, index >= 0 && grantResults.getOrNull(index) == PackageManager.PERMISSION_GRANTED)
        if (KioskState.resumed) KioskState.maintenanceUntil = 0
        immersive()
    }
    override fun onBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value.coerceIn(0.03f, 1f) }
    }
    override fun onError(pluginId: String, message: String) {
        // Failed packages preserve the previous working page. Details are available in maintenance.
        Log.w("ThinkOS", "Plugin error shown in diagnostics: $pluginId")
    }

    private fun nativeDialogTheme() = if (host.store.current.theme == Theme.LIGHT)
        android.R.style.Theme_Material_Light_Dialog_Alert else android.R.style.Theme_Material_Dialog_Alert

    /** The recovery menu is native on purpose: it must work when the settings plugin does not. */
    private fun showMaintenance() {
        miracastModule?.dismiss()
        if (maintenance?.isShowing == true) return
        val state = host.store.current
        val diagnostics = host.plugins.diagnostics().joinToString("\n") { it.toString() }
        val actions = arrayOf(
            getString(R.string.maintenance_diagnostics), getString(R.string.maintenance_restore_plugins),
            getString(R.string.maintenance_reset_settings), getString(R.string.maintenance_android_settings),
            getString(R.string.maintenance_exit_safe_mode),
            getString(R.string.maintenance_licenses),
            getString(R.string.update_check),
            getString(R.string.maintenance_remove_management),
        )
        val theme = nativeDialogTheme()
        maintenance = AlertDialog.Builder(this, theme).setTitle(getString(R.string.maintenance_title))
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> {
                        val diagnosticTheme = nativeDialogTheme()
                        diagnosticDialog = AlertDialog.Builder(this, diagnosticTheme).setTitle(getString(R.string.maintenance_diag_title))
                            .setMessage(getString(R.string.maintenance_diag_body, BuildConfig.VERSION_NAME, state.attention, state.theme, state.deck.page,
                                if (CrashRecovery.isSafeMode(this)) "✓" else "–", diagnostics))
                            .setPositiveButton(getString(R.string.maintenance_back), null).create().also { dialog ->
                                dialog.setOnDismissListener { diagnosticDialog = null; nativeWindowsChanged(); immersive() }
                                dialog.show(); dialog.readableButtons(diagnosticTheme)
                                nativeWindowsChanged()
                            }
                    }
                    1 -> {
                        host.plugins.restoreFactory()
                        android.widget.Toast.makeText(this, getString(R.string.maintenance_restored), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        host.settings.resetAll()
                        android.widget.Toast.makeText(this, getString(R.string.maintenance_settings_reset), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        KioskState.allowSystemSettings()
                        kiosk.release(this)
                        startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                    4 -> {
                        CrashRecovery.clearSafeMode(this)
                        CrashRecovery.schedule(this, 1_000)
                        Process.killProcess(Process.myPid())
                    }
                    5 -> onAbout()
                    6 -> onUpdates()
                    7 -> confirmManagementExit()
                }
            }.setNegativeButton(getString(R.string.maintenance_back), null).create()
        maintenance?.setOnDismissListener { nativeWindowsChanged(); immersive() }
        maintenance?.show()
        maintenance?.readableButtons(theme)
        nativeWindowsChanged()
    }

    /** No Lua, intent, debug command or ordinary click callback can approve
     * this identity change: approval consumes a new native touch release. */
    private fun confirmManagementExit() {
        if (isFinishing || isDestroyed || !KioskState.ownsForegroundTask(taskId)) return
        val theme = nativeDialogTheme()
        val dialog = AlertDialog.Builder(this, theme)
            .setTitle(getString(R.string.maintenance_remove_management))
            .setMessage(getString(R.string.maintenance_remove_management_body))
            .setPositiveButton(getString(R.string.maintenance_remove_management_confirm), null)
            .setNegativeButton(getString(R.string.plugins_cancel), null).create()
        diagnosticDialog = dialog
        var gate: moe.kiwi.thinkos.ui.NativeTouchGate? = null
        dialog.setOnShowListener {
            dialog.readableButtons(theme)
            gate = NativeTouchConfirmation.bind(dialog) {
                if (!KioskState.ownsForegroundTask(taskId) || isFinishing || isDestroyed) return@bind
                dialog.dismiss()
                val outcome = kiosk.surrender(this, host.system.kioskLock, host.system.panelEnabled)
                host.foreignPanel.sync()
                val message = when {
                    !outcome.released -> R.string.maintenance_remove_management_failed
                    !outcome.complete -> R.string.maintenance_remove_management_partial
                    else -> R.string.maintenance_remove_management_done
                }
                val result = AlertDialog.Builder(this, theme)
                    .setTitle(getString(R.string.maintenance_remove_management))
                    .setMessage(getString(message)).setPositiveButton(getString(R.string.maintenance_back), null).create()
                diagnosticDialog = result
                result.setOnDismissListener { if (diagnosticDialog === result) diagnosticDialog = null; nativeWindowsChanged(); immersive() }
                result.show(); result.readableButtons(theme); nativeWindowsChanged()
            }
        }
        dialog.setOnDismissListener { gate?.cancel(); if (diagnosticDialog === dialog) diagnosticDialog = null; nativeWindowsChanged(); immersive() }
        dialog.show(); nativeWindowsChanged()
    }

    private fun nativeWindowsChanged() {
        if (::host.isInitialized) host.updates.nativeWindow(pluginConsent?.dialog?.isShowing == true ||
            listOf(maintenance, diagnosticDialog, legal, updates, miracastModule).any { it?.isShowing == true })
    }

    private companion object { const val STORAGE_PERMISSION_REQUEST = 1027 }

    /** Debug builds only (set in onCreate): the way the debug receiver puts a touch through exactly the path a finger takes. */
    object TouchInjection {
        @Volatile var sink: ((MotionEvent) -> Unit)? = null
        @Volatile var legalState: (() -> String)? = null
        @Volatile var updateState: (() -> String)? = null
        @Volatile var stageState: (() -> String)? = null
    }
}
