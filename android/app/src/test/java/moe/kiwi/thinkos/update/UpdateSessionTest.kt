package moe.kiwi.thinkos.update

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdateSessionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()
    private val keys = fixture.keys()
    private val parses = AtomicInteger()
    private val payload = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("AndroidManifest.xml")); zip.write("fixture".toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry("classes.dex"))
            val bytes = ByteArray(2*1024*1024+32); java.util.Random(17).nextBytes(bytes)
            zip.write(bytes); zip.closeEntry()
        }; output.toByteArray()
    }
    private val verifier = UpdatePackageVerifier.forTest("moe.kiwi.thinkos", { fixture.device }, {
        parses.incrementAndGet(); UpdatePackageVerifier.Archive("moe.kiwi.thinkos", "0.2.0", 2000, listOf(fixture.signer), 27, 27, false)
    })
    private fun fetcher() = Fetcher("0.1.1", UpdateHttpClient.Timeouts(2000, 2000))
    private fun environment() = MutableStateFlow(UpdateSession.Environment(Attention.AMBIENT, false, false))
    private fun catalog(url: String) = fixture.catalog(url = url).apply {
        getJSONObject("release").put("size", payload.size).put("sha256", UpdateCatalog.sha256(payload))
    }
    private fun session(root: File, transport: Fetcher): UpdateSession {
        val ledger = UpdateSequenceStore(File(root, "ledger"))
        val client = UpdateCatalogClient(transport, keys, ledger, UpdateCatalogCache(File(root, "metadata"), keys))
        return UpdateSession("stable", client, UpdateState(File(root, "workflow"), ledger), File(root, "downloads"),
            verifier, { fixture.device }, { UpdateState.Time(7, 1000, 1_000_000) })
    }

    private fun withOrigin(block: (UpdateTestOrigin, ByteArray) -> Unit) {
        val signed = AtomicReference(byteArrayOf()); val signature = AtomicReference(byteArrayOf())
        UpdateTestOrigin { exchange ->
            when (exchange.requestURI.path) {
                "/app.apk" -> UpdateTestOrigin.artifact(exchange, payload)
                "/stable.json.sig" -> UpdateTestOrigin.reply(exchange, signature.get())
                else -> UpdateTestOrigin.reply(exchange, signed.get())
            }
        }.use { source ->
            signed.set(UpdateFixture.bytes(catalog(source.url("/app.apk")))); signature.set(fixture.sign(signed.get()))
            block(source, signed.get())
        }
    }

    @Test(timeout = 15_000) fun checkDownloadReadyAndOfflineReopenUseOneDurableProductionWorkflow() = runBlocking<Unit> {
        val root = temporary.newFolder()
        withOrigin { source, _ -> runBlocking {
            fetcher().use { transport ->
                val first = session(root, transport)
                assertEquals("IDLE", first.restore().phase)
                first.check(listOf(source.url("/stable.json")))
                assertEquals("AVAILABLE", first.status.value.phase)
                val verified = first.download(false, environment())!!
                assertEquals(payload.size.toLong(), verified.bytes); assertEquals("READY", first.status.value.phase)
                assertEquals(payload.size.toLong(), first.status.value.downloaded)
                val hits = source.hits.size
                assertEquals("READY", session(root, transport).restore().phase)
                assertEquals(hits, source.hits.size); assertEquals(2, parses.get())
            }
        } }
    }

    @Test(timeout = 15_000) fun damagedReadyBytesAndDirectCheckAfterRestartCannotSkipRevalidation() = runBlocking<Unit> {
        val root = temporary.newFolder()
        withOrigin { source, _ -> runBlocking { fetcher().use { transport ->
            val first = session(root, transport); first.check(listOf(source.url("/stable.json")))
            val file = first.download(false, environment())!!.file
            file.writeBytes(payload.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() })
            val restored = session(root, transport)
            restored.check(listOf(source.url("/stable.json"))) // Intentionally no explicit restore() call.
            assertEquals("AVAILABLE", restored.status.value.phase); assertEquals(1, parses.get())
            restored.download(false, environment())
            assertEquals("READY", restored.status.value.phase); assertEquals(2, parses.get())
        } } }
    }

    @Test(timeout = 15_000) fun manualDownloadsOverrideAttentionButNeverTheVideoOrCastGate() = runBlocking<Unit> {
        val root = temporary.newFolder()
        withOrigin { source, _ -> runBlocking { fetcher().use { transport ->
            val session = session(root, transport); session.check(listOf(source.url("/stable.json")))
            val env = environment(); env.value = env.value.copy(attention = Attention.AWAKE)
            assertNull(session.download(false, env))
            env.value = env.value.copy(video = true); assertNull(session.download(true, env))
            env.value = env.value.copy(video = false, casting = true); assertNull(session.download(true, env))
            assertFalse(source.hits.any { it.path == "/app.apk" })
            env.value = env.value.copy(casting = false)
            assertNotNull(session.download(true, env)); assertEquals("READY", session.status.value.phase)
        } } }
    }

    @Test(timeout = 20_000) fun liveVideoGateClosesAStalledTransferAndAReopenedSessionResumesItsPrefix() = runBlocking<Unit> {
        val root = temporary.newFolder(); val entered = CountDownLatch(1); val release = CountDownLatch(1); val stall = AtomicBoolean(true)
        val signed = AtomicReference(byteArrayOf()); val signature = AtomicReference(byteArrayOf())
        UpdateTestOrigin { exchange ->
            when (exchange.requestURI.path) {
                "/stable.json.sig" -> UpdateTestOrigin.reply(exchange, signature.get())
                "/stable.json" -> UpdateTestOrigin.reply(exchange, signed.get())
                else -> if (stall.compareAndSet(true, false)) {
                    exchange.responseHeaders.add("Content-Range", "bytes 0-1048575/${payload.size}")
                    exchange.sendResponseHeaders(206, 1_048_576)
                    exchange.responseBody.write(payload, 0, 65_536); exchange.responseBody.flush(); entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                } else UpdateTestOrigin.artifact(exchange, payload)
            }
        }.use { source -> fetcher().use { transport ->
            signed.set(UpdateFixture.bytes(catalog(source.url("/app.apk")))); signature.set(fixture.sign(signed.get()))
            val first = session(root, transport); first.check(listOf(source.url("/stable.json")))
            val env = environment()
            val transfer = async { first.download(false, env) }
            val partial = File(root, "downloads/${UpdateCatalog.sha256(payload)}.apk.part")
            withTimeout(5000) { while (entered.count > 0 || partial.length() < 65_536) delay(5) }
            env.value = env.value.copy(video = true)
            assertNull(transfer.await()); release.countDown()
            assertEquals("AVAILABLE", first.status.value.phase); assertEquals(65_536L, first.status.value.downloaded)
            val resumed = session(root, transport); assertEquals("AVAILABLE", resumed.restore().phase)
            env.value = env.value.copy(video = false)
            assertNotNull(resumed.download(false, env)); assertEquals("READY", resumed.status.value.phase)
            val artifactHits = source.hits.filter { it.path == "/app.apk" }
            assertEquals(listOf("bytes=65536-1114111"), artifactHits[1].headers["range"])
        } }
    }

    @Test(timeout = 15_000) fun anInterruptedInstallationIsNotAutomaticallyResurrectedAsReady() = runBlocking<Unit> {
        val root = temporary.newFolder()
        withOrigin { source, raw -> runBlocking { fetcher().use { transport ->
            val first = session(root, transport); first.check(listOf(source.url("/stable.json")))
            val verified = first.download(false, environment())!!
            val catalog = UpdateCatalog.read(raw, fixture.sign(raw), keys, "stable")
            val ledger = UpdateSequenceStore(File(root, "ledger")); val state = UpdateState(File(root, "workflow"), ledger)
            state.installing(catalog, verified)
            val restored = session(root, transport).restore()
            assertEquals("FAILED", restored.phase); assertEquals("interrupted", restored.error)
        } } }
    }
}
