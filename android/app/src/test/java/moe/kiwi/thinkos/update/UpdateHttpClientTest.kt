package moe.kiwi.thinkos.update

import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy

class UpdateHttpClientTest {
    private val timeouts = UpdateHttpClient.Timeouts(15_000, 30_000)
    private val local = InetAddress.getByName("127.0.0.1")
    private val public = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))

    @Test fun aDedicatedClientHasNoCookiesProxyAutomaticRedirectsOrMediaCleartextOverride() {
        val client = UpdateHttpClient.create("0.1.1", timeouts)
        assertEquals(15_000, client.connectTimeoutMillis); assertEquals(30_000, client.readTimeoutMillis)
        assertSame(CookieJar.NO_COOKIES, client.cookieJar); assertEquals(Proxy.NO_PROXY, client.proxy)
        assertFalse(client.followRedirects); assertFalse(client.followSslRedirects); assertFalse(client.retryOnConnectionFailure)
        assertNull(client.cache)
        assertThrows(IllegalArgumentException::class.java) { UpdateHttpClient.create("bad\r\nheader", timeouts) }
        assertThrows(IllegalArgumentException::class.java) { UpdateHttpClient.Timeouts(0, 1) }
    }

    @Test(timeout = 10_000) fun onlyApprovedHeadersReachTheWireIncludingRedirectsAndSetCookieIsIgnored() {
        UpdateTestOrigin { UpdateTestOrigin.reply(it, "ok".toByteArray()) }.use { destination ->
            UpdateTestOrigin { exchange ->
                UpdateTestOrigin.reply(exchange, byteArrayOf(), 302,
                    listOf("Location" to destination.url("/file"), "Set-Cookie" to "id=secret"))
            }.use { origin ->
                val client = UpdateHttpClient.create("0.1.1", timeouts)
                val request = Request.Builder().url(origin.url()).header("Accept-Language", "zh-CN")
                    .header("Cookie", "secret=yes").header("Authorization", "Bearer secret").header("X-Device-Id", "private")
                    .header("Referer", "private").header("User-Agent", "private").header("Range", "bytes=0-1").build()
                client.newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
                for (hit in origin.hits + destination.hits) {
                    assertEquals(setOf("host", "connection", "user-agent", "accept-encoding", "range"), hit.headers.keys)
                    assertEquals(listOf("ThinkOS/0.1.1"), hit.headers["user-agent"])
                    assertEquals(listOf("identity"), hit.headers["accept-encoding"])
                    assertEquals(listOf("bytes=0-1"), hit.headers["range"])
                }
                client.connectionPool.evictAll()
            }
        }
    }

    @Test(timeout = 10_000) fun publicOrMixedCleartextDnsFailsBeforeOpeningASocket() {
        for (answers in listOf(listOf(public), listOf(local, public), emptyList())) {
            var lookups = 0
            val dns = object : Dns { override fun lookup(hostname: String) = answers.also { lookups++ } }
            val client = UpdateHttpClient.create("test", timeouts, dns)
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url("http://update.test/file").build()).execute() }
            assertEquals(1, lookups)
        }
    }

    @Test(timeout = 10_000) fun anHttpRedirectCannotEscapeToAPublicDnsAnswer() {
        val dns = object : Dns { override fun lookup(hostname: String) = listOf(if (hostname == "public.test") public else local) }
        UpdateTestOrigin { exchange ->
            UpdateTestOrigin.reply(exchange, byteArrayOf(), 302, listOf("Location" to "http://public.test/file"))
        }.use { origin ->
            val client = UpdateHttpClient.create("test", timeouts, dns)
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(origin.url()).build()).execute() }
            assertEquals(1, origin.hits.size)
            client.connectionPool.evictAll()
        }
    }

    @Test fun redirectsRejectDowngradesCredentialsAmbiguityAndLoops() {
        val request = Request.Builder().url("https://updates.test/a").build()
        for (location in listOf("http://127.0.0.1/b", "https://user:secret@updates.test/b", "file:///tmp/b")) {
            response(request, 302, listOf(location)).use { reply ->
                assertThrows(IOException::class.java) { UpdateHttpClient.redirect(request, reply, 0) }
            }
        }
        for (locations in listOf(emptyList(), listOf("/b", "/c"))) response(request, 302, locations).use { reply ->
            assertThrows(IOException::class.java) { UpdateHttpClient.redirect(request, reply, 0) }
        }
        response(request, 307, listOf("/b?q=x%26y")).use { reply ->
            assertEquals("https://updates.test/b?q=x%26y", UpdateHttpClient.redirect(request, reply, 0)!!.url.toString())
            assertThrows(IOException::class.java) { UpdateHttpClient.redirect(request, reply, 3) }
        }
        response(request, 200, emptyList()).use { assertNull(UpdateHttpClient.redirect(request, it, 0)) }
    }

    private fun response(request: Request, code: Int, locations: List<String>) = Response.Builder()
        .request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body("".toResponseBody())
        .apply { locations.forEach { addHeader("Location", it) } }.build()
}
