package moe.kiwi.thinkos.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.StandardOpenOption

/** App-private, bounded atomic records. Call on UpdateIo workers, except the
 * deliberately synchronous startup-attempt commit before loading the runtime. */
internal class UpdateFiles(private val directory: File, private val lockName: String) {
    class Oversized : IOException("Update record exceeds size bound")
    fun <T> locked(blocking: Boolean = false, block: (File) -> T): T {
        ensureDirectory(directory)
        val root = directory.canonicalFile
        RandomAccessFile(safe(root, lockName), "rw").use { handle ->
            val lock = try { if (blocking) handle.channel.lock() else handle.channel.tryLock() } catch (error: OverlappingFileLockException) {
                throw IOException("Update record busy", error)
            } ?: throw IOException("Update record busy")
            return lock.use { block(root) }
        }
    }

    companion object {
        fun safe(root: File, name: String): File {
            require(name.matches(Regex("[a-zA-Z0-9._-]{1,128}")) && name !in setOf(".", ".."))
            return File(root, name).also {
                if (it.canonicalFile != it.absoluteFile || it.exists() && !it.isFile) throw IOException("Invalid update record path")
            }
        }

        fun read(root: File, name: String, maxBytes: Int): ByteArray? {
            val file = safe(root, name)
            if (!file.exists()) return null
            require(maxBytes in 1..2*1024*1024)
            return file.inputStream().use { input ->
                val bytes = ByteArray(maxBytes + 1)
                var count = 0
                while (count < bytes.size) {
                    val next = input.read(bytes, count, bytes.size - count)
                    if (next < 0) break
                    count += next
                }
                if (count > maxBytes) throw Oversized()
                bytes.copyOf(count)
            }
        }

        fun write(root: File, name: String, bytes: ByteArray, maxBytes: Int) {
            require(bytes.isNotEmpty() && bytes.size <= maxBytes)
            val target = safe(root, name)
            val temporary = File.createTempFile("record-", ".tmp", root)
            try {
                FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
                if (!temporary.renameTo(target)) throw IOException("Cannot commit update record")
                FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } finally { temporary.delete() }
        }

        fun delete(root: File, name: String) {
            val file = safe(root, name)
            if (file.exists()) {
                if (!file.delete()) throw IOException("Cannot remove update record")
                FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }
        }

        private fun ensureDirectory(directory: File) {
            if (Files.isSymbolicLink(directory.toPath())) throw IOException("Invalid update record directory")
            if (directory.isDirectory) return
            if (directory.exists()) throw IOException("Invalid update record directory")
            val parent = directory.absoluteFile.parentFile ?: throw IOException("Missing update record parent")
            ensureDirectory(parent)
            if (!directory.mkdir() && !directory.isDirectory) throw IOException("Cannot create update record directory")
            FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
