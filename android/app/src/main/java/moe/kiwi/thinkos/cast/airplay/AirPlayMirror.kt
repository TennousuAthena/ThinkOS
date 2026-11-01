package moe.kiwi.thinkos.cast.airplay

import moe.kiwi.thinkos.media.MirrorSource
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import javax.crypto.Cipher

/** What a connection from a sender carries between its requests. */
internal class AirPlayConnection(val remote: InetAddress, val pairing: AirPlayPairing, private val closer: () -> Unit) {
    /** The 164-byte key message of the FairPlay handshake (phase 2). */
    @Volatile var keyMessage: ByteArray? = null
    @Volatile var mirror: MirrorSession? = null
    fun close() = closer()
}

/** The FairPlay handshake and key unwrapping; native in the app, replaceable in tests. */
internal interface FairPlayKeys {
    fun phase1(request: ByteArray): ByteArray?
    fun phase2(request: ByteArray): ByteArray?
    fun unwrap(keyMessage: ByteArray, wrapped: ByteArray): ByteArray?
}

internal object NativeFairPlay : FairPlayKeys {
    /** Debug builds only: a key to answer every unwrap with, so a test sender that cannot produce Apple's wrapping can still run the rest of the path. */
    @Volatile var testKey: ByteArray? = null

    override fun phase1(request: ByteArray) = PlayFair.phase1(request)
    override fun phase2(request: ByteArray) = PlayFair.phase2(request)
    override fun unwrap(keyMessage: ByteArray, wrapped: ByteArray) = (if (moe.kiwi.thinkos.BuildConfig.DEBUG) testKey else null) ?: PlayFair.unwrap(keyMessage, wrapped)
}

/** Where the mirrored screen shows. The engine's mirror source in the app, a recorder in tests. */
internal interface MirrorScreen {
    fun show(source: MirrorSource, title: String)
    /** Ends what [show] began and gives the screen back. */
    fun hide()
}

/** What the HTTP/RTSP server hands to the mirror side. */
internal interface AirPlayMirrorControl {
    /** `SETUP`: the keys (first call) and the streams (later calls). Throws [IllegalArgumentException] for a request that cannot be served. */
    fun setup(connection: AirPlayConnection, request: Map<String, Any?>): Map<String, Any?>
    /** `TEARDOWN`: the listed stream types (110 video, 96 audio), or everything when [types] is null or empty. */
    fun teardown(connection: AirPlayConnection, types: List<Int>?)
    fun closed(connection: AirPlayConnection)
    /** The sender's volume, -30..0 dB, or -144 for mute. */
    fun volume(decibels: Float)
}

/**
 * Receives an iPhone's, iPad's or Mac's screen (and its sound) over AirPlay mirroring and plays it in the engine (ADR-031).
 *
 * The sender runs a handshake on the control connection (`/pair-verify`, `/fp-setup`), then `SETUP`s a session with FairPlay-wrapped
 * keys, and a video stream (TCP, AES-CTR, H.264) and an audio stream (UDP, AES-CBC, AAC) after that. Both are re-packed as MPEG-TS
 * for the engine's [MirrorSource]; the engine plays it as it plays a Miracast screen. Everything the sender says is untrusted and bounded.
 * One session at a time: a new sender's `SETUP` ends the old one.
 */
