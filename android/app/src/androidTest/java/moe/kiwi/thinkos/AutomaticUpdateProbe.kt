package moe.kiwi.thinkos

import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.*
import moe.kiwi.thinkos.cast.CastHost
import moe.kiwi.thinkos.cast.EngineCastPlayer
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.media.MediaEntry
import moe.kiwi.thinkos.media.MediaOptions
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.update.*
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.ZoneId

/** RAM-only authenticated source, then the real Runtime quiet adapter,
 * controller, package verifier, journal and installer. Default 600-second touch
 * grace is used for the final unattended installation. No system clock change. */
internal object AutomaticUpdateProbe {
    private suspend fun until(test: suspend () -> Boolean) = withTimeout(45_000) { while (!test()) delay(20) }
    @Suppress("DEPRECATION")
    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit) {
        val context = runner.targetContext
        val activity = runner.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val host = withContext(Dispatchers.Main) { (context.applicationContext as ThinkApplication).runtime }
        val candidate = File(directory, "install/candidate.apk")
        val builds = JSONObject(File(directory, "install/builds.json").readText())
        val expected = builds.getJSONObject("to")
        check(UpdateInstallContext.running(context).code == builds.getJSONObject("from").getInt("code"))
        check(candidate.length() == expected.getLong("size") && UpdateCatalog.sha256(candidate.readBytes()) == expected.getString("sha256"))
        check(!UpdateInstallContext.directory(context).exists())
        val fixture = UpdateFixture()
        val keys = UpdateKeys.parse(UpdateFixture.bytes(fixture.production()))
        val payload = candidate.readBytes()
        val silent = runner.context.assets.open("runtime-video.mp4").use { it.readBytes() }
        val root = File(directory, "automatic")
        val documents = mutableMapOf<String, ByteArray>()
        var armed = false
        var submitted = false
        UpdateTestOrigin { exchange ->
            when (exchange.requestURI.path) {
                "/app.apk" -> {
                    val match = Regex("bytes=([0-9]+)-([0-9]+)").matchEntire(exchange.requestHeaders.getFirst("Range").orEmpty())!!
                    val first = match.groupValues[1].toInt(); val last = match.groupValues[2].toInt()
                    exchange.responseHeaders.add("Content-Range", "bytes $first-$last/${payload.size}")
                    exchange.sendResponseHeaders(206, (last-first+1).toLong())
                    exchange.responseBody.use { it.write(payload, first, last-first+1) }
                }
                "/silent.mp4" -> UpdateTestOrigin.reply(exchange, silent)
                else -> UpdateTestOrigin.reply(exchange, documents.getValue(exchange.requestURI.path))
            }
        }.use { origin ->
            val json = fixture.catalog(501, url = origin.url("/app.apk")).apply {
                getJSONObject("release").put("version", expected.getString("version")).put("code", expected.getInt("code"))
                    .put("size", expected.getLong("size")).put("sha256", expected.getString("sha256")).put("signer", expected.getString("signer"))
                    .put("notes", JSONObject().put("zh-Hans", "# 自动更新\n\n已完成夜间更新。\n\n- 控制栏和设置中可查看更新说明。"))
            }
            val raw = UpdateFixture.bytes(json)
            documents["/stable.json"] = raw; documents["/stable.json.sig"] = fixture.sign(raw)
            suspend fun setting(key: String, value: Any) = withContext(Dispatchers.Main) { host.settings.set(SettingsStore.SYSTEM, "update.$key", value) }
            setting("enabled", false); setting("auto_install", false); setting("mirror", origin.url("/stable.json"))
            val hour = Instant.ofEpochMilli(moe.kiwi.thinkos.core.WallClock.now()).atZone(ZoneId.systemDefault()).hour
            setting("install_hour", hour); setting("install_poll_s", 1)
            val runtime = withContext(Dispatchers.Main) {
                UpdateRuntime(context, host.settings, host.system, host.store, { host.sensors.touches }, host.updates::screensSnapshot,
                    workOverride = { config, owner -> UpdateIo.run {
                        val ledger = UpdateSequenceStore(File(root, "ledger"))
                        val fetcher = Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(config.connectMillis, config.readMillis))
                        val info = UpdateInstallContext.running(context)
                        val session = UpdateSession(config.channel,
                            UpdateCatalogClient(fetcher, keys, ledger, UpdateCatalogCache(File(root, "metadata"), keys)),
                            UpdateState(File(root, "state"), ledger), File(root, "downloads"), UpdatePackageVerifier.forContext(context),
                            { UpdateCatalog.Device(android.os.Build.DEVICE, 27, info.code, info.signer) },
                            { val now = UpdateInstallContext.now(context); UpdateState.Time(now.boot, now.elapsed, moe.kiwi.thinkos.core.WallClock.now()) },
                            UpdateInstallContext.store(context))
                        val real = owner.sessionWork(session, fetcher)
                        object : UpdateController.Work by real {
                            // Preflight tests can become eligible but never commit.
                            // The final arm delegates to the actual installer.
                            override val installable get() = armed && real.installable
                        }
                    } }, environmentOverride = host.updates.environment)
            }
            try {
                withContext(Dispatchers.Main) { host.sensors.debugHoldPresence(false); host.sensors.debugHoldAttention(Attention.ASLEEP); runtime.start() }
                until { runtime.status.value.configured }
                test("RAM-only signed source reaches the actual Runtime READY with automatic downloads and installs off") {
                    check(withContext(Dispatchers.Main) { runtime.manual(UpdateController.Action.CHECK, SystemClock.elapsedRealtime()) })
                    until { runtime.status.value.state.phase == "AVAILABLE" && !runtime.status.value.busy }
                    check(withContext(Dispatchers.Main) { runtime.manual(UpdateController.Action.DOWNLOAD, SystemClock.elapsedRealtime()) })
                    until { runtime.status.value.state.phase == "READY" && !runtime.status.value.busy }
                    check(!runtime.automaticReadiness.value)
                    check(!File(context.filesDir, "update/pending.json").exists())
                }
                setting("touch_grace_s", 0); setting("auto_install", true)
                until { runtime.automaticReadiness.value }
                test("a real touch resets quiet admission even while the debug attention override stays ASLEEP") {
                    setting("touch_grace_s", 600)
                    withContext(Dispatchers.Main) { host.sensors.touch() }
                    until { !runtime.automaticReadiness.value }
                    check(host.store.current.attention == Attention.ASLEEP)
                    check(!withContext(Dispatchers.Main) { runtime.automaticEligibility() })
                    setting("touch_grace_s", 0); until { runtime.automaticReadiness.value }
                }
                test("actual paused audio and video sessions each block automatic installation") {
                    withContext(Dispatchers.Main) { host.media.load("automatic-probe", listOf(MediaEntry(origin.url("/silent.mp4"), kind = "audio")), MediaOptions(autoplay = false)) }
                    until { runtime.environment.value.media && !runtime.automaticReadiness.value }
                    check(!runtime.environment.value.video)
                    check(!withContext(Dispatchers.Main) { runtime.automaticEligibility() })
                    withContext(Dispatchers.Main) { host.media.load("automatic-probe", listOf(MediaEntry(origin.url("/silent.mp4"), kind = "video")), MediaOptions(autoplay = false)) }
                    until { runtime.environment.value.video && !runtime.automaticReadiness.value }
                    check(!withContext(Dispatchers.Main) { runtime.automaticEligibility() })
                    withContext(Dispatchers.Main) { host.media.stop() }; until { runtime.automaticReadiness.value }
                }
                test("an actual EngineCastPlayer session blocks automatic installation") {
                    val player = EngineCastPlayer(host.media, Handler(Looper.getMainLooper()), CastHost.OWNER, {}, {})
                    player.load(MediaEntry(origin.url("/silent.mp4"), kind = "video"), false)
                    until { runtime.environment.value.casting && !runtime.automaticReadiness.value }
                    check(!withContext(Dispatchers.Main) { runtime.automaticEligibility() })
                    player.stop(); until { runtime.automaticReadiness.value }
                }
                test("actual settings and native recovery windows block installation despite forced sleep") {
                    withContext(Dispatchers.Main) { check(host.router.openSettings(SettingsStore.SYSTEM)) }
                    until { !runtime.automaticReadiness.value }
                    check(host.updates.screensSnapshot().settings)
                    check(!withContext(Dispatchers.Main) { runtime.automaticEligibility() })
                    withContext(Dispatchers.Main) { host.router.closeSettings() }; until { runtime.automaticReadiness.value }
                    withContext(Dispatchers.Main) { check(activity.onUpdates()) }
                    until { host.updates.screensSnapshot().settings && !runtime.automaticReadiness.value }
                    check(!withContext(Dispatchers.Main) { runtime.automaticEligibility() })
                    runner.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
                    until { !host.updates.screensSnapshot().settings && runtime.automaticReadiness.value }
                }
                test("actual Android settings foreground blocks installation and returning restores eligibility") {
                    withContext(Dispatchers.Main) { check(activity.onOpenAndroid("settings")) }
                    until { runtime.environment.value.foreign && !runtime.automaticReadiness.value }
                    check(!withContext(Dispatchers.Main) { runtime.automaticEligibility() })
                    withContext(Dispatchers.Main) { context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)) }
                    until { !runtime.environment.value.foreign && runtime.automaticReadiness.value }
                }
                test("the configured local window blocks daytime and no automatic session has been submitted by preflight") {
                    setting("install_hour", (hour+12)%24); until { !runtime.automaticReadiness.value }
                    check(!withContext(Dispatchers.Main) { runtime.automaticEligibility() })
                    setting("install_hour", hour); until { runtime.automaticReadiness.value }
                    check(!File(context.filesDir, "update/pending.json").exists())
                    result.put("preflight_platform_sessions", context.packageManager.packageInstaller.mySessions.map { it.sessionId })
                }
                setting("auto_install", false)
                until { !runtime.automaticReadiness.value }
                result.put("checks", runner.checkedSnapshot())
                withContext(Dispatchers.Main) { host.device.setWifi(false) }
                until { !runtime.readiness.value.connected }
                setting("touch_grace_s", 600); setting("install_poll_s", 30)
                withContext(Dispatchers.Main) { host.sensors.touch(); armed = true }
                val lastTouch = host.sensors.touches.value
                setting("auto_install", true)
                val request = JSONObject().put("run_id", token).put("uid", Process.myUid()).put("pid", Process.myPid())
                    .put("last_touch_elapsed", lastTouch).put("touch_grace_ms", 600_000).put("quiet_start_hour", hour)
                    .put("network_connected", runtime.readiness.value.connected)
                    .put("from", builds.getJSONObject("from")).put("to", expected).put("preflight", result)
                FileOutputStream(File(directory, "install/automatic-wait.json")).use { it.write(request.toString(2).toByteArray()); it.fd.sync() }
                android.util.Log.i("ThinkOS", "AUTOMATIC_UPDATE_WAIT last_touch=$lastTouch grace_ms=600000")
                submitted = true
                // No input, no manual install call, no queued fake tap. The
                // production controller installs when real monotonic idle ages.
                awaitCancellation()
            } finally {
                if (!submitted) withContext(NonCancellable + Dispatchers.Main) { runtime.stop(); host.media.stop() }
            }
        }
    }
}
