package moe.kiwi.thinkos.media

import org.junit.Assert.*
import org.junit.Test

class MediaEntryTest {
    private fun rejected(block: () -> Unit) = assertThrows(IllegalArgumentException::class.java) { block() }

    @Test fun theSourceTypeFollowsTheUrlUnlessTheItemSaysOtherwise() {
        assertEquals(MediaSourceType.HLS, MediaEntry("https://tv.test/live/index.m3u8?token=1").effectiveType)
        assertEquals(MediaSourceType.DASH, MediaEntry("https://tv.test/manifest.MPD").effectiveType)
        assertEquals(MediaSourceType.RTSP, MediaEntry("rtsp://192.168.1.9/live").effectiveType)
        assertNull(MediaEntry("https://tv.test/video.mp4").effectiveType)
        assertEquals(MediaSourceType.HLS, MediaEntry("https://tv.test/stream?id=7", mimeType = MediaEntry.M3U8_APPLE).effectiveType)
        assertEquals(MediaSourceType.DASH, MediaEntry("https://tv.test/stream?id=7", type = MediaSourceType.DASH).effectiveType)
        assertNull(MediaEntry("file:///sdcard/Movies/a.mp4").effectiveType)
    }

    @Test fun onlyRtspMayCarryCredentialsAndTheyAreStrippedFromAnythingPublic() {
        val camera = MediaEntry("rtsp://admin:secret@192.168.1.9:554/stream1", kind = "video")
        assertEquals("rtsp://192.168.1.9:554/stream1", camera.publicUrl)
        assertNull(camera.origin)
        for (url in listOf("https://admin:x@tv.test/a.mp4", "http://admin@192.168.1.9/a.mp4", "file://admin@localhost/a.mp3")) rejected { MediaEntry(url) }
        assertEquals("https://tv.test:443", MediaEntry("https://TV.test/a.mp4").origin)
        assertEquals("http://tv.test:8080", MediaEntry("http://tv.test:8080/a.mp4").origin)
    }

    @Test fun typeAndSchemeMustAgree() {
        rejected { MediaEntry("https://tv.test/a", type = MediaSourceType.RTSP) }
        rejected { MediaEntry("rtsp://192.168.1.9/live", type = MediaSourceType.HLS) }
        rejected { MediaEntry("rtsp://192.168.1.9:99999/live") }
        rejected { MediaEntry("rtsp:///live") }
        rejected { MediaEntry("rtmp://tv.test/live") }
        MediaEntry("rtsp://192.168.1.9/live", type = MediaSourceType.RTSP)
        MediaEntry("file:///sdcard/Movies/a.m3u8", type = MediaSourceType.HLS)
    }

    @Test fun manifestMimeTypesAreAcceptedButArbitraryOnesAreNot() {
        MediaEntry("https://tv.test/a", mimeType = MediaEntry.MPD)
        MediaEntry("https://tv.test/a", mimeType = MediaEntry.M3U8)
        rejected { MediaEntry("https://tv.test/a", mimeType = "text/html") }
        rejected { MediaEntry("https://tv.test/a", mimeType = "video/mp4\r\nX: y") }
    }

    @Test fun requestHeadersAreBoundedAndCannotTouchTheHttpFraming() {
        MediaEntry("https://tv.test/a.mp4", headers = mapOf("Authorization" to "Bearer t", "Referer" to "https://tv.test/", "User-Agent" to "Player/1"))
        for (name in listOf("Host", "Range", "content-length", "Transfer-Encoding", "Connection", "Accept-Encoding", "If-None-Match", "Bad Name", "", "X:Y")) {
            rejected { MediaEntry("https://tv.test/a.mp4", headers = mapOf(name to "v")) }
        }
        rejected { MediaEntry("https://tv.test/a.mp4", headers = mapOf("Cookie" to "a=b\r\nHost: evil")) }
        rejected { MediaEntry("https://tv.test/a.mp4", headers = mapOf("Cookie" to "x".repeat(2049))) }
        rejected { MediaEntry("https://tv.test/a.mp4", headers = mapOf("a" to "1", "A" to "2")) }
        rejected { MediaEntry("https://tv.test/a.mp4", headers = (1..9).associate { "X-$it" to "v" }) }
        // Headers belong to HTTP(S) requests; a local file or an RTSP session has nowhere to send them.
        rejected { MediaEntry("file:///sdcard/Movies/a.mp4", headers = mapOf("Referer" to "x")) }
        rejected { MediaEntry("rtsp://192.168.1.9/live", headers = mapOf("Referer" to "x")) }
    }

