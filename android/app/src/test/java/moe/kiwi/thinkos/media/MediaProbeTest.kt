package moe.kiwi.thinkos.media

import moe.kiwi.thinkos.plugin.MediaCapability
import moe.kiwi.thinkos.net.PublicNetworkPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class MediaProbeTest {
    @Test fun nestedHlsUsesFinalReplyUrlsButCredentialsStayWithTheEntryOrigin() {
        val headers = mapOf("Authorization" to "Bearer secret", "Cookie" to "session=secret", "X-Api-Key" to "secret",
            "User-Agent" to "Radio/1", "Referer" to "https://origin.test/")
        val seen = mutableListOf<Pair<String, Map<String, String>>>()
        val probe = MediaProbe { url, fields, _ ->
            seen += url to fields
            when (seen.size) {
                1 -> reply(body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nnested/low.m3u8",
                    url = "https://cdn.test/a/master.m3u8")
                2 -> reply(body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nhttps://ORIGIN.test:443/seg.ts",
                    url = "https://cdn.test/b/low.m3u8")
                else -> reply(body = ts, url = url)
            }
        }
        val result = probe.run(MediaEntry("https://origin.test/master.m3u8", headers = headers, publicOnly = true))
        assertEquals(true, result["ok"])
        assertEquals(listOf("https://origin.test/master.m3u8", "https://cdn.test/a/nested/low.m3u8", "https://ORIGIN.test:443/seg.ts"), seen.map { it.first })
        assertEquals(headers, seen[0].second)
        assertEquals(mapOf("User-Agent" to "Radio/1", "Referer" to "https://origin.test/"), seen[1].second)
        assertEquals(headers, seen[2].second) // Explicit :443 and hostname case still mean the original origin.
    }

    @Test fun aDifferentHlsPortDoesNotInheritCredentialsEvenOnTheSameHost() {
        val seen = mutableListOf<Map<String, String>>()
        val result = MediaProbe { url, fields, _ ->
            seen += fields
            if (seen.size == 1) reply(body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nhttps://origin.test:444/seg.ts", url = url)
            else reply(body = ts, url = url)
        }.run(MediaEntry("https://origin.test/live.m3u8", headers = mapOf("Token" to "secret", "Accept" to "*/*")))
        assertEquals(true, result["ok"])
        assertEquals(mapOf("Accept" to "*/*"), seen[1])
    }

    @Test fun publicOnlyProbeOptionsPreserveFalseAndEnforceTheSharedApiGate() {
        val strict = MediaCapability.probeEntry("https://radio.test/live", mapOf("public_only" to true, "headers" to mapOf("User-Agent" to "Radio/1")), 10)
        assertTrue(strict.publicOnly)
        assertEquals("Radio/1", strict.headers["User-Agent"])
        assertFalse(MediaCapability.probeEntry("http://192.168.1.1/live", mapOf("public_only" to false), 10).publicOnly)
        for (value in listOf("true", 1, 0, null)) assertThrows(IllegalArgumentException::class.java) {
            MediaCapability.probeEntry("https://radio.test/live", mapOf("public_only" to value), 10)
        }
        for (value in listOf(true, false)) {
            assertThrows(IllegalArgumentException::class.java) { MediaCapability.probeEntry("https://radio.test/live", mapOf("public_only" to value), 9) }
            assertThrows(IllegalArgumentException::class.java) { MediaCapability.loadEntry(mapOf("url" to "https://radio.test/live", "public_only" to value), 9) }
        }
        assertFalse(MediaCapability.loadEntry(mapOf("url" to "http://192.168.1.1/live"), 8).publicOnly)
    }

    @Test fun publicOnlyHlsRejectsCleartextLocalSegmentsKeysAndSubtitleRenditionsBeforeFetchingThem() {
        val references = listOf(
            "#EXTINF:6,\nhttp://8.8.8.8/seg.ts",
            "#EXTINF:6,\nhttps://192.168.1.1/seg.ts",
            "#EXTINF:6,\nfile:///data/private/seg.ts",
            "#EXT-X-KEY:METHOD=AES-128,URI=\"http://8.8.8.8/key\"\n#EXTINF:6,\nseg.ts",
            "#EXT-X-MEDIA:TYPE=SUBTITLES,GROUP-ID=\"s\",URI=\"https://camera.local/text.m3u8\"\n#EXTINF:6,\nseg.ts",
            "#EXT-X-MAP:URI=\"content://media/private\"\n#EXTINF:6,\nseg.ts")
        for (reference in references) {
            val log = mutableListOf<String>()
            val result = probe(log) { reply(body = "#EXTM3U\n#EXT-X-VERSION:3\n" + reference) }
                .run(MediaEntry("https://radio.test/live.m3u8", publicOnly = true))
            assertEquals(reference, "policy", result["error"])
            assertEquals(false, result["ok"])
            assertEquals(listOf("https://radio.test/live.m3u8"), log)
        }
    }

    @Test fun publicOnlyFollowsHttpsHlsAndKeepsChildPolicyFailuresDistinctFromBadStreams() {
        val log = mutableListOf<String>()
        val result = probe(log) { url -> when {
            url.endsWith("master.m3u8") -> reply(body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nlow.m3u8")
            url.endsWith("low.m3u8") -> reply(body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nseg.ts")
            else -> IOException("Public-only media policy: non-public address")
        } }.run(MediaEntry("https://radio.test/master.m3u8", publicOnly = true))
        assertEquals(3, log.size)
        assertEquals("policy", result["error"])
        assertEquals(false, result["ok"])
        assertEquals("policy", MediaProbe.categoryOf(IOException("wrapped", PublicNetworkPolicy.Blocked("non-public address"))))
        assertEquals("policy", MediaProbe.policyResult()["error"])
    }

    @Test fun ordinaryHlsStillReachesItsPrivateSegment() {
        val log = mutableListOf<String>()
        val result = probe(log) { url -> if (url.endsWith(".m3u8"))
            reply(body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nhttp://192.168.1.1/seg.ts") else reply(body = ts)
        }.run(MediaEntry("http://192.168.1.1/live.m3u8"))
        assertEquals(true, result["ok"])
        assertEquals("http://192.168.1.1/seg.ts", log.last())
    }
    private fun reply(status: Int = 200, type: String = "", body: String = "", url: String = "") =
        MediaProbe.Reply(status, type, body.toByteArray(Charsets.ISO_8859_1), url)

    /** A probe over a table of canned answers; [log] collects the addresses asked for, in order. */
    private fun probe(log: MutableList<String> = mutableListOf(), answers: (String) -> Any): MediaProbe = MediaProbe { url, _, _ ->
        log += url
        when (val answer = answers(url)) {
            is IOException -> throw answer
            is MediaProbe.Reply -> if (answer.url.isEmpty()) MediaProbe.Reply(answer.status, answer.contentType, answer.head, url) else answer
            else -> error("unexpected answer")
        }
    }

    private fun run(probe: MediaProbe, url: String, headers: Map<String, String> = emptyMap()) =
        probe.run(MediaEntry.fromMap(mapOf("url" to url, "headers" to headers)))

    private val ts = "G\u0000\u0011\u0010 transport stream bytes"

    @Test fun aStreamThatAnswersIsAlive() {
        val result = run(probe { reply(body = ts, type = "video/mp2t") }, "http://192.168.1.5/live/1")
        assertEquals(true, result["ok"]); assertEquals("stream", result["kind"]); assertEquals(200L, result["status"])
        assertFalse(result.containsKey("error"))
    }

    @Test fun anHtmlPageWithStatus200IsAnAnswerButNotAStream() {
        val byType = run(probe { reply(type = "text/html; charset=utf-8", body = "whatever") }, "http://192.168.1.5/live/1")
        assertEquals(false, byType["ok"]); assertEquals("html", byType["kind"]); assertEquals("format", byType["error"])
        val byBody = run(probe { reply(body = "  \n<!DOCTYPE HTML><html><body>Channel moved</body></html>") }, "http://192.168.1.5/live/1")
        assertEquals("html", byBody["kind"]); assertEquals(false, byBody["ok"])
    }

    @Test fun errorStatusesAndEmptyBodiesAreDead() {
        val missing = run(probe { reply(status = 404) }, "https://tv.test/a.m3u8")
        assertEquals(false, missing["ok"]); assertEquals(404L, missing["status"]); assertEquals("http", missing["error"])
        val empty = run(probe { reply(body = "") }, "https://tv.test/a.ts")
        assertEquals(false, empty["ok"]); assertEquals("empty", empty["kind"]); assertEquals("format", empty["error"])
    }

    @Test fun aChannelListIsNotAStreamButAnHlsPlaylistIs() {
        val list = run(probe { reply(body = "#EXTM3U\n#EXTINF:-1,A\nhttp://a/x\n") }, "https://tv.test/list.m3u")
        assertEquals("list", list["kind"]); assertEquals(false, list["ok"])
        val hls = run(probe { url -> if (url.endsWith(".m3u8")) reply(body = "#EXTM3U\n#EXT-X-VERSION:3\n#EXTINF:6,\nseg0.ts\n") else reply(body = ts) }, "https://tv.test/a.m3u8")
        assertEquals("hls", hls["kind"]); assertEquals(true, hls["ok"])
    }

    @Test fun anHlsPlaylistIsFollowedDownToItsFirstSegment() {
        val log = mutableListOf<String>()
        val master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nlow/index.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=2000000\nhigh/index.m3u8\n"
        val media = "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6,\nseg100.ts\n#EXTINF:6,\nseg101.ts\n"
        val alive = run(probe(log) { url ->
            when {
                url == "https://tv.test/live/master.m3u8" -> reply(body = master)
                url == "https://tv.test/live/low/index.m3u8" -> reply(body = media)
                url == "https://tv.test/live/low/seg100.ts" -> reply(body = ts, type = "video/mp2t")
                else -> reply(status = 404)
            }
        }, "https://tv.test/live/master.m3u8")
        assertEquals(true, alive["ok"]); assertEquals("hls", alive["kind"])
        assertEquals(listOf("https://tv.test/live/master.m3u8", "https://tv.test/live/low/index.m3u8", "https://tv.test/live/low/seg100.ts"), log)
        // The playlist is served, the segments are gone: a dead channel that still has a front door.
        val dead = run(probe { url -> if (url.endsWith(".m3u8")) reply(body = media) else reply(status = 404) }, "https://tv.test/live/low/index.m3u8")
        assertEquals(false, dead["ok"]); assertEquals(404L, dead["status"]); assertEquals("http", dead["error"])
        // ... or serves a portal page in their place.
        val portal = run(probe { url -> if (url.endsWith(".m3u8")) reply(body = media) else reply(type = "text/html", body = "<html>") }, "https://tv.test/a.m3u8")
        assertEquals(false, portal["ok"]); assertEquals("html", portal["kind"])
    }

    @Test fun segmentsAreResolvedAgainstWhereTheRedirectsEnded() {
        val log = mutableListOf<String>()
        run(probe(log) { url ->
            if (url.endsWith("a.m3u8")) MediaProbe.Reply(200, "", "#EXTM3U\n#EXT-X-VERSION:3\nseg.ts".toByteArray(), "https://cdn.test/x/y/a.m3u8") else reply(body = ts)
        }, "https://tv.test/a.m3u8")
        assertEquals(listOf("https://tv.test/a.m3u8", "https://cdn.test/x/y/seg.ts"), log)
    }

    @Test fun playlistsThatPointAtPlaylistsStopAtTheThirdRequest() {
        val log = mutableListOf<String>()
        val result = run(probe(log) { reply(body = "#EXTM3U\n#EXT-X-VERSION:3\nnext.m3u8") }, "https://tv.test/0.m3u8")
        assertEquals(3, log.size); assertEquals(true, result["ok"])
    }

    @Test fun aByteOrderMarkDoesNotHideTheMarker() {
        assertEquals("hls", MediaProbe.sniff("", "ï»¿#EXTM3U\n#EXT-X-VERSION:3".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("stream", MediaProbe.sniff("video/mp2t", byteArrayOf(0x47, 0, 0x11, 0x10)))
        assertEquals("empty", MediaProbe.sniff("", ByteArray(0)))
    }

    @Test fun failuresAreToldApartByWhatWentWrong() {
        fun category(error: IOException) = run(probe { error }, "https://tv.test/a")["error"]
        assertEquals("dns", category(UnknownHostException("tv.test")))
        assertEquals("timeout", category(SocketTimeoutException("timeout")))
        assertEquals("tls", category(SSLHandshakeException("bad certificate")))
        assertEquals("policy", category(IOException("Cleartext HTTP is restricted to local/private addresses")))
        assertEquals("policy", category(IOException("HTTPS downgrade rejected")))
        assertEquals("network", category(IOException("Connection reset")))
        assertEquals(0L, run(probe { UnknownHostException("x") }, "https://tv.test/a")["status"])
    }

    @Test fun anAddressNobodyCanProbeIsSaidSo() {
        val result = run(probe { error("must not be asked") }, "rtsp://192.168.1.9/cam1")
        assertEquals(false, result["ok"]); assertEquals("unsupported", result["error"])
    }

    @Test fun theRequestHeadersOfTheEntryReachTheFetch() {
        var seen: Map<String, String> = emptyMap()
        MediaProbe { _, headers, limit -> seen = headers; assertEquals(MediaProbe.HEAD_BYTES, limit); reply(body = ts, url = "https://tv.test/a") }
            .run(MediaEntry.fromMap(mapOf("url" to "https://tv.test/a", "headers" to mapOf("Referer" to "https://tv.test/", "User-Agent" to "Vendor/2"))))
        assertEquals(mapOf("Referer" to "https://tv.test/", "User-Agent" to "Vendor/2"), seen)
    }

    @Test fun theLuaArgumentsAreCheckedLikeAQueueItem() {
        assertEquals("http://192.168.1.5/a", MediaCapability.probeEntry("http://192.168.1.5/a", null).url)
        assertEquals(mapOf("Referer" to "https://tv.test/"), MediaCapability.probeEntry("https://tv.test/a", mapOf("headers" to mapOf("Referer" to "https://tv.test/"))).headers)
        assertThrows(IllegalArgumentException::class.java) { MediaCapability.probeEntry(42, null) }
        assertThrows(IllegalArgumentException::class.java) { MediaCapability.probeEntry("https://tv.test/a", mapOf("range" to "1")) }
        assertThrows(IllegalArgumentException::class.java) { MediaCapability.probeEntry("https://tv.test/a", mapOf("headers" to mapOf("Range" to "bytes=0-1"))) }
        assertThrows(IllegalArgumentException::class.java) { MediaCapability.probeEntry("https://tv.test/a", "headers") }
        assertThrows(IllegalArgumentException::class.java) { MediaCapability.probeEntry("file:///sdcard/Movies/a.mp4", null) }
        assertThrows(IllegalArgumentException::class.java) { MediaCapability.probeEntry("https://user:secret@tv.test/a", null) }
    }
}
