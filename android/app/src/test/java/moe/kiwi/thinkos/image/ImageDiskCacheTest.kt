package moe.kiwi.thinkos.image

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ImageDiskCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun write(file: File, size: Int) = file.writeBytes(ByteArray(size) { 7 })

    @Test fun failedAndOversizedWritesNeverPublishPartialEntries() {
        val root = temporary.newFolder()
        File(root, "interrupted.part").writeText("stale")
        val cache = ImageDiskCache(root, 20, 10)
        assertNull(cache.cached("a"))
        assertFalse(File(root, "interrupted.part").exists())
        assertThrows(IllegalStateException::class.java) {
            cache.acquire("a", { write(it, 5); error("connection lost") }, {})
        }
        assertThrows(IllegalArgumentException::class.java) { cache.acquire("a", { write(it, 11) }, {}) }
        assertThrows(IllegalArgumentException::class.java) { cache.acquire("a", { write(it, 5) }, { require(false) }) }
        assertNull(cache.cached("a"))
        assertTrue(root.listFiles().orEmpty().isEmpty())
        cache.acquire("a", { write(it, 5) }, {}).use { assertEquals(5, it.file.length()) }
        cache.cached("a").use { assertNotNull(it) }
    }

    @Test fun lruEvictsOnlyUnpinnedFilesAndEnforcesByteAndEntryLimits() {
        val root = temporary.newFolder()
        val cache = ImageDiskCache(root, 10, 8, 2)
        val pinned = cache.acquire("a", { write(it, 6) }, {})
        assertThrows(IllegalArgumentException::class.java) { cache.acquire("b", { write(it, 6) }, {}) }
        assertNotNull(cache.cached("a").also { it?.close() })
        pinned.close(); pinned.close()
        cache.acquire("b", { write(it, 6) }, {}).close()
        assertNull(cache.cached("a"))
        cache.acquire("c", { write(it, 3) }, {}).close()
        File(root, ImageDiskCache.key("b")).setLastModified(1)
        File(root, ImageDiskCache.key("c")).setLastModified(2)
        cache.acquire("d", { write(it, 1) }, {}).close()
        assertNull(cache.cached("b"))
        assertEquals(2, root.listFiles()!!.size)
        assertTrue(root.listFiles()!!.sumOf { it.length() } <= 10)
    }

    @Test fun simultaneousRequestsShareOneDownloadWithoutBlockingOtherCacheHits() {
        val root = temporary.newFolder()
        val cache = ImageDiskCache(root)
        cache.acquire("ready", { write(it, 1) }, {}).close()
        val started = CountDownLatch(1); val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)
        try {
            fun request() = pool.submit<ImageDiskCache.Lease> {
                cache.acquire("remote", { calls.incrementAndGet(); started.countDown(); check(release.await(3, TimeUnit.SECONDS)); write(it, 4) }, {})
            }
            val first = request(); assertTrue(started.await(3, TimeUnit.SECONDS))
            val second = request()
            cache.cached("ready").use { assertNotNull(it) }
            release.countDown()
            first.get(3, TimeUnit.SECONDS).use { a -> second.get(3, TimeUnit.SECONDS).use { b -> assertEquals(a.file, b.file) } }
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    @Test fun cacheDoesNotReadThroughSymlinks() {
        val root = temporary.newFolder(); val external = temporary.newFile()
        external.writeText("private")
        Files.createSymbolicLink(File(root, ImageDiskCache.key("url")).toPath(), external.toPath())
        val cache = ImageDiskCache(root)
        assertNull(cache.cached("url"))
        cache.acquire("url", { write(it, 2) }, {}).use { assertEquals(2, it.file.length()) }
        assertEquals("private", external.readText())
    }
}
