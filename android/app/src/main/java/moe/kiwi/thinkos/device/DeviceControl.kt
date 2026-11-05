package moe.kiwi.thinkos.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.wifi.SupplicantState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.kiwi.thinkos.ui.DeviceLevels

/**
 * What the control sheet may read and change about the device itself (ADR-035): the screen brightness, the media volume, mute,
 * and the Wi-Fi radio. Device controls require the factory `system.device` grant; API 10 plugins may read the current
 * day/redshift/sleep mode without that grant. No plugin receives direct AudioManager or mode storage access.
 *
 * Reads are cheap and safe from any thread. Changes the device makes by itself (a volume key, the network dropping) are
 * announced through [onChange], at most a few times a second.
 */
class DeviceControl(context: Context, private val levels: () -> DeviceLevels, private val onChange: () -> Unit,
                    private val onNetworkChange: () -> Unit = {},
                    private val mode: () -> String = { "day" },
                    private val manualMode: (String) -> Boolean = { false }) {
    private val app = context.applicationContext
    private val audio by lazy { app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private val wifi by lazy { app.getSystemService(Context.WIFI_SERVICE) as WifiManager }
    private val connectivity by lazy { app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val main = Handler(Looper.getMainLooper())
    private val sleepAudio = SleepAudioGate(object : SleepAudioGate.Volumes {
        override fun read(stream: Int): Int? = runCatching { audio.getStreamVolume(stream) }
            .onFailure { Log.w(TAG, "Unable to read sleep volume", it) }.getOrNull()
        override fun maximum(stream: Int): Int? = runCatching { audio.getStreamMaxVolume(stream) }.getOrNull()
        override fun write(stream: Int, index: Int): Boolean = runCatching { audio.setStreamVolume(stream, index, 0); true }
            .onFailure { Log.w(TAG, "Unable to set sleep volume", it) }.getOrDefault(false)
    }, SleepAudioJournal(java.io.File(app.filesDir, "state/sleep-audio")))
    private var started = false
    @Volatile private var networkChanged = false
    private val announce = Runnable {
        onChange()
        if (networkChanged) { networkChanged = false; onNetworkChange() }
    }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action in setOf(VOLUME_CHANGED, MUTE_CHANGED)) syncSleepAudio()
            if (intent.action !in setOf(VOLUME_CHANGED, MUTE_CHANGED)) networkChanged = true
            changed()
        }
    }

    /** A snapshot for `tos.device.get()`: only the keys that have a value are present. */
    fun snapshot(): Map<String, Any?> {
        val level = levels()
        val result = LinkedHashMap<String, Any?>()
        result["brightness"] = Math.round(level.brightness * 100f).toLong()
        result["volume"] = Math.round(level.volume * 100f).toLong()
        result["muted"] = muted()
        result["wifi"] = wifiState()
        result["online"] = runCatching { connectivity.activeNetworkInfo?.isConnected == true }.getOrDefault(false)
        return result
    }

    private fun muted(): Boolean = runCatching { audio.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)

    private fun wifiState(): Map<String, Any?> {
        val state = LinkedHashMap<String, Any?>()
        val enabled = runCatching { wifi.isWifiEnabled }.getOrDefault(false)
        state["enabled"] = enabled
        val info = if (enabled) runCatching { wifi.connectionInfo }.getOrNull() else null
        val connected = info != null && info.supplicantState == SupplicantState.COMPLETED && info.networkId != -1
        state["connected"] = connected
        if (info != null && connected) {
            // Android 8.1 reports "<unknown ssid>" unless the app may see the location and location services are on. The network's own
            // description carries the name without that, so it is the second place to look; failing both, only signal and address show.
            (ssid(info.ssid) ?: ssid(runCatching { connectivity.activeNetworkInfo?.takeIf { it.type == ConnectivityManager.TYPE_WIFI }?.extraInfo }.getOrNull()))
                ?.let { state["ssid"] = it }
            state["level"] = WifiManager.calculateSignalLevel(info.rssi, 5).toLong()
            address(info.ipAddress)?.let { state["ip"] = it }
        }
        return state
    }

    fun setWifi(enabled: Boolean) {
        runCatching {
            @Suppress("DEPRECATION")
            wifi.isWifiEnabled = enabled
        }.onFailure { Log.w(TAG, "Unable to switch Wi-Fi", it) }
        networkChanged = true
        changed()
    }

    fun setMuted(muted: Boolean) {
        if (currentMode() == "sleep") { syncSleepAudio(); changed(); return }
        runCatching { audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE, 0) }
            .onFailure { Log.w(TAG, "Unable to change mute", it) }
        changed()
    }

    /** Listens for the changes the device makes on its own. Safe to call twice. */
    fun start() {
        if (started) return
        started = true
        app.registerReceiver(receiver, IntentFilter().apply {
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
            addAction(VOLUME_CHANGED)
            addAction(MUTE_CHANGED)
        })
        onModeChanged()
    }

    /** Read-only for plugins; the host owns the actual mode and the manual override policy. */
    fun currentMode(): String = mode().takeIf { it in setOf("day", "redshift", "sleep") } ?: "day"

    fun setMode(value: String): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(value in setOf("day", "redshift", "sleep")) { "unknown device mode" }
        return manualMode(value)
    }

    /** Called after the host publishes its new mode. No media is resumed when the fence opens. */
    fun onModeChanged() {
        check(Looper.myLooper() == Looper.getMainLooper())
        syncSleepAudio()
        changed()
    }

    private fun syncSleepAudio() {
        runCatching { sleepAudio.update(currentMode() == "sleep") }
            .onFailure { Log.e(TAG, "Unable to reconcile sleep audio recovery", it) }
    }

    fun stop() {
        if (!started) return
        started = false
        runCatching { app.unregisterReceiver(receiver) }
        main.removeCallbacks(announce)
        networkChanged = false
    }

    /** Called when something here may have changed, whoever changed it; announcements are coalesced. */
    fun changed() {
        main.removeCallbacks(announce)
        main.postDelayed(announce, 250)
    }

    companion object {
        private const val TAG = "ThinkOS"
        // Not in the public SDK, but broadcast by every Android release for the media stream.
        private const val VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION"
        private const val MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION"

        /** `"Home"` -> `Home`; Android's placeholder for a name it will not tell becomes null. */
        internal fun ssid(raw: String?): String? {
            val name = raw?.trim()?.removeSurrounding("\"").orEmpty()
            return name.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID && !it.equals("<unknown ssid>", true) }
        }

        /** Android hands the IPv4 address over as a little-endian int; 0 means none. */
        internal fun address(value: Int): String? =
            if (value == 0) null else "${value and 0xff}.${value shr 8 and 0xff}.${value shr 16 and 0xff}.${value shr 24 and 0xff}"
    }
}
