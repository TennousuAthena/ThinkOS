package moe.kiwi.thinkos.cast.miracast

import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** The Sink against a scripted Source over a real loopback connection. */
class WfdSinkTest {
    private class Events : WfdSink.Listener {
        val setup = CountDownLatch(1)
        val playing = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val reason = AtomicReference<String>()
        override fun onSetup() = setup.countDown()
        override fun onPlaying() = playing.countDown()
        override fun onEnded(reason: String) { this.reason.set(reason); ended.countDown() }
    }

    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val events = Events()
    private val logs = ArrayList<String>()
    private lateinit var sinkSocket: Socket
    private lateinit var source: Socket
    private lateinit var fromSink: RtspReader
    private lateinit var sink: WfdSink
    private lateinit var thread: Thread

    private fun connect(rtpPort: Int = 19000) {
        sinkSocket = Socket(InetAddress.getByName("127.0.0.1"), server.localPort)
        sinkSocket.soTimeout = 5000
        source = server.accept(); source.soTimeout = 5000
        fromSink = RtspReader(source.getInputStream())
        sink = WfdSink(sinkSocket.getInputStream(), sinkSocket.getOutputStream(), "192.168.49.1", rtpPort, events, { synchronized(logs) { logs += it } })
        thread = Thread { sink.run() }.apply { isDaemon = true; start() }
    }

    @After fun close() { runCatching { source.close() }; runCatching { sinkSocket.close() }; runCatching { server.close() } }

    private fun say(bytes: ByteArray) { source.getOutputStream().write(bytes); source.getOutputStream().flush() }
    private fun get(): RtspMessage = fromSink.read() ?: fail("the sink closed the connection") as Nothing
    private fun ask(method: String, uri: String, cseq: Int, headers: List<Pair<String, String>> = emptyList(), body: String = ""): RtspMessage {
        say(Rtsp.request(method, uri, cseq, headers, body)); return get()
    }

    /** M1..M7 as a Windows or Android Source does them. Returns with the session playing. */
    private fun handshake(): String {
        var m = ask("OPTIONS", "*", 1, listOf("Require" to "org.wfa.wfd1.0"))
        assertEquals(200, m.status); assertEquals("org.wfa.wfd1.0, GET_PARAMETER, SET_PARAMETER", m.headers["public"])
        m = get()                                                                              // M2 from the sink
        assertEquals("OPTIONS", m.method); assertEquals("org.wfa.wfd1.0", m.headers["require"])
        say(Rtsp.response(200, m.cseq!!, listOf("Public" to "org.wfa.wfd1.0, SETUP, TEARDOWN, PLAY, PAUSE, GET_PARAMETER, SET_PARAMETER")))

        m = ask("GET_PARAMETER", "rtsp://localhost/wfd1.0", 2, body = "wfd_video_formats\r\nwfd_audio_codecs\r\nwfd_3d_video_formats\r\nwfd_content_protection\r\n" +
            "wfd_display_edid\r\nwfd_coupled_sink\r\nwfd_client_rtp_ports\r\nwfd_I2C\r\nwfd_uibc_capability\r\nwfd_connector_type\r\nwfd_standby_resume_capability\r\nmicrosoft_cursor\r\n")
        assertEquals(200, m.status)
        val caps = m.parameters()
        assertEquals("AAC 00000001 00", caps["wfd_audio_codecs"])
        assertEquals("RTP/AVP/UDP;unicast 19000 0 mode=play", caps["wfd_client_rtp_ports"])
        assertEquals("none", caps["wfd_content_protection"]); assertEquals("none", caps["wfd_uibc_capability"])
        assertFalse("a parameter nobody defined is not invented", caps.containsKey("microsoft_cursor"))
        assertTrue(caps.getValue("wfd_video_formats").matches(Regex("30 00 03 04 00000061 00000000 00000000 00 0000 0000 00 none none")))

        m = ask("SET_PARAMETER", "rtsp://localhost/wfd1.0", 3, body = "wfd_video_formats: 30 00 02 04 00000040 00000000 00000000 00 0000 0000 00 none none\r\n" +
            "wfd_audio_codecs: AAC 00000001 00\r\nwfd_presentation_URL: rtsp://192.168.49.1/wfd1.0/streamid=0 none\r\nwfd_client_rtp_ports: RTP/AVP/UDP;unicast 19000 0 mode=play\r\n")
        assertEquals(200, m.status)
        say(Rtsp.request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 4, body = "wfd_trigger_method: SETUP\r\n"))
        assertEquals(200, get().status)

