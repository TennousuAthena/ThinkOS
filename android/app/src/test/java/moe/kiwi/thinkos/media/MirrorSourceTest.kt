package moe.kiwi.thinkos.media

import org.junit.Assert.*
import org.junit.Test
import kotlin.concurrent.thread

class MirrorSourceTest {
    private fun chunk(size: Int, fill: Int) = ByteArray(size) { fill.toByte() }

    @Test fun bytesComeOutInTheOrderTheyWentIn() {
        val source = MirrorSource("test")
        source.write(chunk(188, 1)); source.write(chunk(188, 2))
        val out = ByteArray(1000)
        assertEquals(376, source.read(out, 0, 1000, 50))
        assertEquals(1, out[0].toInt()); assertEquals(1, out[187].toInt()); assertEquals(2, out[188].toInt()); assertEquals(2, out[375].toInt())
        assertEquals(0, source.queuedBytes)
    }

    @Test fun aSmallReadTakesPartOfAChunkAndKeepsTheRest() {
        val source = MirrorSource("test")
        source.write(ByteArray(10) { it.toByte() })
        val out = ByteArray(4)
        assertEquals(4, source.read(out, 0, 4, 50)); assertArrayEquals(byteArrayOf(0, 1, 2, 3), out)
        assertEquals(6, source.queuedBytes)
        assertEquals(4, source.read(out, 0, 4, 50)); assertArrayEquals(byteArrayOf(4, 5, 6, 7), out)
        assertEquals(2, source.read(out, 0, 4, 50)); assertEquals(8, out[0].toInt()); assertEquals(9, out[1].toInt())
    }

    @Test fun readTimesOutWithZeroWhenNothingArrives() {
        val source = MirrorSource("test")
        val started = System.nanoTime()
        assertEquals(0, source.read(ByteArray(10), 0, 10, 60))
        assertTrue(System.nanoTime() - started >= 50_000_000)
    }

    @Test fun whenThePlayerFallsBehindTheOldestBytesAreDroppedAndTheNewestKept() {
        val source = MirrorSource("test", capacityBytes = 16 * 1024)
        for (i in 0 until 40) source.write(chunk(1316, i))           // 52 KB into a 16 KB queue
        assertTrue(source.queuedBytes <= 16 * 1024)
        assertEquals(40L * 1316, source.receivedBytes)
        assertEquals(source.receivedBytes - source.queuedBytes, source.droppedBytes)
        val out = ByteArray(1316 * 40)
        val n = source.read(out, 0, out.size, 50)
        assertEquals(39, out[n - 1].toInt())                           // the last thing written is still there
        assertTrue(out[0].toInt() > 20)                                // and the beginning is gone
    }

    @Test fun aSingleChunkLargerThanTheQueueIsNeverDroppedEmptyHanded() {
        val source = MirrorSource("test", capacityBytes = 16 * 1024)
        assertTrue(source.write(chunk(20_000, 7)))
        assertEquals(20_000, source.queuedBytes)
    }

    @Test fun closingDrainsWhatIsQueuedThenEnds() {
        val source = MirrorSource("test")
        source.write(chunk(10, 5))
        source.close()
        assertFalse(source.write(chunk(10, 6)))
        val out = ByteArray(100)
        assertEquals(10, source.read(out, 0, 100, 50))
        assertEquals(-1, source.read(out, 0, 100, 50))
    }

    @Test fun aBlockedReaderWakesWhenDataArrivesOrTheSourceCloses() {
        val source = MirrorSource("test")
        var got = -2
        val reader = thread { got = source.read(ByteArray(100), 0, 100, 5000) }
        Thread.sleep(80)
        source.write(chunk(30, 1))
        reader.join(2000)
        assertEquals(30, got)
        val ender = thread { got = source.read(ByteArray(100), 0, 100, 5000) }
        Thread.sleep(80)
        source.close()
        ender.join(2000)
        assertEquals(-1, got)
    }

    @Test fun anInterruptedReaderStops() {
        val source = MirrorSource("test")
        var failure: Throwable? = null
        val reader = thread { try { source.read(ByteArray(10), 0, 10, 10_000) } catch (e: Throwable) { failure = e } }
        Thread.sleep(80)
        reader.interrupt(); reader.join(2000)
        assertTrue(failure is InterruptedException)
    }

    @Test fun onlyTheOsCanBuildAMirrorEntry() {
        val entry = MediaEntry("thinkos-mirror://miracast", title = "Phone", kind = "video", type = MediaSourceType.PROGRESSIVE, mirror = MirrorSource("m"))
        assertEquals("thinkos-mirror", entry.scheme)
        // The same URL without the source object, as a plugin would have to say it, is refused like any unknown scheme.
        assertThrows(IllegalArgumentException::class.java) { MediaEntry("thinkos-mirror://miracast", kind = "video", type = MediaSourceType.PROGRESSIVE) }
        assertThrows(IllegalArgumentException::class.java) { MediaEntry.fromMap(mapOf("url" to "thinkos-mirror://miracast", "kind" to "video", "type" to "progressive")) }
        // And a mirror cannot be dressed up as something else.
        assertThrows(IllegalArgumentException::class.java) { MediaEntry("https://tv.test/a.mp4", kind = "video", type = MediaSourceType.PROGRESSIVE, mirror = MirrorSource("m")) }
        assertThrows(IllegalArgumentException::class.java) { MediaEntry("thinkos-mirror://x", kind = "audio", type = MediaSourceType.PROGRESSIVE, mirror = MirrorSource("m")) }
        assertThrows(IllegalArgumentException::class.java) { MediaEntry("thinkos-mirror://x", kind = "video", mirror = MirrorSource("m")) }
    }

    @Test fun aMirrorThatIsCaughtUpPlaysAtNormalSpeed() {
        val pace = MirrorPace()
        for (ahead in listOf(0L, 120L, 300L, 400L, 500L)) assertEquals(1f, pace.update(ahead), 0f)
    }

    @Test fun aMirrorThatFellBehindSpeedsUpMoreTheFurtherBehindItIs() {
        val pace = MirrorPace()
        val small = pace.update(700); pace.reset()
        val big = pace.update(1000); pace.reset()
        val huge = pace.update(5000)
        assertTrue(small > 1.05f && small < big && big < huge)
        assertEquals(1.3f, huge, 0.001f)
    }

    @Test fun theSpeedHoldsBetweenTheThresholdsAndReturnsToNormalOnceCaughtUp() {
        val pace = MirrorPace()
        val fast = pace.update(1000)
        assertEquals(fast, pace.update(450), 0f)          // still draining: not yet below the settle threshold
        assertEquals(fast, pace.update(340), 0f)
        assertEquals(1f, pace.update(300), 0f)            // caught up: 300 ms is about the least the player can hold, so this is the live edge
        assertEquals(1f, pace.update(450), 0f)            // and a middling surplus does not start it again
    }
}
