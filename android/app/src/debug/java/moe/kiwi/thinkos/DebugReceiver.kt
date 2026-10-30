package moe.kiwi.thinkos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.Theme

/** Test input is shell-protected and exists only in debug APKs. It never writes device settings. */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = (context.applicationContext as ThinkApplication).runtime
        when (intent.getStringExtra("command")) {
            "orientation" -> host.debugOrientation(intent.getIntExtra("value", 10))
            "maintenance" -> host.debugMaintenance()
            "page" -> intent.getStringExtra("value")?.let { host.router.closeSettings(); host.router.open(it) }
            "benchmark_state" -> {
                val s = host.store.current
                resultData = listOf(s.deck.page, s.attention.name, s.theme.name, s.orientation.name,
                    android.os.Process.myPid().toString(), host.sensors.debugTouchCount.toString(), host.router.lastNavigationReason,
                    host.plugins.diagnostics().sumOf { it.bytes }.toString(), host.router.navigationGeneration.toString()).joinToString("|")
            }
            "state" -> Log.i("ThinkOS", "DEBUG state=${host.store.current} plugins=${host.plugins.diagnostics()}")
            "locale" -> {
                // Process-local locale for native UI tests; never changes Android's saved language.
                val locale = intent.getStringExtra("value")?.let(java.util.Locale::forLanguageTag)
                    ?: android.content.res.Resources.getSystem().configuration.locales[0]
                java.util.Locale.setDefault(locale)
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(locale)
                }, context.resources.displayMetrics)
                host.localeChanged()
            }
            "lux" -> host.sensors.debugInjectLux(if (intent.hasExtra("value")) intent.getFloatExtra("value", 20f) else null)
            "presence" -> host.sensors.debugPresence(intent.getBooleanExtra("value", false))
            "hold_attention" -> host.sensors.debugHoldAttention(Attention.entries.firstOrNull { it.name == intent.getStringExtra("value") })
            "theme" -> host.sensors.debugHoldTheme(Theme.entries.firstOrNull { it.name == intent.getStringExtra("value") })
            "attention" -> intent.getStringExtra("value")?.let { name ->
                Attention.entries.firstOrNull { it.name == name }?.let { attention -> host.store.update { it.copy(attention = attention) } }
            }
            "crash" -> Handler(Looper.getMainLooper()).post { error("Intentional debug recovery probe") }
            "kiosk_surrender" -> surrenderDeviceOwner(context)
            "clear_recovery" -> moe.kiwi.thinkos.shell.CrashRecovery.clearSafeMode(context)
        }
        resultCode = 0
    }

    /** Recovery for debug installs, where `dpm remove-active-admin` refuses (the APK is not testOnly). */
    private fun surrenderDeviceOwner(context: Context) {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) { Log.i("ThinkOS", "DEBUG kiosk_surrender: not Device Owner"); return }
        val admin = android.content.ComponentName(context, moe.kiwi.thinkos.admin.ThinkOSAdminReceiver::class.java)
        runCatching {
            dpm.setStatusBarDisabled(admin, false)
            dpm.setKeyguardDisabled(admin, false)
            dpm.setLockTaskPackages(admin, emptyArray())
            dpm.clearDeviceOwnerApp(context.packageName)
        }.onFailure { Log.e("ThinkOS", "DEBUG kiosk_surrender failed", it) }
        Log.i("ThinkOS", "DEBUG kiosk_surrender done; owner=${dpm.isDeviceOwnerApp(context.packageName)}")
    }
}
