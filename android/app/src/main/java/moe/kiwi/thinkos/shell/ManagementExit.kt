package moe.kiwi.thinkos.shell

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.system.Os
import android.util.AtomicFile
import android.util.Log
import moe.kiwi.thinkos.service.KioskService
import java.io.File
import java.io.FileNotFoundException

/** Fixed private OS journal, never a setting, preference or plugin capability.
 * Clearing app data removes this identity state with the rest of the app data. */
internal object ManagementExit {
    private const val PREFIX = "thinkos-management-exit 1\n"
    private fun file(context: Context) = AtomicFile(File(context.filesDir, "management-exit"))
    @Synchronized private fun read(context: Context): ManagementExitTransaction.Record? {
        val raw = try { file(context).openRead().use { input ->
            val buffer = ByteArray(128)
            var count = 0
            while (count < buffer.size) {
                val read = input.read(buffer, count, buffer.size - count)
                if (read < 0) break
                if (read == 0) throw IllegalStateException("Unfinished management exit journal read")
                count += read
            }
            check(count < buffer.size) { "Oversized management exit journal" }
            buffer.copyOf(count)
        } }
                  catch (_: FileNotFoundException) { return null }
        return when (raw.toString(Charsets.US_ASCII)) {
            PREFIX + "pending\n" -> ManagementExitTransaction.Record.PENDING
            PREFIX + "released\n" -> ManagementExitTransaction.Record.RELEASED
            else -> throw IllegalStateException("Invalid management exit journal")
        }
    }
    @Synchronized private fun write(context: Context, state: String) {
        val journal = file(context)
        val stream = journal.startWrite()
        try {
            Os.fchmod(stream.fd, 0x180) // owner read/write only (0600)
            stream.write((PREFIX + state + "\n").toByteArray(Charsets.US_ASCII))
            journal.finishWrite(stream)
        } catch (error: Exception) { journal.failWrite(stream); throw error }
    }
    fun prepare(context: Context) = write(context, "pending")
    fun commit(context: Context) = write(context, "released")
    @Synchronized fun abort(context: Context) = file(context).delete()
    fun isReleased(context: Context): Boolean = try {
        val record = read(context)
        record != null && ManagementExitTransaction.suppressRecovery(record,
            context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName))
    } catch (error: Exception) {
        Log.e("ThinkOS", "Unable to inspect management exit", error)
        false
    }
    fun finish(context: Context) {
        KioskState.maintenanceUntil = Long.MAX_VALUE
        KioskState.enterForeign(0)
        CrashRecovery.cancel(context)
        context.stopService(Intent(context, KioskService::class.java))
    }
}
