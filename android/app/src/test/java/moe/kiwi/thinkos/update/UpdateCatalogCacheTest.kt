package moe.kiwi.thinkos.update

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class UpdateCatalogCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()
    private val keys = fixture.keys()
    private fun fetcher() = Fetcher("0.1.1", UpdateHttpClient.Timeouts(1000, 1000))

    @Test fun rawSignedBytesSurviveAnAtomicReopenWithoutReencoding() {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder())
        val raw = byteArrayOf(32, 10) + UpdateFixture.bytes(fixture.catalog(Long.MAX_VALUE))
        val signature = fixture.sign(raw)
        val catalog = UpdateCatalog.read(raw, signature, keys, "stable")
        ledger.accept(catalog)
        UpdateCatalogCache(folder, keys).write(catalog, "https://mirror.test/stable.json", "\"one\"", ledger)
        val entry = UpdateCatalogCache(folder, keys).read("stable")!!
        assertArrayEquals(raw, entry.catalog.bytes()); assertArrayEquals(signature, entry.catalog.signature())
        assertEquals(Long.MAX_VALUE, entry.catalog.document.seq); assertEquals("\"one\"", entry.etag)
        assertFalse(folder.listFiles()!!.any { it.name.endsWith(".tmp") })
    }

    @Test fun cacheCorruptionTamperingDifferentKeysAndNoncanonicalEncodingAreNotAuthority() {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val catalog = fixture.verified()
        ledger.accept(catalog)
        val cache = UpdateCatalogCache(folder, keys)
        cache.write(catalog, "https://mirror.test/stable.json", "one", ledger)
        val file = File(folder, "catalog-stable.json"); val original = file.readText()
        val mutations = listOf("", "{}", "x".repeat(2*1024*1024+1),
            JSONObject(original).put("body", java.util.Base64.getEncoder().encodeToString("bad".toByteArray())).toString(),
            JSONObject(original).put("signature", "AAAA").toString(), JSONObject(original).put("channel", "beta").toString(),
            JSONObject(original).put("source", "https://user:secret@mirror.test/a").toString(),
            JSONObject(original).put("signature", JSONObject(original).getString("signature").let {
                if (it.endsWith('=')) it.trimEnd('=') else it + "="
            }).toString())
        for (mutation in mutations) {
            file.writeText(mutation)
            assertThrows(UpdateCatalogCache.Invalid::class.java) { cache.read("stable") }
            assertEquals(1L, ledger.snapshot().stable!!.seq)
        }
        file.writeText(original)
        assertThrows(UpdateCatalogCache.Invalid::class.java) { UpdateCatalogCache(folder, UpdateFixture().keys()).read("stable") }
    }

    @Test fun cacheCannotCreateOrLowerTheIndependentFloorAndChannelsStaySeparate() = runBlocking<Unit> {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val catalog = fixture.verified()
        val cache = UpdateCatalogCache(folder, keys)
        assertThrows(UpdateSequenceStore.Rollback::class.java) { cache.write(catalog, "https://m.test/a", null, ledger) }
        assertNull(ledger.snapshot().stable)
        ledger.accept(catalog); cache.write(catalog, "https://m.test/a", null, ledger)
        ledger.accept(fixture.verified(fixture.catalog(2)))
        fetcher().use { transport ->
            val client = UpdateCatalogClient(transport, keys, ledger, cache)
            assertNull(client.cached("stable")); assertNull(client.cached("beta"))
        }
        assertEquals(2L, ledger.snapshot().stable!!.seq)
        assertThrows(UpdateSequenceStore.Rollback::class.java) { cache.write(catalog, "https://m.test/a", null, ledger) }
        assertEquals(1L, cache.read("stable")!!.catalog.document.seq)
    }

    @Test fun atomicCacheLockAndSymlinkGuardsDoNotOverwriteUnrelatedFiles() {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val catalog = fixture.verified()
        ledger.accept(catalog)
        RandomAccessFile(File(folder, "catalog-cache.lock"), "rw").use { handle -> handle.channel.lock().use {
            assertThrows(IOException::class.java) { UpdateCatalogCache(folder, keys).write(catalog, "https://m.test/a", null, ledger) }
        } }
        val outside = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(File(folder, "catalog-stable.json").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { UpdateCatalogCache(folder, keys).write(catalog, "https://m.test/a", null, ledger) }
        assertEquals("keep", outside.readText())
    }

    @Test(timeout = 10_000) fun aFreshClientUsesEtagOnlyWithTheAuthenticatedMatchingSourceAnd304NeedsNoNewSignature() = runBlocking<Unit> {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val bytes = UpdateFixture.bytes(fixture.catalog())
        val signature = fixture.sign(bytes)
        UpdateTestOrigin { exchange ->
            if (exchange.requestURI.path.endsWith(".sig")) UpdateTestOrigin.reply(exchange, signature)
            else if (exchange.requestHeaders.getFirst("If-None-Match") == "\"one\"") UpdateTestOrigin.reply(exchange, byteArrayOf(), 304)
            else { exchange.responseHeaders.add("ETag", "\"one\""); UpdateTestOrigin.reply(exchange, bytes) }
        }.use { origin -> fetcher().use { transport ->
            val url = origin.url("/stable.json")
            val first = UpdateCatalogClient(transport, keys, ledger, UpdateCatalogCache(folder, keys)).checkDetailed(listOf(url), "stable")
            assertEquals(UpdateCatalogClient.Origin.NETWORK, first.origin)
            val second = UpdateCatalogClient(transport, keys, ledger, UpdateCatalogCache(folder, keys)).checkDetailed(listOf(url), "stable")
            assertEquals(UpdateCatalogClient.Origin.NOT_MODIFIED, second.origin)
            assertArrayEquals(first.catalog.bytes(), second.catalog.bytes())
            assertEquals(3, origin.hits.size)
            assertNull(origin.hits[0].headers["if-none-match"]); assertNull(origin.hits[1].headers["if-none-match"])
            assertEquals(listOf("\"one\""), origin.hits[2].headers["if-none-match"])
        } }
    }

    @Test(timeout = 10_000) fun anotherMirrorNeverReceivesTheFirstMirrorsConditionalHeader() = runBlocking<Unit> {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val cache = UpdateCatalogCache(folder, keys)
        val bytes = UpdateFixture.bytes(fixture.catalog()); val signature = fixture.sign(bytes)
        UpdateTestOrigin { UpdateTestOrigin.reply(it, byteArrayOf(), 503) }.use { first ->
            val catalog = fixture.verified(); ledger.accept(catalog); cache.write(catalog, first.url("/stable.json"), "first", ledger)
            UpdateTestOrigin { UpdateTestOrigin.reply(it, if (it.requestURI.path.endsWith(".sig")) signature else bytes) }.use { second ->
                fetcher().use { transport ->
                    val checked = UpdateCatalogClient(transport, keys, ledger, cache).checkDetailed(listOf(first.url("/stable.json"), second.url("/stable.json")), "stable")
                    assertEquals(UpdateCatalogClient.Origin.NETWORK, checked.origin)
                    assertTrue(second.hits.all { "if-none-match" !in it.headers })
                }
            }
        }
    }

    @Test(timeout = 10_000) fun unavailableOrBadSignedSourcesRetainAnAuthenticatedOfflineCatalogAndItsEtag() = runBlocking<Unit> {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val cache = UpdateCatalogCache(folder, keys)
        val catalog = fixture.verified(); ledger.accept(catalog)
        UpdateTestOrigin { exchange ->
            UpdateTestOrigin.reply(exchange, if (exchange.requestURI.path.endsWith(".sig")) ByteArray(71) else UpdateFixture.bytes(fixture.catalog(999)))
        }.use { source -> fetcher().use { transport ->
            cache.write(catalog, source.url("/stable.json"), "old", ledger)
            val old = File(folder, "catalog-stable.json").readBytes()
            val checked = UpdateCatalogClient(transport, keys, ledger, cache).checkDetailed(listOf(source.url("/stable.json")), "stable")
            assertEquals(UpdateCatalogClient.Origin.OFFLINE_CACHE, checked.origin); assertNotNull(checked.networkError)
            assertEquals(1L, checked.catalog.document.seq); assertEquals(1L, ledger.snapshot().stable!!.seq)
            assertArrayEquals(old, File(folder, "catalog-stable.json").readBytes())
        } }
    }

    @Test(timeout = 10_000) fun corruptCacheCanBeReplacedByFreshSignedMetadataButCannotAuthenticateA304() = runBlocking<Unit> {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val cache = UpdateCatalogCache(folder, keys)
        File(folder, "catalog-stable.json").writeText("broken")
        val bytes = UpdateFixture.bytes(fixture.catalog(7)); val signature = fixture.sign(bytes)
        UpdateTestOrigin { UpdateTestOrigin.reply(it, byteArrayOf(), 304) }.use { bad ->
            UpdateTestOrigin { UpdateTestOrigin.reply(it, if (it.requestURI.path.endsWith(".sig")) signature else bytes) }.use { good ->
                fetcher().use { transport ->
                    val checked = UpdateCatalogClient(transport, keys, ledger, cache).checkDetailed(listOf(bad.url(), good.url("/stable.json")), "stable")
                    assertEquals(7L, checked.catalog.document.seq); assertTrue(bad.hits.all { "if-none-match" !in it.headers })
                    assertEquals(7L, cache.read("stable")!!.catalog.document.seq)
                }
            }
        }
    }

    @Test fun corruptLedgerDoesNotBecomeFreshBecauseASignedCacheExists() = runBlocking<Unit> {
        val folder = temporary.newFolder(); val records = temporary.newFolder(); val ledger = UpdateSequenceStore(records)
        val cache = UpdateCatalogCache(folder, keys); val catalog = fixture.verified(); ledger.accept(catalog); cache.write(catalog, "https://m.test/a", null, ledger)
        File(records, "sequences.json").writeText("broken")
        UpdateTestOrigin { UpdateTestOrigin.reply(it, byteArrayOf(), 503) }.use { source -> fetcher().use { transport ->
            assertThrows(IOException::class.java) { runBlocking { UpdateCatalogClient(transport, keys, ledger, cache).check(listOf(source.url()), "stable") } }
            assertTrue(source.hits.isEmpty()); assertEquals("broken", File(records, "sequences.json").readText())
        } }
    }

    @Test(timeout = 10_000) fun cancellingACheckNeverTurnsIntoAnOfflineSuccess() = runBlocking<Unit> {
        val folder = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val cache = UpdateCatalogCache(folder, keys)
        val catalog = fixture.verified(); ledger.accept(catalog)
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        UpdateTestOrigin { entered.countDown(); release.await(3, TimeUnit.SECONDS); UpdateTestOrigin.reply(it, byteArrayOf(), 503) }.use { source ->
            fetcher().use { transport ->
                cache.write(catalog, source.url(), "old", ledger)
                val request = async { UpdateCatalogClient(transport, keys, ledger, cache).checkDetailed(listOf(source.url()), "stable") }
                while (entered.count > 0) delay(5)
                request.cancel()
                assertThrows(CancellationException::class.java) { runBlocking { request.await() } }
                release.countDown()
                assertEquals(1L, cache.read("stable")!!.catalog.document.seq)
            }
        }
    }
}
