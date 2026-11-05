package moe.kiwi.thinkos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Process
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.nio.file.Files

/** Debug-only PackageInstaller receipt for OTA-0. It never starts an Activity,
 * installs a package, trusts a key, or changes production update state. */
class InstallProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val token = intent.getStringExtra("run_id")?.takeIf { it.matches(Regex("[a-f0-9]{32}")) } ?: return
        android.util.Log.i("ThinkOS", "SELF_UPDATE_RECEIPT status=${intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)} session=${intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)} uid=${Process.myUid()} pid=${Process.myPid()}")
        val pending = goAsync()
        Thread({
            try {
                val directory = File(context.cacheDir, "update-device-$token/install")
                check(directory.isDirectory && !Files.isSymbolicLink(directory.toPath()) &&
                    directory.canonicalFile == File(context.cacheDir.canonicalFile, "update-device-$token/install")) { "Invalid probe directory" }
                val file = File(directory, "request.json")
                check(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() in 1..16384) { "Missing probe request" }
                val request = JSONObject(file.readText())
                val session = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
                check(session >= 0 && session == request.getInt("session_id")) { "Unexpected probe session" }
                val code = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, Int.MIN_VALUE)
                val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                val result = JSONObject().put("session_id", session).put("status", code)
                    .put("message", intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.take(512))
                    .put("package", intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))
                    .put("uid", Process.myUid()).put("pid", Process.myPid())
                    .put("installed_code", installed.versionCode).put("installed_version", installed.versionName)
                    .put("requires_user_action", code == PackageInstaller.STATUS_PENDING_USER_ACTION)
                val atomic = AtomicFile(File(directory, "result.json"))
                val output = atomic.startWrite()
                try { output.write(result.toString().toByteArray()); atomic.finishWrite(output) }
                catch (error: Exception) { atomic.failWrite(output); throw error }
            } catch (error: Exception) {
                android.util.Log.w("ThinkOS", "Unable to record the debug install receipt", error)
            } finally { pending.finish() }
        }, "ThinkOS-InstallReceipt").apply { priority = Thread.MIN_PRIORITY; start() }
    }
    companion object { const val ACTION = "moe.kiwi.thinkos.SELF_UPDATE_PROBE_RESULT" }
}