internal class AirPlayMirror(
    private val screen: MirrorScreen,
    private val fairPlay: FairPlayKeys,
    private val log: (String) -> Unit,
    private val setVolume: (Float) -> Unit = {},
    private val audioDecoder: (sampleRate: Int, channels: Int, codec: Int, out: (ByteArray, Long) -> Unit) -> AudioFrames? = { _, _, _, _ -> null },
    /** Who may connect to the ports a session opens. The control connection's address is not enough: a sender may use another address of its own for the streams. */
    private val peerAllowed: (InetAddress) -> Boolean = { true },
) : AirPlayMirrorControl {
    @Volatile private var current: MirrorSession? = null

    override fun setup(connection: AirPlayConnection, request: Map<String, Any?>): Map<String, Any?> {
        val response = LinkedHashMap<String, Any?>()
        val wrapped = request["ekey"] as? ByteArray
        if (wrapped != null) {
            if (wrapped.size != 72) throw IllegalArgumentException("a wrapped key is 72 bytes, not ${wrapped.size}")
            val keyMessage = connection.keyMessage ?: throw IllegalArgumentException("SETUP before the FairPlay handshake")
            val secret = connection.pairing.sharedSecret ?: throw IllegalArgumentException("SETUP before pair-verify")
            val key = fairPlay.unwrap(keyMessage, wrapped) ?: throw IllegalArgumentException("the key could not be unwrapped")
            val iv = (request["eiv"] as? ByteArray)?.takeIf { it.size == 16 } ?: ByteArray(16)
            val timing = (request["timingPort"] as? Number)?.toInt()?.takeIf { it in 1..65535 } ?: 0
            current?.close("another sender started a mirror")
            val session = MirrorSession(key, secret, iv, connection.remote, timing, screen, audioDecoder, peerAllowed, log) { ended -> if (current === ended) current = null }
            val (eventPort, timingPort) = session.open()
            session.hangUp = connection::close
            connection.mirror = session; current = session
            response["eventPort"] = eventPort.toLong(); response["timingPort"] = timingPort.toLong()
            log("AirPlay mirror: keys accepted (event port $eventPort, timing port $timingPort)")
        }
        val streams = request["streams"] as? List<*>
        if (streams != null) {
            val session = connection.mirror ?: throw IllegalArgumentException("a stream before the keys")
            val answered = ArrayList<Map<String, Any?>>()
            for (item in streams.take(8)) {
                val stream = item as? Map<*, *> ?: continue
                when ((stream["type"] as? Number)?.toInt()) {
                    110 -> {
                        val id = (stream["streamConnectionID"] as? Number)?.toLong() ?: 0L
                        answered += mapOf("type" to 110L, "dataPort" to session.addVideo(id).toLong())
                    }
                    96 -> {
                        val rate = (stream["sr"] as? Number)?.toInt()?.takeIf { it in 8000..96000 } ?: 44100
                        val channels = (stream["channels"] as? Number)?.toInt()?.takeIf { it in 1..2 } ?: 2
                        val codec = (stream["ct"] as? Number)?.toInt() ?: 8
                        session.addAudio(codec, rate, channels)?.let { (data, control) ->
                            answered += mapOf("type" to 96L, "dataPort" to data.toLong(), "controlPort" to control.toLong())
                        }
                    }
                    else -> log("AirPlay mirror: a stream of type ${stream["type"]} is not supported")
                }
            }
            response["streams"] = answered
        }
        return response
    }

    override fun teardown(connection: AirPlayConnection, types: List<Int>?) {
        val session = connection.mirror ?: return
        if (types.isNullOrEmpty()) { session.close("the sender ended it"); connection.mirror = null; return }
        if (110 in types) session.removeVideo()
        if (96 in types) session.removeAudio()
        if (!session.hasStreams) {
            // A full-screen video can take the mirror's streams away and give them back on the same session: keep the keys for a while.
            log("AirPlay mirror: no streams left, waiting for new ones")
        }
    }

    override fun closed(connection: AirPlayConnection) {
        connection.mirror?.close("the connection closed")
        connection.mirror = null
    }

    override fun volume(decibels: Float) {
        // -30 dB is the quietest the sender asks for, -144 is mute: a straight line between them is gentler than the dB scale.
        setVolume(if (decibels <= -100f) 0f else ((decibels + 30f) / 30f).coerceIn(0f, 1f))
    }

    /** The engine's state changed. A mirror that was playing and has been replaced or stopped is over; a video that took its place is not a reason to end it. */
    fun onEngineState(snapshot: Map<String, Any?>, owner: String) {
        val session = current ?: return
        val mine = snapshot["owner"] == owner
        if (mine && snapshot["mirror"] == true) { session.showing = true; return }
        if (session.showing) {
            session.showing = false
            if (!mine) session.endFromOutside("the player was taken")
        }
    }

    fun shutdown() { current?.close("AirPlay was switched off"); current = null }
}

