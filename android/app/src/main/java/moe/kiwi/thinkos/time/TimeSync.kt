package moe.kiwi.thinkos.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.SystemSettings
import moe.kiwi.thinkos.settings.SystemValues
import java.net.InetAddress
import java.util.Calendar
import kotlin.random.Random

/**
 * Keeps [WallClock] right without the Server and without writing the system clock (impossible on API 27).
 * Each round resolves every server in `time.ntp_servers`, asks every address it returns (pool names give several),
 * and takes the answer with the smallest round trip. Retries back off until the first success, then runs daily
 * at `time.sync_hour` (a few minutes of jitter keeps the pool's load spread).
 */
class TimeSync(private val context: Context, private val system: SystemValues, private val settings: SettingsStore) {
    private val thread = HandlerThread("ThinkOS-TimeSync").apply { start() }
    private val handler = Handler(thread.looper)
    private var failures = 0
    private var started = false

    private val round = Runnable { syncNow() }
    private val clockChanged = object : BroadcastReceiver() {
        // The ROM's own NTP (or a person) moved the system clock: our offset no longer applies, measure again.
        override fun onReceive(context: Context, intent: Intent) { WallClock.offsetMillis = 0; reschedule(0) }
    }

    fun start() {
        if (started) return
        started = true
        context.registerReceiver(clockChanged, IntentFilter(Intent.ACTION_TIME_CHANGED), null, handler)
        settings.addListener { scope, keys ->
            if (scope == SettingsStore.SYSTEM && (SystemSettings.Keys.NTP_SERVERS in keys || SystemSettings.Keys.TIME_SYNC_HOUR in keys)) reschedule(0)
        }
        reschedule(0)
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
            WallClock.offsetMillis = best.offsetMillis
            Log.i("ThinkOS", "time sync: ${best.server} offset=${best.offsetMillis}ms rtt=${best.rttMillis}ms")
            next = untilNextSync()
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
            for (address in addresses) SntpClient.query(address, 2_000)?.let { samples += SntpSample("$host ${address.hostAddress}", it.offsetMillis, it.rttMillis) }
            if (samples.size >= 4) break
        }
        return samples.minByOrNull { it.rttMillis }
    }

    private fun untilNextSync(): Long {
        val now = WallClock.now()
        val at = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, system.timeSyncHour); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now + 60_000) add(Calendar.DAY_OF_MONTH, 1)
        }.timeInMillis
        return at - now + Random.nextLong(0, 10 * 60_000)
    }
}
