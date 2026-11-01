package moe.kiwi.thinkos.cast.airplay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MirrorTimelineTest {
    private val video = MirrorTimeline.VIDEO
    private val audio = MirrorTimeline.AUDIO
    private fun ms(n: Long) = n * 1000

    @Test fun aSteadyStreamIsLeftAlone() {
        val t = MirrorTimeline()
        for (i in 0 until 300) assertEquals(ms(i * 33L), t.map(video, ms(i * 33L)))
        for (i in 0 until 300) assertEquals(ms(10_000 + i * 23L), t.map(audio, ms(10_000 + i * 23L)))
    }

    @Test fun aStillScreenDoesNotMakeTheNextPictureWait() {
        val t = MirrorTimeline()
        assertEquals(ms(1000), t.map(video, ms(1000)))
        assertEquals(ms(1033), t.map(video, ms(1033)))
        // Nothing for 25 seconds, then the screen moves again: the picture comes right after the last one, the ones behind it keep their spacing.
        val first = t.map(video, ms(26_033))
        assertEquals(ms(1033) + 40_000, first)
        assertEquals(first + ms(33), t.map(video, ms(26_066)))
        assertEquals(first + ms(66), t.map(video, ms(26_099)))
    }

    @Test fun everyStillnessIsCutNotJustTheFirst() {
        val t = MirrorTimeline()
        t.map(video, ms(0))
        val a = t.map(video, ms(10_000))
        val b = t.map(video, ms(30_000))
        assertTrue("the second silence is cut as well: $a $b", b - a == 40_000L)
    }

    @Test fun aPictureThatIsLateWhileSoundGoesOnStaysWhereTheSoundPutsIt() {
        val t = MirrorTimeline()
        t.map(video, ms(0))
        var at = 0L
        while (at < 20_000) { at += 23; t.map(audio, ms(at)) }        // music over a still screen
        assertEquals(ms(20_050), t.map(video, ms(20_050)))            // the picture that ends the stillness is due when the sound reaches it
    }

    @Test fun soundThatStartsAheadOfThePictureIsNotAnIdleGap() {
        // The two streams are stamped from different origins; a first audio sample 200 ms ahead of the video is not a silence.
        val t = MirrorTimeline()
        for (i in 0 until 10) t.map(video, ms(i * 33L))
        assertEquals(ms(300), t.map(audio, ms(300)))
        assertEquals(ms(330), t.map(video, ms(330)))
    }

    @Test fun aSilenceOnBothStreamsIsCutWhicheverEndsIt() {
        val t = MirrorTimeline()
        t.map(video, ms(0)); t.map(audio, ms(0))
        val back = t.map(audio, ms(60_000))                            // the music starts again over a still screen
        assertEquals(40_000L, back)
        assertEquals(ms(60_033) - ms(60_000) + back, t.map(video, ms(60_033)))
    }

    @Test fun timestampsNeverRunBackwardsBecauseOfACut() {
        val t = MirrorTimeline()
        var last = Long.MIN_VALUE
        for (raw in listOf(0L, 33, 66, 20_000, 20_033, 20_066, 90_000, 90_033)) {
            val out = t.map(video, ms(raw))
            assertTrue("$raw -> $out after $last", out >= last)
            last = out
        }
    }

    @Test fun theLengthOfALongSilenceIsReportedOnce() {
        val told = ArrayList<Long>()
        val t = MirrorTimeline(onQuiet = { told += it })
        t.map(video, 0); t.map(video, ms(200))                         // a short gap is cut quietly
        t.map(video, ms(30_000))
        assertEquals(1, told.size)
        assertTrue(told[0] in ms(29_000)..ms(30_000))
    }

    @Test fun resetForgetsTheOldStream() {
        val t = MirrorTimeline()
        t.map(video, ms(0)); t.map(video, ms(10_000))
        t.reset()
        assertEquals(ms(5), t.map(video, ms(5)))
        assertEquals(ms(40), t.map(video, ms(40)))
    }
}