/** One decoder for the audio stream: takes encrypted-and-decrypted AAC frames in, gives AAC-LC ADTS frames out. */
internal interface AudioFrames {
    fun start()
    fun feed(frame: ByteArray, ptsUs: Long)
    fun stop()
}

/**
 * One mirroring session of one sender: its keys, its streams, and the transport stream they are packed into. Streams come and go
 * (an iPhone drops the video while a full-screen movie plays and asks for it again afterwards); the session keeps its keys.
 */
internal class MirrorSession(
    private val aesKey: ByteArray,
    private val ecdhSecret: ByteArray,
    private val iv: ByteArray,
    private val remote: InetAddress,
    private val senderTimingPort: Int,
    private val screen: MirrorScreen,
    private val audioDecoder: (Int, Int, Int, (ByteArray, Long) -> Unit) -> AudioFrames?,
    private val peerAllowed: (InetAddress) -> Boolean,
    private val log: (String) -> Unit,
    private val onEnded: (MirrorSession) -> Unit,
) {
    private val lock = Any()
    private val started = System.nanoTime() / 1000
    /** Microseconds on the transport stream's clock: zero at the session's start, a second in so the first timestamps are not negative. */
    private fun now(): Long = System.nanoTime() / 1000 - started + 1_000_000
    private var events: ServerSocket? = null
    private var ntp: NtpClient? = null
    private var video: VideoStream? = null
    private var audio: AudioStream? = null
    private var source: MirrorSource? = null
    private var muxer: TsMuxer? = null
    private var config: Avc.Config? = null
    private val gop = ArrayList<Pair<ByteArray, Long>>()
    private var gopBytes = 0
    private val timeline = MirrorTimeline(onQuiet = { log("AirPlay mirror: the sender was quiet for ${it / 1000} ms; the player is not made to wait that long") })
    @Volatile private var closed = false
    val isClosed: Boolean get() = closed
    /** The engine has shown this session's screen at least once and has not yet replaced it. */
    @Volatile var showing = false
    /** Drops the sender's control connection, which is how it learns the mirror is over. */
    @Volatile var hangUp: (() -> Unit)? = null
    @Volatile var shown = false
        private set
    val hasStreams: Boolean get() = video != null || audio != null

    /** Opens the event port (the sender connects and we only listen) and the timing client. Returns (event port, timing port). */
    fun open(): Pair<Int, Int> {
        val server = ServerSocket(0).also { events = it }
        Thread({
            try { server.accept().use { s -> val buf = ByteArray(4096); val input = s.getInputStream(); while (!closed && input.read(buf) >= 0) { /* the sender's events: nothing in them we act on */ } } }
            catch (error: IOException) { /* closed */ }
        }, "ThinkOS-AirPlay-Events").apply { isDaemon = true; start() }
        val client = NtpClient(remote, senderTimingPort, log).also { ntp = it; it.start() }
        return server.localPort to client.localPort
    }

    fun addVideo(streamConnectionId: Long): Int {
        synchronized(lock) {
            video?.stop()
            val stream = VideoStream(MirrorCrypto.videoCipher(aesKey, ecdhSecret, streamConnectionId), ::onConfig, ::onFrame, ::now, peerAllowed, log) { synchronized(lock) { if (video === it) video = null } }
            video = stream
            stream.start()
            return stream.port
        }
    }

    /** Returns (data port, control port), or null for a codec that cannot be turned into something the player reads. */
    fun addAudio(codec: Int, sampleRate: Int, channels: Int): Pair<Int, Int>? {
        synchronized(lock) {
            if (codec != CT_AAC_LC && codec != CT_AAC_ELD) { log("AirPlay mirror: audio codec $codec is not supported (2 is ALAC)"); return null }
            audio?.stop()
            var transcoder: AudioFrames? = null
            val emit: (ByteArray, Long) -> Unit = { adts, pts -> onAudio(adts, pts) }
            if (codec == CT_AAC_ELD) {
                transcoder = audioDecoder(sampleRate, channels, codec, emit)
                if (transcoder == null) { log("AirPlay mirror: no decoder for AAC-ELD audio"); return null }
                transcoder.start()
            }
            val stream = AudioStream(MirrorCrypto.audioKey(aesKey, ecdhSecret), iv, sampleRate, channels, codec, transcoder, emit, ::now, peerAllowed, log)
            audio = stream
            stream.start()
            // The audio reaches the player in the same transport stream as the video; a stream already playing without it starts again with it.
            audioFormat = sampleRate to channels
            if (muxer != null && muxer?.hasAudio != true) rebuildOutput(audioFormat)
            return stream.dataPort to stream.controlPort
        }
    }

    private var audioFormat: Pair<Int, Int>? = null

    /**
     * The sender took its picture away (it does that while a full-screen video plays) and may give it back. What was built for the old picture
     * is dropped without touching the screen: whoever has it now keeps it, and the next first picture builds a new transport stream and shows it.
     */
    fun removeVideo() {
        synchronized(lock) {
            video?.stop(); video = null
            source?.close(); source = null; muxer = null; shown = false
            gop.clear(); gopBytes = 0
            timeline.reset()
        }
    }

    /** The sound went away while the picture goes on: the transport stream must not promise sound that will not come, so it starts again without. */
    fun removeAudio() {
        synchronized(lock) {
            audio?.stop(); audio = null
            audioFormat = null
            if (muxer?.hasAudio == true) rebuildOutput(null)
        }
    }

    // ---- what the streams deliver ---------------------------------------------------------------------------------------------------

    private fun onConfig(c: Avc.Config) { synchronized(lock) { config = c; muxer?.setConfig(c) } }

    private fun onFrame(annexB: ByteArray, ptsUs: Long) {
        synchronized(lock) {
            if (closed) return
            val keyframe = Avc.isKeyframe(annexB)
            if (muxer == null) {
                if (!keyframe || config == null) return                    // nothing can be decoded before the first complete picture
                buildOutput(audioFormat)
            }
            val pts = timeline.map(MirrorTimeline.VIDEO, ptsUs)
            if (keyframe) { gop.clear(); gopBytes = 0 }
            if (gopBytes < GOP_BYTES && gop.size < GOP_FRAMES) { gop += annexB to pts; gopBytes += annexB.size }
            muxer?.writeVideo(annexB, pts)
        }
    }

    private fun onAudio(adts: ByteArray, ptsUs: Long) {
        synchronized(lock) {
            val mux = muxer
            if (!closed && mux != null && mux.hasAudio) mux.writeAudio(adts, timeline.map(MirrorTimeline.AUDIO, ptsUs))
        }
    }

    private fun buildOutput(format: Pair<Int, Int>?) {
        val newSource = MirrorSource("airplay")
        val mux = TsMuxer({ newSource.write(it) }, audioSampleRate = format?.first ?: 0, audioChannels = format?.second ?: 2)
        config?.let(mux::setConfig)
        val old = source
        source = newSource; muxer = mux
        screen.show(newSource, "AirPlay")
        shown = true
        old?.close()
    }

    /** The audio arrived after the picture started: start the transport stream again with an audio stream in it, and replay the last keyframe's pictures into it. */
    private fun rebuildOutput(format: Pair<Int, Int>?) {
        buildOutput(format)
        val mux = muxer ?: return
        for ((frame, pts) in gop) mux.writeVideo(frame, pts)
    }

    // ---- ending ---------------------------------------------------------------------------------------------------------------------

    /** The player no longer shows us: tell the sender by dropping its connection. */
    fun endFromOutside(reason: String) { close(reason); hangUp?.invoke() }

    fun close(reason: String) {
        synchronized(lock) {
            if (closed) return
            closed = true
            log("AirPlay mirror ended: $reason")
            video?.stop(); audio?.stop(); ntp?.stop()
            runCatching { events?.close() }
            val wasShown = shown
            source?.close(); source = null; muxer = null
            video = null; audio = null
            if (wasShown) runCatching { screen.hide() }
        }
        onEnded(this)
    }

    companion object {
        const val CT_ALAC = 2
        const val CT_AAC_LC = 4
        const val CT_AAC_ELD = 8
        private const val GOP_BYTES = 3 * 1024 * 1024
        private const val GOP_FRAMES = 240
    }
}

