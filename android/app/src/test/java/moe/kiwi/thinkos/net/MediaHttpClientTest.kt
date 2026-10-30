package moe.kiwi.thinkos.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.URI

class MediaHttpClientTest {
    private val local = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 20))
    private val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

    @Test fun aCleartextAnswerIsPinnedUntilTheRequestEndsAndRevalidatedOnTheNextRequest() {
        var calls = 0
        val dns = MediaHttpClient.RequestDns(resolver { calls++; if (calls == 1) listOf(local) else listOf(public) })
        dns.forRequest("http://media.test/song.mp3".toHttpUrl()) {
            assertEquals(listOf(local), dns.lookup("media.test"))
            assertEquals(listOf(local), dns.lookup("media.test"))
            assertEquals(1, calls)
        }
        assertThrows(IOException::class.java) {
            dns.forRequest("http://media.test/song.mp3".toHttpUrl()) { fail("must reject before opening a socket") }
        }
        assertEquals(2, calls)
        // HTTPS uses normal TLS trust and may resolve to public addresses.
        dns.forRequest("https://media.test/song.mp3".toHttpUrl()) {
            assertEquals(listOf(public), dns.lookup("media.test"))
        }
    }

    @Test fun mixedAnswersAndReusedPublicConnectionsCannotBypassTheCleartextGate() {
        val dns = MediaHttpClient.RequestDns(resolver { listOf(local, public) })
        assertThrows(IOException::class.java) {
            dns.forRequest("http://media.test/a".toHttpUrl()) { fail("mixed DNS must fail") }
        }
        assertThrows(IOException::class.java) { MediaHttpClient.validateConnected(URI("http://media.test/a"), public) }
        assertThrows(IOException::class.java) { MediaHttpClient.validateConnected(URI("http://media.test/a"), null) }
        MediaHttpClient.validateConnected(URI("http://media.test/a"), local)
        MediaHttpClient.validateConnected(URI("https://media.test/a"), public)
    }

    @Test fun failedRequestScopeIsRemovedBeforeTheNextRequest() {
        val dns = MediaHttpClient.RequestDns(resolver { listOf(local) })
        assertThrows(IOException::class.java) {
            dns.forRequest("http://media.test/a".toHttpUrl()) { throw IOException("cancelled") }
        }
        // If a per-request cached answer leaked, this second request would avoid a fresh lookup.
        var calls = 0
        val fresh = MediaHttpClient.RequestDns(resolver { calls++; listOf(local) })
        fresh.forRequest("http://media.test/a".toHttpUrl()) { fresh.lookup("media.test") }
        fresh.forRequest("http://media.test/a".toHttpUrl()) { fresh.lookup("media.test") }
        assertEquals(2, calls)
    }

    @Test fun everyRedirectIsExplicitAndHttpsCanNeverDowngrade() {
        val client = MediaHttpClient.create(resolver { listOf(local) })
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        val request = Request.Builder().url("https://media.test/one").build()
        assertThrows(IOException::class.java) {
            MediaHttpClient.redirect(request, response(request, 302, "http://192.168.1.20/two"), 0)
        }
        assertThrows(IOException::class.java) {
            MediaHttpClient.redirect(request, response(request, 302, "/two"), 3)
        }
        assertThrows(IOException::class.java) {
            MediaHttpClient.redirect(request, response(request, 302, "https://user:secret@other.test/two"), 0)
        }
        val relative = MediaHttpClient.redirect(request, response(request, 302, "/two?a=1%26b"), 0)!!
        assertEquals("https://media.test/two?a=1%26b", relative.url.toString())
    }

    @Test fun crossOriginRedirectsDoNotForwardAuthentication() {
        val request = Request.Builder().url("https://media.test/one").header("Authorization", "Bearer token")
            .header("Cookie", "session=private").build()
        val next = MediaHttpClient.redirect(request, response(request, 307, "https://cdn.test/two"), 0)!!
        assertNull(next.header("Authorization"))
        assertNull(next.header("Cookie"))
        assertEquals("GET", next.method)
        assertNull(MediaHttpClient.redirect(request, response(request, 200, null), 0))
    }

    private fun resolver(block: (String) -> List<InetAddress>): Dns = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = block(hostname)
    }

    private fun response(request: Request, status: Int, location: String?): Response = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(status).message("fixture").body("".toResponseBody())
        .apply { if (location != null) header("Location", location) }.build()
}
