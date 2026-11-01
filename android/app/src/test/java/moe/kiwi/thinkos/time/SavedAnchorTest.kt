package moe.kiwi.thinkos.time

import org.junit.Assert.*
import org.junit.Test

class SavedAnchorTest {
    private val wall = 1_793_000_000_000L

    @Test fun roundTripsThroughItsTextForm() {
        val saved = SavedAnchor.decode(SavedAnchor(7, wall, 123_456).encode())!!
        assertEquals(7, saved.boot); assertEquals(wall, saved.wall); assertEquals(123_456L, saved.elapsed)
    }

    @Test fun garbageIsNotAReading() {
        for (text in listOf("", "1,2", "a,b,c", "1,2,3,4", "7,12,x")) assertNull(text, SavedAnchor.decode(text))
    }

    @Test fun onlyUsableOnTheBootItWasTakenInAndNeverFromTheFuture() {
        val saved = SavedAnchor(7, wall, 100_000)
        assertTrue(saved.usable(7, 100_000))
        assertTrue(saved.usable(7, 9_000_000))
        assertFalse("another boot restarts the monotonic clock", saved.usable(8, 9_000_000))
        assertFalse("a clock that reads earlier than the saved moment is a different boot", saved.usable(7, 99_999))
        assertFalse("boot count unknown", SavedAnchor(-1, wall, 1).usable(-1, 10))
        assertFalse("a reading from before 2020 is not a real time", SavedAnchor(7, 1_000, 1).usable(7, 10))
    }
}