/**
 * The picture: the sender connects to our TCP port and sends packets of a 128-byte header (payload size and type, an NTP timestamp)
 * and a payload. Type 1 is the avcC record, type 0 an AES-CTR encrypted frame in length-prefixed H.264. The cipher's keystream runs
 * through every payload of the connection in order, so every frame is decrypted, even those that are not used.
 */
internal class VideoStream(
    private val cipher: Cipher,
    private val onConfig: (Avc.Config) -> Unit,
    private val onFrame: (annexB: ByteArray, ptsUs: Long) -> Unit,
    private val clock: () -> Long,
    private val peerAllowed: (InetAddress) -> Boolean,
    private val log: (String) -> Unit,
    private val ended: (VideoStream) -> Unit,
) {
    private val server = ServerSocket(0)
    @Volatile private var running = false
    private var client: Socket? = null
    val port: Int get() = server.localPort

    fun start() {
        running = true
        server.soTimeout = ACCEPT_MILLIS
        Thread({ run() }, "ThinkOS-AirPlay-Video").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1; start() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }; runCatching { server.close() }
    }

    private fun run() {
        try {
            // The first connection from somewhere allowed is the sender's; others are turned away without ending the wait.
            var socket: Socket
            while (true) {
                socket = server.accept()
                if (peerAllowed(socket.inetAddress)) break
                runCatching { socket.close() }
            }
            client = socket
            socket.tcpNoDelay = true
            socket.soTimeout = READ_MILLIS
            val input = socket.getInputStream()
            val header = ByteArray(128)
            var anchorNtp = -1L
            var anchorLocal = 0L
            var frames = 0
            while (running) {
                if (!readFully(input, header, 128)) break
                val size = le32(header, 0)
                val type = header[4].toInt() and 0xff
                if (size < 0 || size > MAX_PAYLOAD) { log("AirPlay video: a packet of $size bytes is not believable"); break }
                val payload = ByteArray(size)
                if (!readFully(input, payload, size)) break
                when (type) {
                    0 -> {
                        val plain = cipher.update(payload)                          // the keystream must advance for every frame, wanted or not
                        val annexB = Avc.avccToAnnexB(plain)
                        if (annexB.isEmpty()) continue
                        val ntp = ntpMicros(header)
                        val now = clock()
                        // The sender's own clock sets the spacing of the pictures; where the first one arrived sets where they sit on ours.
                        if (anchorNtp < 0 || ntp - anchorNtp < -1_000_000 || (ntp - anchorNtp) - (now - anchorLocal) > 2_000_000) { anchorNtp = ntp; anchorLocal = now }
                        onFrame(annexB, anchorLocal + (ntp - anchorNtp))
                        frames++
                    }
                    1 -> Avc.parseConfig(payload)?.let(onConfig) ?: log("AirPlay video: a configuration packet could not be read")
                    else -> { /* heartbeat */ }
                }
            }
        } catch (error: SocketTimeoutException) {
            if (running) log("AirPlay video: the sender stopped sending")
        } catch (error: IOException) {
            // closed
        } finally {
            running = false
            runCatching { client?.close() }; runCatching { server.close() }
            ended(this)
        }
    }

    private fun readFully(input: InputStream, into: ByteArray, size: Int): Boolean {
        var read = 0
        while (read < size) { val n = input.read(into, read, size - read); if (n < 0) return false; read += n }
        return true
    }

    private fun le32(b: ByteArray, at: Int) = (b[at].toInt() and 0xff) or ((b[at + 1].toInt() and 0xff) shl 8) or ((b[at + 2].toInt() and 0xff) shl 16) or ((b[at + 3].toInt() and 0xff) shl 24)

    /** The header's 64-bit little-endian NTP timestamp as microseconds: whole seconds in the upper half, the upper 16 bits of the fraction below them. */
    private fun ntpMicros(h: ByteArray): Long {
        val raw = (0 until 8).fold(0L) { acc, i -> acc or ((h[8 + i].toLong() and 0xff) shl (8 * i)) }
        return ((raw ushr 32) and 0xffffffffL) * 1_000_000L + (((raw ushr 16) and 0xffffL) * 1_000_000L) / 65536L
    }

    private companion object { const val MAX_PAYLOAD = 8 * 1024 * 1024; const val ACCEPT_MILLIS = 30_000; const val READ_MILLIS = 20_000 }
}

