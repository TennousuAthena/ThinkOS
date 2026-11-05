package moe.kiwi.thinkos

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import moe.kiwi.thinkos.cast.CastHost
import moe.kiwi.thinkos.cast.EngineCastPlayer
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.media.MediaEntry
import moe.kiwi.thinkos.media.MediaOptions
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.shell.KioskState
import moe.kiwi.thinkos.update.*
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/** Actual RuntimeHost/MediaEngine callbacks feed the production controller and
 * session. Signing keys remain in RAM; all artifacts belong to the test cache. */
internal class UpdateRuntimeProbe(private val instrumentation: Instrumentation, private val directory: File) {
    private val context = instrumentation.targetContext
    private suspend fun until(predicate: suspend () -> Boolean) = withTimeout(40_000) { while (!predicate()) delay(20) }

    @Suppress("DEPRECATION")
    suspend fun run(result: JSONObject, test: suspend (String, suspend () -> Unit) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val host = withContext(Dispatchers.Main) { (context.applicationContext as ThinkApplication).runtime }
        val initialPolicy = withContext(Dispatchers.Main) { host.system.updatePolicy }
        val payload = File(directory, "packages/valid.apk").readBytes()
        val fixtureInfo = JSONObject(File(directory, "packages/fixtures.json").readText())
        val silent = instrumentation.context.assets.open("runtime-video.mp4").use { it.readBytes() }
        val fixture = UpdateFixture()
        val keys = UpdateKeys.parse(UpdateFixture.bytes(fixture.production()))
        val root = File(directory, "runtime")
        val files = ConcurrentHashMap<String, ByteArray>()
        val hold = CountDownLatch(1)
        val first = AtomicBoolean(true)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var controller: UpdateController? = null
        UpdateTestOrigin { exchange ->
            when (val path = exchange.requestURI.path) {
                "/app.apk" -> {
                    val range = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(exchange.requestHeaders.getFirst("Range").orEmpty())!!
                    val start = range.groupValues[1].toInt(); val end = range.groupValues[2].toInt()
                    exchange.responseHeaders.add("Content-Range", "bytes $start-$end/${payload.size}")
                    exchange.sendResponseHeaders(206, (end - start + 1).toLong())
                    if (first.getAndSet(false)) {
                        exchange.responseBody.write(payload, start, 65_536); exchange.responseBody.flush(); hold.await()
                        exchange.responseBody.write(payload, start + 65_536, end - start + 1 - 65_536)
                    } else exchange.responseBody.write(payload, start, end - start + 1)
                    exchange.responseBody.close()
                }
                "/video.mp4" -> UpdateTestOrigin.reply(exchange, silent)
                else -> UpdateTestOrigin.reply(exchange, files.getValue(path))
            }
        }.use { origin ->
            val installed = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            val device = UpdateCatalog.Device(android.os.Build.DEVICE, 27, installed.versionCode,
                UpdateCatalog.sha256(installed.signatures!!.single().toByteArray()))
            val document = fixture.catalog(201, url = origin.url("/app.apk")).apply {
                getJSONObject("release").put("version", fixtureInfo.getString("version")).put("code", fixtureInfo.getInt("code"))
                    .put("signer", device.signer).put("sha256", UpdateCatalog.sha256(payload)).put("size", payload.size)
            }
            val raw = UpdateFixture.bytes(document)
            files["/stable.json"] = raw; files["/stable.json.sig"] = fixture.sign(raw)
            val policy = MutableStateFlow(initialPolicy.copy(enabled = false, channel = "stable", sources = listOf(origin.url("/stable.json"))))
            val ready = MutableStateFlow(UpdateController.Availability(true, true))
            val c = UpdateController(scope, policy, host.updates.environment, ready, SystemClock::elapsedRealtime, { config ->
                val ledger = UpdateSequenceStore(File(root, "ledger"))
                val fetcher = Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(config.connectMillis, config.readMillis))
                UpdateRuntime.SessionWork(UpdateSession(config.channel,
                    UpdateCatalogClient(fetcher, keys, ledger, UpdateCatalogCache(File(root, "metadata"), keys)),
                    UpdateState(File(root, "state"), ledger), File(root, "downloads"), UpdatePackageVerifier.forContext(context), { device },
                    { UpdateState.Time(null, SystemClock.elapsedRealtime(), null) }), fetcher)
            })
            controller = c
            val part = File(root, "downloads/${document.getJSONObject("release").getString("sha256")}.apk.part")
            try {
                withContext(Dispatchers.Main) { host.sensors.debugHoldAttention(Attention.ENGAGED); c.start() }
                test("runtime settings listener changes channel; absent deployment keys stay fail-closed") {
                    until { host.updates.status.value.error == "keys" }
                    check(!host.updates.status.value.configured)
                    withContext(Dispatchers.Main) { host.settings.set(SettingsStore.SYSTEM, "update.channel", "beta") }
                    until { host.updates.status.value.state.channel == "beta" && host.updates.status.value.error == "keys" }
                    check(!File(context.filesDir, "update").exists())
                    withContext(Dispatchers.Main) { host.settings.set(SettingsStore.SYSTEM, "update.channel", initialPolicy.channel) }
                    result.put("actual_network_connected", host.updates.readiness.value.connected)
                    check(host.updates.readiness.value.connected)
                }
                test("fresh manual check works with automatic updates off; stale taps cannot request network") {
                    until { c.status.value.configured }
                    check(!withContext(Dispatchers.Main) { c.manual(UpdateController.Action.CHECK, SystemClock.elapsedRealtime() - 5001) })
                    check(withContext(Dispatchers.Main) { c.manual(UpdateController.Action.CHECK, SystemClock.elapsedRealtime()) })
                    until { c.status.value.state.phase == "AVAILABLE" && !c.status.value.busy }
                    check(origin.hits.count { it.path == "/stable.json" } == 1)
                }
                test("a real video session cancels a live update socket and preserves its 64KiB prefix") {
                    check(withContext(Dispatchers.Main) { c.manual(UpdateController.Action.DOWNLOAD, SystemClock.elapsedRealtime()) })
                    until { part.length() == 65_536L }
                    withContext(Dispatchers.Main) { host.media.load("ota-runtime-probe", listOf(MediaEntry(origin.url("/video.mp4"), kind = "video")), MediaOptions(autoplay = true)) }
                    until { host.updates.environment.value.video && !c.status.value.busy && c.status.value.state.phase == "AVAILABLE" }
                    check(part.length() == 65_536L); hold.countDown()
                    result.put("real_video_cancel_offset", part.length())
                }
                test("playing and paused video both prevent manual download") {
                    until { withContext(Dispatchers.Main) { host.media.snapshot()["status"] == "playing" } }
                    check(!withContext(Dispatchers.Main) { c.manual(UpdateController.Action.DOWNLOAD, SystemClock.elapsedRealtime()) })
                    withContext(Dispatchers.Main) { host.media.pause() }
                    until { withContext(Dispatchers.Main) { host.media.snapshot()["status"] == "paused" } }
                    check(host.updates.environment.value.video)
                    check(!withContext(Dispatchers.Main) { c.manual(UpdateController.Action.DOWNLOAD, SystemClock.elapsedRealtime()) })
                }
                test("EngineCastPlayer sessions reach the real runtime casting gate") {
                    val player = EngineCastPlayer(host.media, Handler(Looper.getMainLooper()), CastHost.OWNER, {}, {})
                    player.load(MediaEntry(origin.url("/video.mp4"), kind = "video"), false)
                    until { host.updates.environment.value.casting }
                    check(!withContext(Dispatchers.Main) { c.manual(UpdateController.Action.DOWNLOAD, SystemClock.elapsedRealtime()) })
                    player.stop(); until { !host.updates.environment.value.casting && !host.updates.environment.value.video }
                }
                test("real Android settings foreground pauses eligibility and return restores it") {
                    withContext(Dispatchers.Main) { check(activity.onOpenAndroid("settings")) }
                    until { host.updates.environment.value.foreign && !KioskState.resumed }
                    check(!withContext(Dispatchers.Main) { c.manual(UpdateController.Action.DOWNLOAD, SystemClock.elapsedRealtime()) })
                    withContext(Dispatchers.Main) { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                    until { !host.updates.environment.value.foreign && KioskState.resumed }
                }
                test("automatic resume after media exits uses Range and verifies the full actual APK") {
                    withContext(Dispatchers.Main) { host.media.stop(); host.sensors.debugHoldAttention(Attention.AMBIENT); policy.value = policy.value.copy(enabled = true) }
                    until { c.status.value.state.phase == "READY" && !c.status.value.busy }
                    check(origin.hits.any { it.path == "/app.apk" && it.headers["range"]?.singleOrNull()?.startsWith("bytes=65536-") == true })
                    result.put("final_phase", c.status.value.state.phase).put("final_bytes", c.status.value.state.downloaded)
                    result.put("cast_scope", "real EngineCastPlayer/MediaEngine session; no DLNA/AirPlay/Miracast protocol handshake in this probe")
                }
                test("public update status uses the native-verified offer and never exposes transport or trust material") {
                    val projected = withContext(Dispatchers.Main) {
                        UpdatePublicStatus.read(c.status.value, host.updates.environment.value, policy.value,
                            BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, java.util.Locale.SIMPLIFIED_CHINESE)
                    }
                    check(projected.phase == "ready" && projected.version == fixtureInfo.getString("version"))
                    check(projected.notes == "更新内容" && !projected.can_install && !projected.can_download)
                    val encoded = UpdateModels.writeUpdateStatusDocument(projected).toString()
                    check(!encoded.contains(origin.url()) && !encoded.contains("signer") && !encoded.contains("path"))
                    check(UpdateModels.readUpdateStatusDocument(UpdateJson.read(encoded.toByteArray(), 16384)) == projected)
                    val realHost = withContext(Dispatchers.Main) { host.updateStatus() }
                    check(realHost["phase"] == "unavailable" && realHost["error"] == "keys")
                    val admission = CompletableDeferred<Pair<Boolean, String?>>()
                    withContext(Dispatchers.Main) {
                        host.onUpdateAction("check", SystemClock.elapsedRealtime()) { accepted, error -> admission.complete(accepted to error) }
                    }
                    check(withTimeout(5000) { admission.await() } == (false to "unavailable"))
                    result.put("public_status_roundtrip", true)
                }
            } finally {
                hold.countDown()
                withContext(Dispatchers.Main) {
                    c.stop(); host.media.stop(); host.sensors.debugHoldAttention(null)
                    host.settings.set(SettingsStore.SYSTEM, "update.channel", initialPolicy.channel)
                    context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                }
            }
        }
        controller?.let { withContext(Dispatchers.Main) { it.stop() } }
        scope.cancel()
    }
}
