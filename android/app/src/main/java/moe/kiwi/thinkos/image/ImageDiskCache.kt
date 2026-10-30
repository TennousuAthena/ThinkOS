package moe.kiwi.thinkos.image

import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/** Bounded, atomic content-URL cache. All filesystem methods run on background workers. */
internal class ImageDiskCache(private val root: File, private val maxBytes: Long = 50L * 1024 * 1024,
                              private val maxFileBytes: Long = 8L * 1024 * 1024,
                              private val maxEntries: Int = 512) {
    private val monitor = Any()
    private var initialized = false
    private val pins = mutableMapOf<String, Int>()
    private class KeyLock { val lock = ReentrantLock(); var users = 0 }
    private val locks = mutableMapOf<String, KeyLock>()

    inner class Lease internal constructor(val file: File, private val key: String) : Closeable {
        private val closed = AtomicBoolean()
        override fun close() {
            if (closed.compareAndSet(false, true)) synchronized(monitor) {
                val count = (pins[key] ?: 1) - 1
                if (count <= 0) pins.remove(key) else pins[key] = count
            }
        }
    }

    private fun initialize() {
        if (initialized) return
        check(root.isDirectory || root.mkdirs()) { "Image cache is unavailable" }
        root.listFiles().orEmpty().filter { it.name.endsWith(".part") }.forEach { it.delete() }
        initialized = true
    }
    private fun pin(key: String, file: File): Lease {
        pins[key] = (pins[key] ?: 0) + 1
        file.setLastModified(System.currentTimeMillis())
        return Lease(file, key)
    }
    fun cached(url: String): Lease? = synchronized(monitor) {
        initialize()
        val key = key(url); val file = File(root, key)
        if (Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS) && file.length() in 1..maxFileBytes) pin(key, file) else null
    }

    /** Per-key coalescing never holds the cache monitor during a network operation. */
    fun acquire(url: String, write: (File) -> Unit, validate: (File) -> Unit): Lease {
        val key = key(url)
        val slot = synchronized(monitor) { locks.getOrPut(key) { KeyLock() }.also { it.users++ } }
        try {
            slot.lock.lockInterruptibly()
            try {
                cached(url)?.let { return it }
                val temporary = File.createTempFile(key.take(12), ".part", root)
                try {
                    write(temporary)
                    check(!Thread.currentThread().isInterrupted) { "Image request cancelled" }
                    require(temporary.length() in 1..maxFileBytes) { "Image file exceeds budget or is empty" }
                    validate(temporary)
                    return synchronized(monitor) {
                        val files = root.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{64}")) }
                        var total = files.sumOf { it.length() }; var count = files.size
                        for (file in files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })) {
                            if (total + temporary.length() <= maxBytes && count < maxEntries) break
                            if ((pins[file.name] ?: 0) > 0) continue
                            val bytes = file.length()
                            if (file.delete()) { total -= bytes; count-- }
                        }
                        require(total + temporary.length() <= maxBytes && count < maxEntries) { "Image cache is full with active images" }
                        val destination = File(root, key)
                        check(temporary.renameTo(destination)) { "Cannot commit image cache entry" }
                        pin(key, destination)
                    }
                } finally { temporary.delete() }
            } finally { slot.lock.unlock() }
        } finally { synchronized(monitor) { if (--slot.users == 0) locks.remove(key) } }
    }
    fun discard(url: String) = synchronized(monitor) { File(root, key(url)).delete(); Unit }

    companion object {
        fun key(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
