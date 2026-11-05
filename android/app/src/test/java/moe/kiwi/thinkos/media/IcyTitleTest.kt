package moe.kiwi.thinkos.media

import org.junit.Assert.*
import org.junit.Test

class IcyTitleTest {
    @Test fun controlCharactersCannotCreateExtraRowsOrNulText() {
        assertEquals("Artist Track", icyTitle(" \u0000Artist\r\nTrack\u2028 "))
        assertNull(icyTitle("\u0000\n\r "))
    }

    @Test fun unicodeNamesRemainIntactWithinTheBound() {
        assertEquals("周杰倫 - 夜曲", icyTitle("周杰倫 - 夜曲"))
        assertEquals(256, icyTitle("a".repeat(2000))!!.length)
        val value = icyTitle("a".repeat(255) + "\uD83C\uDFB5extra")!!
        assertEquals(255, value.length)
        assertFalse(value.last().isHighSurrogate())
    }
}
