package moe.kiwi.thinkos.net

import moe.kiwi.thinkos.media.MediaProbe
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
import java.net.Proxy
import java.net.URI

class PublicMediaHttpClientTest {
    private val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
    private val local = InetAddress.getByAddress(byteArrayOf(10, 0, 0, 1))
    private fun resolver(block: () -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = block()
    }

    @Test fun everyHttpsRequestChecksAllDnsAnswersEvenWhenASocketCouldBePooled() {
        var calls = 0
        val dns = RequestDns(resolver { calls++; if (calls == 1) listOf(public) else listOf(public, local) }, publicOnly = true)
        val url = "https://alias.test/live".toHttpUrl()
        dns.forRequest(url) {
            assertEquals(listOf(public), dns.lookup(url.host))
            assertEquals(listOf(public), dns.lookup(url.host))
            assertEquals(1, calls) // One approved snapshot pins this request's addresses.
        }
        val blocked = assertThrows(IOException::class.java) { dns.forRequest(url) { fail("A pooled request still needs a new DNS admission") } }
        assertEquals("policy", MediaProbe.categoryOf(blocked))
        assertEquals(2, calls)
    }

    @Test fun aMixedOrEmptyAliasAnswerStopsBeforeAnyHttpRequest() {
        for (answers in listOf(emptyList(), listOf(public, local), listOf(local, public))) {
            val client = MediaHttpClient.create(resolver { answers }, publicOnly = true)
            val error = assertThrows(IOException::class.java) {
                client.newCall(Request.Builder().url("https://mixed.test/media.m3u8").build()).execute().close()
            }
            assertEquals("policy", MediaProbe.categoryOf(error))
            client.dispatcher.executorService.shutdown()
            client.connectionPool.evictAll()
        }
    }

    @Test fun publicOnlyAlwaysRechecksTheActualPeerAndNeverUsesTheCleartextOverride() {
        var resolved = false
        val dns = RequestDns(resolver { resolved = true; listOf(public) }, publicOnly = true) { true }
        val initial = assertThrows(IOException::class.java) {
            dns.forRequest("http://radio.test/live".toHttpUrl()) { fail("Public-only HTTP must fail before DNS/connection") }
        }
        assertEquals("policy", MediaProbe.categoryOf(initial))
        assertFalse(resolved)
        for (peer in listOf(null, local, InetAddress.getByName("::1"))) {
            val error = assertThrows(IOException::class.java) {
                MediaHttpClient.validateConnected(URI("https://cdn.test/segment.ts"), peer, publicOnly = true) { true }
            }
            assertEquals("policy", MediaProbe.categoryOf(error))
        }
        MediaHttpClient.validateConnected(URI("https://cdn.test/segment.ts"), public, publicOnly = true)
        assertThrows(IOException::class.java) {
            MediaHttpClient.validateConnected(URI("http://cdn.test/segment.ts"), public, publicOnly = true) { true }
        }
        // The ordinary client keeps both existing HTTPS-to-LAN and opted-in HTTP behavior.
        MediaHttpClient.validateConnected(URI("https://camera.test/live"), local)
        MediaHttpClient.validateConnected(URI("http://radio.test/live"), public) { true }
    }

    @Test fun aRedirectCannotDowngradeOrRedirectIntoALocalResource() {
        val request = Request.Builder().url("https://radio.test/live").build()
        for (next in listOf("http://radio.test/live", "https://127.0.0.1/live", "https://100.64.1.1/live", "https://camera.local/live")) {
            val error = assertThrows(IOException::class.java) { MediaHttpClient.redirect(request, response(request, next), 0, publicOnly = true) }
            assertEquals("policy", MediaProbe.categoryOf(error))
        }
        val next = MediaHttpClient.redirect(request, response(request, "https://alias.test/cdn"), 0, publicOnly = true)!!
        val dns = RequestDns(resolver { listOf(local) }, publicOnly = true)
        assertThrows(IOException::class.java) { dns.forRequest(next.url) { fail("The redirect's DNS alias is private") } }
    }

    @Test fun strictAndOrdinaryClientsHaveIndependentPoolsAndDnsPolicies() {
        val ordinary = MediaHttpClient.create(resolver { listOf(local) })
        val strict = MediaHttpClient.create(resolver { listOf(public) }, publicOnly = true)
        assertNotSame(ordinary.connectionPool, strict.connectionPool)
        assertNotSame(ordinary.dispatcher, strict.dispatcher)
        assertNotSame(ordinary.dns, strict.dns)
        assertEquals(Proxy.NO_PROXY, strict.proxy)
        assertFalse(strict.followRedirects); assertFalse(strict.followSslRedirects)
    }

    private fun response(request: Request, next: String) = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        .code(302).message("fixture").header("Location", next).body("".toResponseBody()).build()
}
