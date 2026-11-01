package moe.kiwi.thinkos.cast.airplay

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AirPlayServerTest {
    private class FakeControl : AirPlayControl {
        val calls = ArrayList<String>()
        var played: AirPlayPlay? = null
        var refuse: String? = null
        var playback: AirPlayPlayback? = null
        override fun play(request: AirPlayPlay) { refuse?.let { throw IllegalArgumentException(it) }; played = request; calls += "play" }
        override fun rate(value: Float) { calls += "rate $value" }
        override fun scrub(positionSeconds: Double) { calls += "scrub $positionSeconds" }
        override fun stop() { calls += "stop" }
        override fun playback() = playback
    }

    private class Reply(val status: Int, val headers: Map<String, String>, val body: ByteArray)

    private val control = FakeControl()
    private val identity = AirPlayIdentity(ByteArray(32) { it.toByte() })
    private val device = AirPlayDevice("AA:BB:CC:DD:EE:FF", { "Living room" })
    private val logs = ArrayList<String>()
    private var allow = true
    private lateinit var server: AirPlayServer
    private var port = 0
    private val sockets = ArrayList<Socket>()

    @Before fun start() {
        server = AirPlayServer(device, identity, control, { allow }, { synchronized(logs) { logs += it } })
        port = server.start(0, InetAddress.getByName("127.0.0.1"))
    }

    @After fun stop() { sockets.forEach { runCatching { it.close() } }; server.stop() }

    private fun connect(): Socket = Socket(InetAddress.getByName("127.0.0.1"), port).also { it.soTimeout = 5000; sockets += it }

    private fun readReply(input: InputStream): Reply {
        val head = ByteArrayOutputStream(); var matched = 0
        while (matched < 4) {
            val b = input.read(); if (b < 0) throw java.io.IOException("closed")
            head.write(b); matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = head.toString("ISO-8859-1").split("\r\n").filter { it.isNotEmpty() }
        val headers = lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }
        val body = ByteArray(headers["content-length"]?.toInt() ?: 0)
        var n = 0; while (n < body.size) { val r = input.read(body, n, body.size - n); if (r < 0) break; n += r }
        return Reply(lines[0].split(' ')[1].toInt(), headers, body)
    }

    private fun send(socket: Socket, method: String, path: String, body: ByteArray = ByteArray(0), type: String? = null, extra: String = ""): Reply {
        val head = "$method $path HTTP/1.1\r\nHost: tv\r\n" + (type?.let { "Content-Type: $it\r\n" } ?: "") + "Content-Length: ${body.size}\r\n$extra\r\n"
        socket.getOutputStream().write(head.toByteArray() + body); socket.getOutputStream().flush()
        return readReply(socket.getInputStream())
    }

    /** The other side hung up: an end of stream, or a reset when it closed with our bytes unread. */
    private fun hungUp(socket: Socket): Boolean = try { socket.getInputStream().read() == -1 } catch (error: java.net.SocketException) { true }

    private fun request(method: String, path: String, body: ByteArray = ByteArray(0), type: String? = null) = send(connect(), method, path, body, type)

    @Test fun serverInfoSaysWhatTheDeviceCanDo() {
        val r = request("GET", "/server-info")
        assertEquals(200, r.status); assertEquals("text/x-apple-plist+xml", r.headers["content-type"])
        val p = Plist.read(r.body)
        assertEquals("AA:BB:CC:DD:EE:FF", p["deviceid"]); assertEquals(0x19L, p["features"]); assertEquals("AppleTV3,2", p["model"])
    }

    @Test fun infoIsABinaryPlistWithTheName() {
        val r = request("GET", "/info")
        assertEquals("application/x-apple-binary-plist", r.headers["content-type"])
        val p = Plist.read(r.body)
        assertEquals("Living room", p["name"]); assertEquals(0x19L, p["features"]); assertEquals("AA:BB:CC:DD:EE:FF", p["deviceID"])
    }

    @Test fun theFeaturesClaimOnlyWhatIsImplemented() {
        val f = AirPlayDevice.FEATURES
        for (bit in listOf(1, 2, 5, 7, 9, 13, 14)) assertEquals("bit $bit", 0L, (f shr bit) and 1)          // photos, FairPlay, slideshow, mirroring, audio, caching, FairPlay auth
        for (bit in listOf(0, 3, 4)) assertEquals("bit $bit", 1L, (f shr bit) and 1)                      // video, its volume control, HTTP live streams
    }

    @Test fun pairingRoutesWork() {
        val s = connect()
        val setup = send(s, "POST", "/pair-setup", ByteArray(32), "application/octet-stream")
        assertEquals(200, setup.status); assertArrayEquals(identity.publicKey, setup.body)
        assertEquals(470, send(s, "POST", "/pair-verify", byteArrayOf(0, 0, 0, 0) + ByteArray(64)).status)
        assertEquals(470, send(s, "POST", "/pair-setup", ByteArray(5)).status)
    }

    @Test fun playTakesABinaryPlist() {
        val body = Plist.binary(mapOf("Content-Location" to "http://192.168.3.5/video.m3u8", "Start-Position" to 0.5, "uuid" to "x"))
        val r = send(connect(), "POST", "/play", body, "application/x-apple-binary-plist", "X-Apple-Session-ID: 7D1E\r\n")
        assertEquals(200, r.status)
        val play = control.played!!
        assertEquals("http://192.168.3.5/video.m3u8", play.url); assertEquals(0.5, play.startFraction, 0.0001); assertNull(play.startSeconds); assertEquals("7D1E", play.sessionId)
        assertEquals("7D1E", r.headers["x-apple-session-id"])
    }

    @Test fun playTakesAnXmlPlistAndASecondsStart() {
        val r = request("POST", "/play", Plist.xml(mapOf("Content-Location" to "https://tv.test/a.mp4", "Start-Position-Seconds" to 83.5)), "text/x-apple-plist+xml")
        assertEquals(200, r.status); assertEquals(83.5, control.played!!.startSeconds!!, 0.0001)
    }

    @Test fun aChunkedBodyIsReadToo() {
        val body = Plist.binary(mapOf("Content-Location" to "http://192.168.3.5/v.mp4"))
        val s = connect()
        val head = "POST /play HTTP/1.1\r\nContent-Type: application/x-apple-binary-plist\r\nTransfer-Encoding: chunked\r\n\r\n"
        val half = body.size / 2
        val out = ByteArrayOutputStream()
        out.write(head.toByteArray())
        out.write("%x\r\n".format(half).toByteArray()); out.write(body, 0, half); out.write("\r\n".toByteArray())
        out.write("%x\r\n".format(body.size - half).toByteArray()); out.write(body, half, body.size - half); out.write("\r\n0\r\n\r\n".toByteArray())
        s.getOutputStream().write(out.toByteArray())
        assertEquals(200, readReply(s.getInputStream()).status)
        assertEquals("http://192.168.3.5/v.mp4", control.played!!.url)
    }

    @Test fun aBadChunkedBodyIsRefused() {
        val s = connect()
        s.getOutputStream().write("POST /play HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nzz\r\n".toByteArray())
        assertEquals(400, readReply(s.getInputStream()).status)
        val big = connect()
        big.getOutputStream().write("POST /play HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\nFFFFFF\r\n".toByteArray())
        assertEquals(413, readReply(big.getInputStream()).status)
    }

    @Test fun playTakesTheOldTextForm() {
        val r = request("POST", "/play", "Content-Location: http://192.168.1.2/a.mp4\r\nStart-Position: 0.25\r\n".toByteArray(), "text/parameters")
        assertEquals(200, r.status); assertEquals("http://192.168.1.2/a.mp4", control.played!!.url); assertEquals(0.25, control.played!!.startFraction, 0.0001)
    }

    @Test fun playWithoutAnAddressOrWithGarbageIsRefused() {
        assertEquals(400, request("POST", "/play", Plist.binary(mapOf("x" to 1L)), "application/x-apple-binary-plist").status)
        assertEquals(400, request("POST", "/play", "bplist00garbage".toByteArray(), "application/x-apple-binary-plist").status)
        assertEquals(400, request("POST", "/play").status)
        assertNull(control.played)
    }

    @Test fun theStartFractionIsClampedToTheFilm() {
        request("POST", "/play", Plist.binary(mapOf("Content-Location" to "http://a/b.mp4", "Start-Position" to 7.0)), null)
        assertEquals(1.0, control.played!!.startFraction, 0.0)
    }

    @Test fun whenThePlayerRefusesTheSenderIsToldToStop() {
        val events = connect()
        assertEquals(101, send(events, "POST", "/reverse", extra = "Upgrade: PTTH/1.0\r\nConnection: Upgrade\r\nX-Apple-Purpose: event\r\nX-Apple-Session-ID: S1\r\n").status)
        control.refuse = "only http and https addresses can be played, not mlhls"
        assertEquals(400, request("POST", "/play", Plist.binary(mapOf("Content-Location" to "mlhls://localhost/master.m3u8")), null).status)
        val event = readEvent(events)
        assertEquals("stopped", event["state"]); assertEquals("video", event["category"])
    }

    private fun readEvent(socket: Socket): Map<String, Any?> {
        // After the upgrade this side is the server: the receiver sends a POST /event request.
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream(); var matched = 0
        while (matched < 4) { val b = input.read(); head.write(b); matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0 }
        val text = head.toString("ISO-8859-1")
        assertTrue(text, text.startsWith("POST /event HTTP/1.1"))
        val length = Regex("Content-Length: (\\d+)").find(text)!!.groupValues[1].toInt()
        val body = ByteArray(length); var n = 0; while (n < length) n += input.read(body, n, length - n)
        return Plist.read(body)
    }

    @Test fun eventsGoDownTheReverseChannelInOrder() {
        val events = connect()
        val up = send(events, "POST", "/reverse", extra = "Upgrade: PTTH/1.0\r\nConnection: Upgrade\r\nX-Apple-Session-ID: S9\r\n")
        assertEquals(101, up.status); assertEquals("PTTH/1.0", up.headers["upgrade"])
        Thread.sleep(100)
        for (state in listOf("loading", "playing", "paused", "stopped")) server.sendEvent(state)
        assertEquals(listOf("loading", "playing", "paused", "stopped"), (1..4).map { readEvent(events)["state"] })
    }

    @Test fun anEventWithNoChannelIsDroppedQuietly() { server.sendEvent("playing") }

    @Test fun rateScrubStopAndTheScrubReadback() {
        val s = connect()
        assertEquals(200, send(s, "POST", "/rate?value=0.000000").status)
        assertEquals(200, send(s, "POST", "/rate?value=1.000000").status)
        assertEquals(200, send(s, "POST", "/scrub?position=12.5").status)
        control.playback = AirPlayPlayback(100.0, 12.5, 1f, true, false)
        val get = send(s, "GET", "/scrub")
        assertEquals("duration: 100.000000\r\nposition: 12.500000\r\n", String(get.body))
        assertEquals(200, send(s, "POST", "/stop").status)
        assertEquals(listOf("rate 0.0", "rate 1.0", "scrub 12.5", "stop"), control.calls)
    }

    @Test fun playbackInfoDescribesTheFilm() {
        assertEquals(false, Plist.read(request("GET", "/playback-info").body)["readyToPlay"])
        control.playback = AirPlayPlayback(3600.0, 61.0, 1f, true, false)
        val p = Plist.read(request("GET", "/playback-info").body)
        assertEquals(true, p["readyToPlay"]); assertEquals(3600.0, p["duration"]); assertEquals(61.0, p["position"]); assertEquals(1.0, p["rate"])
        assertEquals(true, p["playbackLikelyToKeepUp"]); assertEquals(1, (p["seekableTimeRanges"] as List<*>).size)
    }

    @Test fun oneConnectionServesManyRequests() {
        val s = connect()
        repeat(5) { assertEquals(200, send(s, "GET", "/server-info").status) }
    }

    @Test fun unknownRoutesAndMalformedRequestsAreAnswered() {
        assertEquals(404, request("GET", "/nope").status)
        assertEquals(404, request("POST", "/fp-setup", ByteArray(16)).status)
        val s = connect(); s.getOutputStream().write("hello\r\n\r\n".toByteArray())
        assertEquals(400, readReply(s.getInputStream()).status)
    }

    @Test fun anOversizeBodyIsRefusedWithoutReadingIt() {
        val s = connect()
        s.getOutputStream().write("POST /play HTTP/1.1\r\nContent-Length: 5000000\r\n\r\n".toByteArray())
        assertEquals(413, readReply(s.getInputStream()).status)
    }

    @Test fun anOversizeHeaderIsRefused() {
        val s = connect()
        s.getOutputStream().write(("GET /server-info HTTP/1.1\r\nX: " + "a".repeat(20_000) + "\r\n\r\n").toByteArray())
        assertEquals(431, readReply(s.getInputStream()).status)
    }

    @Test fun peersTheDeviceDoesNotServeAreDropped() {
        allow = false
        val s = connect()
        s.getOutputStream().write("GET /server-info HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(hungUp(s))
    }

    @Test fun theNumberOfConnectionsIsBounded() {
        val idle = (1..AirPlayServer.MAX_CONNECTIONS).map { connect().also { s -> send(s, "GET", "/server-info") } }
        val extra = connect()
        extra.getOutputStream().write("GET /server-info HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue("the ninth is closed, not served", hungUp(extra))
        idle.first().close()
        Thread.sleep(300)
        assertEquals(200, request("GET", "/server-info").status)         // a slot is free again
    }

    @Test fun theLogNeverHoldsAFullAddress() {
        request("POST", "/play", Plist.binary(mapOf("Content-Location" to "http://tv.test/a.m3u8?token=SECRET")), null)
        synchronized(logs) { assertTrue(logs.none { "SECRET" in it }) }
    }
}
