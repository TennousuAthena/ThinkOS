package moe.kiwi.thinkos.cast.airplay

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import moe.kiwi.thinkos.cast.CastPlayer
import moe.kiwi.thinkos.cast.CastState
import moe.kiwi.thinkos.cast.CastStates
import moe.kiwi.thinkos.cast.CastStatus
import moe.kiwi.thinkos.cast.EngineCastPlayer
import moe.kiwi.thinkos.media.MediaEntry
import moe.kiwi.thinkos.media.MirrorSource
import java.net.Inet4Address
import java.net.InetAddress
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/** The two TXT records as bytes, in the order the announcement carries them. */
internal class AirPlayTxt(val airplay: ByteArray, val raop: ByteArray)

/** The mDNS records that make iPhones, iPads and Macs list this device as an AirPlay target. Pure, so they are tested without Android. */
internal object AirPlayRecord {
    const val SERVICE_TYPE = "_airplay._tcp."
    const val RAOP_TYPE = "_raop._tcp."

    /**
     * What the `_airplay._tcp` record says. A mirroring device also publishes its public key (`pk`: what `/pair-setup` hands out, which the
     * sender checks), a `pi`, `vv` and `pw`; the same set UxPlay announces.
     */
    fun attributes(device: AirPlayDevice): Map<String, String> {
        val out = linkedMapOf("deviceid" to device.deviceId, "features" to device.featureText)
        if (device.mirror) out["pw"] = "false"
        out["flags"] = "0x%X".format(Locale.ROOT, AirPlayDevice.STATUS_FLAGS)
        out["model"] = device.model
        if (device.mirror) { out["pk"] = hex(device.identityKey()); out["pi"] = device.identityId }
        out["srcvers"] = device.sourceVersion
        if (device.mirror) out["vv"] = "2"
        return out
    }

    /** The audio half of AirPlay. Senders look for it even to mirror a screen; its name starts with the device id without colons. */
    fun raopName(device: AirPlayDevice): String = device.deviceId.replace(":", "") + "@" + device.name()

    fun raopAttributes(device: AirPlayDevice): Map<String, String> = linkedMapOf(
        "ch" to "2", "cn" to "0,1,2,3", "da" to "true", "et" to "0,3,5", "vv" to "2", "ft" to device.featureText, "am" to device.model,
        "md" to "0,1,2", "rhd" to "5.6.0.0", "pw" to "false", "sr" to "44100", "ss" to "16", "sv" to "false", "tp" to "UDP",
        "txtvers" to "1", "sf" to "0x%X".format(Locale.ROOT, AirPlayDevice.STATUS_FLAGS), "vs" to device.sourceVersion, "vn" to "65537",
        "pk" to hex(device.identityKey()),
    )

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    /** DNS-SD TXT record data: every `key=value` behind a length byte. */
    fun txtBytes(attributes: Map<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        for ((key, value) in attributes) {
            val pair = key.toByteArray(Charsets.UTF_8) + '='.code.toByte() + value
            require(pair.size <= 255) { "TXT entry too long" }
            out.write(pair.size); out.write(pair)
        }
        return out.toByteArray()
    }

    /** A stable, locally administered, unicast "MAC address" derived from something the device already has. */
    fun deviceId(seed: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest("thinkos-airplay:$seed".toByteArray())
        digest[0] = ((digest[0].toInt() or 0x02) and 0xFE).toByte()
        return (0 until 6).joinToString(":") { "%02X".format(Locale.ROOT, digest[it].toInt() and 0xff) }
    }

    /** A stable UUID for the `pi` field, derived from the device id. */
    fun identityId(deviceId: String): String = java.util.UUID.nameUUIDFromBytes("thinkos-airplay-pi:$deviceId".toByteArray()).toString()
}

/**
 * Plays what an AirPlay sender asks for through the OS media session (owner `cast:airplay`). Pure policy and bookkeeping on top of
 * [EngineCastPlayer]; the protocol is in [AirPlayServer].
 */
