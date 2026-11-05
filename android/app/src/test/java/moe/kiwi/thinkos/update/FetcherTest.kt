package moe.kiwi.thinkos.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Dns
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class FetcherTest {
    @get:Rule val temporary = TemporaryFolder()
    private val timeouts = UpdateHttpClient.Timeouts(15_000, 30_000)
    private fun fetcher() = Fetcher("0.1.1", timeouts)
    private fun bytes(size: Int) = ByteArray(size) { (it * 31 + 7).toByte() }
    private fun spec(data: ByteArray) = Fetcher.Artifact(Fetcher.ArtifactKind.APK, data.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it.toInt() and 0xff) })
    private fun partial(root: File, artifact: Fetcher.Artifact) = File(root, "${artifact.sha256}.${artifact.kind.extension}.part")
    private fun target(root: File, artifact: Fetcher.Artifact) = File(root, "${artifact.sha256}.${artifact.kind.extension}")
    private fun range(origin: UpdateTestOrigin, index: Int = 0) = origin.hits[index].headers.getValue("range").single()
    private suspend fun rejected(block: suspend () -> Any?): IOException {
        try { block() } catch (error: IOException) { return error }
        throw AssertionError("Expected a rejected download")
    }

    @Test(timeout = 10_000) fun documentBoundsAcceptEachExactBoundaryWithoutTransformingBytes() = runBlocking<Unit> {
        for (kind in Fetcher.DocumentKind.entries) {
            val content = bytes(kind.maxBytes)
            UpdateTestOrigin { UpdateTestOrigin.reply(it, content) }.use { origin -> fetcher().use { fetcher ->
                val result = fetcher.readDocument(listOf(origin.url()), kind, content.size) as Fetcher.Document.Body
                assertArrayEquals(content, result.bytes)
                assertEquals(listOf("identity"), origin.hits.single().headers["accept-encoding"])
            } }
        }
    }

    @Test(timeout = 10_000) fun knownAndChunkedOversizedDocumentsAreRejectedBeforeTheNextMirror() = runBlocking<Unit> {
        for (chunked in listOf(false, true)) {
            UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(1025), chunked = chunked) }.use { bad ->
                UpdateTestOrigin { UpdateTestOrigin.reply(it, byteArrayOf(3, 4)) }.use { good -> fetcher().use { fetcher ->
                    val result = fetcher.readDocument(listOf(bad.url(), good.url()), Fetcher.DocumentKind.SIGNATURE) as Fetcher.Document.Body
                    assertArrayEquals(byteArrayOf(3, 4), result.bytes)
                    assertEquals(1, bad.hits.size); assertEquals(1, good.hits.size)
                } }
            }
        }
    }

    @Test(timeout = 10_000) fun aDeclaredDocumentSizeCannotBeExceededOrSilentlyTruncated() = runBlocking<Unit> {
        for (size in listOf(1, 3)) UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(size), chunked = true) }.use { origin ->
            fetcher().use { rejected { it.readDocument(listOf(origin.url()), Fetcher.DocumentKind.MANIFEST, 2) } }
        }
        UpdateTestOrigin { UpdateTestOrigin.reply(it, byteArrayOf()) }.use { origin ->
            fetcher().use { rejected { it.readDocument(listOf(origin.url()), Fetcher.DocumentKind.MANIFEST) } }
        }
    }

    @Test(timeout = 10_000) fun compressedDocumentsAreNotDecodedIntoSignedBytes() = runBlocking<Unit> {
        UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(16), headers = listOf("Content-Encoding" to "gzip")) }.use { origin ->
            fetcher().use { rejected { it.readDocument(listOf(origin.url()), Fetcher.DocumentKind.CATALOG) } }
        }
    }

    @Test(timeout = 10_000) fun conditionalRequestsReturnNotModifiedOnlyWhenTheCallerSuppliedAnEtag() = runBlocking<Unit> {
        UpdateTestOrigin { UpdateTestOrigin.reply(it, byteArrayOf(), 304) }.use { origin -> fetcher().use { fetcher ->
            assertSame(Fetcher.Document.NotModified, fetcher.readDocument(listOf(origin.url()), Fetcher.DocumentKind.CATALOG, etag = "\"v1\""))
            assertEquals(listOf("\"v1\""), origin.hits.single().headers["if-none-match"])
            rejected { fetcher.readDocument(listOf(origin.url()), Fetcher.DocumentKind.CATALOG) }
        } }
        UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(2), headers = listOf("ETag" to "\"new\"")) }.use { origin ->
            fetcher().use { fetcher ->
                assertEquals("\"new\"", (fetcher.readDocument(listOf(origin.url()), Fetcher.DocumentKind.CATALOG) as Fetcher.Document.Body).etag)
            }
        }
    }

    @Test(timeout = 10_000) fun invalidUrlsAndFailedHttpStatusesFallBackInOrder() = runBlocking<Unit> {
        UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(4), 503) }.use { bad ->
            UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(4)) }.use { good -> fetcher().use { fetcher ->
                val result = fetcher.readDocument(listOf("file:///tmp/not-an-update", bad.url(), good.url()), Fetcher.DocumentKind.CATALOG)
                assertTrue(result is Fetcher.Document.Body)
                assertEquals(1, bad.hits.size); assertEquals(1, good.hits.size)
            } }
        }
    }

    @Test(timeout = 10_000) fun aLargeArtifactUsesOneMebibyteRangesAndOnlyPublishesAfterItsHashMatches() = runBlocking<Unit> {
        val data = bytes(2 * CHUNK + 17); val artifact = spec(data); val root = temporary.newFolder()
        UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { origin ->
            fetcher().use { fetcher ->
                val result = fetcher.download(listOf(origin.url()), artifact, root)
                assertArrayEquals(data, result.readBytes()); assertFalse(partial(root, artifact).exists())
                assertEquals(listOf("bytes=0-1048575", "bytes=1048576-2097151", "bytes=2097152-2097168"), origin.hits.indices.map { range(origin, it) })
            }
            fetcher().use { assertArrayEquals(data, it.download(listOf(origin.url()), artifact, root).readBytes()) }
            assertEquals(3, origin.hits.size) // Cached artifacts are rehashed, without another HTTP request.
        }
    }

    @Test(timeout = 10_000) fun aNewFetcherResumesAnInterruptedResponseFromThePersistedByteOffset() = runBlocking<Unit> {
        val data = bytes(CHUNK + 19); val artifact = spec(data); val root = temporary.newFolder()
        UpdateTestOrigin { exchange ->
            UpdateTestOrigin.reply(exchange, data.copyOf(CHUNK), 206, listOf("Content-Range" to "bytes 0-${CHUNK - 1}/${data.size}"), cutAt = 65_536)
        }.use { broken -> fetcher().use { rejected { it.download(listOf(broken.url()), artifact, root) } } }
        assertEquals(65_536L, partial(root, artifact).length()); assertFalse(target(root, artifact).exists())
        UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { recovered -> fetcher().use { fetcher ->
            assertArrayEquals(data, fetcher.download(listOf(recovered.url()), artifact, root).readBytes())
            assertEquals("bytes=65536-1048594", range(recovered))
        } }
    }

    @Test(timeout = 10_000) fun theNextMirrorContinuesAPartialChunkInsteadOfStartingOver() = runBlocking<Unit> {
        val data = bytes(100_000); val artifact = spec(data); val root = temporary.newFolder()
        UpdateTestOrigin { UpdateTestOrigin.reply(it, data, 206, listOf("Content-Range" to "bytes 0-99999/100000"), cutAt = 32_768) }.use { broken ->
            UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { good -> fetcher().use { fetcher ->
                assertArrayEquals(data, fetcher.download(listOf(broken.url(), good.url()), artifact, root).readBytes())
                assertEquals("bytes=32768-99999", range(good))
            } }
        }
    }

    @Test(timeout = 10_000) fun aRangeIgnoringMirrorCannotOverwriteAPartialDownload() = runBlocking<Unit> {
        val data = bytes(200); val artifact = spec(data); val root = temporary.newFolder()
        partial(root, artifact).writeBytes(data.copyOf(73))
        UpdateTestOrigin { UpdateTestOrigin.reply(it, data) }.use { ignores ->
            UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { good -> fetcher().use { fetcher ->
                assertArrayEquals(data, fetcher.download(listOf(ignores.url(), good.url()), artifact, root).readBytes())
                assertEquals("bytes=73-199", range(ignores)); assertEquals("bytes=73-199", range(good))
            } }
        }
    }

    @Test(timeout = 10_000) fun aFreshDownloadCanUseAnOriginWithoutRangeSupport() = runBlocking<Unit> {
        val data = bytes(CHUNK + 3); val artifact = spec(data)
        UpdateTestOrigin { UpdateTestOrigin.reply(it, data, chunked = true) }.use { origin -> fetcher().use { fetcher ->
            assertArrayEquals(data, fetcher.download(listOf(origin.url()), artifact, temporary.newFolder()).readBytes())
            assertEquals(1, origin.hits.size)
        } }
    }

    @Test(timeout = 10_000) fun malformedRangesNeverAppendToAnExistingPrefix() = runBlocking<Unit> {
        val data = bytes(100); val artifact = spec(data)
        val ranges = listOf(emptyList(), listOf("bytes 0-89/100"), listOf("bytes 10-100/100"), listOf("bytes 10-99/101"),
            listOf("bytes 10-99/*"), listOf("bytes 10-99/999999999999999999999999"), listOf("bytes 10-99/100", "bytes 10-99/100"))
        for (headers in ranges) {
            val root = temporary.newFolder(); partial(root, artifact).writeBytes(data.copyOf(10))
            UpdateTestOrigin { UpdateTestOrigin.reply(it, data.copyOfRange(10, 100), 206, headers.map { "Content-Range" to it }) }.use { bad ->
                UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { good -> fetcher().use { fetcher ->
                    assertArrayEquals(data, fetcher.download(listOf(bad.url(), good.url()), artifact, root).readBytes())
                    assertEquals("bytes=10-99", range(good))
                } }
            }
        }
    }

    @Test(timeout = 10_000) fun excessChunkedBytesAndMismatchedContentLengthAreNotKept() = runBlocking<Unit> {
        val data = bytes(100); val artifact = spec(data)
        for (chunked in listOf(false, true)) {
            val root = temporary.newFolder()
            UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(101), 206, listOf("Content-Range" to "bytes 0-99/100"), chunked = chunked) }.use { bad ->
                UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { good -> fetcher().use { fetcher ->
                    assertArrayEquals(data, fetcher.download(listOf(bad.url(), good.url()), artifact, root).readBytes())
                    assertEquals("bytes=0-99", range(good))
                } }
            }
        }
    }

    @Test(timeout = 10_000) fun aWrongHashDiscardsTheCorruptArtifactBeforeTryingAnotherMirror() = runBlocking<Unit> {
        val data = bytes(100); val artifact = spec(data); val root = temporary.newFolder()
        UpdateTestOrigin { UpdateTestOrigin.artifact(it, data.copyOf().apply { this[4] = 0 }) }.use { corrupt ->
            UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { good -> fetcher().use { fetcher ->
                assertArrayEquals(data, fetcher.download(listOf(corrupt.url(), good.url()), artifact, root).readBytes())
                assertEquals("bytes=0-99", range(good))
            } }
        }
    }

    @Test(timeout = 10_000) fun aFullyDownloadedPartIsVerifiedAndPublishedWithoutTheNetwork() = runBlocking<Unit> {
        val data = bytes(100); val artifact = spec(data); val root = temporary.newFolder()
        partial(root, artifact).writeBytes(data)
        fetcher().use { assertArrayEquals(data, it.download(listOf("file:///not-used"), artifact, root).readBytes()) }
        assertFalse(partial(root, artifact).exists())
    }

    @Test(timeout = 10_000) fun corruptCachedAndCompletePartialFilesCannotBypassHashVerification() = runBlocking<Unit> {
        val data = bytes(100); val artifact = spec(data); val root = temporary.newFolder()
        target(root, artifact).writeBytes(ByteArray(100)); partial(root, artifact).writeBytes(ByteArray(100))
        UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { origin -> fetcher().use { fetcher ->
            assertArrayEquals(data, fetcher.download(listOf(origin.url()), artifact, root).readBytes())
            assertEquals("bytes=0-99", range(origin))
        } }
    }

    @Test(timeout = 10_000) fun anOversizedPartialIsResetBeforeRequestingBytes() = runBlocking<Unit> {
        val data = bytes(100); val artifact = spec(data); val root = temporary.newFolder()
        partial(root, artifact).writeBytes(bytes(101))
        UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { origin -> fetcher().use { fetcher ->
            assertArrayEquals(data, fetcher.download(listOf(origin.url()), artifact, root).readBytes())
            assertEquals("bytes=0-99", range(origin))
        } }
    }

    @Test(timeout = 10_000) fun cancellingAStalledBodyClosesTheCallAndKeepsProgressWithoutFallingBack() = runBlocking<Unit> {
        val data = bytes(CHUNK + 1); val artifact = spec(data); val root = temporary.newFolder(); val release = CountDownLatch(1)
        UpdateTestOrigin { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-${CHUNK - 1}/${data.size}")
            exchange.sendResponseHeaders(206, CHUNK.toLong())
            exchange.responseBody.write(data, 0, 8192); exchange.responseBody.flush()
            release.await(5, TimeUnit.SECONDS)
        }.use { stalled -> UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { fallback -> fetcher().use { fetcher ->
            try {
                val task = async(Dispatchers.Default) { fetcher.download(listOf(stalled.url(), fallback.url()), artifact, root) }
                withTimeout(3000) { while (partial(root, artifact).length() == 0L) delay(5) }
                withTimeout(3000) { task.cancelAndJoin() }
                assertTrue(partial(root, artifact).length() in 1..8192); assertFalse(target(root, artifact).exists())
                assertTrue(fallback.hits.isEmpty())
            } finally { release.countDown() }
        } } }
    }

    @Test(timeout = 10_000) fun concurrentDownloadersCannotWriteTheSamePart() = runBlocking<Unit> {
        val data = bytes(100); val artifact = spec(data); val root = temporary.newFolder()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        UpdateTestOrigin { exchange -> entered.countDown(); release.await(5, TimeUnit.SECONDS); UpdateTestOrigin.artifact(exchange, data) }.use { origin ->
            fetcher().use { first -> fetcher().use { second ->
                val task = async(Dispatchers.Default) { first.download(listOf(origin.url()), artifact, root) }
                try {
                    assertTrue(entered.await(3, TimeUnit.SECONDS))
                    rejected { second.download(listOf(origin.url()), artifact, root) }
                    assertEquals(1, origin.hits.size)
                } finally { release.countDown() }
                assertArrayEquals(data, task.await().readBytes())
            } }
        }
    }

    @Test(timeout = 10_000) fun aSymlinkCannotRedirectStagingWritesOutsideTheCache() = runBlocking<Unit> {
        val data = bytes(100); val artifact = spec(data); val root = temporary.newFolder(); val outside = temporary.newFile()
        outside.writeText("keep")
        Files.createSymbolicLink(partial(root, artifact).toPath(), outside.toPath())
        fetcher().use { rejected { it.download(listOf("file:///not-used"), artifact, root) } }
        assertEquals("keep", outside.readText())
    }

    @Test fun artifactAndDocumentDeclarationsAreBoundedBeforeAnyIo() {
        for (kind in Fetcher.ArtifactKind.entries) {
            for (size in listOf(-1, 0, kind.maxBytes + 1)) assertThrows(IllegalArgumentException::class.java) {
                Fetcher.Artifact(kind, size, "0".repeat(64))
            }
        }
        for (hash in listOf("../outside", "0".repeat(63), "G".repeat(64))) assertThrows(IllegalArgumentException::class.java) {
            Fetcher.Artifact(Fetcher.ArtifactKind.APK, 1, hash)
        }
        fetcher().use { fetcher ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fetcher.readDocument(listOf("file:///not-used"), Fetcher.DocumentKind.SIGNATURE, 1025) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fetcher.readDocument(listOf("file:///not-used"), Fetcher.DocumentKind.CATALOG, etag = "bad\r\nheader") } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { fetcher.download(emptyList(), spec(bytes(1)), temporary.root) } }
        }
    }

    @Test(timeout = 10_000) fun networkWorkRunsOnAnIoThreadAtMinimumPriority() = runBlocking<Unit> {
        val caller = Thread.currentThread().id; val observed = CopyOnWriteArrayList<Pair<Long, Int>>()
        val dns = object : Dns { override fun lookup(hostname: String): List<InetAddress> {
            observed.add(Thread.currentThread().let { it.id to it.priority })
            return listOf(InetAddress.getByName("127.0.0.1"))
        } }
        UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(1)) }.use { origin ->
            Fetcher("test", timeouts, dns).use { it.readDocument(listOf(origin.url()), Fetcher.DocumentKind.CATALOG) }
            assertTrue(observed.isNotEmpty()); assertTrue(observed.all { it.first != caller && it.second == Thread.MIN_PRIORITY })
        }
    }

    @Test(timeout = 10_000) fun aReadTimeoutTriesTheNextCandidate() = runBlocking<Unit> {
        val release = CountDownLatch(1)
        UpdateTestOrigin { release.await(5, TimeUnit.SECONDS) }.use { stalled ->
            UpdateTestOrigin { UpdateTestOrigin.reply(it, bytes(2)) }.use { good ->
                try { Fetcher("test", UpdateHttpClient.Timeouts(500, 150)).use { fetcher ->
                    assertTrue(fetcher.readDocument(listOf(stalled.url(), good.url()), Fetcher.DocumentKind.CATALOG) is Fetcher.Document.Body)
                    assertEquals(1, stalled.hits.size); assertEquals(1, good.hits.size)
                } } finally { release.countDown() }
            }
        }
    }

    @Test(timeout = 15_000) fun aKilledDownloadProcessResumesFromItsOwnPartInANewJvm() = runBlocking<Unit> {
        val data = bytes(CHUNK + 11); val artifact = spec(data); val root = temporary.newFolder()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val resource = UpdateTrust::class.java.getResource("roots.pem") ?: error("Missing bundled CA roots")
        val resourcePath = if (resource.protocol == "jar") File((resource.openConnection() as java.net.JarURLConnection).jarFileURL.toURI())
            else generateSequence(File(resource.toURI())) { it.parentFile }.drop(5).first()
        val classpath = (listOf(Fetcher::class.java, FetcherProcessProbe::class.java, Unit::class.java,
            kotlinx.coroutines.Job::class.java, okhttp3.OkHttpClient::class.java, okio.Buffer::class.java)
            .map { File(it.protectionDomain.codeSource.location.toURI()).absolutePath } + resourcePath.absolutePath).distinct().joinToString(File.pathSeparator)
        fun process(url: String, log: File) = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").absolutePath,
            "-cp", classpath, FetcherProcessProbe::class.java.name, url, root.absolutePath, artifact.bytes.toString(), artifact.sha256)
            .redirectErrorStream(true).redirectOutput(log).start()
        UpdateTestOrigin { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-${CHUNK - 1}/${data.size}")
            exchange.sendResponseHeaders(206, CHUNK.toLong())
            exchange.responseBody.write(data, 0, 65_536); exchange.responseBody.flush(); entered.countDown()
            release.await(10, TimeUnit.SECONDS)
        }.use { interrupted ->
            val log = temporary.newFile(); val child = process(interrupted.url(), log)
            try {
                assertTrue(log.readText(), entered.await(5, TimeUnit.SECONDS))
                withTimeout(3000) { while (partial(root, artifact).length() != 65_536L) delay(5) }
                child.destroyForcibly()
                assertTrue(child.waitFor(3, TimeUnit.SECONDS)); assertFalse(child.isAlive)
            } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) }; release.countDown() }
        }
        assertEquals(65_536L, partial(root, artifact).length()); assertFalse(target(root, artifact).exists())
        UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { resumed ->
            val log = temporary.newFile(); val child = process(resumed.url(), log)
            try {
                assertTrue(child.waitFor(5, TimeUnit.SECONDS)); assertEquals(log.readText(), 0, child.exitValue())
            } finally { if (child.isAlive) { child.destroyForcibly(); child.waitFor(3, TimeUnit.SECONDS) } }
            assertEquals("bytes=65536-1048586", range(resumed))
            assertArrayEquals(data, target(root, artifact).readBytes())
        }
    }

    @Test(timeout = 10_000) fun closingTheFetcherCancelsItsActiveBodyAndPreventsAnotherMirror() = runBlocking<Unit> {
        val data = bytes(10_000); val artifact = spec(data); val root = temporary.newFolder(); val release = CountDownLatch(1)
        UpdateTestOrigin { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-9999/10000")
            exchange.sendResponseHeaders(206, 10_000)
            exchange.responseBody.write(data, 0, 8192); exchange.responseBody.flush(); release.await(5, TimeUnit.SECONDS)
        }.use { stalled -> UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { fallback ->
            val fetcher = fetcher()
            try {
                val task = async(Dispatchers.Default) { fetcher.download(listOf(stalled.url(), fallback.url()), artifact, root) }
                withTimeout(3000) { while (partial(root, artifact).length() == 0L) delay(5) }
                fetcher.close()
                try { withTimeout(3000) { task.await() }; fail("Closed fetcher must cancel") } catch (_: CancellationException) { }
                assertTrue(fallback.hits.isEmpty()); assertFalse(target(root, artifact).exists())
            } finally { release.countDown(); fetcher.close() }
        } }
    }

    @Test(timeout = 10_000) fun aLocalStorageFailureDoesNotTryAnyNetworkMirror() = runBlocking<Unit> {
        val data = bytes(1)
        UpdateTestOrigin { UpdateTestOrigin.artifact(it, data) }.use { origin -> fetcher().use { fetcher ->
            rejected { fetcher.download(listOf(origin.url(), origin.url()), spec(data), temporary.newFile()) }
            assertTrue(origin.hits.isEmpty())
        } }
    }

    private companion object { const val CHUNK = 1024 * 1024 }
}
