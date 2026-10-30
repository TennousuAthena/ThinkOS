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
            "state" -> Log.i("ThinkOS", "DEBUG state=${host.store.current} plugins=${host.plugins.diagnostics()}")
            "lux" -> host.sensors.debugInjectLux(if (intent.hasExtra("value")) intent.getFloatExtra("value", 20f) else null)
            "presence" -> host.sensors.debugPresence(intent.getBooleanExtra("value", false))
            "hold_attention" -> host.sensors.debugHoldAttention(Attention.entries.firstOrNull { it.name == intent.getStringExtra("value") })
            "theme" -> intent.getStringExtra("value")?.let { name ->
                Theme.entries.firstOrNull { it.name == name }?.let { theme -> host.store.update { it.copy(theme = theme) } }
            }
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
