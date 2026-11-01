package moe.kiwi.thinkos.cast.dlna

import moe.kiwi.thinkos.cast.CastPlayer
import moe.kiwi.thinkos.cast.CastState
import moe.kiwi.thinkos.cast.CastStatus
import moe.kiwi.thinkos.media.MediaEntry
import java.net.URI
import java.util.Locale

/** `H:MM:SS(.mmm)`, the way UPnP writes positions. */
internal object UpnpTime {
    fun format(millis: Long): String {
        val total = millis.coerceAtLeast(0) / 1000
        return "%d:%02d:%02d".format(Locale.ROOT, total / 3600, total / 60 % 60, total % 60)
    }

    /** Null when [text] is not a time. A leading `+` or `-` is accepted and ignored: controllers use REL_TIME as a position. */
    fun parse(text: String): Long? {
        val body = text.trim().trimStart('+', '-')
        val parts = body.split(':')
        if (parts.size != 3) return null
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val seconds = parts[2].toDoubleOrNull() ?: return null
        if (hours < 0 || minutes !in 0..59 || seconds < 0 || seconds >= 60 || seconds.isNaN()) return null
        return hours * 3_600_000 + minutes * 60_000 + (seconds * 1000).toLong()
    }
}

/**
 * The three services of a UPnP MediaRenderer (AVTransport, RenderingControl, ConnectionManager), speaking only to a
 * [CastPlayer]. It has no sockets and no Android classes; [handle] is called on the HTTP threads, [playerChanged] on one
 * worker thread (never the main thread: [CastPlayer.state] waits for the main thread).
 *
 * [validate] is the host's rule for pushed addresses (it throws [IllegalArgumentException]); [publish] receives the
 * `LastChange` document of a service whenever something a controller subscribed to changed.
 */
