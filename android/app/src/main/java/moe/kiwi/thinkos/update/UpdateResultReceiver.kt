package moe.kiwi.thinkos.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import kotlinx.coroutines.*

/** Dedicated auxiliary process: receiving a result must not consume a startup
 * trial, initialize Lua/media, clear safe mode, or certify package health. */
class UpdateResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != UpdateInstaller.ACTION) return
        val uri = intent.data ?: return
        val path = uri.pathSegments
        if (uri.scheme != "thinkos-update" || uri.host != "receipt" || path.size != 2 || !path[0].matches(Regex("[a-f0-9]{32}"))) return
        val id = path[1].toIntOrNull()?.takeIf { it >= 0 } ?: return
        if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
        val token = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = UpdateInstallContext.store(context)
                val installer = UpdateInstaller(context, store) { UpdateInstallContext.now(context) }
                if (!installer.receipt(path[0], id, status)) return@launch
                if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    val pending = store.snapshot().pending ?: return@launch
                    @Suppress("DEPRECATION")
                    val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    val component = confirmation?.resolveActivity(context.packageManager)
                    val app = component?.let { context.packageManager.getApplicationInfo(it.packageName, 0) }
                    if (pending.phase != "INSTALLING" || pending.mode != "manual" || confirmation == null || app == null ||
                        app.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) {
                        installer.rejectConfirmation(pending, id)
                    } else {
                        confirmation.component = component
                        context.startActivity(Intent(context, UpdateConfirmationActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("transaction", path[0]).putExtra("session", id)
                            .putExtra("confirmation", confirmation))
                    }
                }
            } catch (error: Exception) { android.util.Log.e("ThinkOS", "Update result could not be recorded", error) }
            finally { token.finish() }
        }
    }
}