/**
 * The sound: RTP over UDP, each packet AES-CBC encrypted, most of them sent two or three times. Packets are put in order, copies
 * dropped, and what comes out is AAC. AAC-LC goes on as it is (an ADTS header is put in front); ELD is first converted by [converter].
 */
internal class AudioStream(
    key: ByteArray,
    private val iv: ByteArray,
    private val sampleRate: Int,
    private val channels: Int,
    private val codec: Int,
    private val converter: AudioFrames?,
    private val emit: (ByteArray, Long) -> Unit,
    private val clock: () -> Long,
    private val peerAllowed: (InetAddress) -> Boolean,
    private val log: (String) -> Unit,
) {
    private val key = key.copyOf()
    private val data = datagramSocket()
    private val control = datagramSocket()
    @Volatile private var running = false
    private val seen = java.util.ArrayDeque<Int>()
    private val seenSet = HashSet<Int>()
    private val reorder = java.util.TreeMap<Int, Pair<ByteArray, Long>>()
    private var next = -1
    private var anchorTs = -1L
    private var anchorLocal = 0L
    val dataPort: Int get() = data.localPort
    val controlPort: Int get() = control.localPort

    fun start() {
        running = true
        Thread({ receive() }, "ThinkOS-AirPlay-Audio-Receive").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1; start() }
        Thread({ drain() }, "ThinkOS-AirPlay-Audio-Control").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        runCatching { data.close() }; runCatching { control.close() }
        converter?.stop()
    }

    private fun receive() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            while (running) {
                packet.length = buffer.size
                data.receive(packet)
                if (packet.length <= 12 || !peerAllowed(packet.address)) continue
                val sequence = ((buffer[2].toInt() and 0xff) shl 8) or (buffer[3].toInt() and 0xff)
                val timestamp = ((buffer[4].toLong() and 0xff) shl 24) or ((buffer[5].toLong() and 0xff) shl 16) or ((buffer[6].toLong() and 0xff) shl 8) or (buffer[7].toLong() and 0xff)
                if (!seenSet.add(sequence)) continue                                   // the same packet again
                seen.addLast(sequence)
                while (seen.size > 2048) seenSet.remove(seen.removeFirst())
                val payload = MirrorCrypto.decryptAudio(key, iv, buffer.copyOfRange(12, packet.length))
                val now = clock()
                if (anchorTs < 0) { anchorTs = timestamp; anchorLocal = now }
                var delta = (timestamp - anchorTs).toInt()                              // 32-bit wrap-safe
                var pts = anchorLocal + delta * 1_000_000L / sampleRate
                if (pts < now - 2_000_000 || pts > now + 2_000_000) { anchorTs = timestamp; anchorLocal = now; pts = now }   // the sender's clock jumped
                deliver(sequence, payload, pts)
            }
        } catch (error: IOException) {
            // closed
        }
    }

    /** Releases packets in order; a gap is given up on once enough later packets have come in. */
    private fun deliver(sequence: Int, payload: ByteArray, pts: Long) {
        synchronized(reorder) {
            if (next < 0) next = sequence
            val ahead = (sequence - next) and 0xffff
            if (ahead > 0x8000) return                                                 // older than what was already released
            reorder[sequence] = payload to pts
            while (true) {
                val head = reorder[next]
                if (head == null) {
                    if (reorder.size < REORDER_LIMIT) return
                    next = reorder.keys.minByOrNull { (it - next) and 0xffff } ?: return    // enough have piled up behind the gap: give it up
                    continue
                }
                reorder.remove(next)
                out(head.first, head.second)
                next = (next + 1) and 0xffff
            }
        }
    }

    private fun out(frame: ByteArray, pts: Long) {
        if (codec == CT_ELD) converter?.feed(frame, pts)
        else emit(TsMuxer.adtsHeader(frame.size, sampleRate, channels) + frame, pts)
    }

    /** The control socket only needs to be there and answered by nobody: the sender's sync packets can be left unread, but the socket is drained. */
    private fun drain() {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        try { while (running) { packet.length = buffer.size; control.receive(packet) } } catch (error: IOException) { /* closed */ }
    }

    private companion object {
        const val CT_ELD = MirrorSession.CT_AAC_ELD
        const val REORDER_LIMIT = 24
        /** Dual-stack: an iPhone may send its audio to the device's IPv6 link-local address. */
        fun datagramSocket(): DatagramSocket = DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(InetAddress.getByName("::"), 0)) }
    }
}

