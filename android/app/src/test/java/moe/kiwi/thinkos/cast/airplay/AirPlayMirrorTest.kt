package moe.kiwi.thinkos.cast.airplay

import moe.kiwi.thinkos.media.MirrorSource
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The whole mirror path, with the test playing the sender: handshake, SETUP, an encrypted video stream and an encrypted audio stream. */
class AirPlayMirrorTest {
    private val identity = AirPlayIdentity(ByteArray(32) { (it * 5).toByte() })
    private val aesKey = ByteArray(16) { (0x30 + it).toByte() }
    private val eiv = ByteArray(16) { (0x70 + it).toByte() }

    private class Screen : MirrorScreen {
        val shown = java.util.Collections.synchronizedList(ArrayList<MirrorSource>())
        @Volatile var hidden = 0
        override fun show(source: MirrorSource, title: String) { shown += source }
        override fun hide() { hidden++ }
    }

    private val fake = object : FairPlayKeys {
        var unwrapped = 0
        override fun phase1(request: ByteArray) = ByteArray(142)
        override fun phase2(request: ByteArray) = ByteArray(32)
        override fun unwrap(keyMessage: ByteArray, wrapped: ByteArray): ByteArray? { unwrapped++; return aesKey }
    }

    private val logs = java.util.Collections.synchronizedList(ArrayList<String>())
    private val screen = Screen()
    private var volume = -1f
    private val mirror = AirPlayMirror(screen, fake, { logs += it }, { volume = it })

    /** A connection that has been through pair-verify M1 (which is what gives it the secret the keys are derived from). */
    private fun paired(): AirPlayConnection {
        val pairing = AirPlayPairing(identity)
        val ecdh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        pairing.pairVerify(byteArrayOf(1, 0, 0, 0) + Curve25519.x25519Base(ecdh) + ByteArray(32) { 3 })
        return AirPlayConnection(InetAddress.getLoopbackAddress(), pairing) {}.also { it.keyMessage = ByteArray(164) { i -> i.toByte() } }
    }

    private fun keys() = mapOf<String, Any?>("ekey" to ByteArray(72) { it.toByte() }, "eiv" to eiv, "timingPort" to 0L)

    private fun which(name: String): String? = (System.getenv("PATH").orEmpty().split(':') + listOf("/opt/homebrew/bin", "/usr/local/bin")).map { File(it, name) }.firstOrNull { it.canExecute() }?.path
    private fun run(vararg command: String): Pair<Int, String> {
        val p = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText(); p.waitFor(60, TimeUnit.SECONDS); return p.exitValue() to text
    }

    @Test fun theKeysComeFromTheFairPlayUnwrapAndThePairingSecret() {
        val c = paired()
        val answer = mirror.setup(c, keys())
        assertEquals(1, fake.unwrapped)
        assertTrue((answer["eventPort"] as Long) > 0); assertTrue((answer["timingPort"] as Long) > 0)
        mirror.closed(c)
    }

    @Test fun setupBeforeTheHandshakesIsRefused() {
        val noKeyMessage = paired().also { it.keyMessage = null }
        assertThrows(IllegalArgumentException::class.java) { mirror.setup(noKeyMessage, keys()) }
        val unpaired = AirPlayConnection(InetAddress.getLoopbackAddress(), AirPlayPairing(identity)) {}.also { it.keyMessage = ByteArray(164) }
        assertThrows(IllegalArgumentException::class.java) { mirror.setup(unpaired, keys()) }
        assertThrows(IllegalArgumentException::class.java) { mirror.setup(paired(), mapOf("ekey" to ByteArray(10))) }
        assertThrows(IllegalArgumentException::class.java) { mirror.setup(paired(), mapOf("streams" to listOf(mapOf("type" to 110L)))) }
    }

    @Test fun anotherSendersSetupEndsTheFirstSession() {
        val first = paired(); mirror.setup(first, keys())
        val session = first.mirror!!
        mirror.setup(paired(), keys())
        assertTrue("the first session is over", session.isClosed)
        assertTrue(logs.any { "another sender" in it })
    }

