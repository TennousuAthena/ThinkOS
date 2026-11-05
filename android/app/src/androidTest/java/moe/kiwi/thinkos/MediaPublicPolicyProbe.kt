package moe.kiwi.thinkos

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.media.MediaEntry
import moe.kiwi.thinkos.media.MediaProbe
import moe.kiwi.thinkos.media.StoragePermission
import moe.kiwi.thinkos.plugin.CapabilityAwait
import moe.kiwi.thinkos.plugin.MediaCapability
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual API27 production capability/probe, real system DNS, loopback TCP only.
 * No playback/decoder, public origin, synthetic DNS, TLS certificate bypass,
 * key/configuration override, activity launch or user/store write. The driver
 * owns foreground/input/installed-APK checks and restoration across instrumentation.
 * This phase does not claim public HTTPS, redirect, HLS, DASH or subtitle success.
 */
internal object MediaPublicPolicyProbe {
    private val immutable = listOf("settings/local.json", "store/welcome.json",
        "cast/identity-seed", "cast/airplay-identity", "management-exit")
    private val pending = listOf("pending.json", "pending.json.bak", "module-pending.json", "module-pending.json.bak")
    private const val SAMPLE_SIZE = 16300
    private const val SAMPLE_SHA256 = "fcc06fb1849c1f725e4c7d7f7dcac5481c60e2b6f659cd504409767e96fd71db"

    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit) {
        check(token.matches(Regex("[a-f0-9]{32}")))
        val context = runner.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun quiet() = check((1..5).all { audio.getStreamVolume(it) == 0 }) { "Audio streams 1-5 must remain zero" }
        val area = File(directory, "media-public-policy")
        check(!area.exists() && area.mkdirs()) { "Use a fresh owned run_id" }
        val original = immutable.associateWith { name ->
            File(context.filesDir, name).let { file ->
                if (!file.exists()) null else { check(file.isFile && file.length() <= 8 * 1024 * 1024); file.readBytes() }
            }
        }
        fun filesUnchanged() = original.all { (name, before) ->
            val file = File(context.filesDir, name)
            if (before == null) !file.exists() else file.isFile && file.length() == before.size.toLong() && file.readBytes().contentEquals(before)
        }
        val report = JSONObject().put("scope",
            "Production typed media capability and MediaProbe.create on API27; real system resolver/initial private-address admission and ordinary loopback HTTP compatibility only")
            .put("not_covered", JSONArray(listOf("public HTTPS/TLS success", "public peer socket success", "mixed DNS",
                "redirects", "HLS children", "DASH", "subtitles", "codec decoding", "Lua/JNI permission grant")))
            .put("network", "loopback only").put("playback", false).put("cases", JSONArray())
        result.put("media_public_policy", report)
        val cases = report.getJSONArray("cases")
        var baseline: Map<String, Any?>? = null
        var v4: LoopbackOrigin? = null
        var v6: LoopbackOrigin? = null
        val jobs = Collections.synchronizedList(mutableListOf<CapabilityAwait>())
        val permissionLaunches = AtomicInteger()
        val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ThinkOS-MediaPolicy-" + token.take(8)).apply { isDaemon = true }
        }
        var host: RuntimeHost? = null
        try {
            test("media public-policy preflight has five silent streams, no session or update transaction, and genuine silent MP3 bytes") {
                quiet()
                check(Process.myUid() >= 10_000)
                check(pending.none { File(context.filesDir, "update/" + it).exists() }) { "Preserve an existing update record" }
                val actual = withContext(Dispatchers.Main) { (context.applicationContext as ThinkApplication).runtime }
                host = actual
                baseline = withContext(Dispatchers.Main) {
                    check(!actual.router.onboardingOpen && !actual.store.current.safeMode)
                    val state = actual.media.snapshot()
                    check(state["status"] == "stopped" && state["owner"].toString().isEmpty() &&
                        (state["count"] as Number).toInt() == 0 && state["kind"].toString().isEmpty()) { "Preserve an existing media/cast session" }
                    val update = actual.updates.status.value
                    check(!actual.updates.screensSnapshot().settings && !update.busy && !update.checking && update.state.phase !in
                        setOf("CHECKING", "DOWNLOADING", "READY", "INSTALLING"))
                    state
                }
                val sample = sample()
                check(sample.size == SAMPLE_SIZE && sha(sample) == SAMPLE_SHA256)
                check(filesUnchanged())
                report.put("sample_bytes", sample.size).put("sample_sha256", SAMPLE_SHA256)
                    .put("sample_provenance", "Existing tools/media/icy.py silent_mp3, local FFmpeg anullsrc, 2 seconds; byte probe only, no codec claim")
                quiet()
            }
            runner.checkedSnapshot()
            val actual = requireNotNull(host)
            val expected = requireNotNull(baseline)
            suspend fun unchanged() {
                quiet()
                check(withContext(Dispatchers.Main) { actual.media.snapshot() } == expected) { "The current media session changed" }
                check(withContext(Dispatchers.Main) {
                    val update = actual.updates.status.value
                    !update.busy && !update.checking && update.state.phase !in setOf("CHECKING", "DOWNLOADING", "READY", "INSTALLING")
                }) { "An update operation started; stop this network fixture" }
                check(pending.none { File(context.filesDir, "update/" + it).exists() })
                check(filesUnchanged())
            }
            val sample = sample()
            v4 = LoopbackOrigin("127.0.0.1", sample)
            val ipv4 = requireNotNull(v4)
            val capability = withContext(Dispatchers.Main) {
                MediaCapability(context, actual.media, StoragePermission(context) {
                    permissionLaunches.incrementAndGet(); false
                }, io, Handler(Looper.getMainLooper()))
            }
            // Exact production constructor, no injected DNS/client/fetch. The
            // typed capability also lazily creates its real production probe.
            val direct = MediaProbe.create(actual.media::allowsPublicCleartext)
            suspend fun throughBridge(url: String, options: Map<String, Any?>?): Map<*, *> {
                val done = CompletableDeferred<Array<Any?>>()
                val job = CapabilityAwait({ true }) { done.complete(it) }
                jobs += job
                try {
                    capability.call("media-public-policy-probe", setOf("media", "network"), "media.probe",
                        arrayOf(url, options), job, 10)
                    val answer = withTimeout(10_000) { done.await() }
                    check(answer.size == 2 && answer[1] == null)
                    return answer[0] as? Map<*, *> ?: error("The real capability did not return a probe verdict")
                } finally { if (!job.isDone()) job.cancel(true) }
            }
            suspend fun ordinary(origin: LoopbackOrigin, viaBridge: Boolean) {
                origin.settle()
                val before = origin.accepted.get()
                val requests = origin.requests.get()
                val verdict = if (viaBridge) throughBridge(origin.url("http"), mapOf("public_only" to false))
                    else withContext(Dispatchers.IO) { direct.run(MediaEntry(origin.url("http"))) }
                check(verdict["ok"] == true && verdict["status"] == 200L && verdict["kind"] == "stream" && !verdict.containsKey("error"))
                origin.settle()
                check(origin.accepted.get() == before + 1 && origin.requests.get() == requests + 1) {
                    "A positive control must prove exactly one real loopback TCP/HTTP exchange"
                }
                cases.put(JSONObject().put("family", origin.family).put("request", "ordinary_http")
                    .put("public_only", if (viaBridge) false else JSONObject.NULL)
                    .put("ok", true).put("tcp_delta", 1).put("http_delta", 1))
                unchanged()
            }
            test("ordinary omitted public_only uses actual loopback HTTP and leaves the current session untouched") {
                unchanged(); ordinary(ipv4, false)
            }
            runner.checkedSnapshot()
            test("public_only false uses the real typed capability and preserves ordinary LAN HTTP") {
                ordinary(ipv4, true)
            }
            runner.checkedSnapshot()
            test("strict HTTP, file, content and RTSP are policy failures before any loopback TCP connection") {
                for (url in listOf(ipv4.url("http"), File(area, "unopened.mp3").toURI().toASCIIString(),
                    "content://media-public-policy-unopened/1", "rtsp://127.0.0.1:" + ipv4.port + "/silent.mp3")) {
                    unchanged(); ipv4.settle()
                    val before = ipv4.accepted.get()
                    val verdict = throughBridge(url, mapOf("public_only" to true))
                    check(verdict["ok"] == false && verdict["status"] == 0L && verdict["error"] == "policy")
                    ipv4.settle()
                    check(ipv4.accepted.get() == before) { "Strict admission opened TCP before denying the resource" }
                    cases.put(JSONObject().put("family", "ipv4").put("request", java.net.URI(url).scheme)
                        .put("public_only", true).put("error", "policy").put("tcp_delta", 0))
                }
                unchanged()
            }
            runner.checkedSnapshot()
            test("strict private HTTPS and localhost are policy failures with zero TCP accepts, including no TLS handshake") {
                for (url in listOf(ipv4.url("https"), "https://localhost:" + ipv4.port + "/silent.mp3")) {
                    unchanged(); ipv4.settle()
                    val before = ipv4.accepted.get()
                    val verdict = throughBridge(url, mapOf("public_only" to true))
                    check(verdict["ok"] == false && verdict["status"] == 0L && verdict["error"] == "policy")
                    ipv4.settle()
                    check(ipv4.accepted.get() == before) { "No HTTP request is insufficient: strict mode must not establish private TLS TCP" }
                    cases.put(JSONObject().put("family", "ipv4").put("request", "private_https")
                        .put("public_only", true).put("error", "policy").put("tcp_delta", 0))
                }
                // Another genuine positive connection proves the accept loop
                // remained alive while strict requests were denied.
                ordinary(ipv4, false)
                unchanged()
            }
            runner.checkedSnapshot()
            val ipv6 = try { LoopbackOrigin("::1", sample).also { v6 = it } }
                catch (error: BindException) { report.put("ipv6", "skipped: loopback bind unavailable"); null }
                catch (error: SocketException) {
                    if (listOf("EAFNOSUPPORT", "EPROTONOSUPPORT", "EADDRNOTAVAIL").any { error.message?.contains(it) == true }) {
                        report.put("ipv6", "skipped: loopback address family unavailable"); null
                    } else throw error
                }
            if (ipv6 != null) {
                test("available IPv6 loopback works ordinarily and real system-resolved strict HTTPS fails before TCP") {
                    ordinary(ipv6, false)
                    ipv6.settle()
                    val before = ipv6.accepted.get()
                    val verdict = throughBridge(ipv6.url("https"), mapOf("public_only" to true))
                    check(verdict["ok"] == false && verdict["status"] == 0L && verdict["error"] == "policy")
                    ipv6.settle()
                    check(ipv6.accepted.get() == before) { "Strict IPv6 private admission must not start TLS TCP" }
                    ordinary(ipv6, true)
                    report.put("ipv6", "executed")
                    cases.put(JSONObject().put("family", "ipv6").put("request", "private_https")
                        .put("public_only", true).put("error", "policy").put("tcp_delta", 0))
                    unchanged()
                }
                runner.checkedSnapshot()
            }
            test("completed local probes require no permission UI and preserve immutable user files, update records and all media state") {
                check(permissionLaunches.get() == 0 && jobs.all { it.isDone() && !it.isCancelled() })
                unchanged()
                report.put("session_unchanged", true).put("five_immutable_files_unchanged", true)
                    .put("audio_streams_1_to_5_zero", true).put("no_permission_ui", true)
            }
            runner.checkedSnapshot()
        } finally {
            synchronized(jobs) { jobs.forEach { if (!it.isDone()) it.cancel(true) } }
            var cleanup: Throwable? = null
            for (origin in listOfNotNull(v6, v4)) try { origin.close() } catch (error: Throwable) { cleanup = cleanup ?: error }
            io.shutdownNow()
            check(withContext(Dispatchers.IO) { io.awaitTermination(3, TimeUnit.SECONDS) }) { "An owned capability worker did not stop" }
            report.put("listeners_closed", listOfNotNull(v6, v4).all { it.closed() })
                .put("owned_worker_closed", true).put("owned_tcp_accepts", (v4?.accepted?.get() ?: 0) + (v6?.accepted?.get() ?: 0))
            area.resolve("observations.json").writeText(report.toString(2))
            quiet()
            check(filesUnchanged()) { "An immutable user file changed; do not overwrite it" }
            baseline?.let { saved -> host?.let { actual ->
                check(withContext(Dispatchers.Main) { actual.media.snapshot() } == saved) { "Media state changed during final cleanup" }
            } }
            cleanup?.let { throw it }
        }
    }

    /** Accept count is incremented before any HTTP parsing, so a private TLS
     * ClientHello would count too. The origin never provides TLS or a trust
     * bypass. Its only valid response is one bounded silent MP3 GET.
     */
    private class LoopbackOrigin(private val host: String, private val sample: ByteArray) : AutoCloseable {
        private val listener = ServerSocket()
        private val running = AtomicBoolean(true)
        private val active = AtomicReference<Socket?>()
        private val failure = AtomicReference<Throwable?>()
        private val idle = AtomicInteger()
        val accepted = AtomicInteger()
        val requests = AtomicInteger()
        val family = if (host == "::1") "ipv6" else "ipv4"
        val port: Int
        private val thread: Thread
        init {
            try {
                check(host == "127.0.0.1" || host == "::1")
                listener.reuseAddress = false
                listener.soTimeout = 125
                listener.bind(InetSocketAddress(InetAddress.getByName(host), 0), 4)
                port = listener.localPort
                check(port in 1..65535 && listener.inetAddress.isLoopbackAddress)
                thread = Thread({ serve() }, "ThinkOS-PolicyOrigin-" + family).apply { isDaemon = true; start() }
            } catch (error: Throwable) { listener.close(); throw error }
        }
        fun url(scheme: String): String {
            check(scheme == "http" || scheme == "https")
            return scheme + "://" + (if (family == "ipv6") "[" + host + "]" else host) + ":" + port + "/silent.mp3"
        }
        suspend fun settle() {
            val start = idle.get()
            withTimeout(3_000) {
                while (idle.get() < start + 2 || active.get() != null) {
                    failure.get()?.let { throw it }
                    check(thread.isAlive && running.get())
                    delay(20)
                }
            }
            failure.get()?.let { throw it }
        }
        private fun serve() {
            try {
                while (running.get()) {
                    val socket = try { listener.accept() } catch (_: SocketTimeoutException) { idle.incrementAndGet(); continue }
                    if (accepted.incrementAndGet() > 12) {
                        socket.close()
                        error("The bounded origin received too many TCP connections")
                    }
                    active.set(socket)
                    try {
                        socket.use { client ->
                            client.soTimeout = 750
                            val input = client.getInputStream()
                            val head = ByteArrayOutputStream()
                            var last = ""
                            while (head.size() < 4096 && !last.endsWith("\r\n\r\n")) {
                                val value = input.read()
                                if (value < 0 || value !in 9..13 && value !in 32..126) break
                                head.write(value)
                                last = (last + value.toChar()).takeLast(4)
                            }
                            if (last == "\r\n\r\n" && head.toString("US-ASCII").substringBefore("\r\n") == "GET /silent.mp3 HTTP/1.1") {
                                requests.incrementAndGet()
                                val header = "HTTP/1.1 200 OK\r\nContent-Type: audio/mpeg\r\nContent-Length: " +
                                    sample.size + "\r\nConnection: close\r\n\r\n"
                                client.getOutputStream().apply { write(header.toByteArray(Charsets.US_ASCII)); write(sample); flush() }
                            }
                        }
                    } catch (_: SocketTimeoutException) {
                        // A malformed or TLS client still increments accepted;
                        // never turn its lack of an HTTP GET into a false pass.
                    } catch (error: SocketException) { if (running.get()) throw error }
                    finally { active.compareAndSet(socket, null) }
                }
            } catch (error: Throwable) { if (running.get()) failure.compareAndSet(null, error) }
        }
        fun closed() = listener.isClosed && !thread.isAlive && active.get() == null
        override fun close() {
            running.set(false)
            listener.close()
            active.getAndSet(null)?.close()
            thread.join(2_000)
            check(closed()) { "An owned loopback origin did not close" }
            failure.get()?.let { throw it }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun sample(): ByteArray = Base64.decode(SILENT_MP3, Base64.DEFAULT)
    // Generated by the repository's existing icy.silent_mp3 with local FFmpeg
    // anullsrc; fixed bytes/hash above. No external media or encoder executes
    // on the device, and this phase never invokes playback or a decoder.
    private const val SILENT_MP3 = """
//tQZAAP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAETEFNRVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmRWD/AA
AGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgA
AA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEA
AAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAg
AAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAE
VVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVV
VUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQu
MFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
Vf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP
8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAA
CAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAA
AQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAA
ACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAA
AARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVV
VVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1F
NC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1Jk
qQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkA
AAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0g
AAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGk
AAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0
gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVV
VVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxB
TUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7
UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAA
aQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAA
DSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAA
AaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAA
ADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARV
VVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVV
TEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4w
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/w
AABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAI
AAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAAB
AAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAA
IAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAA
BFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVV
VUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVTEFNRTQu
MFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVf/7UGSpj/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVMQU1FNC4wVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
Vf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP
8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAA
CAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAA
AQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAA
ACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAA
AARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVV
VVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1F
NC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1Jk
qQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkA
AAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0g
AAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGk
AAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0
gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVV
VVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxB
TUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7
UmSpD/AAAGkAAAAIAAANIAAAAQAAAaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAA
aQAAAAgAAA0gAAABAAABpAAAACAAADSAAAAEVVVVVVVVVVVMQU1FNC4wVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAA
DSAAAAEAAAGkAAAAIAAANIAAAARVVVVVVVVVVUxBTUU0LjBVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVf/7UmSpD/AAAGkAAAAIAAANIAAAAQAA
AaQAAAAgAAA0gAAABFVVVVVVVVVVTEFNRTQuMFVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV//tSZKkP8AAAaQAAAAgAAA0gAAABAAABpAAAACAA
ADSAAAAEVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVX/+1JkqQ/wAABpAAAACAAADSAAAAEAAAGkAAAAIAAANIAAAARV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVV
VVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVVQ==
"""
}
