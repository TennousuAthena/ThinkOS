package moe.kiwi.thinkos.cast

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.util.Log
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.cast.airplay.AirPlayDevice
import moe.kiwi.thinkos.cast.airplay.AirPlayIdentity
import moe.kiwi.thinkos.cast.airplay.AirPlayRecord
import moe.kiwi.thinkos.cast.airplay.NativeFairPlay
import moe.kiwi.thinkos.cast.airplay.PlayFair
import moe.kiwi.thinkos.cast.airplay.AirPlayService
import moe.kiwi.thinkos.cast.dlna.DeviceDescription
import moe.kiwi.thinkos.cast.dlna.SsdpService
import moe.kiwi.thinkos.cast.dlna.UpnpDevice
import moe.kiwi.thinkos.cast.miracast.LocalSocketWfdTransport
import moe.kiwi.thinkos.cast.miracast.MiracastService
import moe.kiwi.thinkos.cast.miracast.WfdControl
import moe.kiwi.thinkos.core.DeckRouter
import moe.kiwi.thinkos.media.MediaEngine
import moe.kiwi.thinkos.net.LocalNetworkPolicy
import moe.kiwi.thinkos.settings.SystemValues
import moe.kiwi.thinkos.vision.SensorController
import java.net.Inet4Address
import java.net.InetAddress
import java.io.File
import java.io.IOException
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Receives casts from phones and computers (ADR-031): DLNA / UPnP and AirPlay video on the local network, Miracast screen sharing
 * over Wi-Fi Direct. Everything is local to the device (ADR-015).
 *
 * Each protocol is off by default (`cast.enabled`, `cast.airplay`, `cast.miracast`): while one is on, the device can be found
 * and anyone in reach can start a cast, so only private-network peers are served and pushed addresses are checked.
 * Playback is the OS media session (owner `cast:<protocol>`); the screen is the `cast` plugin's live page.
 */
