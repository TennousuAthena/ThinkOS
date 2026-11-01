package moe.kiwi.thinkos.cast.miracast

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.net.SocketTimeoutException

/**
 * The Sink side of one Wi-Fi Display RTSP session (WFD 1.0, sections 6 and 7), on a connection that the Sink itself opened to
 * the Source's control port. Over that one connection both ends are client and server:
 *
 * ```
 * M1  Source -> Sink    OPTIONS         answered here
 * M2  Sink   -> Source  OPTIONS         sent here
 * M3  Source -> Sink    GET_PARAMETER   answered with what this device can display
 * M4  Source -> Sink    SET_PARAMETER   what the Source picked, and where the presentation lives
 * M5  Source -> Sink    SET_PARAMETER   wfd_trigger_method: SETUP
 * M6  Sink   -> Source  SETUP           sent here, naming the UDP port RTP should go to
 * M7  Sink   -> Source  PLAY            sent here; RTP (MPEG-TS) starts to flow
 * ```
 *
 * Everything the Source says is untrusted: messages are bounded by [RtspReader], values are validated before use, and nothing
 * the Source sends can make this class open another connection. The class does not touch the network itself (the caller owns
 * the socket and the RTP port), so it runs in plain JVM tests.
 *
 * [run] blocks until the session ends and then calls [Listener.onEnded] exactly once.
 */