    @Test fun volumeIsMappedFromDecibels() {
        mirror.volume(0f); assertEquals(1f, volume, 0.001f)
        mirror.volume(-15f); assertEquals(0.5f, volume, 0.001f)
        mirror.volume(-30f); assertEquals(0f, volume, 0.001f)
        mirror.volume(-144f); assertEquals(0f, volume, 0.001f)
    }

    @Test fun anUnknownStreamTypeOrCodecIsLeftOut() {
        val c = paired(); mirror.setup(c, keys())
        val answer = mirror.setup(c, mapOf("streams" to listOf(mapOf("type" to 103L), mapOf("type" to 96L, "ct" to 2L, "sr" to 44100L), "junk")))
        assertEquals(emptyList<Any>(), answer["streams"])
        mirror.closed(c)
    }

    private fun annexBUnits(stream: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>(); var i = 0
        while (i + 4 < stream.size) { if (stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 0 && stream[i + 3].toInt() == 1 && (stream[i + 4].toInt() and 0x1f) == 9) { starts += i; i += 4 } else i++ }
        return starts.mapIndexed { n, at -> stream.copyOfRange(at + 6, if (n + 1 < starts.size) starts[n + 1] else stream.size) }
    }

    private fun nals(au: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(); var i = 0; var start = -1
        while (i + 3 <= au.size) {
            val three = i + 2 < au.size && au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1
            val four = i + 3 < au.size && au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 0 && au[i + 3].toInt() == 1
            if (three || four) { if (start >= 0) out += au.copyOfRange(start, i); i += if (four) 4 else 3; start = i } else i++
        }
        if (start in 0 until au.size) out += au.copyOfRange(start, au.size)
        return out
    }

    private fun header(size: Int, type: Int, ntpMicros: Long): ByteArray {
        val h = ByteArray(128)
        for (i in 0 until 4) h[i] = (size shr (8 * i)).toByte()
        h[4] = type.toByte()
        val seconds = ntpMicros / 1_000_000; val fraction = (ntpMicros % 1_000_000) * 65536 / 1_000_000
        val raw = (seconds shl 32) or (fraction shl 16)
        for (i in 0 until 8) h[8 + i] = (raw shr (8 * i)).toByte()
        return h
    }

