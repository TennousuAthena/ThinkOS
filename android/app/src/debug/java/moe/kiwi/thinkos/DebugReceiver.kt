package moe.kiwi.thinkos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.Theme

/** Test input is shell-protected and exists only in debug APKs. It never writes device settings. */
class DebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = (context.applicationContext as ThinkApplication).runtime
        when (intent.getStringExtra("command")) {
            "orientation" -> host.debugOrientation(intent.getIntExtra("value", 10))
            "maintenance" -> host.debugMaintenance()
            "plugins_disable_all" -> host.plugins.debugDisableAll()
            "plugin_cpu" -> { resultData = org.json.JSONObject(host.plugins.debugCpuNanos()).toString() }
            "legal_state" -> { resultData = MainActivity.TouchInjection.legalState?.invoke() ?: "{}" }
            "update_state" -> { resultData = MainActivity.TouchInjection.updateState?.invoke() ?: "{}" }
            "update_status" -> { resultData = org.json.JSONObject(host.updates.snapshot()).toString() }
            "onboarding_open" -> {
                resultData = host.router.openOnboarding(intent.getStringExtra("value") ?: "restart").toString()
            }
            "onboarding_reset" -> {
                host.settings.setMany(moe.kiwi.thinkos.settings.SettingsStore.SYSTEM,
                    mapOf("onboarding.done" to 0, "onboarding.attempts" to 0))
            }
            "trust_roots" -> {
                val pending = goAsync()
                Thread({
                    try {
                        pending.resultData = runCatching { TrustRootsProbe.read().toString() }
                            .getOrElse { org.json.JSONObject().put("error", it.javaClass.simpleName).toString() }
                    } finally {
                        pending.finish()
                    }
                }, "ThinkOS-trust-probe").apply { priority = Thread.MIN_PRIORITY }.start()
                return // goAsync transferred the pending result to the worker.
            }
            "stage_state" -> { resultData = MainActivity.TouchInjection.stageState?.invoke() ?: "{}" }
            "foreign_panel" -> { resultData = host.foreignPanel.debugState().toString() }
            // OS-route integration fixture while the video/mirror page remains on screen.
            // Normal application/permission checks are covered by real grid taps.
            "foreign_launch" -> {
                val target = intent.getStringExtra("value").orEmpty()
                host.onOpenApp(moe.kiwi.thinkos.apps.AppEntry("android:$target", "android", target)) {
                    Log.i("ThinkOS", "DEBUG foreign launch completed=$it")
                }
            }
            // Same-UID fixture for receiver checks without installing a persistent system module.
            "edge_return" -> context.sendBroadcast(Intent(context, moe.kiwi.thinkos.shell.EdgeReturnReceiver::class.java))
            "kiosk_state" -> {
                val kiosk = moe.kiwi.thinkos.shell.KioskState
                resultData = org.json.JSONObject().put("resumed", kiosk.resumed).put("taskId", kiosk.foregroundTaskId)
                    .put("foreign", kiosk.foreign).put("recoverable", kiosk.shouldRecover()).toString()
            }
            // Process-local only; restarting the debug APK restores Android's own animation scale.
            "motion_scale" -> {
                val value = intent.getFloatExtra("value", 1f)
                require(value == 0f || value == 1f)
                android.animation.ValueAnimator::class.java.getDeclaredMethod("setDurationScale", Float::class.javaPrimitiveType).invoke(null, value)
            }
            "ui_state" -> {
                val state = host.store.current
                resultData = host.sensors.debugOverrides()
                    .put("locale", java.util.Locale.getDefault().toLanguageTag())
                    .put("orientation", host.debugOrientationCode)
                    .put("page", state.deck.page).put("variant", state.deck.variant)
                    .put("onboardingOpen", host.router.onboardingOpen)
                    .put("safeMode", state.safeMode)
                    .put("mode", state.mode.name.lowercase(java.util.Locale.ROOT)).toString()
            }
            "mode_state" -> {
                val decision = host.modes.decision
                resultData = org.json.JSONObject()
                    .put("mode", host.store.current.mode.name.lowercase(java.util.Locale.ROOT))
                    .put("theme", host.store.current.theme.name)
                    .put("manual", decision?.manual?.mode?.name?.lowercase(java.util.Locale.ROOT) ?: org.json.JSONObject.NULL)
                    .put("expiresAt", decision?.manual?.expiresAtWallMillis ?: org.json.JSONObject.NULL)
                    .put("deferred", decision?.deferred ?: false).toString()
            }
            "mode" -> {
                val value = intent.getStringExtra("value")
                require(value == null || value in setOf("day", "redshift", "sleep"))
                if (value == null) host.sensors.clearMode()
                else host.sensors.setMode(moe.kiwi.thinkos.core.Mode.valueOf(value.uppercase(java.util.Locale.ROOT)))
            }
            "settings" -> host.router.openSettings(intent.getStringExtra("value") ?: "system")
            "form_change" -> {
                // Inject a malformed native editor result through the real settings handler.
                // Release APKs have no receiver; normal controls clamp before emitting.
                val owner = host.router.currentPage?.pluginId
                val key = intent.getStringExtra("key").orEmpty()
                val raw = intent.getStringExtra("value_json").orEmpty()
                if (host.router.settingsOpen && owner != null && key.length in 1..80 && raw.length in 1..4096) {
                    runCatching {
                        val value = org.json.JSONTokener(raw).nextValue()
                        host.plugins.event(owner, "form.change", org.json.JSONArray().put(key).put(value))
                    }.onFailure { Log.w("ThinkOS", "DEBUG form_change rejected", it) }
                }
            }
            "variant" -> {
                val variants = host.router.currentPage?.variants.orEmpty()
                val target = variants.indexOf(intent.getStringExtra("value"))
                val current = variants.indexOf(host.store.current.deck.variant)
                if (target >= 0 && current >= 0 && target != current) host.router.changeVariant(target - current)
            }
            // Preview the banner without changing CrashRecovery or disabling any plugin.
            "preview_safe_mode" -> host.store.update { it.copy(safeMode = intent.getStringExtra("value") == "true") }
            "page" -> intent.getStringExtra("value")?.let { key ->
                host.router.closeSettings()
                // A jump starts from the page sequence, as a tap on a tile would: nothing stays layered from an earlier jump.
                if (host.router.canGoBack) host.router.homeKey()
                host.router.open(key)
                Log.i("ThinkOS", "DEBUG page $key -> ${host.store.current.deck.page} known=${host.router.pages.any { it.key == key }} " +
                    "attention=${host.store.current.attention} reason=${host.router.lastNavigationReason}")
            }
            "benchmark_state" -> {
                val s = host.store.current
                resultData = listOf(s.deck.page, s.attention.name, s.theme.name, s.orientation.name,
                    android.os.Process.myPid().toString(), host.sensors.debugTouchCount.toString(), host.router.lastNavigationReason,
                    host.plugins.diagnostics().sumOf { it.bytes }.toString(), host.router.navigationGeneration.toString()).joinToString("|")
            }
            "json_size_check" -> {
                // Run against this ROM's org.json, whose escaping differs from
                // the reference JVM implementation used by local unit tests.
                val samples = listOf("", "ASCII", "测试频道", "mixed 中文", "quote\"/\\", "\u0000\n\t",
                    "\u007f\u0080\u009f\u00a0\u07ff\u0800\u1fff\u2000\u2028\u2029\u20ff\u2100\ud7ff\ue000\uffff", "😀", "\ud800", "\udfff")
                var checks = 0
                for (sample in samples) {
                    val patch = org.json.JSONObject().put("title", sample)
                    val bytes = patch.toString().toByteArray(Charsets.UTF_8).size
                    val doc = moe.kiwi.thinkos.plugin.JsonDocument(limit = bytes)
                    doc.merge(patch)
                    check(doc.bytes == bytes)
                    val short = moe.kiwi.thinkos.plugin.JsonDocument(limit = bytes - 1)
                    check(runCatching { short.merge(patch) }.isFailure && short.value.length() == 0)
                    checks += 2
                }
                val doc = moe.kiwi.thinkos.plugin.JsonDocument()
                repeat(4) { revision ->
                    val rows = org.json.JSONArray()
                    repeat(500) { rows.put(org.json.JSONObject().put("title", "测试频道 $it").put("revision", revision)) }
                    doc.merge(org.json.JSONObject().put("rows", rows))
                    check(doc.bytes == doc.value.toString().toByteArray(Charsets.UTF_8).size)
                    checks++
                }
                resultData = "ok|$checks"
            }
            "state" -> Log.i("ThinkOS", "DEBUG state=${host.store.current} plugins=${host.plugins.diagnostics()}")
            "locale" -> {
                // Process-local locale for native UI tests; never changes Android's saved language.
                val locale = intent.getStringExtra("value")?.let(java.util.Locale::forLanguageTag)
                    ?: android.content.res.Resources.getSystem().configuration.locales[0]
                java.util.Locale.setDefault(locale)
                @Suppress("DEPRECATION")
                context.resources.updateConfiguration(android.content.res.Configuration(context.resources.configuration).apply {
                    setLocale(locale)
                }, context.resources.displayMetrics)
                host.localeChanged()
            }
            // Holds the main thread across the next second boundary and `value` ms beyond it, as a long frame would:
            // the clock must come back showing the true time, late by the stall and no more.
            "clock_stall" -> {
                val wall = moe.kiwi.thinkos.core.WallClock.now()
                Thread.sleep(1_000 - wall % 1_000 + intent.getIntExtra("value", 500).toLong().coerceIn(0, 3_000))
            }
            "clock_reset" -> moe.kiwi.thinkos.anim.ClockStats.reset()
            "clock_report" -> Log.i("ThinkOS", "DEBUG clock ${moe.kiwi.thinkos.anim.ClockStats.report()}")
            "clock_error" -> {
                // How far the displayed time is from a fresh reading of a time server, measured now and independent of TimeSync.
                Thread {
                    run {
                        val sample = host.system.ntpServers.firstNotNullOfOrNull { name ->
                            runCatching { java.net.InetAddress.getAllByName(name).toList() }.getOrDefault(emptyList()).take(3)
                                .mapNotNull { moe.kiwi.thinkos.time.SntpClient.query(it, 2_000) }.minByOrNull { it.rttMillis }
                        }
                        if (sample == null) Log.w("ThinkOS", "DEBUG clock_error unavailable")
                        else {
                            val ours = moe.kiwi.thinkos.core.WallClock.now() - (moe.kiwi.thinkos.core.WallClock.elapsed() - sample.anchorElapsedMillis)
                            Log.i("ThinkOS", "DEBUG clock_error ms=${ours - sample.anchorWallMillis} rtt=${sample.rttMillis} synced=${moe.kiwi.thinkos.core.WallClock.synced}")
                        }
                    }
                }.start()
            }
            "lux" -> host.sensors.debugInjectLux(if (intent.hasExtra("value")) intent.getFloatExtra("value", 20f) else null)
            "presence" -> host.sensors.debugPresence(intent.getBooleanExtra("value", false))
            "hold_presence" -> host.sensors.debugHoldPresence(if (intent.hasExtra("value")) intent.getBooleanExtra("value", false) else null)
            "hold_attention" -> host.sensors.debugHoldAttention(Attention.entries.firstOrNull { it.name == intent.getStringExtra("value") })
            "drift" -> moe.kiwi.thinkos.ui.BurnIn.forced = if (intent.hasExtra("x")) intent.getFloatExtra("x", 0f) to intent.getFloatExtra("y", 0f) else null
            "theme" -> host.sensors.debugHoldTheme(Theme.entries.firstOrNull { it.name == intent.getStringExtra("value") })
            "attention" -> intent.getStringExtra("value")?.let { name ->
                Attention.entries.firstOrNull { it.name == name }?.let { attention -> host.store.update { it.copy(attention = attention) } }
            }
            "media_load" -> debugMedia(host, intent)
            "fetch_probe" -> debugFetch(context, intent)
            "media_play" -> host.media.play()
            "media_pause" -> host.media.pause()
            "media_toggle" -> host.media.toggle()
            "media_stop" -> host.media.stop()
            "media_next" -> host.media.next()
            "media_prev" -> host.media.prev()
            "media_live" -> host.media.goLive()
            "media_seek" -> host.media.seek(intent.getLongExtra("value", 0))
            "media_jump" -> host.media.jump(intent.getLongExtra("value", 1).toInt())
            "media_audio" -> host.media.selectAudio(intent.getLongExtra("value", 1).toInt())
            "media_text" -> host.media.selectText(intent.getLongExtra("value", 0).toInt())
            "miracast_dial" -> host.cast.dialMiracastForTest(intent.getStringExtra("host").orEmpty(), intent.getLongExtra("port", 7236L).toInt())
            "media_lowlatency" -> host.media.lowLatency = intent.getStringExtra("value") == "true"
            "touch" -> {
                // `taps` touches at (x, y), `gap` ms apart: a double tap is not something `input tap` can do (it takes 0.7 s a tap here).
                val x = intent.getFloatExtra("x", 0f); val y = intent.getFloatExtra("y", 0f)
                val x2 = intent.getFloatExtra("x2", x); val y2 = intent.getFloatExtra("y2", y)       // where every second tap lands
                val taps = intent.getLongExtra("taps", 1).toInt(); val gap = intent.getLongExtra("gap", 120)
                val main = Handler(Looper.getMainLooper())
                repeat(taps) { k ->
                    main.postDelayed({
                        val down = android.os.SystemClock.uptimeMillis()
                        fun send(action: Int, at: Long) {
                            val event = android.view.MotionEvent.obtain(down, at, action, if (k % 2 == 1) x2 else x, if (k % 2 == 1) y2 else y, 0)
                            MainActivity.TouchInjection.sink?.invoke(event)
                            event.recycle()
                        }
                        send(android.view.MotionEvent.ACTION_DOWN, down)
                        main.postDelayed({ send(android.view.MotionEvent.ACTION_UP, android.os.SystemClock.uptimeMillis()) }, 35)
                    }, k * gap)
                }
            }
            "levels" -> {
                // Tests read (and put back) what the picture's drag gestures change.
                if (intent.hasExtra("brightness")) host.levels.brightness = intent.getFloatExtra("brightness", .85f)
                if (intent.hasExtra("volume")) host.levels.volume = intent.getFloatExtra("volume", .5f)
                // The time makes every line unique: logd folds identical consecutive lines, and a test would wait for one that never comes.
                Log.i("ThinkOS", "DEBUG levels brightness=${host.levels.brightness} volume=${host.levels.volume} at=${android.os.SystemClock.elapsedRealtime()}")
            }
            "setting" -> {
                // A setting changed at run time, as the settings page does: `key`, and `value` as true / false or text. `scope` names a
                // plugin (the system's own settings by default); `reset` puts the device's change back instead (every key of the scope without `key`).
                val scope = intent.getStringExtra("scope") ?: moe.kiwi.thinkos.settings.SettingsStore.SYSTEM
                val key = intent.getStringExtra("key").orEmpty()
                if (intent.hasExtra("reset")) {
                    Log.i("ThinkOS", "DEBUG setting reset $scope/$key ${runCatching { host.settings.reset(scope, key.ifEmpty { null }) }.exceptionOrNull()?.message ?: "ok"}")
                } else {
                    val raw = intent.getStringExtra("value").orEmpty()
                    val value: Any = when (raw) { "true" -> true; "false" -> false; else -> raw.toDoubleOrNull() ?: raw }
                    Log.i("ThinkOS", "DEBUG setting $scope/$key=${runCatching { host.settings.set(scope, key, value) }.getOrElse { "refused: ${it.message}" }}")
                }
            }
            "airplay_testkey" -> {
                // A fixed key for every FairPlay unwrap (hex), or none: lets a test sender stand in for an iPhone after the key exchange.
                val hex = intent.getStringExtra("value").orEmpty()
                moe.kiwi.thinkos.cast.airplay.NativeFairPlay.testKey = if (hex.length == 32) ByteArray(16) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() } else null
                Log.i("ThinkOS", "DEBUG airplay_testkey ${if (hex.length == 32) "set" else "cleared"}")
            }
            "fairplay_probe" -> {
                // The real native key routine on made-up input: it must come back with 16 bytes and not crash. What it computes is not checked here.
                val message = ByteArray(164) { (it * 7).toByte() }.also { it[4] = 3 }
                val key = moe.kiwi.thinkos.cast.airplay.PlayFair.unwrap(message, ByteArray(72) { (it * 3).toByte() })
                val one = moe.kiwi.thinkos.cast.airplay.PlayFair.phase1(ByteArray(16).also { it[4] = 3; it[14] = 2 })
                val two = moe.kiwi.thinkos.cast.airplay.PlayFair.phase2(message)
                Log.i("ThinkOS", "DEBUG fairplay_probe available=${moe.kiwi.thinkos.cast.airplay.PlayFair.isAvailable} key=${key?.size} phase1=${one?.size} mode=${one?.get(13)} phase2=${two?.size} at=${android.os.SystemClock.elapsedRealtime()}")
            }
            "fairplay_fuzz" -> Thread {
                // The reverse-engineered key routine on random input: a crash in it would take the whole app down, so run it many times and report what came back.
                val random = java.util.Random(7); var ok = 0; val count = intent.getLongExtra("count", 20000L).toInt()
                val started = android.os.SystemClock.elapsedRealtime()
                repeat(count) {
                    val message = ByteArray(164).also { random.nextBytes(it) }; val wrapped = ByteArray(72).also { random.nextBytes(it) }
                    if (moe.kiwi.thinkos.cast.airplay.PlayFair.unwrap(message, wrapped)?.size == 16) ok++
                    moe.kiwi.thinkos.cast.airplay.PlayFair.phase2(message.also { it[4] = 3 })
                }
                Log.i("ThinkOS", "DEBUG fairplay_fuzz ran=$count ok=$ok ms=${android.os.SystemClock.elapsedRealtime() - started} at=${android.os.SystemClock.elapsedRealtime()}")
            }.start()
            "eld_selftest" -> Thread { moe.kiwi.thinkos.cast.airplay.EldSelfTest.run { Log.i("ThinkOS", "DEBUG eld_selftest $it at=${android.os.SystemClock.elapsedRealtime()}") } }.start()
            "media_state" -> {
                val json = org.json.JSONObject(host.media.snapshot()).toString()
                resultData = json
                Log.i("ThinkOS", "DEBUG media $json")
            }
            "crash" -> Handler(Looper.getMainLooper()).post { error("Intentional debug recovery probe") }
            "kiosk_surrender" -> surrenderDeviceOwner(context)
            "clear_recovery" -> moe.kiwi.thinkos.shell.CrashRecovery.clearSafeMode(context)
        }
        resultCode = 0
    }

    /**
     * `--es url A|B` queues several items. `kind`, `type` (auto/progressive/hls/dash/rtsp), `headers` (a JSON object),
     * `subtitle` (a URL) and `start` (ms) apply to every item. It bypasses the plugin permission checks, nothing else.
     */
    private fun debugMedia(host: RuntimeHost, intent: Intent) {
        runCatching {
            val headers = intent.getStringExtra("headers")?.let { raw ->
                org.json.JSONObject(raw).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            }.orEmpty()
            val entries = intent.getStringExtra("url").orEmpty().split('|').filter { it.isNotBlank() }.mapIndexed { i, url ->
                moe.kiwi.thinkos.media.MediaEntry(url, title = intent.getStringExtra("title") ?: "debug ${i + 1}",
                    kind = intent.getStringExtra("kind") ?: "audio",
                    type = moe.kiwi.thinkos.media.MediaSourceType.parse(intent.getStringExtra("type") ?: "auto"),
                    headers = headers,
                    subtitles = intent.getStringExtra("subtitle")?.let { listOf(moe.kiwi.thinkos.media.MediaSubtitle(it)) }.orEmpty())
            }
            host.media.load("debug", entries, moe.kiwi.thinkos.media.MediaOptions(start = intent.getLongExtra("start", 0),
                repeat = moe.kiwi.thinkos.media.MediaRepeat.parse(intent.getStringExtra("repeat") ?: "off")))
        }.onFailure { Log.e("ThinkOS", "DEBUG media_load rejected: ${it.message}") }
    }

    /** REL-0: runs the PRODUCTION update transport (system plus bundled trust, manual redirects, Range segments, SHA-256)
     * against a public URL. The caller hashed the same file elsewhere, so Fetcher's normal verification applies unchanged.
     * The bytes are checked and then deleted; they are never installed, opened or executed. */
    private fun debugFetch(context: Context, intent: Intent) {
        val url = intent.getStringExtra("url") ?: return
        val size = intent.getLongExtra("size", 0)
        val sha = intent.getStringExtra("sha").orEmpty()
        val tag = intent.getStringExtra("tag") ?: "probe"
        // No goAsync(): onReceive sets the result code afterwards, and this long-lived app process outlives the broadcast.
        Thread({
            val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
            val started = android.os.SystemClock.elapsedRealtime()
            val directory = java.io.File(context.cacheDir, "fetch-probe")
            try {
                moe.kiwi.thinkos.update.Fetcher(BuildConfig.VERSION_NAME, moe.kiwi.thinkos.update.UpdateHttpClient.Timeouts(15_000, 30_000))
                    .use { fetcher ->
                        val artifact = moe.kiwi.thinkos.update.Fetcher.Artifact(moe.kiwi.thinkos.update.Fetcher.ArtifactKind.APK, size, sha)
                        var next = 4L * 1024 * 1024
                        val file = kotlinx.coroutines.runBlocking {
                            fetcher.download(listOf(url), artifact, directory) { bytes ->
                                if (bytes >= next) {
                                    Log.i("ThinkOS", "DEBUG fetch_probe tag=$tag progress bytes=$bytes ms=${android.os.SystemClock.elapsedRealtime() - started}")
                                    next = bytes + 4L * 1024 * 1024
                                }
                            }
                        }
                        val ms = android.os.SystemClock.elapsedRealtime() - started
                        Log.i("ThinkOS", "DEBUG fetch_probe tag=$tag host=$host OK bytes=${file.length()} ms=$ms kBps=${file.length() / maxOf(ms, 1)}")
                    }
            } catch (error: Throwable) {
                val ms = android.os.SystemClock.elapsedRealtime() - started
                val causes = generateSequence<Throwable>(error) { it.cause }.take(4).joinToString(" <- ") { "${it.javaClass.simpleName}: ${it.message}".take(120) }
                val suppressed = error.suppressed.take(3).joinToString("; ") { "${it.javaClass.simpleName}: ${it.message}".take(120) }
                Log.e("ThinkOS", "DEBUG fetch_probe tag=$tag host=$host FAILED ms=$ms $causes suppressed=[$suppressed]")
            } finally {
                runCatching { directory.deleteRecursively() }
            }
        }, "fetch-probe").start()
    }

    /** Recovery for debug installs, where `dpm remove-active-admin` refuses (the APK is not testOnly). */
    private fun surrenderDeviceOwner(context: Context) {
        val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
        if (!dpm.isDeviceOwnerApp(context.packageName)) { Log.i("ThinkOS", "DEBUG kiosk_surrender: not Device Owner"); return }
        val admin = android.content.ComponentName(context, moe.kiwi.thinkos.admin.ThinkOSAdminReceiver::class.java)
        runCatching {
            dpm.setStatusBarDisabled(admin, false)
            dpm.setKeyguardDisabled(admin, false)
            dpm.setLockTaskPackages(admin, emptyArray())
            dpm.clearDeviceOwnerApp(context.packageName)
        }.onFailure { Log.e("ThinkOS", "DEBUG kiosk_surrender failed", it) }
        Log.i("ThinkOS", "DEBUG kiosk_surrender done; owner=${dpm.isDeviceOwnerApp(context.packageName)}")
    }
}