internal class WfdSink(
    input: InputStream,
    private val output: OutputStream,
    private val peer: String,
    private val rtpPort: Int,
    private val listener: Listener,
    private val log: (String) -> Unit = {},
    private val video: Video = Video(),
) {
    interface Listener {
        /** The Source accepted SETUP; PLAY goes out next. The time to get the player ready, so the first frames are not lost. */
        fun onSetup() {}
        /** PLAY was accepted: RTP will arrive from now on. */
        fun onPlaying()
        /** The session is over, for whatever [reason]. Called once. */
        fun onEnded(reason: String)
    }

    /** What this device offers to display. Fixed to what the screen and the decoder can really do (see docs/28-casting.md). */
    class Video(
        /** CEA resolution bits: 640x480p60, 1280x720p30, 1280x720p60. A 1280x800 panel gains nothing from 1080p. */
        val ceaMask: Long = 0x61L,
        val profileMask: Int = 0x03,
        val levelMask: Int = 0x04,
        val nativeIndex: Int = 6,
    ) {
        val formats: String get() = "%02x 00 %02x %02x %08x 00000000 00000000 00 0000 0000 00 none none".format((nativeIndex shl 3) and 0xff, profileMask, levelMask, ceaMask)
    }

    private val reader = RtspReader(input)
    private val writeLock = Any()
    private val pending = HashMap<Int, String>()
    private var outCseq = 100
    private var presentationUrl = ""
    private var optionsSent = false
    @Volatile var sessionId = ""
        private set
    @Volatile private var ended = false
    @Volatile var established = false
        private set
    /** The `timeout=` of the Source's session, in seconds (the Source sends keep-alives well inside it); 0 when it named none. */
    @Volatile var sessionTimeoutSeconds = 0
        private set
    /** The Source's UDP port RTP comes from, when its SETUP answer said so; informational. */
    @Volatile var serverPort = 0
        private set

    fun run() {
        var reason = "closed"
        try {
            while (true) {
                val message = reader.read() ?: break
                if (message.isResponse) onResponse(message) else if (!onRequest(message)) { reason = "teardown"; break }
            }
        } catch (error: SocketTimeoutException) {
            reason = "timeout"
        } catch (error: RtspError) {
            reason = "protocol: ${error.message}"
        } catch (error: SocketException) {
            reason = "closed"
        } catch (error: IOException) {
            reason = "io: ${error.message}"
        } finally {
            finish(reason)
        }
    }

    private fun finish(reason: String) {
        if (ended) return
        ended = true
        listener.onEnded(reason)
    }

    // ---- requests from the Source -------------------------------------------------------------------------------------------------

    /** Returns false when the session is over. */
    private fun onRequest(message: RtspMessage): Boolean {
        val cseq = message.cseq ?: 0
        when (message.method) {
            "OPTIONS" -> {
                send(Rtsp.response(200, cseq, listOf("Public" to "org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER")))
                if (!optionsSent) { optionsSent = true; sendRequest("OPTIONS", "*", listOf("Require" to "org.wfa.wfd1.0")) }
            }
            "GET_PARAMETER" -> {
                if (message.body.isBlank()) send(Rtsp.response(200, cseq))                       // keep-alive
                else send(Rtsp.response(200, cseq, body = capabilities(message.parameters().keys)))
            }
            "SET_PARAMETER" -> {
                val params = message.parameters()
                send(Rtsp.response(200, cseq))
                params["wfd_presentation_URL"]?.let(::takePresentationUrl)
                params["wfd_video_formats"]?.let { log("source picked video format: ${it.take(120)}") }
                params["wfd_audio_codecs"]?.let { log("source picked audio codec: ${it.take(60)}") }
                when (params["wfd_trigger_method"]?.uppercase()) {
                    "SETUP" -> sendSetup()
                    "PLAY" -> sendPlay()
                    "PAUSE" -> sendRequest("PAUSE", url(), sessionHeader())
                    "TEARDOWN" -> { sendRequest("TEARDOWN", url(), sessionHeader()); return false }
                }
            }
            "TEARDOWN" -> { send(Rtsp.response(200, cseq)); return false }
            else -> send(Rtsp.response(501, cseq))
        }
        return true
    }

    /** Answers what M3 asked for. A parameter this device does not know is simply left out, which every Source tried so far accepts. */
    internal fun capabilities(requested: Collection<String>): String {
        val answers = LinkedHashMap<String, String>()
        for (name in requested) {
            val value = when (name) {
                "wfd_video_formats" -> video.formats
                "wfd_audio_codecs" -> "AAC 00000001 00"                         // AAC-LC, 48 kHz, 2 channels. LPCM would need a decoder the TS demuxer does not have.
                "wfd_client_rtp_ports" -> "RTP/AVP/UDP;unicast $rtpPort 0 mode=play"
                "wfd_3d_video_formats", "wfd_content_protection", "wfd_display_edid", "wfd_coupled_sink", "wfd_I2C",
                "wfd_uibc_capability", "wfd_standby_resume_capability" -> "none"
                "wfd_connector_type" -> "05"
                else -> null
            }
            if (value != null) answers[name] = value
        }
        return answers.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
    }

    private fun takePresentationUrl(raw: String) {
        // "<url> none": the second word is the URL of a second stream, which this sink does not use.
        val candidate = raw.substringBefore(' ').trim()
        if (candidate.startsWith("rtsp://") && candidate.length <= 256 && candidate.none { it.code < 33 || it.code == 127 }) presentationUrl = candidate
        else log("ignored an odd presentation URL")
    }

    // ---- requests of our own -------------------------------------------------------------------------------------------------------

    private fun url() = presentationUrl.ifEmpty { "rtsp://$peer/wfd1.0/streamid=0" }
    private fun sessionHeader() = if (sessionId.isEmpty()) emptyList() else listOf("Session" to sessionId)

    private fun sendSetup() = sendRequest("SETUP", url(), listOf("Transport" to "RTP/AVP/UDP;unicast;client_port=$rtpPort"))
    private fun sendPlay() = sendRequest("PLAY", url(), sessionHeader())

    /** M13: ask for an IDR frame after packets were lost, so the picture heals. Safe to call from any thread. */
    fun requestIdr() {
        if (!established || ended) return
        try { sendRequest("SET_PARAMETER", "rtsp://localhost/wfd1.0", sessionHeader(), "wfd_idr_request\r\n") } catch (error: IOException) { /* the read loop notices */ }
    }

    /** The viewer ended the session: tell the Source politely. */
    fun teardown() {
        if (ended) return
        try { sendRequest("TEARDOWN", url(), sessionHeader()) } catch (error: IOException) { /* gone already */ }
    }

    private fun sendRequest(method: String, uri: String, headers: List<Pair<String, String>>, body: String = "") {
        synchronized(writeLock) {
            val cseq = ++outCseq
            pending[cseq] = method
            if (pending.size > 32) pending.remove(pending.keys.minOrNull())
            write(Rtsp.request(method, uri, cseq, headers, body))
        }
    }

    private fun send(bytes: ByteArray) = synchronized(writeLock) { write(bytes) }

    private fun write(bytes: ByteArray) {
        output.write(bytes)
        output.flush()
    }

    // ---- answers from the Source -----------------------------------------------------------------------------------------------------

    private fun onResponse(message: RtspMessage) {
        val method = synchronized(writeLock) { pending.remove(message.cseq) } ?: return
        if (message.status != 200) {
            if (method == "SETUP" || method == "PLAY") { log("source refused $method: ${message.status}"); throw RtspError("$method refused with ${message.status}") }
            return
        }
        when (method) {
            "SETUP" -> {
                val session = message.headers["session"]?.substringBefore(';')?.trim().orEmpty()
                if (session.isEmpty() || session.length > 64 || session.any { it.code < 33 || it.code == 127 }) throw RtspError("SETUP answered without a usable session")
                sessionId = session
                message.headers["session"]?.let { header ->
                    Regex("timeout=(\\d{1,4})").find(header)?.groupValues?.get(1)?.toIntOrNull()?.let { sessionTimeoutSeconds = it }
                }
                listener.onSetup()
                message.headers["transport"]?.let { transport ->
                    Regex("server_port=(\\d{1,5})").find(transport)?.groupValues?.get(1)?.toIntOrNull()?.let { serverPort = it }
                }
                sendPlay()
            }
            "PLAY" -> { established = true; listener.onPlaying() }
        }
    }
}
