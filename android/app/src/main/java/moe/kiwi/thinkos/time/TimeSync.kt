package moe.kiwi.thinkos.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.util.AtomicFile
import android.util.Log
import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.SystemSettings
import moe.kiwi.thinkos.settings.SystemValues
import java.io.File
import java.net.InetAddress
import kotlin.random.Random

/**
 * Keeps [WallClock] right without the Server and without writing the system clock (impossible on API 27).
 * Each round resolves every server in `time.ntp_servers`, asks every address it returns (pool names give several),
 * and takes the answer with the smallest round trip. Retries back off until the first success, then it runs every
 * `time.sync_interval` hours (a few minutes of jitter keeps the pool's load spread): a crystal drifts tens of
 * milliseconds an hour, and a clock should not be half a second off by evening.
 *
 * The reading is saved, so a restarted app is exact before the network answers.
 */
class TimeSync(private val context: Context, private val system: SystemValues, private val settings: SettingsStore) {
    private val thread = HandlerThread("ThinkOS-TimeSync").apply { start() }
    private val handler = Handler(thread.looper)
    private val file = AtomicFile(File(context.filesDir, "time-anchor.txt"))
    private var failures = 0
    private var started = false

    private val round = Runnable { syncNow() }
    private val clockChanged = object : BroadcastReceiver() {
        // The ROM's own NTP (or a person) moved the system clock. The reading we keep time from is unaffected;
        // measure again anyway, since the change may mean the network just came up.
        override fun onReceive(context: Context, intent: Intent) { WallClock.systemClockChanged(); reschedule(0) }
    }

    fun start() {
        if (started) return
        started = true
        restore()
        context.registerReceiver(clockChanged, IntentFilter(Intent.ACTION_TIME_CHANGED), null, handler)
        settings.addListener { scope, keys ->
            if (scope == SettingsStore.SYSTEM && (SystemSettings.Keys.NTP_SERVERS in keys || SystemSettings.Keys.TIME_SYNC_INTERVAL in keys)) reschedule(0)
        }
        reschedule(0)
    }

    private fun bootCount() = runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)

    private fun restore() {
        val saved = runCatching { SavedAnchor.decode(String(file.readFully(), Charsets.UTF_8)) }.getOrNull() ?: return
        if (saved.usable(bootCount(), WallClock.elapsed())) {
            WallClock.adopt(saved.wall, saved.elapsed)
            Log.i("ThinkOS", "time sync: restored the reading from ${(WallClock.elapsed() - saved.elapsed) / 1000}s ago")
        }
    }

    private fun save(sample: SntpSample) {
        val boot = bootCount()
        if (boot < 0) return
        val stream = file.startWrite()
        try { stream.write(SavedAnchor(boot, sample.anchorWallMillis, sample.anchorElapsedMillis).encode().toByteArray()); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream) }
    }

    private fun reschedule(delayMillis: Long) {
        handler.removeCallbacks(round)
        handler.postDelayed(round, delayMillis)
    }

    private fun syncNow() {
        val best = measure()
        val next: Long
        if (best != null) {
            failures = 0
            // A reading taken over a slow, lopsided link can be off by half its round trip. Once we have a good
            // one, a poor one only makes things worse, so wait for a better moment.
            if (WallClock.synced && best.rttMillis > MAX_ROUND_TRIP_MILLIS) {
                Log.w("ThinkOS", "time sync: ignored a reading with rtt=${best.rttMillis}ms")
                next = RETRY_AFTER_POOR_READING_MILLIS
            } else {
                val before = WallClock.now()
                WallClock.adopt(best.anchorWallMillis, best.anchorElapsedMillis)
                save(best)
                Log.i("ThinkOS", "time sync: ${best.server} system-offset=${best.offsetMillis}ms rtt=${best.rttMillis}ms corrected-by=${WallClock.now() - before}ms")
                next = untilNextSync()
            }
        } else {
            failures++
            next = (30_000L shl (failures - 1).coerceAtMost(6)).coerceAtMost(30 * 60_000L)   // 30 s … 30 min
            Log.w("ThinkOS", "time sync failed ($failures); retry in ${next / 1000}s")
        }
        reschedule(next)
    }

    private fun measure(): SntpSample? {
        val samples = mutableListOf<SntpSample>()
        for (host in system.ntpServers) {
            val addresses = runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList()).take(4)
            for (address in addresses) SntpClient.query(address, 2_000)?.let { samples += it.copy(server = "$host ${address.hostAddress}") }
            if (samples.size >= 4) break
        }
        return samples.minByOrNull { it.rttMillis }
    }

    private fun untilNextSync(): Long {
        val interval = system.timeSyncIntervalMillis
        return interval + Random.nextLong(0, (interval / 12).coerceIn(1, 5 * 60_000L))
    }

    private companion object {
        const val MAX_ROUND_TRIP_MILLIS = 500L
        const val RETRY_AFTER_POOR_READING_MILLIS = 5 * 60_000L
    }
}
