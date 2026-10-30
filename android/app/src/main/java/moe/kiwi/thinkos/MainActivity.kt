package moe.kiwi.thinkos

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
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
import moe.kiwi.thinkos.ui.readableButtons
import org.json.JSONObject

class MainActivity : Activity(), RuntimeHost.Observer {
    private lateinit var host: RuntimeHost
    private lateinit var stage: ThinkStage
    private var maintenance: AlertDialog? = null
    private lateinit var kiosk: KioskPolicy
    private var reportedDrawn = false
    private var lastLocale: java.util.Locale = java.util.Locale.getDefault()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        immersive()
        host = (application as ThinkApplication).runtime
        kiosk = KioskPolicy(this)
        host.store.update { it.copy(safeMode = CrashRecovery.isSafeMode(this)) }
        stage = ThinkStage(this, host.store, host.router, { host.sensors.touch() }, { showMaintenance() }, host.pageEvents, host::openSettings)
        setContentView(stage)
        host.observe(this)
        host.start()
        startForegroundService(Intent(this, KioskService::class.java))
        Log.i("ThinkOS", "Activity created; orientation=${resources.configuration.orientation}")
    }

    override fun onResume() {
        super.onResume()
        KioskState.resumed = true
        KioskState.maintenanceUntil = 0
        immersive()
        kiosk.apply(this, host.system.kioskLock)   // coming back from Android's settings locks again
        stage.start()
    }
    override fun onPause() {
        KioskState.resumed = false
        stage.stop()
        super.onPause()
    }
    override fun onDestroy() {
        host.removeObserver(this)
        maintenance?.dismiss()
        super.onDestroy()
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        stage.requestLayout()
        if (newConfig.locales[0] != lastLocale) { lastLocale = newConfig.locales[0]; host.localeChanged() }
        Log.i("ThinkOS", "Configuration changed without Activity recreation: ${newConfig.orientation}")
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // The Home key always means "take me home": it also leaves the settings page.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) host.router.closeSettings()
        if (BuildConfig.DEBUG && intent.hasExtra("debug.orientation")) {
            requestedOrientation = intent.getIntExtra("debug.orientation", android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR)
        }
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersive()
    }
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) host.sensors.touch()
        return super.dispatchTouchEvent(event)
    }
    @Deprecated("Kiosk keeps the device on its home page")
    override fun onBackPressed() { /* Maintenance has an explicit return button. */ }

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
    override fun onKioskLock(locked: Boolean) { kiosk.apply(this, locked) }
    override fun onSettingsUnavailable() { showMaintenance() }
    override fun onLocaleChanged() { stage.localeChanged() }
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
        if (maintenance?.isShowing == true) return
        val state = host.store.current
        val diagnostics = host.plugins.diagnostics().joinToString("\n") { it.toString() }
        val actions = arrayOf(
            getString(R.string.maintenance_diagnostics), getString(R.string.maintenance_restore_plugins),
            getString(R.string.maintenance_reset_settings), getString(R.string.maintenance_android_settings),
            getString(R.string.maintenance_exit_safe_mode),
        )
        val theme = nativeDialogTheme()
        maintenance = AlertDialog.Builder(this, theme).setTitle(getString(R.string.maintenance_title))
            .setItems(actions) { _, index ->
                when (index) {
                    0 -> {
                        val diagnosticTheme = nativeDialogTheme()
                        AlertDialog.Builder(this, diagnosticTheme).setTitle(getString(R.string.maintenance_diag_title))
                            .setMessage(getString(R.string.maintenance_diag_body, BuildConfig.VERSION_NAME, state.attention, state.theme, state.deck.page,
                                if (CrashRecovery.isSafeMode(this)) "✓" else "–", diagnostics))
                            .setPositiveButton(getString(R.string.maintenance_back), null).show().readableButtons(diagnosticTheme)
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
                }
            }.setNegativeButton(getString(R.string.maintenance_back), null).create()
        maintenance?.setOnDismissListener { immersive() }
        maintenance?.show()
        maintenance?.readableButtons(theme)
    }
}
