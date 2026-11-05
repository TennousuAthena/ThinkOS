package moe.kiwi.thinkos

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.update.*
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Full production workflow under the app UID. Only disposable public test keys
 * are persisted so a new ART process can authenticate the old cache. No private key leaves RAM. */
internal class UpdateSessionProbe(private val context: Context, private val directory: File) {
    private val root = File(directory, "workflow")
    private val downloads = File(root, "downloads")
    private val sourceApk = File(directory, "packages/valid.apk")
    private val fixtureInfo get() = JSONObject(File(directory, "packages/fixtures.json").readText())
    private fun environment() = MutableStateFlow(UpdateSession.Environment(Attention.AMBIENT, false, false))
    @Suppress("DEPRECATION")
    private fun device(): UpdateCatalog.Device {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        return UpdateCatalog.Device(android.os.Build.DEVICE, android.os.Build.VERSION.SDK_INT, info.versionCode,
            UpdateCatalog.sha256(info.signatures!!.single().toByteArray()))
    }
    private fun now(): UpdateState.Time {
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1).takeIf { it >= 0 }?.toLong()
        return UpdateState.Time(boot, SystemClock.elapsedRealtime(), WallClock.now().takeIf { it >= 1_704_067_200_000L })
    }
    private fun socket(port: Int = 0) = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
    }
    private fun ledger() = UpdateSequenceStore(File(root, "ledger"))
    private fun cache(keys: UpdateKeys) = UpdateCatalogCache(File(root, "metadata"), keys)
    private fun state() = UpdateState(File(root, "state"), ledger())
    private fun session(fetcher: Fetcher, keys: UpdateKeys) = UpdateSession("stable",
        UpdateCatalogClient(fetcher, keys, ledger(), cache(keys)), state(), downloads,
        UpdatePackageVerifier.forContext(context), ::device, ::now)

    suspend fun prepare(): Unit = coroutineScope {
        root.mkdirs()
        val fixture = UpdateFixture()
        File(root, "public-keys.json").writeBytes(UpdateFixture.bytes(fixture.production()))
        val keys = UpdateKeys.parse(File(root, "public-keys.json").readBytes())
        val payload = sourceApk.readBytes()
        val files = ConcurrentHashMap<String, ByteArray>()
        val release = CountDownLatch(1)
        UpdateTestOrigin(socket()) { exchange ->
            if (exchange.requestURI.path == "/app.apk") {
                exchange.responseHeaders.add("Content-Range", "bytes 0-1048575/${payload.size}")
                exchange.sendResponseHeaders(206, 1_048_576)
                exchange.responseBody.write(payload, 0, 65_536); exchange.responseBody.flush()
                release.await()
            } else {
                if (exchange.requestURI.path == "/stable.json") exchange.responseHeaders.add("ETag", "\"workflow\"")
                UpdateTestOrigin.reply(exchange, files.getValue(exchange.requestURI.path))
            }
        }.use { origin ->
            val doc = fixture.catalog(101, url = origin.url("/app.apk")).apply {
                getJSONObject("release").put("version", fixtureInfo.getString("version")).put("code", fixtureInfo.getInt("code"))
                    .put("signer", device().signer).put("sha256", UpdateCatalog.sha256(payload)).put("size", payload.size)
            }
            val raw = UpdateFixture.bytes(doc); files["/stable.json"] = raw; files["/stable.json.sig"] = fixture.sign(raw)
            Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(5000, 60_000)).use { fetcher ->
                val workflow = session(fetcher, keys)
                workflow.check(listOf(origin.url("/stable.json")))
                async { workflow.download(false, environment()) }
                val hash = doc.getJSONObject("release").getString("sha256")
                val part = File(downloads, "$hash.apk.part")
                withTimeout(25_000) { while (part.length() != 65_536L) delay(20) }
                check(workflow.status.value.phase == "DOWNLOADING" && cache(keys).read("stable") != null)
                File(directory, "workflow-checkpoint").writeText(JSONObject().put("pid", Process.myPid()).put("bytes", 65_536)
                    .put("sha256", hash).put("port", java.net.URI(origin.url()).port).put("seq", 101).toString())
                awaitCancellation()
            }
        }
    }

    suspend fun resume(report: JSONObject, test: suspend (String, suspend () -> Unit) -> Unit) {
        val checkpoint = JSONObject(File(directory, "workflow-checkpoint").readText())
        check(checkpoint.getInt("pid") != Process.myPid())
        val keysRaw = File(root, "public-keys.json").readBytes(); check(keysRaw.size <= 64*1024)
        val keys = UpdateKeys.parse(keysRaw)
        val cached = cache(keys).read("stable")!!
        check(cached.catalog.document.seq == 101L && ledger().snapshot().stable!!.seq == 101L)
        val payload = sourceApk.readBytes()
        val stalled = AtomicBoolean(false)
        val stallRelease = AtomicReference(CountDownLatch(0))
        val port = checkpoint.getInt("port"); check(port in 1..65535)
        UpdateTestOrigin(socket(port)) { exchange ->
            when (exchange.requestURI.path) {
                "/stable.json" -> {
                    if (exchange.requestHeaders.getFirst("If-None-Match") == "\"workflow\"") UpdateTestOrigin.reply(exchange, byteArrayOf(), 304)
                    else UpdateTestOrigin.reply(exchange, cached.catalog.bytes())
                }
                "/stable.json.sig" -> UpdateTestOrigin.reply(exchange, cached.catalog.signature())
                else -> if (stalled.compareAndSet(true, false)) {
                    exchange.responseHeaders.add("Content-Range", "bytes 0-1048575/${payload.size}")
                    exchange.sendResponseHeaders(206, 1_048_576)
                    exchange.responseBody.write(payload, 0, 65_536); exchange.responseBody.flush()
                    stallRelease.get().await()
                } else UpdateTestOrigin.artifact(exchange, payload)
            }
        }.use { origin -> Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(5000, 60_000)).use { fetcher ->
            val workflow = session(fetcher, keys)
            test("new ART process authenticates cached metadata and recovers the interrupted workflow") {
                val recovered = workflow.restore()
                check(recovered.phase == "AVAILABLE" && recovered.downloaded == 65_536L && recovered.seq == 101L)
                check(origin.hits.isEmpty())
            }
            test("durable workflow resumes a real full APK at byte 65536 and reaches native-verified READY") {
                check(workflow.download(false, environment())!!.sha256 == checkpoint.getString("sha256"))
                check(workflow.status.value.phase == "READY")
                check(origin.hits.first().headers["range"] == listOf("bytes=65536-1114111"))
            }
            test("persisted READY is reverified offline while preserving its monotonic ready time") {
                val before = workflow.status.value
                val count = origin.hits.size
                val restored = session(fetcher, keys).restore()
                check(restored.phase == "READY" && restored.ready_elapsed == before.ready_elapsed && origin.hits.size == count)
            }
            test("damaged READY bytes cannot authorize an update after process recovery") {
                val file = File(downloads, checkpoint.getString("sha256")+".apk")
                val damaged = file.readBytes().apply { this[0] = (this[0].toInt() xor 1).toByte() }
                file.writeBytes(damaged)
                check(session(fetcher, keys).restore().phase == "AVAILABLE")
                file.writeBytes(payload)
                check(workflow.download(false, environment()) != null)
            }
            test("tampered cached signature never restores READY or changes the sequence floor") {
                val file = File(root, "metadata/catalog-stable.json"); val original = file.readBytes()
                file.writeText(JSONObject(original.toString(Charsets.UTF_8)).put("signature", "AAAA").toString())
                check(session(fetcher, keys).restore().phase == "IDLE" && ledger().snapshot().stable!!.seq == 101L)
                file.writeBytes(original)
                workflow.restore(); workflow.download(false, environment())
            }
            test("authenticated same-source ETag returns 304 without fetching a replacement signature") {
                val count = origin.hits.size
                val checked = workflow.check(listOf(origin.url("/stable.json")))
                check(checked.origin == UpdateCatalogClient.Origin.NOT_MODIFIED && origin.hits.size == count+1)
                check(origin.hits.last().headers["if-none-match"] == listOf("\"workflow\""))
            }
            test("live video gate cancels and preserves a real in-device transfer before later resume") {
                File(downloads, checkpoint.getString("sha256")+".apk").delete()
                workflow.restore()
                stallRelease.set(CountDownLatch(1)); stalled.set(true)
                val environment = environment()
                coroutineScope {
                    val transfer = async { workflow.download(true, environment) }
                    val partial = File(downloads, checkpoint.getString("sha256")+".apk.part")
                    withTimeout(25_000) { while (partial.length() != 65_536L) delay(20) }
                    environment.value = environment.value.copy(video = true)
                    check(transfer.await() == null && workflow.status.value.phase == "AVAILABLE" && partial.length() == 65_536L)
                    stallRelease.get().countDown()
                }
                environment.value = environment.value.copy(video = false)
                check(workflow.download(false, environment) != null && workflow.status.value.phase == "READY")
            }
            report.put("workflow_recovered_seq", 101).put("workflow_original_pid", checkpoint.getInt("pid"))
                .put("workflow_final_phase", workflow.status.value.phase)
        } }
    }
}
