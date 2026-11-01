package moe.kiwi.thinkos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class MediaTimeTest {
    @Test fun positionsReadAsMinutesAndHours() {
        assertEquals("0:00", MediaTime.format(0))
        assertEquals("0:09", MediaTime.format(9_999))
        assertEquals("1:05", MediaTime.format(65_000))
        assertEquals("59:59", MediaTime.format(3_599_999))
        assertEquals("1:00:00", MediaTime.format(3_600_000))
        assertEquals("2:03:04", MediaTime.format(7_384_000))
        assertEquals("0:00", MediaTime.format(-5))
    }
}