/**
 * AirPlay's timing exchange. The sender expects the receiver to ask for the time: without these requests it does not go on with the
 * streams. The answers are read and thrown away; the pictures are paced by the timestamps they carry, and no tighter sync is needed here.
 */
internal class NtpClient(private val remote: InetAddress, private val remotePort: Int, private val log: (String) -> Unit) {
    private val socket = DatagramSocket()
    @Volatile private var running = false
    val localPort: Int get() = socket.localPort

    fun start() {
        running = true
        socket.soTimeout = 1000
        if (remotePort <= 0) return
        Thread({ loop() }, "ThinkOS-AirPlay-Timing").apply { isDaemon = true; start() }
    }

    fun stop() { running = false; runCatching { socket.close() } }

    private fun loop() {
        val request = ByteArray(32).also { it[0] = 0x80.toByte(); it[1] = 0xD2.toByte(); it[3] = 0x07 }
        val answer = ByteArray(128)
        while (running) {
            try {
                val now = System.currentTimeMillis()
                val seconds = now / 1000 + NTP_EPOCH; val fraction = (now % 1000) * (1L shl 32) / 1000
                for (i in 0 until 4) { request[24 + i] = (seconds shr (24 - 8 * i)).toByte(); request[28 + i] = (fraction shr (24 - 8 * i)).toByte() }
                socket.send(DatagramPacket(request, request.size, remote, remotePort))
                try { socket.receive(DatagramPacket(answer, answer.size)) } catch (error: SocketTimeoutException) { /* try again next round */ }
            } catch (error: IOException) { if (running) log("AirPlay timing: ${error.message}") }
            try { Thread.sleep(INTERVAL_MILLIS) } catch (error: InterruptedException) { return }
        }
    }

    private companion object { const val NTP_EPOCH = 2208988800L; const val INTERVAL_MILLIS = 2000L }
}
