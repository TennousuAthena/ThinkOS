package moe.kiwi.thinkos.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TilesTest {
    @Test fun firstFitDropAndPortraitCapacity() {
        assertNull(RenderGeometry.tiles(listOf("large", "wide"), 1280, 800, 1.625f)[1])
        assertTrue(RenderGeometry.tiles(listOf("large", "wide"), 800, 1280, 1.625f).all { it != null })
        assertEquals(listOf(null), RenderGeometry.tiles(listOf("small"), 0, 800, 1f))
        assertEquals(listOf(null), RenderGeometry.tiles(listOf("small"), 800, 0, 1f))
        assertEquals(listOf(null), RenderGeometry.tiles(listOf("huge"), 800, 1280, 1f))
    }
    @Test fun geometryIsDeterministicMirroredBoundedAndDisjoint() {
        val random = Random(25)
        for ((w, h) in listOf(1280 to 800, 800 to 1280, 1 to 1, 40 to 30)) repeat(100) {
            val sizes = List(16) { listOf("small", "medium", "wide", "large")[random.nextInt(4)] }
            val boxes = RenderGeometry.tiles(sizes, w, h, 1.625f)
            assertEquals(boxes, RenderGeometry.tiles(sizes, w, h, 1.625f))
            val mirrored = RenderGeometry.tiles(sizes, w, h, 1.625f, true)
            for ((i, a) in boxes.withIndex()) if (a != null) {
                assertTrue(a.left >= 0 && a.top >= 0 && a.right <= w && a.bottom <= h)
                assertEquals(RenderGeometry.Box(w-a.right, a.top, w-a.left, a.bottom), mirrored[i])
                for (b in boxes.drop(i + 1).filterNotNull()) assertTrue(a.right <= b.left || b.right <= a.left || a.bottom <= b.top || b.bottom <= a.top)
            }
        }
    }
    @Test fun colorsFallBackAndAllSampledForegroundsHaveContrast() {
        for (s in listOf(null, "red", "#FFF", "#2D89EF80", " #2D89EF", "#GGGGGG", "{{c}}")) assertNull(TileColor.parse(s))
        val blue = TileColor.parse("#2D89EF")!!; val red = TileColor.parse("#D24726")!!
        assertEquals(blue, TileColor.resolve("#2D89EF", "#D24726", -1))
        assertEquals(red, TileColor.resolve("red", "#D24726", -1))
        assertEquals(-1, TileColor.resolve(null, "bad", -1))
        for (r in 0..15) for (g in 0..15) for (b in 0..15) {
            val color = 0xff000000.toInt() or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            assertTrue(TileColor.contrast(color, TileColor.foreground(color)) >= 4.5)
        }
        for (color in listOf(0xff000000.toInt(), -1, 0xff808080.toInt())) assertTrue(TileColor.contrast(color, TileColor.foreground(color)) >= 4.5)
    }
}
