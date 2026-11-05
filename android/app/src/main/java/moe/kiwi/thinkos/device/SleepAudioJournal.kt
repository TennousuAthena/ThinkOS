package moe.kiwi.thinkos.device

import android.util.AtomicFile
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

/** OS recovery state, never a plugin setting. AtomicFile also recovers an interrupted replacement. */
internal class SleepAudioJournal(file: File) : SleepAudioGate.Persistence {
    private val atomic = AtomicFile(file)
    override fun read(): Map<Int, Int>? {
        val text = try {
            atomic.openRead().use { input ->
                val bytes = ByteArray(257)
                var count = 0
                while (count < bytes.size) {
                    val read = input.read(bytes, count, bytes.size - count)
                    if (read < 0) break
                    count += read
                }
                if (count > 256) throw IOException("oversized sleep audio journal")
                String(bytes, 0, count, Charsets.US_ASCII)
            }
        } catch (error: FileNotFoundException) {
            if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) throw error
            return null
        }
        return decode(text)
    }

    override fun write(values: Map<Int, Int>) {
        val file = atomic.baseFile
        if (!file.parentFile.isDirectory && !file.parentFile.mkdirs()) throw IOException("sleep audio state directory unavailable")
        val output = atomic.startWrite()
        try {
            output.write(encode(values).toByteArray(Charsets.US_ASCII))
            atomic.finishWrite(output)
        } catch (error: Exception) { atomic.failWrite(output); throw error }
    }

    override fun clear() {
        atomic.delete()
        if (atomic.baseFile.exists() || File(atomic.baseFile.path + ".bak").exists()) throw IOException("sleep audio journal deletion failed")
    }

    companion object {
        private const val HEADER = "thinkos-sleep-audio 1"
        internal fun encode(values: Map<Int, Int>): String {
            require(values.keys == (1..5).toSet() && values.values.all { it in 0..65535 })
            return HEADER + "\n" + (1..5).joinToString("") { "$it=${values.getValue(it)}\n" }
        }
        internal fun decode(text: String): Map<Int, Int> {
            require(text.length <= 256)
            val lines = text.split('\n')
            require(lines.size == 7 && lines[0] == HEADER && lines[6].isEmpty()) { "invalid sleep audio journal" }
            return (1..5).associateWith { stream ->
                val value = lines[stream]
                require(value.matches(Regex("$stream=(0|[1-9][0-9]{0,4})"))) { "invalid sleep audio stream" }
                value.substringAfter('=').toInt().also { require(it <= 65535) }
            }
        }
    }
}
