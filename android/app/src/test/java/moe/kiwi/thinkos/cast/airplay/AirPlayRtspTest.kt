package moe.kiwi.thinkos.cast.airplay

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket

/** The RTSP half of the server and the FairPlay steps, against a recording stand-in for the mirror side. */
class AirPlayRtspTest {
    private class Recorder : AirPlayMirrorControl {
        val calls = ArrayList<String>()
        var refuse = false
        var connection: AirPlayConnection? = null
        override fun setup(connection: AirPlayConnection, request: Map<String, Any?>): Map<String, Any?> {
            if (refuse) throw IllegalArgumentException("no")
            this.connection = connection; calls += "setup ${request.keys.sorted()}"
            return mapOf("eventPort" to 4000L, "timingPort" to 4001L)
        }
        override fun teardown(connection: AirPlayConnection, types: List<Int>?) { calls += "teardown $types" }
        override fun closed(connection: AirPlayConnection) { calls += "closed" }
        override fun volume(decibels: Float) { calls += "volume $decibels" }
    }

    private val fairPlay = object : FairPlayKeys {
        override fun phase1(request: ByteArray) = if (request[4].toInt() == 3) ByteArray(142) { 1 } else null
        override fun phase2(request: ByteArray) = if (request[4].toInt() == 3) ByteArray(32) { 2 } else null
        override fun unwrap(keyMessage: ByteArray, wrapped: ByteArray) = ByteArray(16)
    }
    private class NoVideo : AirPlayControl {
        override fun play(request: AirPlayPlay) {}; override fun rate(value: Float) {}; override fun scrub(positionSeconds: Double) {}; override fun stop() {}; override fun playback(): AirPlayPlayback? = null
    }

    private val recorder = Recorder()
    private val identity = AirPlayIdentity(ByteArray(32) { it.toByte() })
    private val device = AirPlayDevice("AA:BB:CC:DD:EE:FF", { "Living room" }, mirror = true, identityKey = { identity.publicKey }, identityId = "11111111-2222-3333-4444-555555555555")
    private lateinit var server: AirPlayServer
    private var port = 0
    private val sockets = ArrayList<Socket>()

    @Before fun start() {
        server = AirPlayServer(device, identity, NoVideo(), { true }, {}, recorder, fairPlay, { AirPlayTxt(byteArrayOf(1, 2, 3), byteArrayOf(9, 8)) })
        port = server.start(0, InetAddress.getByName("127.0.0.1"))
    }

    @After fun stop() { sockets.forEach { runCatching { it.close() } }; server.stop() }

    private class Reply(val protocol: String, val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private fun connect() = Socket(InetAddress.getByName("127.0.0.1"), port).also { it.soTimeout = 5000; sockets += it }

    private fun read(input: InputStream): Reply {
        val head = ByteArrayOutputStream(); var matched = 0
        while (matched < 4) { val b = input.read(); if (b < 0) throw java.io.IOException("closed"); head.write(b); matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0 }
        val lines = head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0); var n = 0
        while (n < body.size) { val r = input.read(body, n, body.size - n); if (r < 0) break; n += r }
        return Reply(lines[0].substringBefore(' '), lines[0].split(' ')[1].toInt(), headers, body)
    }

    private fun send(socket: Socket, line: String, body: ByteArray = ByteArray(0), extra: String = ""): Reply {
        socket.getOutputStream().write(("$line\r\nCSeq: 7\r\nContent-Length: ${body.size}\r\n$extra\r\n").toByteArray() + body); socket.getOutputStream().flush()
        return read(socket.getInputStream())
    }

    @Test fun theHttpStyleRequestsAreAnsweredWhenTheRequestLineSaysRtsp() {
        // What a real sender does: `GET /info RTSP/1.0`, `POST /pair-setup RTSP/1.0`. The answer says RTSP too and echoes the CSeq.
        val s = connect()
        val info = send(s, "GET /info RTSP/1.0")
        assertEquals("RTSP/1.0", info.protocol); assertEquals(200, info.status); assertEquals("7", info.headers["cseq"])
        assertEquals(0x5A7FFEE6L, Plist.read(info.body)["features"])
        val asked = send(s, "GET /info RTSP/1.0", Plist.binary(mapOf("qualifier" to listOf("txtAirPlay"))), "Content-Type: application/x-apple-binary-plist\r\n")
        assertEquals(200, asked.status); assertArrayEquals(byteArrayOf(1, 2, 3), Plist.read(asked.body)["txtAirPlay"] as ByteArray)
        val setup = send(s, "POST /pair-setup RTSP/1.0", ByteArray(32) { 5 })
        assertEquals("RTSP/1.0", setup.protocol); assertEquals(200, setup.status); assertArrayEquals(identity.publicKey, setup.body)
        val verify = send(s, "POST /pair-verify RTSP/1.0", byteArrayOf(1, 0, 0, 0) + ByteArray(32) { 9 } + ByteArray(32) { 8 })
        assertEquals(200, verify.status); assertEquals(96, verify.body.size)
        assertEquals(200, send(s, "POST /fp-setup RTSP/1.0", ByteArray(16).also { it[4] = 3 }).status)
        assertEquals(200, send(s, "POST /feedback RTSP/1.0").status)
        assertEquals(404, send(s, "POST /nothing RTSP/1.0").status)
    }