internal class DlnaRenderer(
    private val player: CastPlayer,
    private val validate: (URI) -> Unit,
    private val publish: (ServiceDef, String) -> Unit,
) {
    private var uri = ""
    private var meta = ""
    private var nextUri = ""
    private var nextMeta = ""
    private var entry: MediaEntry? = null
    /** Play has been asked for since the address was set: before that the renderer reports STOPPED, not PAUSED_PLAYBACK. */
    private var started = false
    private var muted = false
    private var volumeBeforeMute = 1f
    private var lastAvt = ""
    private var lastRcs = ""

    @Synchronized
    fun handle(call: SoapCall): String {
        val service = Services.ALL.firstOrNull { it.type == call.serviceType } ?: throw SoapFault(401, "Unknown service")
        if (service.actions.none { it.name == call.action }) throw SoapFault(401, "Invalid action")
        if (call.arguments.containsKey("InstanceID") && call.arguments["InstanceID"] != "0") throw SoapFault(718, "Invalid InstanceID")
        val out = when (service) {
            Services.AV_TRANSPORT -> avTransport(call)
            Services.RENDERING_CONTROL -> renderingControl(call)
            else -> connectionManager(call)
        }
        return Soap.response(call.serviceType, call.action, out)
    }

    /** Full state for a new subscriber (the first event a controller expects). */
    @Synchronized
    fun snapshot(service: ServiceDef): String? = when (service) {
        Services.AV_TRANSPORT -> avtDocument(player.state())
        Services.RENDERING_CONTROL -> rcsDocument(player.state())
        else -> null
    }

    /** The engine reported new numbers: tell subscribers what changed, and move on to the queued address when one finished. */
    @Synchronized
    fun playerChanged(state: CastState) {
        if (state.owned && state.status == CastStatus.ENDED && started && nextUri.isNotEmpty()) {
            val next = nextUri
            val metadata = nextMeta
            nextUri = ""; nextMeta = ""
            runCatching { setUri(next, metadata, autoplay = true) }
            return
        }
        val avt = avtDocument(state)
        if (avt != lastAvt) { lastAvt = avt; publish(Services.AV_TRANSPORT, avt) }
        val rcs = rcsDocument(state)
        if (rcs != lastRcs) { lastRcs = rcs; publish(Services.RENDERING_CONTROL, rcs) }
    }

    // ---- AVTransport ---------------------------------------------------------------------------------------------

    private fun avTransport(call: SoapCall): List<Pair<String, String>> {
        val args = call.arguments
        return when (call.action) {
            "SetAVTransportURI" -> {
                setUri(args["CurrentURI"].orEmpty(), args["CurrentURIMetaData"].orEmpty(), autoplay = false)
                emptyList()
            }
            "SetNextAVTransportURI" -> {
                val next = args["NextURI"].orEmpty()
                if (next.isNotEmpty()) parseUri(next)
                nextUri = next; nextMeta = args["NextURIMetaData"].orEmpty()
                emptyList()
            }
            "Play" -> {
                if (uri.isEmpty()) throw SoapFault(701, "Nothing to play")
                val state = player.state()
                started = true
                // After Stop, or when someone else has used the session since, play means "start again".
                if (!state.owned || state.status == CastStatus.IDLE || state.status == CastStatus.ENDED || state.status == CastStatus.ERROR) {
                    setUri(uri, meta, autoplay = true)
                } else player.play()
                emptyList()
            }
            "Pause" -> {
                if (uri.isEmpty()) throw SoapFault(701, "Nothing to pause")
                player.pause(); emptyList()
            }
            "Stop" -> {
                player.stop(); started = false; emptyList()
            }
            "Seek" -> {
                if (uri.isEmpty()) throw SoapFault(701, "Nothing to seek in")
                val target = args["Target"].orEmpty()
                val position = when (args["Unit"]) {
                    "REL_TIME", "ABS_TIME" -> UpnpTime.parse(target)
                    "TRACK_NR" -> if (target.trim() == "1") 0L else null
                    else -> throw SoapFault(710, "Seek mode not supported")
                } ?: throw SoapFault(711, "Illegal seek target")
                val duration = player.state().durationMillis
                if (duration > 0 && position > duration) throw SoapFault(711, "Illegal seek target")
                player.seek(position); emptyList()
            }
            "Next" -> {
                if (nextUri.isEmpty()) throw SoapFault(701, "No next item")
                val next = nextUri; val metadata = nextMeta
                nextUri = ""; nextMeta = ""
                setUri(next, metadata, autoplay = true); started = true
                emptyList()
            }
            "Previous" -> throw SoapFault(701, "No previous item")
            "SetPlayMode" -> if (args["NewPlayMode"] == "NORMAL") emptyList() else throw SoapFault(712, "Play mode not supported")
            "GetTransportInfo" -> {
                val (transport, status) = transport(player.state())
                listOf("CurrentTransportState" to transport, "CurrentTransportStatus" to status, "CurrentSpeed" to "1")
            }
            "GetPositionInfo" -> {
                val state = player.state()
                val present = uri.isNotEmpty() && state.owned
                val position = UpnpTime.format(if (present) state.positionMillis else 0)
                listOf("Track" to (if (uri.isEmpty()) "0" else "1"), "TrackDuration" to UpnpTime.format(if (present) state.durationMillis else 0),
                    "TrackMetaData" to meta, "TrackURI" to uri, "RelTime" to position, "AbsTime" to position,
                    "RelCount" to "2147483647", "AbsCount" to "2147483647")
            }
            "GetMediaInfo" -> {
                val state = player.state()
                listOf("NrTracks" to (if (uri.isEmpty()) "0" else "1"), "MediaDuration" to UpnpTime.format(if (state.owned) state.durationMillis else 0),
                    "CurrentURI" to uri, "CurrentURIMetaData" to meta, "NextURI" to nextUri, "NextURIMetaData" to nextMeta,
                    "PlayMedium" to (if (uri.isEmpty()) "NONE" else "NETWORK"), "RecordMedium" to "NOT_IMPLEMENTED", "WriteStatus" to "NOT_IMPLEMENTED")
            }
            "GetDeviceCapabilities" -> listOf("PlayMedia" to "NETWORK", "RecMedia" to "NOT_IMPLEMENTED", "RecQualityModes" to "NOT_IMPLEMENTED")
            "GetTransportSettings" -> listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            "GetCurrentTransportActions" -> listOf("Actions" to actions(player.state()))
            else -> throw SoapFault(401, "Invalid action")
        }
    }

    /** Takes a pushed address: checks it, loads it paused (the controller sends Play next), and remembers what it was. */
    private fun setUri(address: String, metadata: String, autoplay: Boolean) {
        if (address.isEmpty()) {
            player.stop(); uri = ""; meta = ""; entry = null; started = false
            return
        }
        val parsed = parseUri(address)
        val info = Didl.parse(metadata, address)
        val item = try {
            MediaEntry(address, title = info.title.ifEmpty { parsed.host.orEmpty() }, artist = info.artist, album = info.album, art = info.art,
                kind = info.kind ?: "video", mimeType = info.mime?.takeIf(::knownMime), subtitles = info.subtitles)
        } catch (error: IllegalArgumentException) {
            throw SoapFault(402, "Address not accepted")
        }
        try { player.load(item, autoplay) } catch (error: RuntimeException) { throw SoapFault(501, "Could not start playback") }
        uri = address; meta = metadata; entry = item; started = autoplay
    }

    private fun parseUri(address: String): URI {
        val parsed = try { URI(address) } catch (error: Exception) { throw SoapFault(402, "Invalid address") }
        if (parsed.scheme != "http" && parsed.scheme != "https" || parsed.host.isNullOrBlank() || parsed.userInfo != null) throw SoapFault(402, "Only http and https addresses")
        try { validate(parsed) } catch (error: IllegalArgumentException) { throw SoapFault(402, "Address not accepted") }
        return parsed
    }

    private fun knownMime(mime: String) = mime.startsWith("video/") || mime.startsWith("audio/") ||
        mime in setOf(MediaEntry.M3U8, MediaEntry.M3U8_APPLE, MediaEntry.MPD)

    private fun transport(state: CastState): Pair<String, String> {
        if (uri.isEmpty()) return "NO_MEDIA_PRESENT" to "OK"
        if (!state.owned) return "STOPPED" to "OK"
        return when (state.status) {
            CastStatus.PLAYING -> "PLAYING" to "OK"
            CastStatus.BUFFERING -> (if (started) "TRANSITIONING" else "STOPPED") to "OK"
            CastStatus.PAUSED -> (if (started) "PAUSED_PLAYBACK" else "STOPPED") to "OK"
            CastStatus.ERROR -> "STOPPED" to "ERROR_OCCURRED"
            CastStatus.ENDED, CastStatus.IDLE -> "STOPPED" to "OK"
        }
    }

    private fun actions(state: CastState): String = when (transport(state).first) {
        "PLAYING", "TRANSITIONING" -> "Pause,Stop" + (if (state.seekable) ",Seek" else "")
        "PAUSED_PLAYBACK" -> "Play,Stop" + (if (state.seekable) ",Seek" else "")
        "STOPPED" -> "Play" + (if (uri.isNotEmpty()) ",Stop,Seek" else "")
        else -> "Play"
    }

    private fun avtDocument(state: CastState): String {
        val (transport, status) = transport(state)
        fun v(name: String, value: String) = "<$name val=\"${Soap.escape(value)}\"/>"
        val present = uri.isNotEmpty() && state.owned
        return "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/AVT/\"><InstanceID val=\"0\">" +
            v("TransportState", transport) + v("TransportStatus", status) + v("CurrentTransportActions", actions(state)) +
            v("AVTransportURI", uri) + v("AVTransportURIMetaData", meta) + v("CurrentTrackURI", uri) + v("CurrentTrackMetaData", meta) +
            v("NumberOfTracks", if (uri.isEmpty()) "0" else "1") + v("CurrentTrack", if (uri.isEmpty()) "0" else "1") +
            v("CurrentTrackDuration", UpnpTime.format(if (present) state.durationMillis else 0)) +
            v("CurrentMediaDuration", UpnpTime.format(if (present) state.durationMillis else 0)) +
            "</InstanceID></Event>"
    }

    // ---- RenderingControl ----------------------------------------------------------------------------------------

    private fun renderingControl(call: SoapCall): List<Pair<String, String>> {
        val args = call.arguments
        if (call.action in setOf("GetVolume", "SetVolume", "GetMute", "SetMute") && args["Channel"].orEmpty().let { it != "Master" && it.isNotEmpty() }) {
            throw SoapFault(402, "Only the Master channel")
        }
        return when (call.action) {
            "ListPresets" -> listOf("CurrentPresetNameList" to "FactoryDefaults")
            "SelectPreset" -> emptyList()
            "GetVolume" -> listOf("CurrentVolume" to percent(player.state().volume).toString())
            "SetVolume" -> {
                val wanted = args["DesiredVolume"]?.toIntOrNull()?.takeIf { it in 0..100 } ?: throw SoapFault(402, "Volume must be 0 to 100")
                muted = false
                player.volume(wanted / 100f)
                emptyList()
            }
            "GetMute" -> listOf("CurrentMute" to (if (muted) "1" else "0"))
            "SetMute" -> {
                val wanted = when (args["DesiredMute"]?.lowercase(Locale.ROOT)) {
                    "1", "true", "yes" -> true
                    "0", "false", "no" -> false
                    else -> throw SoapFault(402, "Mute must be 0 or 1")
                }
                if (wanted && !muted) { volumeBeforeMute = player.state().volume; player.volume(0f) }
                if (!wanted && muted) player.volume(volumeBeforeMute)
                muted = wanted
                emptyList()
            }
            else -> throw SoapFault(401, "Invalid action")
        }
    }

    private fun percent(volume: Float) = (volume * 100).toInt().coerceIn(0, 100)

    private fun rcsDocument(state: CastState): String =
        "<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/RCS/\"><InstanceID val=\"0\">" +
            "<Volume channel=\"Master\" val=\"${if (muted) percent(volumeBeforeMute) else percent(state.volume)}\"/>" +
            "<Mute channel=\"Master\" val=\"${if (muted) 1 else 0}\"/></InstanceID></Event>"

    // ---- ConnectionManager ---------------------------------------------------------------------------------------

    private fun connectionManager(call: SoapCall): List<Pair<String, String>> = when (call.action) {
        "GetProtocolInfo" -> listOf("Source" to "", "Sink" to SINK_PROTOCOLS)
        "GetCurrentConnectionIDs" -> listOf("ConnectionIDs" to "0")
        "GetCurrentConnectionInfo" -> {
            if (call.arguments["ConnectionID"] != "0") throw SoapFault(706, "Invalid connection reference")
            listOf("RcsID" to "0", "AVTransportID" to "0", "ProtocolInfo" to "", "PeerConnectionManager" to "", "PeerConnectionID" to "-1",
                "Direction" to "Input", "Status" to "OK")
        }
        else -> throw SoapFault(401, "Invalid action")
    }

    companion object {
        /** What the player can read, as DLNA controllers expect to see it; a controller filters its library by this list. */
        val SINK_PROTOCOLS = listOf("video/mp4", "video/x-matroska", "video/webm", "video/mpeg", "video/mp2t", "video/quicktime", "video/x-msvideo",
            "video/x-flv", "video/3gpp", "audio/mpeg", "audio/mp4", "audio/x-m4a", "audio/aac", "audio/flac", "audio/x-flac", "audio/wav",
            "audio/x-wav", "audio/ogg", "audio/opus", "audio/L16", "application/vnd.apple.mpegurl", "application/x-mpegURL", "application/dash+xml")
            .joinToString(",") { "http-get:*:$it:*" }
    }
}
