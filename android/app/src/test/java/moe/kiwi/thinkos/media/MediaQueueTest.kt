package moe.kiwi.thinkos.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class MediaQueueTest {
    private val items = (1..5).map { MediaEntry("file:///sdcard/Music/$it.mp3", title = "Track $it") }

    @Test fun sequentialQueueKeepsLuaIndicesAndEndsOnceAtItsLastItem() {
        val queue = MediaQueue()
        queue.load(items, MediaOptions(index = 2))
        assertEquals(2, queue.index)
        assertEquals("Track 2", queue.current!!.title)
        assertEquals(3, queue.next())
        assertEquals(4, queue.next())
        assertEquals(5, queue.next())
        assertNull(queue.next())
        assertEquals(5, queue.index)
        assertEquals(4, queue.previous())
    }

    @Test fun shuffleVisitsEveryTrackExactlyOnceAndKeepsTheExplicitFirstItem() {
        val queue = MediaQueue(Random(123))
        queue.load(items, MediaOptions(index = 3, shuffle = true))
        assertEquals(3, queue.index)
        val visited = ArrayList<Int>()
        visited += queue.index
        while (queue.next() != null) visited += queue.index
        assertEquals(5, visited.size)
        assertEquals((1..5).toSet(), visited.toSet())
        val current = queue.current
        val index = queue.index
        queue.setShuffle(false)
        assertEquals(index, queue.index)
        assertEquals(current, queue.current)
        assertEquals(items, queue.playlist)
        queue.setShuffle(true)
        assertEquals(index, queue.index)
        assertEquals(current, queue.current)
        assertEquals(0, queue.playerIndex)
    }

    @Test fun repeatOneRepeatsAutomaticPlaybackButExplicitNextStillMoves() {
        val queue = MediaQueue()
        queue.load(items, MediaOptions(index = 2, repeat = MediaRepeat.ONE))
        assertEquals(2, queue.next(manual = false))
        assertEquals(3, queue.next())
        queue.setRepeat(MediaRepeat.ALL)
        queue.selectPlayerIndex(4)
        assertEquals(1, queue.next())
        assertEquals(5, queue.previous())
        queue.setRepeat(MediaRepeat.OFF)
        queue.selectPlayerIndex(0)
        assertEquals(1, queue.previous())
    }

    @Test fun invalidReplacementLeavesTheExistingQueueIntactAndStopClearsIt() {
        val queue = MediaQueue()
        queue.load(items, MediaOptions(index = 3, shuffle = true, repeat = MediaRepeat.ALL))
        val order = queue.playlist
        assertThrows(IllegalArgumentException::class.java) { queue.load(emptyList(), MediaOptions()) }
        assertThrows(IllegalArgumentException::class.java) { queue.load(items, MediaOptions(index = 6)) }
        assertThrows(IllegalArgumentException::class.java) {
            queue.load(List(MediaQueue.MAX_ITEMS + 1) { items[0] }, MediaOptions())
        }
        assertEquals(order, queue.playlist)
        assertEquals(3, queue.index)
        assertEquals(MediaRepeat.ALL, queue.repeat)
        queue.clear()
        assertEquals(0, queue.index)
        assertEquals(0, queue.count)
        assertNull(queue.current)
        assertNull(queue.next())
        assertNull(queue.previous())
        assertFalse(queue.shuffle)
        assertEquals(MediaRepeat.OFF, queue.repeat)
    }

    @Test fun jumpingSelectsByTheLuaIndexWhateverTheShuffleOrderIs() {
        val queue = MediaQueue(Random(7))
        queue.load(items, MediaOptions(index = 1, shuffle = true))
        queue.select(4)
        assertEquals(4, queue.index)
        assertEquals("Track 4", queue.current!!.title)
        assertThrows(IllegalArgumentException::class.java) { queue.select(0) }
        assertThrows(IllegalArgumentException::class.java) { queue.select(6) }
        queue.setShuffle(false)
        queue.select(2)
        assertEquals(1, queue.playerIndex)
        assertEquals(3, queue.next())
    }

    @Test fun aQueueMayNotHoldManyAdaptiveStreams() {
        fun hls(n: Int) = (1..n).map { MediaEntry("https://tv.test/$it/index.m3u8", kind = "video") }
        MediaQueue.validate(hls(MediaQueue.MAX_ADAPTIVE), MediaOptions())
        assertThrows(IllegalArgumentException::class.java) { MediaQueue.validate(hls(MediaQueue.MAX_ADAPTIVE + 1), MediaOptions()) }
        val dash = MediaEntry("https://tv.test/m.mpd", kind = "video")
        assertThrows(IllegalArgumentException::class.java) { MediaQueue.validate(hls(MediaQueue.MAX_ADAPTIVE) + dash, MediaOptions()) }
        // Files and progressive downloads are prepared lazily, so a long list of them is fine.
        MediaQueue.validate((1..300).map { MediaEntry("https://tv.test/$it.mp4", kind = "video") }, MediaOptions())
        MediaQueue.validate(listOf(MediaEntry("rtsp://192.168.1.9/live", kind = "video")) + (1..300).map { MediaEntry("file:///sdcard/Music/$it.mp3") }, MediaOptions())
    }

    @Test fun aStartPositionIsNeverNegative() {
        assertEquals(90_000L, MediaOptions(start = 90_000).start)
        assertThrows(IllegalArgumentException::class.java) { MediaOptions(start = -1) }
    }

    @Test fun mediaInputsAreValidatedBeforeTheyCanClaimASession() {
        for (url in listOf("", "relative.mp3", "ftp://example.com/a.mp3", "file://remote/a.mp3",
            "https://user:secret@example.com/a", "https://example.com/a#fragment", "http://example.com:0/a")) {
            assertThrows("URL $url", IllegalArgumentException::class.java) { MediaEntry(url) }
        }
        assertThrows(IllegalArgumentException::class.java) { MediaEntry(items[0].url, kind = "picture") }
        assertThrows(IllegalArgumentException::class.java) { MediaOptions(volume = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { MediaRepeat.parse("forever") }
        assertThrows(IllegalArgumentException::class.java) { MediaEntry.fromMap(mapOf("url" to items[0].url, "title" to 12)) }
        assertEquals("file:/sdcard/Music/a%20song.mp3", MediaEntry("/sdcard/Music/a song.mp3").normalizedUrl)
        assertEquals("video", MediaEntry.fromMap(mapOf("url" to "content://media/external/video/1", "kind" to "video")).kind)
    }
}