class CastHost(
    private val context: Context,
    private val system: SystemValues,
    private val media: MediaEngine,
    private val router: DeckRouter,
    private val sensors: SensorController,
    private val main: Handler,
    private val sleeping: () -> Boolean = { false },
    private val onForegroundRequested: () -> Unit = {},
    private val onStatusChanged: () -> Unit = {},
) {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "ThinkOS-Cast").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val appContext = context.applicationContext
    private var multicastLock: WifiManager.MulticastLock? = null
    private var ssdp: SsdpService? = null
    private var upnp: UpnpDevice? = null
    @Volatile private var address: Pair<NetworkInterface, Inet4Address>? = null
    private var airplay: AirPlayService? = null
    private var startedFor: List<Any>? = null     // the address and which protocols the network services were started for
    private var port = 0
    private var focused: String? = null
    private var networkPoll: ScheduledFuture<*>? = null
    private var publishedStatus: Map<String, Any?>? = null

    private val castIdentity by lazy { CastIdentity.load(File(appContext.filesDir, "cast/identity-seed")) }

    private val player = EngineCastPlayer(media, main, OWNER, { kind -> show(kind) }, { release() })
    private val airplayPlayer = EngineCastPlayer(media, main, AirPlayService.OWNER, { kind -> show(kind) }, { release() })
    /** Read at each start: mirroring is a setting, and it needs the FairPlay library to be there. */
    private fun airplayDevice(): AirPlayDevice {
        val id = AirPlayRecord.deviceId(castIdentity.airplaySeed)
        return AirPlayDevice(id, { system.castName.ifBlank { DEFAULT_NAME } }, mirror = system.castAirplayMirror && PlayFair.isAvailable,
            identityKey = { airplayIdentity.publicKey }, identityId = AirPlayRecord.identityId(id))
    }
    private val airplayIdentity by lazy { AirPlayIdentity.load(File(appContext.filesDir, "cast/airplay-identity")) }
    private val miracast = MiracastService(appContext, WfdControl(LocalSocketWfdTransport()), media, main,
        EngineCastPlayer(media, main, MiracastService.OWNER, { kind -> show(kind) }, { release() }),
        { system.castName.ifBlank { DEFAULT_NAME } }, { Log.i(TAG, it) },
        onAvailabilityChanged = {
            // Miracast can be the only enabled receiver, with no LAN poll.
            // Re-read on the existing cast worker; never publish a captured
            // old Boolean after disable/reconfigure has superseded it.
            worker.execute { publishStatusIfChanged() }
        })

    /** For `tos.cast.status()`: the name senders see, which receivers are switched on, and the address they would find the device at. */
    fun status(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>()
        result["name"] = system.castName.ifBlank { DEFAULT_NAME }
        result["dlna"] = system.castEnabled
        result["airplay"] = system.castAirplay
        result["mirror"] = system.castAirplay && system.castAirplayMirror
        result["miracast"] = system.castMiracast
        // Switched on is not the same as working: the receiver needs the ThinkOS Cast system module (ADR-033), which a person installs.
        miracast.moduleAvailable?.let { result["miracastModule"] = it }
        address?.second?.hostAddress?.let { result["ip"] = it }
        return result
    }

    /** Applies `cast.enabled` and `cast.name`. Safe to call at any time and from any thread. */
    fun reconfigure() { worker.execute { apply() } }

    /** Admission uses the live getter immediately; service teardown then follows on its worker. */
    fun onModeChanged() {
        if (sleeping()) {
            val owner = media.snapshot()["owner"]
            if (owner in setOf(OWNER, AirPlayService.OWNER, MiracastService.OWNER)) { media.stop(); release() }
        }
        reconfigure()
    }

    /** The engine published new numbers (main thread). */
    fun onMediaState(state: Map<String, Any?>) {
        miracast.onMediaState(state)
        airplay?.onMediaState(state)
        val device = upnp ?: return
        val view = CastStates.from(state, OWNER)
        worker.execute { runCatching { device.playerChanged(view) } }
    }

    fun stop() {
        miracast.shutdown()
        worker.execute { shutdown() }
    }

    /** Debug builds only (a no-op otherwise): play the part of a phone that shares its screen from [host]:[port]. */
    fun dialMiracastForTest(host: String, port: Int) = miracast.dialForTest(host, port)

    // ---- lifecycle (worker thread) -------------------------------------------------------------------------------

    private fun apply() {
        try {
            miracast.reconfigure(system.castMiracast && !sleeping())
            if (sleeping()) { shutdown(); return }
            if (!system.castEnabled && !system.castAirplay) { shutdown(); return }
            if (networkPoll == null) {
                // Network broadcasts trigger immediate checks; retain a fallback for interface changes.
                networkPoll = worker.scheduleWithFixedDelay({ runCatching { follow() } }, NETWORK_CHECK_SECONDS, NETWORK_CHECK_SECONDS, TimeUnit.SECONDS)
            }
            follow()
        } finally {
            publishStatusIfChanged()
        }
    }

    private fun follow() {
        try {
            if (sleeping()) { shutdown(); return }
            if (!system.castEnabled && !system.castAirplay) return
            val now = findNetwork()
            val wanted = now?.let { listOf(it.second, system.castEnabled, system.castAirplay, system.castAirplay && system.castAirplayMirror) }
            val running = upnp != null || airplay != null
            if (now != null && running && wanted == startedFor) return
            if (running) shutdown(keepPolling = true)
            if (now != null) startServices(now)
        } finally {
            publishStatusIfChanged()
        }
    }

    /** Runs on the cast worker: unchanged polling results produce no Lua work. */
    private fun publishStatusIfChanged() {
        val next = status()
        if (next != publishedStatus) {
            publishedStatus = next
            onStatusChanged()
        }
    }

    private fun startServices(network: Pair<NetworkInterface, Inet4Address>) {
        if (sleeping()) return
        // Identity creation stays on the cast worker. Storage failures must not
        // crash the OS or announce a shared fallback discovery identifier.
        try { castIdentity } catch (error: IOException) {
            Log.e(TAG, "Unable to load cast discovery identity", error)
            return
        }
        if (system.castEnabled) startDlna(network)
        if (system.castAirplay) startAirplay(network)
        address = network
        startedFor = listOf(network.second, system.castEnabled, system.castAirplay, system.castAirplay && system.castAirplayMirror)
    }

    private fun startDlna(network: Pair<NetworkInterface, Inet4Address>) {
        val device = UpnpDevice(castIdentity.uuid, { system.castName.ifBlank { DEFAULT_NAME } }, player, ::validate, ::accept, { Log.w(TAG, it) })
        val search: SsdpService
        try {
            port = device.start(PREFERRED_PORT, network.second)
            val lock = (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).createMulticastLock("thinkos-cast")
            lock.setReferenceCounted(false); lock.acquire(); multicastLock = lock
            search = SsdpService(castIdentity.uuid, { "http://${network.second.hostAddress}:$port${DeviceDescription.PATH}" }, ::accept, { Log.w(TAG, it) })
            search.start(network.first)
        } catch (error: Exception) {
            Log.e(TAG, "DLNA could not start on ${network.second.hostAddress}: ${error.message}")
            device.stop(); releaseLock()
            return
        }
        upnp = device; ssdp = search
        Log.i(TAG, "DLNA renderer \"${system.castName}\" at http://${network.second.hostAddress}:$port${DeviceDescription.PATH}")
    }

    private fun startAirplay(network: Pair<NetworkInterface, Inet4Address>) {
        val service = AirPlayService(appContext.getSystemService(Context.NSD_SERVICE) as android.net.nsd.NsdManager, airplayDevice(), airplayIdentity,
            airplayPlayer, ::validate, ::accept, worker, { Log.i(TAG, it) }, NativeFairPlay)
        try { service.start(network.second) } catch (error: Exception) {
            Log.e(TAG, "AirPlay could not start on ${network.second.hostAddress}: ${error.message}")
            service.stop()
            return
        }
        airplay = service
    }

    private fun shutdown(keepPolling: Boolean = false) {
        if (!keepPolling) { networkPoll?.cancel(false); networkPoll = null }
        runCatching { ssdp?.stop() }
        runCatching { upnp?.stop() }
        runCatching { airplay?.stop() }
        ssdp = null; upnp = null; airplay = null; address = null; startedFor = null
        releaseLock()
        // Turning casting off ends a cast that is still on the screen.
        main.post {
            val owner = media.snapshot()["owner"]
            if (sleeping() && owner in setOf(OWNER, AirPlayService.OWNER, MiracastService.OWNER) ||
                owner == OWNER && !system.castEnabled || owner == AirPlayService.OWNER && !system.castAirplay) { media.stop(); release() }
        }
    }

    private fun releaseLock() {
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        multicastLock = null
    }

    /** The Wi-Fi or Ethernet address a controller can reach. Wi-Fi Direct and mobile data interfaces are not it. */
    private fun findNetwork(): Pair<NetworkInterface, Inet4Address>? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && it.supportsMulticast() && (it.name.startsWith("wlan") || it.name.startsWith("eth")) }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .firstNotNullOfOrNull { nif ->
                nif.inetAddresses.toList().filterIsInstance<Inet4Address>().firstOrNull { it.isSiteLocalAddress }?.let { nif to it }
            }
    } catch (error: Exception) { null }

    // ---- policy --------------------------------------------------------------------------------------------------

    /** Only machines on the local network may talk to the renderer. (Loopback is for the debug build's adb tests.) */
    private fun accept(peer: InetAddress): Boolean =
        !sleeping() && LocalNetworkPolicy.privateAddress(peer) && (BuildConfig.DEBUG || !peer.isLoopbackAddress)

    /** A pushed address must not point the device at itself; whether it may be fetched at all is the media policy's call. */
    private fun validate(uri: java.net.URI) {
        check(!sleeping()) { "asleep" }
        val host = uri.host.orEmpty().lowercase()
        val literal = if (host.firstOrNull()?.let { it.isDigit() || it == '[' } == true) runCatching { InetAddress.getByName(host) }.getOrNull() else null
        val itself = host == "localhost" || host.endsWith(".localhost") || literal?.let { it.isLoopbackAddress || it.isAnyLocalAddress } == true
        require(BuildConfig.DEBUG || !itself) { "A cast cannot point at this device" }
    }

    // ---- the screen (main thread) --------------------------------------------------------------------------------

    private fun show(kind: String) {
        if (sleeping()) return
        // A cast is somebody acting on another device: wake the screen like a touch would, then take it over.
        sensors.touch()
        onForegroundRequested()
        val page = "cast.$kind"
        if (focused != null && focused != page) router.requestFocus(focused!!, live = true, release = true)
        focused = page
        router.requestFocus(page, live = true, release = false, answersTap = true)
    }

    private fun release() {
        focused?.let { router.requestFocus(it, live = true, release = true) }
        focused = null
    }

    companion object {
        const val OWNER = "cast:dlna"
        const val DEFAULT_NAME = "ThinkOS"
        private const val TAG = "ThinkOS"
        private const val PREFERRED_PORT = 49152
        private const val NETWORK_CHECK_SECONDS = 15L
    }
}
