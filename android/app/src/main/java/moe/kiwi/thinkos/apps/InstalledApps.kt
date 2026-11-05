package moe.kiwi.thinkos.apps

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/** Launcher entries only. Called on PluginIO; no package enumeration runs on the UI or Lua thread. */
internal class InstalledApps(context: Context, private val changed: () -> Unit) {
    private val app = context.applicationContext
    private var listening = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed()
    }

    fun list(): List<Pair<String, String>> {
        val manager = app.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return manager.queryIntentActivities(intent, 0).asSequence().filter {
            it.activityInfo?.let { activity -> activity.enabled && activity.exported && activity.applicationInfo.enabled } == true
        }.map { it.activityInfo.packageName to it.activityInfo.applicationInfo.loadLabel(manager).toString() }.toList()
    }

    fun start() {
        if (listening) return
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED); addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        listening = true
    }

    fun close() { if (listening) { app.unregisterReceiver(receiver); listening = false } }
}
