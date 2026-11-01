package moe.kiwi.thinkos.cast.airplay

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** What the sender asked the player to do, or wants to know. The server knows the protocol, this knows the player. */
internal interface AirPlayControl {
    /** Start playing [url]. Throws [IllegalArgumentException] with a reason when it will not (the server answers 400 and tells the sender it stopped). */
    fun play(request: AirPlayPlay)
    fun rate(value: Float)
    fun scrub(positionSeconds: Double)
    fun stop()
    /** Null when nothing is loaded. */
    fun playback(): AirPlayPlayback?
}

internal class AirPlayPlay(val url: String, val startFraction: Double, val startSeconds: Double?, val sessionId: String?, val remote: InetAddress)

internal data class AirPlayPlayback(val durationSeconds: Double, val positionSeconds: Double, val rate: Float, val ready: Boolean, val buffering: Boolean)

/** How the device presents itself. The same values go into the mDNS record, `/info` and `/server-info`. */
internal class AirPlayDevice(
    val deviceId: String,
    val name: () -> String,
    /** Screen mirroring and its sound are offered (needs the FairPlay library); without them the device presents itself as a video-only receiver. */
    val mirror: Boolean = false,
    val identityKey: () -> ByteArray = { ByteArray(0) },
    val identityId: String = "",
    val sourceVersion: String = "220.68",
) {
    /** What `/server-info` says (the connection that plays a video address reads it): never FairPlay, so the sender goes straight to `/play`. */
    val features: Long = FEATURES
    val model: String get() = "AppleTV3,2"
    /** What `/info` and the mDNS record say. */
    val advertisedFeatures: Long get() = if (mirror) MIRROR_FEATURES else FEATURES
    val featureText: String get() = if (mirror) "0x%X,0x0".format(MIRROR_FEATURES) else "0x%X".format(FEATURES)

    companion object {
        /** Video (0), video volume control (3), HTTP live streams (4). */
        const val FEATURES = 0x19L
        /**
         * Screen mirroring, audio, FairPlay and legacy pairing, as UxPlay announces them (it is kept working with current iOS). Video (0) and HTTP live
         * streams (4) are left out on purpose: with them set a sender can choose the video route, which this identity does not offer.
         */
        const val MIRROR_FEATURES = 0x5A7FFEE6L
        /** The `flags` of the mDNS record and `sf` of the RAOP one. */
        const val STATUS_FLAGS = 0x4L
        /** The `statusFlags` of `/info`, which is a different field with a different number (0x44). */
        const val INFO_STATUS_FLAGS = 68L
    }
}

/**
 * The HTTP side of AirPlay video (URL playback), on one TCP port. A sender holds several connections open at once: one for requests
 * (`/server-info`, `/play`, `/rate`, `/scrub`, `/playback-info`, `/stop`) and one it turns into an event channel with `POST /reverse`
 * (`Upgrade: PTTH/1.0`), after which *this* side is the client and posts `/event` messages with the playback state.
 *
 * Senders are untrusted: peers are filtered by [accept]; every request is bounded (header 16 KiB, body 1 MiB, at most 8 connections,
 * idle connections closed after five minutes); property lists go through the bounded reader.
 */
