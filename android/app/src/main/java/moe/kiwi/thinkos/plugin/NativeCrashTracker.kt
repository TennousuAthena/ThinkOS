package moe.kiwi.thinkos.plugin

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/** Shared mapping survives process death. No fsync or disk serialization on an event hot path. */
internal class NativeCrashTracker(context: Context) {
    private val recordsFile = File(context.filesDir, "native-failures.json")
    private val file = RandomAccessFile(File(context.filesDir, "native-flight.bin"), "rw")
    private val marker: MappedByteBuffer
    private val failures = runCatching {
        AtomicFile(recordsFile).openRead().bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
    }.getOrDefault(JSONObject())
    private val history = NativeExitHistory(failures,
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1),
        SystemClock::elapsedRealtime, System::currentTimeMillis)
    var recoveredPlugin: String? = null
        private set

    init {
        file.setLength(512)
        marker = file.channel.map(FileChannel.MapMode.READ_WRITE, 0, 512)
        if (marker.getInt(0) == MAGIC) {
            val length = marker.getInt(4)
            if (length in 1..240) {
                val bytes = ByteArray(length)
                marker.position(8)
                marker.get(bytes)
                val key = String(bytes, Charsets.UTF_8)
                recoveredPlugin = key.substringBefore('@')
                history.record(key)
                persist()
            }
        }
        clear()
        if (failures.length() > 0) persist()
    }
    fun blocked(id: String, digest: String): Boolean = history.blocked("$id@$digest")
    fun enter(id: String, digest: String) {
        val bytes = "$id@$digest".toByteArray(Charsets.UTF_8)
        marker.putInt(0, 0)
        marker.putInt(4, bytes.size)
        marker.position(8)
        marker.put(bytes)
        marker.putInt(0, MAGIC)
    }
    fun clear() { marker.putInt(0, 0) }
    fun reset() { failures.keys().asSequence().toList().forEach(failures::remove); persist(); clear() }
    fun close() { clear(); file.close() }
    private fun persist() {
        val atomic = AtomicFile(recordsFile)
        val output = atomic.startWrite()
        try { output.write(failures.toString().toByteArray()); atomic.finishWrite(output) }
        catch (error: Exception) { atomic.failWrite(output); throw error }
    }
    companion object { private const val MAGIC = 0x544F534E }
}
