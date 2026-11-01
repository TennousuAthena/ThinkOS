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
            "page" -> intent.getStringExtra("value")?.let { key ->
                host.router.closeSettings()
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
            "hold_attention" -> host.sensors.debugHoldAttention(Attention.entries.firstOrNull { it.name == intent.getStringExtra("value") })
            "drift" -> moe.kiwi.thinkos.ui.BurnIn.forced = if (intent.hasExtra("x")) intent.getFloatExtra("x", 0f) to intent.getFloatExtra("y", 0f) else null
            "theme" -> host.sensors.debugHoldTheme(Theme.entries.firstOrNull { it.name == intent.getStringExtra("value") })
            "attention" -> intent.getStringExtra("value")?.let { name ->
                Attention.entries.firstOrNull { it.name == name }?.let { attention -> host.store.update { it.copy(attention = attention) } }
            }
            "media_load" -> debugMedia(host, intent)
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
                // A system setting changed at run time, as the settings page does: `key`, and `value` as true / false or text.
                val key = intent.getStringExtra("key").orEmpty()
                val raw = intent.getStringExtra("value").orEmpty()
                val value: Any = when (raw) { "true" -> true; "false" -> false; else -> raw.toDoubleOrNull() ?: raw }
                Log.i("ThinkOS", "DEBUG setting $key=${runCatching { host.settings.set(moe.kiwi.thinkos.settings.SettingsStore.SYSTEM, key, value) }.getOrElse { "refused: ${it.message}" }}")
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
            "media_state" -> Log.i("ThinkOS", "DEBUG media ${org.json.JSONObject(host.media.snapshot())}")
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
