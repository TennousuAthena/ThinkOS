package moe.kiwi.thinkos.cast.miracast

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class RtspTest {
    private fun read(text: String, maxHeader: Int = 16 * 1024, maxBody: Int = 64 * 1024) =
        RtspReader(ByteArrayInputStream(text.toByteArray()), maxHeader, maxBody).read()

    @Test fun aRequestWithABodyIsParsed() {
        val m = read("SET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 5\r\nContent-Type: text/parameters\r\nContent-Length: 41\r\n\r\nwfd_trigger_method: SETUP\r\nwfd_x: a b c\r\n")!!
        assertEquals("SET_PARAMETER", m.method); assertEquals("rtsp://localhost/wfd1.0", m.uri); assertEquals(5, m.cseq)
        assertFalse(m.isResponse)
        assertEquals(mapOf("wfd_trigger_method" to "SETUP", "wfd_x" to "a b c"), m.parameters())
    }

    @Test fun aResponseIsRecognised() {
        val m = read("RTSP/1.0 200 OK\r\nCSeq: 101\r\nSession: ABC;timeout=30\r\n\r\n")!!
        assertTrue(m.isResponse); assertEquals(200, m.status); assertEquals("ABC;timeout=30", m.headers["session"])
    }

    @Test fun headerNamesAreCaseInsensitive() {
        assertEquals(7, read("OPTIONS * RTSP/1.0\r\ncseq: 7\r\n\r\n")!!.cseq)
    }

    @Test fun getParameterListsBareNames() {
        val m = read("GET_PARAMETER rtsp://localhost/wfd1.0 RTSP/1.0\r\nCSeq: 2\r\nContent-Length: 37\r\n\r\nwfd_video_formats\r\nwfd_audio_codecs\r\n")!!
        assertEquals(listOf("wfd_video_formats", "wfd_audio_codecs"), m.parameters().keys.toList())
    }

    @Test fun twoMessagesBackToBack() {
        val reader = RtspReader(ByteArrayInputStream("OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n\r\n\r\nOPTIONS * RTSP/1.0\r\nCSeq: 2\r\n\r\n".toByteArray()))
        assertEquals(1, reader.read()!!.cseq); assertEquals(2, reader.read()!!.cseq); assertNull(reader.read())
    }

    @Test fun nothingAtAllIsACleanEnd() { assertNull(read("")) }

    @Test fun malformedInputIsRefusedNotTrusted() {
        val bad = listOf(
            "GET / HTTP/1.1\r\nHost: x\r\n\r\n",
            "OPTIONS * RTSP/1.0\r\nno colon here\r\n\r\n",
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 10\r\n\r\nshort",
            "OPTIONS * RTSP/1.0\r\nCSeq: 1\r\n",
            "OPTIONS * RTSP/1.0\r\n: empty name\r\n\r\n",
        )
        for (text in bad) assertThrows(text, RtspError::class.java) { read(text) }
    }

    @Test fun limitsAreEnforced() {
        assertThrows(RtspError::class.java) { read("OPTIONS * RTSP/1.0\r\n" + "X: ${"a".repeat(300)}\r\n" + "\r\n", maxHeader = 200) }
        assertThrows(RtspError::class.java) { read("SET_PARAMETER x RTSP/1.0\r\nCSeq: 1\r\nContent-Length: 100000\r\n\r\n") }
        assertThrows(RtspError::class.java) { read("SET_PARAMETER x RTSP/1.0\r\nCSeq: 1\r\nContent-Length: -5\r\n\r\n") }
        val many = "OPTIONS * RTSP/1.0\r\n" + (1..80).joinToString("") { "H$it: v\r\n" } + "\r\n"
        assertThrows(RtspError::class.java) { read(many) }
    }

    @Test fun requestsAndResponsesAreBuiltWithTheRightLength() {
        val r = String(Rtsp.response(200, 3, listOf("Public" to "a, b"), "wfd_x: y\r\n"), Charsets.ISO_8859_1)
        assertEquals("RTSP/1.0 200 OK\r\nCSeq: 3\r\nPublic: a, b\r\nContent-Type: text/parameters\r\nContent-Length: 10\r\n\r\nwfd_x: y\r\n", r)
        assertEquals("OPTIONS * RTSP/1.0\r\nCSeq: 9\r\nRequire: org.wfa.wfd1.0\r\n\r\n", String(Rtsp.request("OPTIONS", "*", 9, listOf("Require" to "org.wfa.wfd1.0"))))
    }

    @Test fun aHeaderCannotCarryALineBreak() {
        assertThrows(IllegalArgumentException::class.java) { Rtsp.request("OPTIONS", "*", 1, listOf("X" to "a\r\nInjected: 1")) }
    }
}
