package moe.kiwi.thinkos.image

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class ImageContractTest {
    @Test fun everyAspectRatioVisitsBothEdgesWithoutDiscardingContent() {
        for ((iw, ih) in listOf(1920 to 1080, 1080 to 1920, 4000 to 3000, 6000 to 1000, 1 to 8192, 64 to 64)) {
            for ((w, h) in listOf(1280 to 800, 800 to 1280)) {
                val frame = ImageGeometry.frame(iw, ih, w, h, true, false, 30000, 45f)
                assertTrue(frame.width >= w && frame.height >= h)
                assertEquals(0f, maxOf(frame.startX, frame.endX), 1f)
                assertEquals(0f, minOf(frame.startX, frame.endX) + frame.width - w, 1f)
                assertEquals(0f, maxOf(frame.startY, frame.endY), 1f)
                assertEquals(0f, minOf(frame.startY, frame.endY) + frame.height - h, 1f)
                val contain = ImageGeometry.frame(iw, ih, w, h, false, false, 30000, 45f)
                assertTrue(contain.width <= w && contain.height <= h)
                assertFalse(contain.moving)
                assertEquals(iw.toDouble() / ih, contain.width.toDouble() / contain.height, .03)
            }
        }
    }

    @Test fun panIsRtlAwareAndExtendsDwellToRespectPeakSpeed() {
        val ltr = ImageGeometry.frame(6000, 1000, 1280, 800, true, false, 5000, 45f)
        val rtl = ImageGeometry.frame(6000, 1000, 1280, 800, true, true, 5000, 45f)
        assertEquals(ltr.startX, rtl.endX)
        assertEquals(ltr.endX, rtl.startX)
        assertTrue(ltr.dwellMillis > 5000)
        assertTrue(kotlin.math.abs(ltr.endX - ltr.startX) * Math.PI / 2 / (ltr.dwellMillis / 1000.0) <= 45.01)
        assertEquals(0, ImageGeometry.frame(0, 0, 0, 0, true, false, 1000, 45f).width)
    }

    @Test fun smoothSweepCanFinishBeforeDwellAndEitherDirectionRetainsBothEdges() {
        val frame = ImageGeometry.frame(1920,1080,1280,800,true,false,30000,67.5f,12000)
        assertEquals(12000L, frame.panMillis)
        assertEquals(30000L, frame.dwellMillis)
        val reversed = frame.reversed()
        assertEquals(frame.startX, reversed.endX)
        assertEquals(frame.endX, reversed.startX)
        assertEquals(frame, reversed.reversed())
        val panorama = ImageGeometry.frame(6000,1000,800,1280,true,false,30000,67.5f,12000)
        assertTrue(panorama.panMillis > 30000)
        assertEquals(panorama.panMillis, panorama.dwellMillis)
        assertTrue(abs(panorama.endX-panorama.startX)*Math.PI/2/(panorama.panMillis/1000.0) <= 67.51)
    }

    @Test fun decodeBudgetRejectsBombsAndBoundsMemoryAndTextureSize() {
        for ((w, h) in listOf(8000 to 4000, 8192 to 1, 1920 to 1080, 64 to 64)) {
            val (dw, dh) = ImageGeometry.decodeSize(w, h, 1280, 800)
            assertTrue(dw * dh <= 1280 * 800 * 1.5)
            assertTrue(maxOf(dw, dh) <= 4096)
            assertTrue(dw <= w && dh <= h)
        }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.decodeSize(8000, 6000, 1280, 800) }
        assertThrows(IllegalArgumentException::class.java) { ImageGeometry.decodeSize(-1, 30, 1280, 800) }
    }

    @Test fun sourcesCannotEscapeThePluginOrBypassLocalPermissions() {
        assertTrue(ImageSource.parse("asset:assets/a.png", emptySet()) is ImageSource.Asset)
        assertTrue(ImageSource.parse("/sdcard/Pictures/a.jpg", setOf("library")) is ImageSource.Local)
        assertTrue(ImageSource.parse("file:///sdcard/Pictures/a.jpg", setOf("library")) is ImageSource.Local)
        assertEquals(ImageSource.Remote("https://www.bing.com/th?id=OHR.Test_1920x1080.jpg"),
            ImageSource.parse("https://www.bing.com/th?id=OHR.Test_1920x1080.jpg", setOf("network")))
        assertTrue(ImageSource.parse("http://127.0.0.1/a.jpg", setOf("network")) is ImageSource.Remote)
        for (src in listOf("asset:../manifest.json", "asset:assets/../manifest.json", "asset:/assets/a.png", "asset:assets//a.png",
            "https://user:secret@example.com/a.jpg", "https://example.com/a.jpg#fragment", "https:///a.jpg",
            "content://media/photo/1", "file://remote/a.jpg", "file:///a.jpg?q=1", "relative/a.png")) {
            assertThrows(src, IllegalArgumentException::class.java) { ImageSource.parse(src, setOf("library", "network")) }
        }
        assertThrows(IllegalArgumentException::class.java) { ImageSource.parse("/sdcard/a.jpg", emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { ImageSource.parse("https://example.com/a.jpg", setOf("library")) }
    }

    @Test fun verticalHistoryIsBoundedBidirectionalAndSkipsFailuresWithoutWrapping() {
        val sequence = PhotoSequence()
        val today = Photo("today", "/today"); val yesterday = Photo("yesterday", "/yesterday"); val older = Photo("older", "/older")
        sequence.update(listOf(today,yesterday,older), false)
        assertNull(sequence.adjacent(-1))
        assertEquals(yesterday, sequence.adjacent(1))
        assertEquals(older, sequence.adjacent(1, excluded=setOf("yesterday")))
        assertEquals(older, sequence.adjacent(1, from="yesterday"))
        sequence.current = "older"
        assertNull(sequence.adjacent(1))
        assertEquals(yesterday, sequence.adjacent(-1))
        assertEquals(today, sequence.adjacent(-1, excluded=setOf("yesterday")))
        assertEquals(today, sequence.next())
        sequence.update(listOf(older,today,yesterday), false)
        assertEquals(older, sequence.selected())
        assertEquals(today, sequence.adjacent(1))
        sequence.update(emptyList(), false)
        assertNull(sequence.adjacent(1))
    }

    @Test fun appIconsNeedGrantedSystemAuthorityAndAcceptOnlyPackageNames() {
        assertEquals(ImageSource.AppIcon("com.example.reader"), ImageSource.parse("app:com.example.reader", setOf("system.apps")))
        assertThrows(IllegalArgumentException::class.java) { ImageSource.parse("app:com.example.reader", setOf("library", "network")) }
        for (bad in listOf("app:", "app:../reader", "app:com.example/Activity", "app:com.example?secret=1", "app:com.example#fragment"))
            assertThrows(bad, IllegalArgumentException::class.java) { ImageSource.parse(bad, setOf("system.apps")) }
    }

    @Test fun refreshReorderRemovalDuplicatesAndFailureSkippingUseStableIds() {
        val sequence = PhotoSequence()
        val a = Photo("a", "/a"); val b = Photo("b", "/b"); val c = Photo("c", "/c")
        sequence.update(listOf(a,b,c), false); sequence.current = "b"
        sequence.update(listOf(c,b,a), false)
        assertEquals(b, sequence.selected())
        sequence.update(emptyList(), false)
        assertNull(sequence.selected())
        sequence.update(listOf(c,b,a), false)
        assertEquals(b, sequence.selected())
        assertEquals(a, sequence.next())
        assertEquals(c, sequence.next(setOf("a")))
        sequence.update(listOf(a,c), false)
        assertEquals(a, sequence.selected())
        assertNull(sequence.next(setOf("a", "c")))
        assertEquals(listOf(a), PhotoSequence.parse(JSONArray("""[{"id":"a","src":"/a"},{"id":"a","src":"/b"},{"src":null}]""")))
    }
}
