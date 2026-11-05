package moe.kiwi.thinkos.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption

/** Private monotonic ledger. Blocking operations belong on UpdateIo, never the UI thread.
 * This is not the update/installation state machine. A bad ledger never resets its floor. */
internal class UpdateSequenceStore(private val directory: File) {
    class Rollback : IllegalArgumentException("Update sequence rollback or equivocation")

    fun accept(catalog: UpdateCatalog) = locked { root ->
        val state = read(root)
        val channel = catalog.document.channel
        val previous = if (channel == "stable") state.stable else state.beta
        val next = UpdateSequence(catalog.document.seq, catalog.digest)
        if (previous != null) {
            if (next.seq < previous.seq || next.seq == previous.seq && next.digest != previous.digest) throw Rollback()
            if (next == previous) return@locked
        }
        val updated = if (channel == "stable") state.copy(stable = next) else state.copy(beta = next)
        val bytes = UpdateModels.writeUpdateSequenceDocument(updated).toString().toByteArray(Charsets.UTF_8)
        val file = safe(root, "sequences.json")
        val temporary = File.createTempFile("sequences-", ".tmp", root)
        try {
            FileOutputStream(temporary).use { it.write(bytes); it.fd.sync() }
            if (!temporary.renameTo(file)) throw IOException("Cannot commit update sequence")
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { temporary.delete() }
    }

    fun snapshot(): UpdateSequenceDocument = locked(::read)

    /** A cache/state read must never advance a floor or revive an older catalog. */
    fun requireCurrent(catalog: UpdateCatalog) = locked { root ->
        val state = read(root)
        val floor = if (catalog.document.channel == "stable") state.stable else state.beta
        if (floor == null || floor.seq != catalog.document.seq || floor.digest != catalog.digest) throw Rollback()
    }

    private fun read(root: File): UpdateSequenceDocument {
        val file = safe(root, "sequences.json")
        if (!file.exists()) return UpdateSequenceDocument(1)
        try {
            return file.inputStream().use { input ->
                val bytes = ByteArray(MAX_BYTES + 1)
                var count = 0
                while (count < bytes.size) {
                    val next = input.read(bytes, count, bytes.size - count)
                    if (next < 0) break
                    count += next
                }
                UpdateModels.readUpdateSequenceDocument(UpdateJson.read(bytes.copyOf(count), MAX_BYTES))
            }
        } catch (error: Exception) { throw IOException("Corrupt update sequence ledger", error) }
    }

    private fun <T> locked(block: (File) -> T): T {
        ensureDirectory(directory)
        val root = directory.canonicalFile
        RandomAccessFile(safe(root, "sequences.lock"), "rw").use { handle ->
            val lock = try { handle.channel.tryLock() } catch (error: OverlappingFileLockException) {
                throw IOException("Update ledger busy", error)
            } ?: throw IOException("Update ledger busy")
            return lock.use { block(root) }
        }
    }

    private fun safe(root: File, name: String): File = File(root, name).also {
        if (it.canonicalFile != it.absoluteFile || it.exists() && !it.isFile) throw IOException("Invalid update ledger path")
    }

    private fun ensureDirectory(file: File) {
        if (file.isDirectory) return
        if (file.exists()) throw IOException("Invalid update ledger directory")
        val parent = file.absoluteFile.parentFile ?: throw IOException("Missing update ledger parent")
        ensureDirectory(parent)
        if (!file.mkdir() && !file.isDirectory) throw IOException("Cannot create update ledger directory")
        FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    private companion object { const val MAX_BYTES = 16 * 1024 }
}