        val setup = get()                                                                       // M6
        assertEquals("SETUP", setup.method); assertEquals("rtsp://192.168.49.1/wfd1.0/streamid=0", setup.uri)
        assertEquals("RTP/AVP/UDP;unicast;client_port=19000", setup.headers["transport"])
        say(Rtsp.response(200, setup.cseq!!, listOf("Session" to "5A1B2C3D;timeout=30", "Transport" to "RTP/AVP/UDP;unicast;client_port=19000;server_port=51000")))
        val play = get()                                                                        // M7
        assertEquals("the player is told before PLAY goes out", 0L, events.setup.count)
        assertEquals("PLAY", play.method); assertEquals("5A1B2C3D", play.headers["session"])
        say(Rtsp.response(200, play.cseq!!))
        assertTrue(events.playing.await(3, TimeUnit.SECONDS))
        assertEquals("5A1B2C3D", sink.sessionId); assertEquals(51000, sink.serverPort); assertEquals(30, sink.sessionTimeoutSeconds); assertTrue(sink.established)
        return "5A1B2C3D"
    }

    @Test fun theWholeHandshakeReachesPlay() {
        connect()
        handshake()
        assertEquals(1L, events.ended.count)
    }

    @Test fun keepAlivesAreAnsweredAndAnIdrRequestGoesOut() {
        connect(); val session = handshake()
        assertEquals(200, ask("GET_PARAMETER", "rtsp://localhost/wfd1.0", 10).status)
        sink.requestIdr()
        val idr = get()
        assertEquals("SET_PARAMETER", idr.method); assertEquals(session, idr.headers["session"]); assertEquals("wfd_idr_request", idr.parameters().keys.single())
        say(Rtsp.response(200, idr.cseq!!))
        assertEquals(200, ask("GET_PARAMETER", "rtsp://localhost/wfd1.0", 11).status)       // still alive afterwards
    }

    @Test fun theSourceMayTearDown() {
        connect(); handshake()
        assertEquals(200, ask("TEARDOWN", "rtsp://192.168.49.1/wfd1.0/streamid=0", 20).status)
        assertTrue(events.ended.await(3, TimeUnit.SECONDS)); assertEquals("teardown", events.reason.get())
    }

    @Test fun aTriggeredTeardownIsSentBackAndEndsTheSession() {
        connect(); handshake()
        say(Rtsp.request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 21, body = "wfd_trigger_method: TEARDOWN\r\n"))
        assertEquals(200, get().status)
        assertEquals("TEARDOWN", get().method)
        assertTrue(events.ended.await(3, TimeUnit.SECONDS))
    }

    @Test fun theViewerEndingItTellsTheSource() {
        connect(); handshake()
        sink.teardown()
        assertEquals("TEARDOWN", get().method)
    }

    @Test fun theConnectionDroppingEndsTheSessionOnce() {
        connect(); handshake()
        source.close()
        assertTrue(events.ended.await(3, TimeUnit.SECONDS)); assertEquals("closed", events.reason.get())
    }

    @Test fun aRefusedSetupEndsTheSession() {
        connect()
        ask("OPTIONS", "*", 1); get()
        say(Rtsp.request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 4, body = "wfd_trigger_method: SETUP\r\n"))
        get()
        val setup = get()
        say(Rtsp.response(453, setup.cseq!!))
        assertTrue(events.ended.await(3, TimeUnit.SECONDS)); assertTrue(events.reason.get().startsWith("protocol"))
        assertEquals(1L, events.playing.count)
    }

    @Test fun aSetupAnswerWithoutASessionIsNotPlayed() {
        connect()
        say(Rtsp.request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 4, body = "wfd_trigger_method: SETUP\r\n"))
        get()
        val setup = get()
        say(Rtsp.response(200, setup.cseq!!))
        assertTrue(events.ended.await(3, TimeUnit.SECONDS))
        assertEquals(1L, events.playing.count)
    }

    @Test fun garbageEndsTheSessionInsteadOfCrashing() {
        connect()
        say("GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
        assertTrue(events.ended.await(3, TimeUnit.SECONDS)); assertTrue(events.reason.get().startsWith("protocol"))
    }

    @Test fun anOddPresentationUrlIsIgnoredAndTheSetupUsesTheSourceAddress() {
        connect()
        ask("SET_PARAMETER", "rtsp://localhost/wfd1.0", 3, body = "wfd_presentation_URL: http://evil.example/x none\r\n")
        say(Rtsp.request("SET_PARAMETER", "rtsp://localhost/wfd1.0", 4, body = "wfd_trigger_method: SETUP\r\n"))
        get()
        assertEquals("rtsp://192.168.49.1/wfd1.0/streamid=0", get().uri)
    }

    @Test fun unknownRequestsAreNotImplementedButTheSessionGoesOn() {
        connect()
        assertEquals(501, ask("RECORD", "rtsp://x", 5).status)
        assertEquals(200, ask("GET_PARAMETER", "rtsp://localhost/wfd1.0", 6).status)
    }
}