    @Test fun sideLoadedSubtitlesAreTypedAndLimited() {
        val srt = MediaSubtitle("https://tv.test/en.srt", language = "en", label = "English")
        assertEquals(MediaSubtitle.SRT, srt.mimeType)
        assertEquals(MediaSubtitle.VTT, MediaSubtitle("/sdcard/Movies/a.vtt").mimeType)
        assertEquals(MediaSubtitle.SSA, MediaSubtitle("https://tv.test/a.ass?x=1").mimeType)
        assertEquals("file:/sdcard/Movies/a.srt", MediaSubtitle("/sdcard/Movies/a.srt").normalizedUrl)
        rejected { MediaSubtitle("ftp://tv.test/a.srt") }
        rejected { MediaSubtitle("https://u:x@tv.test/a.srt") }
        rejected { MediaSubtitle("https://tv.test/a.srt", mimeType = "text/html") }
        rejected { MediaSubtitle("https://tv.test/a.srt", language = "not a language") }
        MediaEntry("https://tv.test/a.mp4", subtitles = List(16) { srt })
        rejected { MediaEntry("https://tv.test/a.mp4", subtitles = List(17) { srt }) }
        rejected { MediaEntry("https://tv.test/a.mp4", type = MediaSourceType.PROGRESSIVE, subtitles = listOf(srt)) }
    }

    @Test fun urlsInErrorTextAndLogsLoseTheirCredentials() {
        assertEquals("cannot open rtsp://192.168.1.9:554/live now", MediaEntry.redact("cannot open rtsp://admin:hunter2@192.168.1.9:554/live now"))
        assertEquals("a https://tv.test/x and rtsp://cam/y", MediaEntry.redact("a https://u:p@tv.test/x and rtsp://token@cam/y"))
        assertEquals("nothing to hide: https://tv.test/a@b", MediaEntry.redact("nothing to hide: https://tv.test/a@b"))
    }

    @Test fun onlyHarmlessHeadersMayFollowARequestToAnotherHost() {
        assertEquals(setOf("user-agent", "referer", "accept", "accept-language", "origin"), MediaEntry.PORTABLE_HEADERS)
        for (credential in listOf("authorization", "cookie", "x-api-key", "x-auth-token", "token", "api-key")) assertFalse(credential, credential in MediaEntry.PORTABLE_HEADERS)
    }

    @Test fun luaTablesBecomeEntriesWithTheNewFields() {
        val entry = MediaEntry.fromMap(mapOf("url" to "https://tv.test/live.m3u8", "kind" to "video", "type" to "hls",
            "headers" to mapOf("Authorization" to "Bearer t"),
            "subtitles" to listOf(mapOf("url" to "https://tv.test/zh.vtt", "lang" to "zh-Hans", "label" to "中文"))))
        assertEquals(MediaSourceType.HLS, entry.type)
        assertEquals("Bearer t", entry.headers["Authorization"])
        assertEquals("zh-Hans", entry.subtitles.single().language)
        rejected { MediaEntry.fromMap(mapOf("url" to "https://tv.test/a.mp4", "type" to "ftp")) }
        rejected { MediaEntry.fromMap(mapOf("url" to "https://tv.test/a.mp4", "headers" to listOf("x"))) }
        rejected { MediaEntry.fromMap(mapOf("url" to "https://tv.test/a.mp4", "headers" to mapOf("Referer" to 3))) }
        rejected { MediaEntry.fromMap(mapOf("url" to "https://tv.test/a.mp4", "subtitles" to listOf("en.srt"))) }
    }
}