internal class EngineAirPlayControl(
    private val player: CastPlayer,
    /** Throws [IllegalArgumentException] when this address must not be fetched (the same rule DLNA applies). */
    private val validate: (URI) -> Unit,
    private val sendEvent: (String) -> Unit,
    private val worker: ScheduledExecutorService,
    private val log: (String) -> Unit,
) : AirPlayControl {
    private var lastEvent = ""

    override fun play(request: AirPlayPlay) {
        val uri = try { URI(request.url) } catch (error: URISyntaxException) { throw IllegalArgumentException("not an address") }
        // Apps that relay HLS through the phone (mlhls:// and friends) have no address this device could fetch.
        require(uri.scheme == "http" || uri.scheme == "https") { "only http and https addresses can be played, not ${uri.scheme}" }
        validate(uri)
        val entry = MediaEntry(request.url, title = "AirPlay", kind = "video")
        lastEvent = "loading"                  // the player's own "buffering" that follows is the same news
        sendEvent("loading")
        log("AirPlay plays ${uri.scheme}://${uri.host}${uri.path.take(60)}")
        player.load(entry, autoplay = true)
        val start = request.startSeconds
        if (start != null && start > 0) worker.schedule({ seekWhenKnown(null, start) }, 300, TimeUnit.MILLISECONDS)
        else if (request.startFraction > 0.0) worker.schedule({ seekWhenKnown(request.startFraction, null) }, 300, TimeUnit.MILLISECONDS)
    }

    /** The start position arrives as a fraction of a duration nobody knows yet: wait until the player does. */
    private fun seekWhenKnown(fraction: Double?, seconds: Double?, attempt: Int = 0) {
        try {
            val state = player.state()
            if (!state.owned) return
            if (seconds != null) { player.seek((seconds * 1000).toLong()); return }
            if (state.durationMillis > 0) { player.seek((fraction!! * state.durationMillis).toLong()); return }
        } catch (error: Exception) { return }
        if (attempt < 40) worker.schedule({ seekWhenKnown(fraction, seconds, attempt + 1) }, 250, TimeUnit.MILLISECONDS)
    }

    override fun rate(value: Float) { if (value <= 0f) player.pause() else player.play() }
    override fun scrub(positionSeconds: Double) { if (positionSeconds >= 0) player.seek((positionSeconds * 1000).toLong()) }
    override fun stop() { player.stop(); sendEvent("stopped") }

    override fun playback(): AirPlayPlayback? {
        val state = player.state()
        if (!state.owned || state.status == CastStatus.IDLE) return null
        return AirPlayPlayback(
            durationSeconds = state.durationMillis / 1000.0, positionSeconds = state.positionMillis / 1000.0,
            rate = if (state.status == CastStatus.PLAYING) 1f else 0f,
            ready = state.status != CastStatus.ERROR, buffering = state.status == CastStatus.BUFFERING,
        )
    }

    /** The engine published new numbers: tell the sender what its video is doing, once per change. */
    fun onState(state: CastState) {
        if (!state.owned) return
        val event = when (state.status) {
            CastStatus.PLAYING -> "playing"
            CastStatus.PAUSED -> "paused"
            CastStatus.BUFFERING -> "loading"
            CastStatus.ENDED, CastStatus.ERROR -> "stopped"
            CastStatus.IDLE -> return
        }
        if (event == lastEvent) return
        lastEvent = event
        sendEvent(event)
        if (event == "stopped") worker.execute { runCatching { player.stop() } }       // the film is over, or it cannot be played: give the screen back
    }
}

/** Receives video, and screens, from AirPlay senders (ADR-031): mDNS records, the server, and the player behind them. One instance per network address. */
internal class AirPlayService(
    private val nsd: NsdManager,
    private val device: AirPlayDevice,
    private val identity: AirPlayIdentity,
    private val player: EngineCastPlayer,
    private val validate: (URI) -> Unit,
    private val accept: (InetAddress) -> Boolean,
    private val worker: ScheduledExecutorService,
    private val log: (String) -> Unit,
    private val fairPlay: FairPlayKeys? = null,
) {
    private var server: AirPlayServer? = null
    private var control: EngineAirPlayControl? = null
    private var mirror: AirPlayMirror? = null
    private val registrations = ArrayList<NsdManager.RegistrationListener>()
    @Volatile var port = 0
        private set

    private val screen = object : MirrorScreen {
        override fun show(source: MirrorSource, title: String) { player.loadMirror(source, title) }
        override fun hide() { player.stop(); player.releaseScreen() }
    }

    fun start(address: Inet4Address) {
        val c = EngineAirPlayControl(player, validate, { server?.sendEvent(it) }, worker, log)
        val m = if (device.mirror && fairPlay != null) AirPlayMirror(screen, fairPlay, log, setVolume = { fraction -> runCatching { player.volume(fraction) } },
            audioDecoder = { rate, channels, _, out -> AacTranscoder(rate, channels, out, log) }, peerAllowed = accept) else null
        // The announcement is built first: a sender asks `/info` for the very bytes of the TXT records and compares them with what it found by mDNS,
        // so those bytes are taken from the service info itself, in the order the platform will announce them.
        val airplay = serviceInfo(device.name(), AirPlayRecord.SERVICE_TYPE, AirPlayRecord.attributes(device))
        val raop = if (m != null) serviceInfo(AirPlayRecord.raopName(device), AirPlayRecord.RAOP_TYPE, AirPlayRecord.raopAttributes(device)) else null
        val records = AirPlayTxt(AirPlayRecord.txtBytes(airplay.attributes), raop?.let { AirPlayRecord.txtBytes(it.attributes) } ?: ByteArray(0))
        val s = AirPlayServer(device, identity, c, accept, log, m, if (m != null) fairPlay else null, { records })
        // A mirroring sender may reach the device over IPv6 link-local too; video alone is served on the one address.
        port = s.start(PREFERRED_PORT, if (m != null) null else address)
        control = c; server = s; mirror = m
        register(airplay); raop?.let(::register)
    }

    private fun serviceInfo(name: String, type: String, attributes: Map<String, String>) = NsdServiceInfo().apply {
        serviceName = name
        serviceType = type
        attributes.forEach { (k, v) -> setAttribute(k, v) }
    }

    private fun register(info: NsdServiceInfo) {
        info.port = port
        val type = info.serviceType
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = log("AirPlay is visible as \"${info.serviceName}\" ($type) on port $port")
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) = log("AirPlay could not be announced ($type): $error")
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) {}
        }
        registrations += listener
        try { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener) } catch (error: Exception) { log("AirPlay could not be announced ($type): ${error.message}") }
    }

    fun stop() {
        registrations.forEach { runCatching { nsd.unregisterService(it) } }
        registrations.clear()
        mirror?.shutdown()
        server?.stop(); server = null; control = null; mirror = null
    }

    /** The engine published new numbers (any thread; the work is queued). */
    fun onMediaState(snapshot: Map<String, Any?>) {
        val c = control ?: return
        val m = mirror
        if (snapshot["mirror"] == true) { m?.onEngineState(snapshot, OWNER); return }
        val state = CastStates.from(snapshot, OWNER)
        worker.execute { runCatching { c.onState(state) } }
        m?.onEngineState(snapshot, OWNER)
    }

    companion object {
        const val OWNER = "cast:airplay"
        const val PREFERRED_PORT = 7000
    }
}