internal class AirPlayServer(
    private val device: AirPlayDevice,
    private val identity: AirPlayIdentity,
    private val control: AirPlayControl,
    private val accept: (InetAddress) -> Boolean,
    private val log: (String) -> Unit = {},
    private val mirror: AirPlayMirrorControl? = null,
    private val fairPlay: FairPlayKeys? = null,
    /** The bytes of the two mDNS TXT records, so `/info` can show a sender what was announced (iOS compares them). */
    private val txt: () -> AirPlayTxt? = { null },
) {
    private var server: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    @Volatile private var running = false
    private val reverseLock = Any()
    private var reverse: Socket? = null
    private var reverseSession: String? = null
    private val events = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "ThinkOS-AirPlay-Events").apply { isDaemon = true } }
    private var eventSession = 1L

    /** Binds the first free port from [preferred] upwards and returns it. */
    fun start(preferred: Int, bind: InetAddress? = null): Int {
        check(!running) { "already running" }
        var failure: IOException? = null
        var socket: ServerSocket? = null
        for (port in preferred..preferred + 20) {
            try {
                socket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(bind, port), 16) }
                break
            } catch (error: IOException) { failure = error }
        }
        val bound = socket ?: throw failure ?: IOException("no free port")
        server = bound
        pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 10, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "ThinkOS-AirPlay-Http").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
        running = true
        Thread({ loop(bound) }, "ThinkOS-AirPlay-Accept").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
        return bound.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        synchronized(reverseLock) { runCatching { reverse?.close() }; reverse = null }
        pool?.shutdownNow()
        events.shutdownNow()
        server = null; pool = null
    }

    private fun loop(socket: ServerSocket) {
        while (running) {
            val client = try { socket.accept() } catch (error: IOException) { if (running) log("AirPlay accept failed: ${error.message}"); return }
            if (!accept(client.inetAddress)) { runCatching { client.close() }; continue }
            try { pool?.execute { serve(client) } } catch (error: RejectedExecutionException) { runCatching { client.close() } }
        }
    }

    // ---- one connection -------------------------------------------------------------------------------------------------------------

    private class Request(val method: String, val target: String, val headers: Map<String, String>, val body: ByteArray, val protocol: String = "HTTP/1.1") {
        val path: String get() = target.substringBefore('?')
        fun query(name: String): String? = target.substringAfter('?', "").split('&').firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")
    }

    private class Response(val status: Int, val body: ByteArray = ByteArray(0), val contentType: String? = null, val headers: List<Pair<String, String>> = emptyList())

    private class HttpError(val status: Int) : Exception()

    private fun serve(socket: Socket) {
        socket.use { client ->
            val connection = AirPlayConnection(client.inetAddress, AirPlayPairing(identity)) { runCatching { client.close() } }
            try {
                client.soTimeout = IDLE_TIMEOUT_MILLIS
                client.tcpNoDelay = true
                val input = client.getInputStream()
                val output = client.getOutputStream()
                while (running) {
                    val request = read(input) ?: return
                    val upgraded = request.method == "POST" && request.path == "/reverse"
                    log("AirPlay ${request.method} ${request.path} from ${client.inetAddress.hostAddress}${describe(request)}")
                    if (upgraded) { reverseChannel(client, request); return }
                    val response = try { route(request, connection) } catch (error: HttpError) { Response(error.status) }
                    write(output, request, response)
                }
            } catch (error: HttpError) {
                runCatching { write(client.getOutputStream(), null, Response(error.status)) }
            } catch (error: SocketTimeoutException) {
                // An idle sender: close quietly.
            } catch (error: IOException) {
                // Reset by the peer.
            } finally {
                mirror?.closed(connection)
            }
        }
    }

    /** One line of what matters in a request, for the log that tunes this against real senders. Never a full URL: addresses carry tokens. */
    private fun describe(request: Request): String {
        val type = request.headers["content-type"]?.let { " type=$it" }.orEmpty()
        val session = request.headers["x-apple-session-id"]?.let { " session=${it.take(36)}" }.orEmpty()
        // What a sender asked for inside the body, where that is safe to write down: the qualifier of /info, and the names (not the values) of a SETUP.
        val inside = if (request.body.isEmpty() || request.path !in LOGGED_BODIES && request.method != "SETUP") "" else runCatching {
            val plist = Plist.read(request.body)
            " keys=" + plist.entries.joinToString(",") { (k, v) -> if (k == "qualifier") "qualifier=$v" else k }
        }.getOrDefault("")
        return " proto=${request.protocol}$type$session body=${request.body.size}B$inside"
    }

    private fun read(input: InputStream): Request? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) { if (head.size() == 0) return null else throw IOException("closed") }
            if (head.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue
            head.write(b)
            if (head.size() > MAX_HEADER_BYTES) throw HttpError(431)
            matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(' ') ?: throw HttpError(400)
        if (first.size != 3 || !(first[2].startsWith("HTTP/1.") || first[2].startsWith("RTSP/1."))) throw HttpError(400)
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400)
            headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        val body = if (headers["transfer-encoding"]?.lowercase(Locale.ROOT)?.contains("chunked") == true) chunked(input) else {
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            if (length < 0 || length > MAX_BODY_BYTES) throw HttpError(413)
            ByteArray(length.toInt()).also { readFully(input, it) }
        }
        return Request(first[0].uppercase(Locale.ROOT), first[1], headers, body, first[2])
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) throw IOException("closed")
            read += n
        }
    }

    /** A chunked body, as some HTTP stacks send a plist. Bounded like every other body. */
    private fun chunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("closed")
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
                if (line.length > 16) throw HttpError(400)
            }
            val size = line.toString().substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400)
            if (size < 0) throw HttpError(400)
            if (size == 0) break
            if (out.size() + size > MAX_BODY_BYTES) throw HttpError(413)
            val chunk = ByteArray(size)
            readFully(input, chunk)
            out.write(chunk)
            if (input.read() != '\r'.code || input.read() != '\n'.code) throw HttpError(400)
        }
        // Trailers are ignored up to the blank line.
        while (true) {
            var length = 0
            while (true) {
                val b = input.read()
                if (b < 0) return out.toByteArray()
                if (b == '\n'.code) break
                if (b != '\r'.code) length++
            }
            if (length == 0) return out.toByteArray()
        }
    }

    private fun write(out: java.io.OutputStream, request: Request?, response: Response) {
        val text = StringBuilder("${request?.protocol ?: "HTTP/1.1"} ${response.status} ${reason(response.status)}\r\n")
        text.append("Server: AirTunes/").append(device.sourceVersion).append("\r\n")
        response.contentType?.let { text.append("Content-Type: ").append(it).append("\r\n") }
        request?.headers?.get("cseq")?.let { text.append("CSeq: ").append(it).append("\r\n") }
        request?.headers?.get("x-apple-session-id")?.let { text.append("X-Apple-Session-ID: ").append(it).append("\r\n") }
        for ((k, v) in response.headers) text.append(k).append(": ").append(v).append("\r\n")
        text.append("Content-Length: ").append(response.body.size).append("\r\n\r\n")
        out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
        if (request?.method != "HEAD") out.write(response.body)
        out.flush()
    }

    private fun reason(status: Int) = when (status) {
        101 -> "Switching Protocols"; 200 -> "OK"; 400 -> "Bad Request"; 404 -> "Not Found"; 413 -> "Payload Too Large"
        431 -> "Request Header Fields Too Large"; 470 -> "Connection Authorization Required"; 500 -> "Internal Server Error"; 501 -> "Not Implemented"; else -> "OK"
    }

    // ---- routes ---------------------------------------------------------------------------------------------------------------------

    private fun xml(map: Map<String, Any?>, status: Int = 200) = Response(status, Plist.xml(map), "text/x-apple-plist+xml")

    private fun route(request: Request, connection: AirPlayConnection): Response {
        // Only the session verbs are RTSP's own. A sender asks for /info and pairs with `GET /info RTSP/1.0` and `POST /pair-setup RTSP/1.0`:
        // the request line says RTSP, the request is the HTTP-style one, and the table below answers it.
        if (request.method in RTSP_VERBS) return rtsp(request, connection)
        val remote = connection.remote
        return when ("${request.method} ${request.path}") {
            "GET /server-info" -> xml(serverInfo())
            "GET /info", "POST /info" -> Response(200, Plist.binary(info(request)), "application/x-apple-binary-plist")
            "POST /pair-setup" -> pairingStep(request) { connection.pairing.pairSetup(it) }
            "POST /pair-verify" -> pairingStep(request) { connection.pairing.pairVerify(it) }
            "POST /fp-setup" -> fairPlayStep(request, connection)
            "POST /play" -> play(request, remote)
            "POST /rate" -> { control.rate(request.query("value")?.toFloatOrNull() ?: 1f); Response(200) }
            "POST /scrub" -> { request.query("position")?.toDoubleOrNull()?.let(control::scrub); Response(200) }
            "GET /scrub" -> control.playback().let { p ->
                Response(200, "duration: %.6f\r\nposition: %.6f\r\n".format(Locale.ROOT, p?.durationSeconds ?: 0.0, p?.positionSeconds ?: 0.0).toByteArray(), "text/parameters")
            }
            "POST /stop" -> { control.stop(); Response(200) }
            "GET /playback-info" -> xml(playbackInfo(control.playback()))
            "PUT /setProperty", "POST /setProperty", "GET /getProperty", "POST /getProperty" -> xml(mapOf("errorCode" to 0))
            "POST /action", "POST /feedback", "POST /command", "POST /audioMode" -> Response(200)
            else -> Response(404)
        }
    }

    /** The RTSP side of AirPlay: what a mirroring sender says after its handshake. Nothing here applies without the mirror side. */
    private fun rtsp(request: Request, connection: AirPlayConnection): Response {
        val handler = mirror ?: return Response(501)
        return when (request.method) {
            "OPTIONS" -> Response(200, headers = listOf("Public" to "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, GET_PARAMETER, SET_PARAMETER, POST, GET"))
            "SETUP" -> try {
                val plist = if (request.body.isEmpty()) emptyMap() else Plist.read(request.body)
                Response(200, Plist.binary(handler.setup(connection, plist)), "application/x-apple-binary-plist")
            } catch (error: IllegalArgumentException) {
                log("AirPlay SETUP refused: ${error.message}")
                Response(400)
            }
            "RECORD", "FLUSH", "PAUSE" -> Response(200)
            "TEARDOWN" -> {
                val types = runCatching { (Plist.read(request.body)["streams"] as? List<*>)?.mapNotNull { ((it as? Map<*, *>)?.get("type") as? Number)?.toInt() } }.getOrNull()
                handler.teardown(connection, types)
                Response(200)
            }
            "GET_PARAMETER" -> if (String(request.body, Charsets.UTF_8).contains("volume")) Response(200, "volume: 0.000000\r\n".toByteArray(), "text/parameters") else Response(200)
            "SET_PARAMETER" -> {
                if (request.headers["content-type"].orEmpty().contains("text/parameters")) {
                    String(request.body, Charsets.UTF_8).lineSequence().firstOrNull { it.startsWith("volume:") }?.substringAfter(':')?.trim()?.toFloatOrNull()?.let(handler::volume)
                }
                Response(200)
            }
            else -> Response(501)
        }
    }

    private fun fairPlayStep(request: Request, connection: AirPlayConnection): Response {
        val keys = fairPlay ?: return Response(404)
        val answer = when (request.body.size) {
            16 -> keys.phase1(request.body)
            164 -> keys.phase2(request.body)?.also { connection.keyMessage = request.body }
            else -> null
        }
        if (answer == null) { log("AirPlay /fp-setup refused (${request.body.size} bytes)"); return Response(400) }
        return Response(200, answer, "application/octet-stream")
    }

    private fun serverInfo(): Map<String, Any?> = mapOf(
        "deviceid" to device.deviceId, "features" to device.features, "model" to device.model, "protovers" to "1.0", "srcvers" to device.sourceVersion,
    )

    /**
     * A sender's first `/info` carries a property list with a `qualifier`: it asks for the Bonjour text records (`txtAirPlay`, `txtRAOP`) to compare
     * them with what it found by mDNS, and wants nothing else in that answer. A plain `/info` gets the description.
     */
    private fun info(request: Request): Map<String, Any?> {
        val qualifiers = if (request.body.isEmpty()) null else runCatching { (Plist.read(request.body)["qualifier"] as? List<*>)?.filterIsInstance<String>() }.getOrNull()
        if (qualifiers != null) {
            val records = txt()
            val out = linkedMapOf<String, Any?>()
            if (records != null) {
                if ("txtAirPlay" in qualifiers) out["txtAirPlay"] = records.airplay
                if ("txtRAOP" in qualifiers) out["txtRAOP"] = records.raop
            }
            return out
        }
        val base = linkedMapOf<String, Any?>(
            "deviceID" to device.deviceId, "macAddress" to device.deviceId, "features" to device.advertisedFeatures, "model" to device.model,
            "name" to device.name(), "sourceVersion" to device.sourceVersion, "statusFlags" to AirPlayDevice.INFO_STATUS_FLAGS, "protovers" to "1.1",
            "keepAliveLowPower" to true, "keepAliveSendStatsAsBody" to true,
        )
        if (!device.mirror) return base
        base["pk"] = device.identityKey()
        base["pi"] = device.identityId
        base["vv"] = 2L
        base["initialVolume"] = 0.0
        // What a mirroring sender wants to know before it commits: the screen it may fill and the audio it may send.
        base["displays"] = listOf(linkedMapOf<String, Any?>(
            "uuid" to "e0ff8a27-6738-3d56-8a16-cc53aacee925", "widthPhysical" to 0L, "heightPhysical" to 0L, "width" to MIRROR_WIDTH, "height" to MIRROR_HEIGHT,
            "widthPixels" to MIRROR_WIDTH, "heightPixels" to MIRROR_HEIGHT, "rotation" to false, "refreshRate" to (1.0 / 60.0), "overscanned" to false, "features" to 14L))
        base["audioFormats"] = listOf(
            linkedMapOf<String, Any?>("type" to 100L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L),
            linkedMapOf<String, Any?>("type" to 101L, "audioInputFormats" to 67108860L, "audioOutputFormats" to 67108860L))
        base["audioLatencies"] = listOf(
            linkedMapOf<String, Any?>("type" to 100L, "audioType" to "default", "inputLatencyMicros" to false, "outputLatencyMicros" to false),
            linkedMapOf<String, Any?>("type" to 101L, "audioType" to "default", "inputLatencyMicros" to false, "outputLatencyMicros" to false))
        return base
    }

    private fun playbackInfo(p: AirPlayPlayback?): Map<String, Any?> {
        if (p == null || !p.ready) return mapOf("readyToPlay" to false)
        val ranges = listOf(mapOf("start" to 0.0, "duration" to p.durationSeconds))
        return mapOf(
            "duration" to p.durationSeconds, "position" to p.positionSeconds, "rate" to p.rate.toDouble(), "readyToPlay" to true,
            "playbackBufferEmpty" to p.buffering, "playbackBufferFull" to !p.buffering, "playbackLikelyToKeepUp" to !p.buffering,
            "loadedTimeRanges" to ranges, "seekableTimeRanges" to ranges,
        )
    }

    private fun pairingStep(request: Request, step: (ByteArray) -> ByteArray): Response = try {
        Response(200, step(request.body), "application/octet-stream")
    } catch (error: AirPlayPairing.Refused) {
        log("AirPlay pairing refused: ${error.message}")
        Response(470)
    }

    private fun play(request: Request, remote: InetAddress): Response {
        val fields = parsePlay(request)
        val url = fields["Content-Location"] as? String
        if (url.isNullOrBlank()) { log("AirPlay /play without an address"); return Response(400) }
        val start = (fields["Start-Position"] as? Number)?.toDouble() ?: 0.0
        val seconds = (fields["Start-Position-Seconds"] as? Number)?.toDouble()
        val session = request.headers["x-apple-session-id"] ?: fields["X-Apple-Session-ID"] as? String
        return try {
            control.play(AirPlayPlay(url, start.coerceIn(0.0, 1.0), seconds?.takeIf { it >= 0 }, session, remote))
            Response(200)
        } catch (error: IllegalArgumentException) {
            log("AirPlay /play refused: ${error.message}")
            sendEvent("stopped")
            Response(400)
        }
    }

    /** `/play` carries a property list (binary or XML), or, from older senders, `text/parameters` lines. */
    private fun parsePlay(request: Request): Map<String, Any?> {
        val body = request.body
        if (body.isEmpty()) return emptyMap()
        val type = request.headers["content-type"].orEmpty()
        return try {
            if (type.contains("plist") || body.size >= 6 && String(body, 0, 6, Charsets.ISO_8859_1) == "bplist" || body[0] == '<'.code.toByte()) Plist.read(body)
            else String(body, Charsets.UTF_8).lineSequence().mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) null else line.substring(0, colon).trim() to (line.substring(colon + 1).trim().toDoubleOrNull() ?: line.substring(colon + 1).trim())
            }.toMap()
        } catch (error: Plist.PlistError) {
            log("AirPlay /play body was not readable: ${error.message}")
            throw HttpError(400)
        } catch (error: IllegalArgumentException) {
            log("AirPlay /play body was not readable: ${error.message}")
            throw HttpError(400)
        }
    }

    // ---- the event channel ----------------------------------------------------------------------------------------------------------

    /** `POST /reverse`: switch this connection to PTTH and keep it until the sender closes it. We post events down it. */
    private fun reverseChannel(socket: Socket, request: Request) {
        val session = request.headers["x-apple-session-id"]
        socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: PTTH/1.0\r\nConnection: Upgrade\r\n\r\n").toByteArray())
        socket.getOutputStream().flush()
        synchronized(reverseLock) {
            reverse?.takeIf { it !== socket }?.let { runCatching { it.close() } }
            reverse = socket; reverseSession = session
        }
        log("AirPlay event channel is up")
        try {
            val sink = ByteArray(2048)
            val input = socket.getInputStream()
            while (running && input.read(sink) >= 0) { /* the sender's answers to our events */ }
        } catch (error: IOException) {
            // closed
        } finally {
            synchronized(reverseLock) { if (reverse === socket) { reverse = null; reverseSession = null } }
            log("AirPlay event channel closed")
        }
    }

    /** `loading`, `playing`, `paused` or `stopped`, to the sender, in order, from any thread. Silent when no channel is open. */
    fun sendEvent(state: String) {
        try {
            events.execute {
                synchronized(reverseLock) {
                    val socket = reverse ?: return@synchronized
                    try {
                        val body = Plist.xml(mapOf("category" to "video", "sessionID" to eventSession, "state" to state))
                        val head = StringBuilder("POST /event HTTP/1.1\r\nContent-Type: text/x-apple-plist+xml\r\nContent-Length: ${body.size}\r\n")
                        reverseSession?.let { head.append("X-Apple-Session-ID: ").append(it).append("\r\n") }
                        head.append("\r\n")
                        socket.getOutputStream().write(head.toString().toByteArray(Charsets.ISO_8859_1))
                        socket.getOutputStream().write(body)
                        socket.getOutputStream().flush()
                        log("AirPlay event: $state")
                    } catch (error: IOException) { runCatching { socket.close() } }
                }
            }
        } catch (error: RejectedExecutionException) { /* stopped */ }
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 1024 * 1024
        private const val IDLE_TIMEOUT_MILLIS = 5 * 60 * 1000
        private val LOGGED_BODIES = setOf("/info")
        const val MIRROR_WIDTH = 1280L
        const val MIRROR_HEIGHT = 720L
        private val RTSP_VERBS = setOf("OPTIONS", "SETUP", "RECORD", "TEARDOWN", "GET_PARAMETER", "SET_PARAMETER", "FLUSH", "PAUSE", "ANNOUNCE")
    }
}