    @Test fun anEncryptedScreenAndItsSoundComeOutAsATransportStreamThatFfmpegDecodes() {
        val ffmpeg = which("ffmpeg"); val ffprobe = which("ffprobe")
        assumeTrue("ffmpeg and ffprobe are needed", ffmpeg != null && ffprobe != null)
        val dir = File.createTempFile("mirror", "").let { it.delete(); it.mkdirs(); it }
        val c = paired()
        try {
            val h264 = File(dir, "in.h264"); val aac = File(dir, "in.aac")
            assertEquals(0, run(ffmpeg!!, "-v", "error", "-y", "-f", "lavfi", "-i", "testsrc=duration=2:size=640x360:rate=30", "-c:v", "libx264", "-pix_fmt", "yuv420p", "-profile:v", "baseline", "-g", "15", "-bf", "0", "-x264-params", "aud=1", "-f", "h264", h264.path).first)
            assertEquals(0, run(ffmpeg, "-v", "error", "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=2:sample_rate=44100", "-ac", "2", "-c:a", "aac", "-f", "adts", aac.path).first)
            val units = annexBUnits(h264.readBytes())
            val parts = nals(units.first())
            val sps = parts.first { (it[0].toInt() and 0x1f) == 7 }; val pps = parts.first { (it[0].toInt() and 0x1f) == 8 }

            // SETUP: keys, then both streams.
            mirror.setup(c, keys())
            val answer = mirror.setup(c, mapOf("streams" to listOf(mapOf("type" to 96L, "ct" to 4L, "sr" to 44100L, "channels" to 2L), mapOf("type" to 110L, "streamConnectionID" to 987654321L))))
            val streams = (answer["streams"] as List<*>).map { it as Map<*, *> }
            val audioPorts = streams.first { it["type"] == 96L }; val videoPort = (streams.first { it["type"] == 110L }["dataPort"] as Long).toInt()
            assertTrue((audioPorts["controlPort"] as Long) > 0)

            // The sender's side of the arithmetic: the same keys, the encryption direction.
            val session = c.mirror!!
            val videoCipher = MirrorCrypto.videoCipher(aesKey, c.pairing.sharedSecret!!, 987654321L)
            val audioKey = MirrorCrypto.audioKey(aesKey, c.pairing.sharedSecret!!)
            val socket = Socket(InetAddress.getLoopbackAddress(), videoPort); val out = socket.getOutputStream()
            val avcC = byteArrayOf(1, sps[1], sps[2], sps[3], 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) + sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
            out.write(header(avcC.size, 1, 0)); out.write(avcC)
            val udp = DatagramSocket(); val audioPort = (audioPorts["dataPort"] as Long).toInt()
            val adts = aac.readBytes(); val frames = ArrayList<ByteArray>(); var at = 0
            while (at + 7 < adts.size) { val len = ((adts[at + 3].toInt() and 3) shl 11) or ((adts[at + 4].toInt() and 0xff) shl 3) or ((adts[at + 5].toInt() and 0xff) shr 5); if (len < 7 || at + len > adts.size) break; frames += adts.copyOfRange(at + 7, at + len); at += len }
            var a = 0; var sequence = 100; var sentAudio = 0
            val iv = ByteArray(16) { (0x70 + it).toByte() }
            val ntpBase = 3_900_000_000L * 1_000_000
            for ((n, au) in units.withIndex()) {
                val plain = ByteArrayOutputStream()
                for (nal in nals(au)) { val type = nal[0].toInt() and 0x1f; if (type == 7 || type == 8 || type == 9) continue; plain.write(byteArrayOf((nal.size shr 24).toByte(), (nal.size shr 16).toByte(), (nal.size shr 8).toByte(), nal.size.toByte())); plain.write(nal) }
                val body = videoCipher.update(plain.toByteArray())
                out.write(header(body.size, 0, ntpBase + n * 33_333L)); out.write(body)
                // About 1.5 audio frames per video frame: send what is due, twice, as an iPhone does.
                while (a < frames.size && a * 1024.0 / 44100 <= n / 30.0) {
                    val frame = frames[a]
                    val whole = frame.size / 16 * 16
                    val enc = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(audioKey, "AES"), IvParameterSpec(iv)) }
                    val cipherText = if (whole > 0) enc.doFinal(frame, 0, whole) + frame.copyOfRange(whole, frame.size) else frame
                    val rtp = ByteArray(12) .also { it[0] = 0x80.toByte(); it[1] = 0x60; it[2] = (sequence shr 8).toByte(); it[3] = sequence.toByte(); val ts = a * 1024; it[4] = (ts shr 24).toByte(); it[5] = (ts shr 16).toByte(); it[6] = (ts shr 8).toByte(); it[7] = ts.toByte() } + cipherText
                    repeat(2) { udp.send(DatagramPacket(rtp, rtp.size, InetAddress.getLoopbackAddress(), audioPort)) }
                    sequence++; a++; sentAudio++
                }
                out.flush()
                if (n % 10 == 9) Thread.sleep(30)
            }
            Thread.sleep(500)
            assertEquals("the screen was shown once, with audio in the stream from the start", 1, screen.shown.size)
            val ts = File(dir, "out.ts"); val sink = ByteArrayOutputStream(); val chunk = ByteArray(65536)
            val source = screen.shown.first()
            while (true) { val n = source.read(chunk, 0, chunk.size, 200); if (n <= 0) break; sink.write(chunk, 0, n) }
            ts.writeBytes(sink.toByteArray())
            val (code, info) = run(ffprobe!!, "-v", "error", "-show_entries", "stream=codec_name,codec_type,width,height,sample_rate,channels", "-of", "compact", ts.path)
            assertEquals(info, 0, code)
            assertTrue(info, "codec_name=h264|codec_type=video|width=640|height=360" in info)
            assertTrue(info, "codec_name=aac|codec_type=audio|sample_rate=44100|channels=2" in info)
            val (decode, log) = run(ffmpeg, "-v", "error", "-i", ts.path, "-f", "null", "-")
            assertEquals(log, 0, decode); assertEquals("ffmpeg complained: $log", "", log.trim())
            val (_, v) = run(ffprobe, "-v", "error", "-count_frames", "-select_streams", "v:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", ts.path)
            assertEquals(units.size, v.lines().first { it.isNotBlank() }.trim().toInt())
            val (_, au) = run(ffprobe, "-v", "error", "-count_frames", "-select_streams", "a:0", "-show_entries", "stream=nb_read_frames", "-of", "csv=p=0", ts.path)
            val audioFrames = au.lines().first { it.isNotBlank() }.trim().toInt()
            // Every frame once, the copies dropped. The first few can be lost: audio that comes in before the first complete picture has no stream to go into.
            assertTrue("$audioFrames audio frames came out of $sentAudio sent twice", audioFrames in (sentAudio - 8)..sentAudio)
            // The sender stops: the screen is given back.
            mirror.closed(c)
            assertEquals(1, screen.hidden)
            session.close("test")
            socket.close(); udp.close()
        } finally { dir.deleteRecursively(); mirror.shutdown() }
    }

    @Test fun anAudioStreamThatComesLaterRestartsTheStreamWithAudioInIt() {
        val c = paired(); mirror.setup(c, keys())
        val answer = mirror.setup(c, mapOf("streams" to listOf(mapOf("type" to 110L, "streamConnectionID" to 1L))))
        val port = ((answer["streams"] as List<*>).first() as Map<*, *>)["dataPort"] as Long
        val cipher = MirrorCrypto.videoCipher(aesKey, c.pairing.sharedSecret!!, 1L)
        val socket = Socket(InetAddress.getLoopbackAddress(), port.toInt()); val out = socket.getOutputStream()
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0x8C.toByte(), 0x8D.toByte(), 0x40, 0x50, 0x1E, 0xD0.toByte()); val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val avcC = byteArrayOf(1, 0x42, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) + sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
        out.write(header(avcC.size, 1, 0)); out.write(avcC)
        val idr = byteArrayOf(0, 0, 0, 6, 0x65, 1, 2, 3, 4, 5)
        val body = cipher.update(idr); out.write(header(body.size, 0, 1_000_000L)); out.write(body); out.flush()
        val end = System.nanoTime() + 3_000_000_000L
        while (screen.shown.isEmpty() && System.nanoTime() < end) Thread.sleep(20)
        assertEquals(1, screen.shown.size)
        mirror.setup(c, mapOf("streams" to listOf(mapOf("type" to 96L, "ct" to 4L, "sr" to 44100L, "channels" to 2L))))
        assertEquals("the picture starts again with audio", 2, screen.shown.size)
        assertTrue("and the first stream is closed", screen.shown[0].isClosed)
        socket.close(); mirror.shutdown()
    }

    @Test fun aPictureThatIsTakenAwayAndGivenBackIsShownAgain() {
        val c = paired(); mirror.setup(c, keys())
        fun start(id: Long): Pair<Socket, MirrorCrypto?> {
            val answer = mirror.setup(c, mapOf("streams" to listOf(mapOf("type" to 110L, "streamConnectionID" to id))))
            val port = (((answer["streams"] as List<*>).first() as Map<*, *>)["dataPort"] as Long).toInt()
            return Socket(InetAddress.getLoopbackAddress(), port) to null
        }
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0x8C.toByte(), 0x8D.toByte(), 0x40, 0x50, 0x1E, 0xD0.toByte()); val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val avcC = byteArrayOf(1, 0x42, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) + sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
        fun send(socket: Socket, id: Long) {
            val cipher = MirrorCrypto.videoCipher(aesKey, c.pairing.sharedSecret!!, id)
            socket.getOutputStream().write(header(avcC.size, 1, 0) + avcC)
            val body = cipher.update(byteArrayOf(0, 0, 0, 6, 0x65, 1, 2, 3, 4, 5))
            socket.getOutputStream().write(header(body.size, 0, 1_000_000L) + body); socket.getOutputStream().flush()
        }
        fun waitFor(count: Int) { val end = System.nanoTime() + 3_000_000_000L; while (screen.shown.size < count && System.nanoTime() < end) Thread.sleep(20) }
        val (first, _) = start(1L); send(first, 1L); waitFor(1)
        assertEquals(1, screen.shown.size)
        assertEquals(200, 200); mirror.teardown(c, listOf(110, 96))                      // a full-screen video takes the streams away
        assertTrue("the old stream was closed", screen.shown[0].isClosed)
        assertEquals("and the screen was not touched", 0, screen.hidden)
        first.close()
        val (second, _) = start(2L); send(second, 2L); waitFor(2)
        assertEquals("the picture is shown again", 2, screen.shown.size)
        assertFalse(screen.shown[1].isClosed)
        second.close(); mirror.shutdown()
    }

    @Test fun aPeerThePolicyDoesNotAllowCannotFeedTheStreams() {
        val strict = AirPlayMirror(screen, fake, { logs += it }, peerAllowed = { false })
        val c = paired(); strict.setup(c, keys())
        val answer = strict.setup(c, mapOf("streams" to listOf(mapOf("type" to 110L, "streamConnectionID" to 1L), mapOf("type" to 96L, "ct" to 4L, "sr" to 44100L))))
        val streams = (answer["streams"] as List<*>).map { it as Map<*, *> }
        val video = (streams.first { it["type"] == 110L }["dataPort"] as Long).toInt(); val audio = (streams.first { it["type"] == 96L }["dataPort"] as Long).toInt()
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F, 0x8C.toByte(), 0x8D.toByte(), 0x40, 0x50, 0x1E, 0xD0.toByte()); val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val avcC = byteArrayOf(1, 0x42, 0, 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) + sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
        val cipher = MirrorCrypto.videoCipher(aesKey, c.pairing.sharedSecret!!, 1L)
        val socket = Socket(InetAddress.getLoopbackAddress(), video)
        runCatching {
            socket.getOutputStream().write(header(avcC.size, 1, 0) + avcC)
            val body = cipher.update(byteArrayOf(0, 0, 0, 6, 0x65, 1, 2, 3, 4, 5)); socket.getOutputStream().write(header(body.size, 0, 1_000_000L) + body); socket.getOutputStream().flush()
        }
        val udp = DatagramSocket(); val rtp = ByteArray(40) { 1 }.also { it[0] = 0x80.toByte(); it[1] = 0x60 }
        udp.send(DatagramPacket(rtp, rtp.size, InetAddress.getLoopbackAddress(), audio))
        Thread.sleep(500)
        assertTrue("nothing was shown", screen.shown.isEmpty())
        socket.close(); udp.close(); strict.shutdown()
    }

    @Test fun theEngineTakingTheScreenAwayEndsTheMirror() {
        val c = paired(); var hung = 0
        val connection = AirPlayConnection(c.remote, c.pairing) { hung++ }.also { it.keyMessage = c.keyMessage }
        mirror.setup(connection, keys())
        val session = connection.mirror!!
        // The engine plays the mirror, then somebody else's film.
        mirror.onEngineState(mapOf("owner" to "cast:airplay", "mirror" to true), "cast:airplay")
        assertTrue(session.showing)
        mirror.onEngineState(mapOf("owner" to "cast:airplay", "mirror" to false), "cast:airplay")       // a video that AirPlay itself plays: the mirror waits
        assertFalse(session.showing); assertEquals(0, hung)
        mirror.setup(connection, keys())                                                                // (a new session of the same sender, to try the other end)
        val second = connection.mirror!!
        mirror.onEngineState(mapOf("owner" to "cast:airplay", "mirror" to true), "cast:airplay")
        mirror.onEngineState(mapOf("owner" to "", "mirror" to false), "cast:airplay")                   // stopped by the viewer
        assertEquals(1, hung); assertTrue(logs.any { "player was taken" in it })
        assertNotNull(second)
    }
}
