package moe.kiwi.thinkos.shell

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

object CrashRecovery {
    private val handling = AtomicBoolean(false)
    private fun prefs(context: Context) = context.getSharedPreferences("recovery", Context.MODE_PRIVATE)
    fun isSafeMode(context: Context): Boolean = prefs(context).getBoolean("safe_mode", false)

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (!handling.compareAndSet(false, true)) {
                previous?.uncaughtException(thread, error)
            } else {
                // Disk-full / logging failures must not prevent the restart itself.
                runCatching { schedule(context, 1_000) }
                    .onFailure { Log.e("ThinkOS", "Unable to schedule crash restart", it) }
                try {
                    val p = prefs(context)
                    val now = SystemClock.elapsedRealtime()
                    val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
                    val history = if (boot >= 0 && p.getInt("boot_count", -2) == boot) p.getString("crashes", "").orEmpty() else ""
                    val recent = history.split(',')
                        .mapNotNull { it.toLongOrNull() }.filter { it <= now && now - it < 3_600_000 }.toMutableList()
                    recent += now
                    p.edit().putInt("boot_count", boot).putString("crashes", recent.takeLast(5).joinToString(","))
                        .putBoolean("safe_mode", isSafeMode(context) || recent.size >= 5).commit()
                    val directory = File(context.filesDir, "crashes").apply { mkdirs() }
                    directory.listFiles()?.sortedByDescending { it.lastModified() }?.drop(4)?.forEach { it.delete() }
                    File(directory, "crash-$now.log").writeText(
                        "thread=${thread.name} elapsed=$now\n" + Log.getStackTraceString(error).take(32_768))
                } catch (failure: Throwable) {
                    Log.e("ThinkOS", "Unable to persist crash recovery", failure)
                }
                Log.e("ThinkOS", "Uncaught exception; restarting", error)
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    fun clearSafeMode(context: Context) {
        prefs(context).edit().remove("crashes").putBoolean("safe_mode", false).commit()
    }

    fun schedule(context: Context, delayMillis: Long = 30_000) {
        // A sticky-service restart must not postpone an already pending crash restart.
        val requestCode = if (delayMillis < 30_000) 42 else 41
        val pending = PendingIntent.getBroadcast(context, requestCode,
            Intent(context, RecoveryReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.setExact(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + delayMillis, pending)
    }
}
