package moe.kiwi.thinkos.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class TilesTest {
    @Test fun metroSquareSizesStaySquareAndWideKeepsTwoToOneModules() {
        for ((w,h) in listOf(1280 to 800,800 to 1280)) {
            for (size in listOf("small","medium","large")) {
                val box=RenderGeometry.tiles(listOf(size),w,h,1.625f).single()!!
                assertEquals(box.width,box.height)
            }
            val wide=RenderGeometry.tiles(listOf("wide"),w,h,1.625f).single()!!
            assertEquals(wide.height*2+13,wide.width)
        }
    }
    @Test fun firstFitDropAndPortraitCapacity() {
        val mixed = listOf("large", "large", "wide", "wide")
        assertTrue(RenderGeometry.tiles(mixed, 1280, 800, 1.625f).all { it != null })
        assertEquals(listOf(false, false, true, true), RenderGeometry.tiles(mixed, 800, 1280, 1.625f).map { it == null })
        assertNull(RenderGeometry.tiles(listOf("large", "large", "large"), 1280, 800, 1.625f)[2])
        assertNull(RenderGeometry.tiles(listOf("large", "large", "large"), 800, 1280, 1.625f)[2])
        assertEquals(listOf(null), RenderGeometry.tiles(listOf("small"), 0, 800, 1f))
        assertEquals(listOf(null), RenderGeometry.tiles(listOf("small"), 800, 0, 1f))
        assertEquals(listOf(null), RenderGeometry.tiles(listOf("huge"), 800, 1280, 1f))
    }
    @Test fun theStartPageFillsEveryCellInBothOrientationsAndTheTitleRailStaysClear() {
        val start = listOf("wide", "medium", "medium", "large", "wide", "medium", "small", "small", "small", "small")
        for ((w, h) in listOf(1280 to 800, 800 to 1280)) {
            val grid = RenderGeometry.tileGrid(w, h, 1.625f)!!
            assertEquals(if (w > h) 8 else 6, grid.cols)
            val boxes = RenderGeometry.tiles(start, w, h, 1.625f)
            assertTrue(boxes.all { it != null })
            val cells = boxes.filterNotNull().sumOf { box ->
                ((box.width + grid.gap) / (grid.cell + grid.gap)) * ((box.height + grid.gap) / (grid.cell + grid.gap))
            }
            assertEquals(grid.cols * grid.rows, cells)
            val title = RenderGeometry.tileTitle(w, h, 1.625f)!!
            for (box in boxes.filterNotNull()) assertTrue(title.right <= box.left || title.bottom <= box.top)
            val mirrored = RenderGeometry.tileTitle(w, h, 1.625f, true)!!
            assertEquals(RenderGeometry.Box(w - title.right, title.top, w - title.left, title.bottom), mirrored)
            // The grid sits on the far edge with the standard margin, the remainder is the rail.
            val rightmost = boxes.filterNotNull().maxOf { it.right }; val bottommost = boxes.filterNotNull().maxOf { it.bottom }
            if (w > h) assertEquals(w - grid.edge, rightmost) else assertEquals(h - grid.edge, bottommost)
        }
    }
    @Test fun noTitleRailMeansNoTitleAndNoGridMeansNoTiles() {
        assertNull(RenderGeometry.tileTitle(0, 800, 1.625f))
        assertNull(RenderGeometry.tileGrid(40, 30, 1.625f))
        assertNull(RenderGeometry.tileTitle(40, 30, 1.625f))
        assertNull(RenderGeometry.tileGrid(800, 0, 1f))
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
    @Test fun tilesAreWhiteOnColourUnlessWhiteCannotBeRead() {
        for (hex in listOf("#0B5CC4", "#C2410C", "#B3173B", "#0F766E", "#6D28D9", "#A21CAF", "#15803D", "#475569", "#0E7490"))
            assertEquals(hex, -1, TileColor.foreground(TileColor.parse(hex)!!))
        for (hex in listOf("#FFB900", "#E9B35A", "#F2F3F5", "#FFFFFF"))
            assertEquals(hex, 0xff000000.toInt(), TileColor.foreground(TileColor.parse(hex)!!))
        // The Windows blue is right at the limit: white reaches only 4.50, so the stricter rule picks black.
        assertEquals(0xff000000.toInt(), TileColor.foreground(TileColor.parse("#0078D7")!!))
    }
    @Test fun nightCompositionAlsoPreservesReadableContrast() {
        for (n in listOf(0f, .25f, .5f, .75f, 1f)) for (r in 0..15) for (g in 0..15) for (b in 0..15) {
            val color = 0xff000000.toInt() or (r*17 shl 16) or (g*17 shl 8) or (b*17)
            val safe = TileColor.nightSafe(color, n)
            assertTrue(TileColor.contrast(TileColor.filtered(safe, n), TileColor.filtered(TileColor.nightForeground(safe, n), n)) >= 4.5)
            if (n == 0f) assertEquals(color, safe)
        }
    }
}