    @Test fun optionsAreAnsweredInRtsp() {
        val r = send(connect(), "OPTIONS * RTSP/1.0")
        assertEquals("RTSP/1.0", r.protocol); assertEquals(200, r.status); assertEquals("7", r.headers["cseq"])
        assertTrue("SETUP" in r.headers["public"]!! && "TEARDOWN" in r.headers["public"]!!)
    }

    @Test fun theFairPlayHandshakeKeepsTheKeyMessageOnTheConnection() {
        val s = connect()
        val one = send(s, "POST /fp-setup HTTP/1.1", ByteArray(16).also { it[4] = 3 })
        assertEquals(200, one.status); assertEquals(142, one.body.size)
        val two = send(s, "POST /fp-setup HTTP/1.1", ByteArray(164).also { it[4] = 3; it[0] = 9 })
        assertEquals(200, two.status); assertEquals(32, two.body.size)
        send(s, "SETUP rtsp://192.168.3.5/1 RTSP/1.0", Plist.binary(mapOf("ekey" to ByteArray(72))), "Content-Type: application/x-apple-binary-plist\r\n")
        assertEquals(9, recorder.connection!!.keyMessage!![0].toInt())
        assertEquals(400, send(s, "POST /fp-setup HTTP/1.1", ByteArray(20)).status)
        assertEquals(400, send(s, "POST /fp-setup HTTP/1.1", ByteArray(16)).status)          // not FairPlay version 3
    }

    @Test fun setupTakesAPlistAndAnswersWithOne() {
        val r = send(connect(), "SETUP rtsp://192.168.3.5/1 RTSP/1.0", Plist.binary(mapOf("ekey" to ByteArray(72), "eiv" to ByteArray(16), "timingPort" to 7010L)), "Content-Type: application/x-apple-binary-plist\r\n")
        assertEquals("RTSP/1.0", r.protocol); assertEquals(200, r.status)
        assertEquals("application/x-apple-binary-plist", r.headers["content-type"])
        assertEquals(4000L, Plist.read(r.body)["eventPort"])
        assertEquals(listOf("setup [eiv, ekey, timingPort]"), recorder.calls)
    }

    @Test fun aSetupThatIsRefusedOrNotAPlistIs400() {
        recorder.refuse = true
        assertEquals(400, send(connect(), "SETUP rtsp://x/1 RTSP/1.0", Plist.binary(mapOf("ekey" to ByteArray(3)))).status)
        recorder.refuse = false
        assertEquals(400, send(connect(), "SETUP rtsp://x/1 RTSP/1.0", "bplist00junk".toByteArray()).status)
    }

    @Test fun teardownNamesTheStreamsOrEndsEverything() {
        val s = connect()
        assertEquals(200, send(s, "TEARDOWN rtsp://x/1 RTSP/1.0", Plist.binary(mapOf("streams" to listOf(mapOf("type" to 96L), mapOf("type" to 110L))))).status)
        assertEquals(200, send(s, "TEARDOWN rtsp://x/1 RTSP/1.0").status)
        assertEquals(listOf("teardown [96, 110]", "teardown null"), recorder.calls)
    }

    @Test fun volumeIsReadAndSet() {
        val s = connect()
        assertEquals("volume: 0.000000\r\n", String(send(s, "GET_PARAMETER rtsp://x/1 RTSP/1.0", "volume\r\n".toByteArray(), "Content-Type: text/parameters\r\n").body))
        assertEquals(200, send(s, "SET_PARAMETER rtsp://x/1 RTSP/1.0", "volume: -12.500000\r\n".toByteArray(), "Content-Type: text/parameters\r\n").status)
        assertEquals(listOf("volume -12.5"), recorder.calls)
    }

    @Test fun theConnectionClosingIsToldToTheMirrorSide() {
        val s = connect(); send(s, "OPTIONS * RTSP/1.0"); s.close()
        val end = System.nanoTime() + 3_000_000_000L
        while ("closed" !in recorder.calls && System.nanoTime() < end) Thread.sleep(20)
        assertTrue("closed" in recorder.calls)
    }

