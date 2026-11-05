package moe.kiwi.thinkos.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

class UpdateCatalogClientTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()
    private fun fetcher(health: UpdateSourceHealth? = null) = Fetcher("0.2.0", UpdateHttpClient.Timeouts(1000, 1000), health = health)
    private val quick = UpdateCatalogClient.Selection(staggerMillis = 150, settleMillis = 400)
    private fun origin(files: Map<String, ByteArray>) = UpdateTestOrigin { exchange ->
        val path = exchange.requestURI.path
        if (path == "/app.apk") UpdateTestOrigin.artifact(exchange, fixture.payload)
        else UpdateTestOrigin.reply(exchange, files[path] ?: byteArrayOf(), if (path in files) 200 else 404)
    }
    private fun publish(files: MutableMap<String, ByteArray>, catalog: org.json.JSONObject, badSignature: Boolean = false) {
        val bytes = UpdateFixture.bytes(catalog)
        files["/stable.json"] = bytes; files["/stable.json.sig"] = if (badSignature) ByteArray(71) else fixture.sign(bytes)
    }

    @Test(timeout = 10_000) fun realHttpCheckAndDownloadAuthenticateMetadataCommitItsFloorAndVerifyTheBlob() = runBlocking<Unit> {
        val files = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder(); val downloads = temporary.newFolder()
        origin(files).use { source ->
            publish(files, fixture.catalog(17, url = source.url("/app.apk")))
            fetcher().use { transport ->
                val client = UpdateCatalogClient(transport, fixture.keys(), UpdateSequenceStore(ledger))
                val catalog = client.check(listOf(source.url("/stable.json")), "stable")
                assertEquals(17L, UpdateSequenceStore(ledger).snapshot().stable!!.seq)
                assertArrayEquals(fixture.payload, client.downloadApk(catalog, fixture.device, downloads).readBytes())
                assertEquals(listOf("/stable.json", "/stable.json.sig", "/app.apk"), source.hits.map { it.path })
                assertTrue(source.hits.all { "cookie" !in it.headers && "accept-language" !in it.headers })
            }
        }
    }

    @Test(timeout = 10_000) fun badSignaturesAndOldCatalogsTryAnotherMirrorWithoutLoweringTheLedger() = runBlocking<Unit> {
        val badFiles = ConcurrentHashMap<String, ByteArray>(); val goodFiles = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder()
        origin(badFiles).use { bad -> origin(goodFiles).use { good -> fetcher().use { transport ->
            val store = UpdateSequenceStore(ledger); val client = UpdateCatalogClient(transport, fixture.keys(), store)
            publish(badFiles, fixture.catalog(100), badSignature = true); publish(goodFiles, fixture.catalog(9))
            assertEquals(9L, client.check(listOf(bad.url("/stable.json"), good.url("/stable.json")), "stable").document.seq)
            publish(badFiles, fixture.catalog(8)); publish(goodFiles, fixture.catalog(10))
            assertEquals(10L, client.check(listOf(bad.url("/stable.json"), good.url("/stable.json")), "stable").document.seq)
            assertEquals(10L, store.snapshot().stable!!.seq)
        } } }
    }

    @Test(timeout = 10_000) fun incompatibleReleasesAreNeverRequestedAsApkBytes() = runBlocking<Unit> {
        val files = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder()
        origin(files).use { source -> fetcher().use { transport ->
            publish(files, fixture.catalog(url = source.url("/app.apk")))
            val client = UpdateCatalogClient(transport, fixture.keys(), UpdateSequenceStore(ledger))
            val catalog = client.check(listOf(source.url("/stable.json")), "stable")
            for (device in listOf(fixture.device.copy(name = "wrong"), fixture.device.copy(sdk = 28), fixture.device.copy(versionCode = 2000), fixture.device.copy(signer = "c".repeat(64)))) {
                try { client.downloadApk(catalog, device, temporary.newFolder()); fail("Incompatible release accepted") } catch (_: IllegalArgumentException) { }
            }
            assertFalse(source.hits.any { it.path == "/app.apk" })
        } }
    }

    @Test(timeout = 10_000) fun aNewerCatalogSeenDuringTheTransferPreventsReturningAnOldCandidate() = runBlocking<Unit> {
        val files = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder(); val store = UpdateSequenceStore(ledger)
        val newer = fixture.verified(fixture.catalog(2))
        UpdateTestOrigin { exchange ->
            if (exchange.requestURI.path == "/app.apk") { store.accept(newer); UpdateTestOrigin.artifact(exchange, fixture.payload) }
            else UpdateTestOrigin.reply(exchange, files.getValue(exchange.requestURI.path))
        }.use { source -> fetcher().use { transport ->
            publish(files, fixture.catalog(1, url = source.url("/app.apk")))
            val client = UpdateCatalogClient(transport, fixture.keys(), store)
            val catalog = client.check(listOf(source.url("/stable.json")), "stable")
            try { client.downloadApk(catalog, fixture.device, temporary.newFolder()); fail("Old candidate returned") } catch (_: UpdateSequenceStore.Rollback) { }
            assertEquals(2L, store.snapshot().stable!!.seq)
        } }
    }

    @Test(timeout = 10_000) fun localLedgerCorruptionAbortsInsteadOfTryingOtherMirrors() = runBlocking<Unit> {
        val firstFiles = ConcurrentHashMap<String, ByteArray>(); val nextFiles = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder()
        File(ledger, "sequences.json").writeText("broken")
        publish(firstFiles, fixture.catalog()); publish(nextFiles, fixture.catalog(2))
        origin(firstFiles).use { first -> origin(nextFiles).use { next -> fetcher().use { transport ->
            val client = UpdateCatalogClient(transport, fixture.keys(), UpdateSequenceStore(ledger))
            try { client.check(listOf(first.url("/stable.json"), next.url("/stable.json")), "stable"); fail("Corrupt ledger ignored") } catch (_: IOException) { }
            // Another mirror may be asked for its catalog, but a broken local floor is never repaired or bypassed by it.
            assertEquals("broken", File(ledger, "sequences.json").readText())
        } } }
    }

    @Test(timeout = 15_000) fun aSilentFirstMirrorOnlyDelaysTheCheckByTheStaggerNotItsTimeout() = runBlocking<Unit> {
        val slowFiles = ConcurrentHashMap<String, ByteArray>(); val goodFiles = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder()
        publish(slowFiles, fixture.catalog(4)); publish(goodFiles, fixture.catalog(4))
        UpdateTestOrigin { exchange -> Thread.sleep(3000); UpdateTestOrigin.reply(exchange, slowFiles[exchange.requestURI.path] ?: byteArrayOf()) }.use { slow ->
            origin(goodFiles).use { good -> fetcher().use { transport ->
                val client = UpdateCatalogClient(transport, fixture.keys(), UpdateSequenceStore(ledger), selection = quick)
                val started = System.nanoTime()
                assertEquals(4L, client.check(listOf(slow.url("/stable.json"), good.url("/stable.json")), "stable").document.seq)
                val elapsed = (System.nanoTime() - started) / 1_000_000
                assertTrue("check took ${elapsed}ms, the second mirror should answer after the stagger", elapsed < 2000)
            } }
        }
    }

    @Test(timeout = 15_000) fun theHighestAuthenticatedSequenceWinsEvenWhenALaggingMirrorAnswersFirst() = runBlocking<Unit> {
        val lagging = ConcurrentHashMap<String, ByteArray>(); val current = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder()
        publish(lagging, fixture.catalog(5)); publish(current, fixture.catalog(6))
        origin(lagging).use { old -> origin(current).use { fresh -> fetcher().use { transport ->
            val store = UpdateSequenceStore(ledger); val client = UpdateCatalogClient(transport, fixture.keys(), store, selection = quick)
            val sources = listOf(old.url("/stable.json"), fresh.url("/stable.json"))
            assertEquals(6L, client.check(sources, "stable").document.seq)
            assertEquals(6L, store.snapshot().stable!!.seq)
            // Later the lagging mirror is still older than the floor: it can neither win nor lower it.
            assertEquals(6L, client.check(sources, "stable").document.seq)
            assertEquals(6L, store.snapshot().stable!!.seq)
        } } }
    }

    @Test(timeout = 15_000) fun aMirrorThatNeverAnswersDoesNotHoldBackAnAuthenticatedResultBeyondTheSettleWindow() = runBlocking<Unit> {
        val goodFiles = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder()
        publish(goodFiles, fixture.catalog(2))
        origin(goodFiles).use { good -> UpdateTestOrigin { exchange -> Thread.sleep(5000); UpdateTestOrigin.reply(exchange, byteArrayOf()) }.use { hung ->
            fetcher().use { transport ->
                val client = UpdateCatalogClient(transport, fixture.keys(), UpdateSequenceStore(ledger), selection = quick)
                val started = System.nanoTime()
                assertEquals(2L, client.check(listOf(good.url("/stable.json"), hung.url("/stable.json")), "stable").document.seq)
                assertTrue("settle window bounds the wait", (System.nanoTime() - started) / 1_000_000 < 3000)
            }
        } }
    }

    @Test(timeout = 15_000) fun everySourceFailingFallsBackToTheAuthenticatedCacheOrFails() = runBlocking<Unit> {
        val files = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder()
        origin(files).use { down -> origin(files).use { alsoDown -> fetcher().use { transport ->
            val client = UpdateCatalogClient(transport, fixture.keys(), UpdateSequenceStore(ledger), selection = quick)
            try { client.check(listOf(down.url("/stable.json"), alsoDown.url("/stable.json"), "https://[bad"), "stable"); fail("No catalog accepted") }
            catch (error: IOException) { assertTrue(error.suppressed.size >= 3) }
        } } }
    }

    @Test(timeout = 15_000) fun anUnreachableMirrorIsRememberedSoTheNextTransferStartsOnTheWorkingOne() = runBlocking<Unit> {
        val deadFiles = ConcurrentHashMap<String, ByteArray>(); val goodFiles = ConcurrentHashMap<String, ByteArray>()
        val ledger = temporary.newFolder(); val downloads = temporary.newFolder()
        origin(deadFiles).use { dead -> origin(goodFiles).use { good ->
            val health = UpdateSourceHealth(File(temporary.newFolder(), "health.json"))
            fetcher(health).use { transport ->
                val release = fixture.catalog(7)
                release.getJSONObject("release").put("urls", org.json.JSONArray().put(dead.url("/app.apk")).put(good.url("/app.apk")))
                publish(goodFiles, release)
                val client = UpdateCatalogClient(transport, fixture.keys(), UpdateSequenceStore(ledger), selection = quick)
                val catalog = client.check(listOf(dead.url("/stable.json"), good.url("/stable.json")), "stable")
                dead.hits.clear()
                assertArrayEquals(fixture.payload, client.downloadApk(catalog, fixture.device, downloads).readBytes())
                assertTrue("the dead mirror was tried first even though it had just failed: ${dead.hits}", dead.hits.none { it.path == "/app.apk" })
                assertTrue(health.isDemoted(dead.url("/")))
                assertFalse(health.isDemoted(good.url("/")))
            }
        } }
    }

    @Test(timeout = 20_000) fun mirrorsAnnouncedByTheNewestAuthenticatedCatalogJoinTheCandidatesLater() = runBlocking<Unit> {
        val firstFiles = ConcurrentHashMap<String, ByteArray>(); val secondFiles = ConcurrentHashMap<String, ByteArray>()
        val deadFiles = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder(); val metadata = temporary.newFolder()
        origin(firstFiles).use { first -> origin(secondFiles).use { second -> origin(deadFiles).use { dead ->
            fetcher().use { transport ->
                val keys = fixture.keys()
                val client = UpdateCatalogClient(transport, keys, UpdateSequenceStore(ledger), UpdateCatalogCache(metadata, keys), quick)
                publish(firstFiles, fixture.catalog(3).put("mirrors", org.json.JSONArray().put(second.url("/stable.json"))))
                publish(secondFiles, fixture.catalog(4).put("mirrors", org.json.JSONArray().put(second.url("/stable.json"))))
                // Nothing is learned before a first authenticated catalog exists.
                assertEquals(3L, client.check(listOf(first.url("/stable.json")), "stable").document.seq)
                assertTrue(second.hits.isEmpty())
                // Now the announced mirror is asked as well, and its higher sequence wins.
                assertEquals(4L, client.check(listOf(first.url("/stable.json")), "stable").document.seq)
                assertTrue(second.hits.any { it.path == "/stable.json" })
                // The configured source no longer helps, but the learned mirror keeps updates reachable.
                assertEquals(4L, client.check(listOf(dead.url("/stable.json")), "stable").document.seq)
                assertTrue(dead.hits.any { it.path == "/stable.json" })
            }
        } } }
    }

    @Test(timeout = 20_000) fun announcedMirrorsNeverDisplaceConfiguredSourcesAndNeedValidSignatures() = runBlocking<Unit> {
        val files = ConcurrentHashMap<String, ByteArray>(); val rogue = ConcurrentHashMap<String, ByteArray>(); val ledger = temporary.newFolder()
        val metadata = temporary.newFolder()
        origin(files).use { good -> origin(rogue).use { evil -> fetcher().use { transport ->
            val keys = fixture.keys()
            val client = UpdateCatalogClient(transport, keys, UpdateSequenceStore(ledger), UpdateCatalogCache(metadata, keys), quick)
            publish(files, fixture.catalog(5).put("mirrors", org.json.JSONArray().put(evil.url("/stable.json"))))
            // The announced mirror serves a higher sequence but a signature that does not verify: it can never win.
            publish(rogue, fixture.catalog(99), badSignature = true)
            assertEquals(5L, client.check(listOf(good.url("/stable.json")), "stable").document.seq)
            assertEquals(5L, client.check(listOf(good.url("/stable.json")), "stable").document.seq)
            assertTrue(evil.hits.isNotEmpty())
            assertEquals(5L, UpdateSequenceStore(ledger).snapshot().stable!!.seq)
            // With eight configured sources there is no room for announced ones.
            val eight = (1..8).map { good.url("/stable.json?x=$it") }
            evil.hits.clear()
            client.check(eight, "stable")
            assertTrue(evil.hits.isEmpty())
        } } }
    }

    @Test fun detachedSignatureUrlsPreserveEncodedPathsAndQueries() {
        assertEquals("https://mirror.test/a%2Fstable.json.sig?token=x%26y", UpdateCatalogClient.signatureUrl("https://mirror.test/a%2Fstable.json?token=x%26y"))
        assertThrows(IllegalArgumentException::class.java) { UpdateCatalogClient.signatureUrl("https://user:secret@mirror.test/a") }
    }
}