    @Test fun withoutTheMirrorSideRtspIsNotImplemented() {
        val plain = AirPlayServer(AirPlayDevice("AA:BB:CC:DD:EE:FF", { "x" }), identity, NoVideo(), { true })
        val p = plain.start(0, InetAddress.getByName("127.0.0.1"))
        try {
            val s = Socket(InetAddress.getByName("127.0.0.1"), p).also { it.soTimeout = 5000; sockets += it }
            assertEquals(501, send(s, "SETUP rtsp://x/1 RTSP/1.0").status)
            assertEquals(200, send(s, "GET /info RTSP/1.0").status)                                   // the video-only receiver is asked the same way
            assertEquals(200, send(s, "POST /pair-setup RTSP/1.0", ByteArray(32)).status)
            assertEquals(404, send(s, "POST /fp-setup HTTP/1.1", ByteArray(16)).status)
        } finally { plain.stop() }
    }

    @Test fun theMirrorCapableInfoSaysWhatTheScreenCanBe() {
        val r = send(connect(), "GET /info HTTP/1.1")
        val p = Plist.read(r.body)
        assertEquals(0x5A7FFEE6L, p["features"]); assertEquals("AppleTV3,2", p["model"]); assertEquals(68L, p["statusFlags"])
        assertArrayEquals(identity.publicKey, p["pk"] as ByteArray)
        val display = (p["displays"] as List<*>).first() as Map<*, *>
        assertEquals(1280L, display["width"]); assertEquals(720L, display["height"])
        assertNotNull(p["audioFormats"])
        // /server-info, which the video connection reads, never offers FairPlay.
        assertEquals(0x19L, Plist.read(send(connect(), "GET /server-info HTTP/1.1").body)["features"])
    }

    @Test fun aSenderThatAsksForTheTextRecordsGetsThemAndNothingElse() {
        val ask = { names: List<String> -> Plist.read(send(connect(), "GET /info HTTP/1.1", Plist.binary(mapOf("qualifier" to names)), "Content-Type: application/x-apple-binary-plist\r\n").body) }
        assertEquals(setOf("txtAirPlay"), ask(listOf("txtAirPlay")).keys)
        assertArrayEquals(byteArrayOf(1, 2, 3), ask(listOf("txtAirPlay"))["txtAirPlay"] as ByteArray)
        assertArrayEquals(byteArrayOf(9, 8), ask(listOf("txtRAOP"))["txtRAOP"] as ByteArray)
        assertEquals(setOf("txtAirPlay", "txtRAOP"), ask(listOf("txtAirPlay", "txtRAOP")).keys)
        assertTrue("an unknown qualifier gets an empty answer", ask(listOf("nothing")).isEmpty())
    }

    @Test fun theMirrorRecordsNameTheDeviceAndCarryItsKey() {
        val txt = AirPlayRecord.attributes(device)
        assertEquals(listOf("deviceid", "features", "pw", "flags", "model", "pk", "pi", "srcvers", "vv"), txt.keys.toList())
        assertEquals("0x5A7FFEE6,0x0", txt["features"]); assertEquals("AppleTV3,2", txt["model"]); assertEquals("2", txt["vv"]); assertEquals("0x4", txt["flags"])
        assertEquals("11111111-2222-3333-4444-555555555555", txt["pi"])
        assertEquals("the key pair-setup hands out", AirPlayRecord.hex(identity.publicKey), txt["pk"]); assertEquals(64, txt["pk"]!!.length)
        assertEquals("AABBCCDDEEFF@Living room", AirPlayRecord.raopName(device))
        val raop = AirPlayRecord.raopAttributes(device)
        assertEquals("0,3,5", raop["et"]); assertEquals("UDP", raop["tp"]); assertEquals("65537", raop["vn"]); assertEquals("AppleTV3,2", raop["am"])
        assertEquals(txt["features"], raop["ft"]); assertEquals(txt["pk"], raop["pk"]); assertEquals("1", raop["txtvers"]); assertEquals("2", raop["ch"]); assertEquals("0x4", raop["sf"])
    }

    @Test fun txtBytesAreLengthPrefixedKeyValuePairs() {
        val bytes = AirPlayRecord.txtBytes(linkedMapOf("a" to "1".toByteArray(), "key" to "value".toByteArray()))
        assertArrayEquals(byteArrayOf(3) + "a=1".toByteArray() + byteArrayOf(9) + "key=value".toByteArray(), bytes)
        assertThrows(IllegalArgumentException::class.java) { AirPlayRecord.txtBytes(mapOf("k" to ByteArray(300))) }
    }

    @Test fun eldAndLcConfigsAreWhatTheSpecsSay() {
        assertEquals("F8E85000", Aac.eldConfig(44100, 2).joinToString("") { "%02X".format(it) })
        assertEquals("1210", Aac.lcConfig(44100, 2).joinToString("") { "%02X".format(it) })
        assertEquals("1190", Aac.lcConfig(48000, 2).joinToString("") { "%02X".format(it) })
    }
}
