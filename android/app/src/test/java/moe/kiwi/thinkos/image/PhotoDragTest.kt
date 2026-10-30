package moe.kiwi.thinkos.image

import org.junit.Assert.*
import org.junit.Test

class PhotoDragTest {
    private fun gesture() = PhotoDrag(10f, 20f, 700f)
    @Test fun highVelocityNoiseDoesNotTurnAPageButDeliberateShortFlicksDo() {
        val g = gesture()
        g.begin(0f, 12f); val tiny = g.move(15f)
        assertFalse(g.commit(tiny, 800, 9000f, false))
        g.begin(0f, -12f); val short = g.move(-40f)
        assertTrue(g.commit(short, 800, -1200f, false))
        assertFalse(g.commit(short, 800, -1200f, true))
    }
    @Test fun catchingCommitOrReturnKeepsPresentedPositionAndAvoidsANewPageOrigin() {
        val g = gesture()
        g.begin(-320f, -10f, true)
        assertEquals(-320f, g.move(-10f), 0f)
        assertEquals(-325f, g.move(-15f), 0f)
        assertTrue(g.commit(-325f, 800, -900f, false))
        g.begin(-18f, 10f, false)
        assertEquals(-18f, g.move(10f), 0f)
        assertEquals(-13f, g.move(15f), 0f)
        assertFalse(g.commit(-13f, 800, 9000f, false))
    }
    @Test fun reversalOverridesDistanceAndCancellationOverridesAnInterruptedCommit() {
        val g = gesture()
        g.begin(-600f, 10f, true)
        val value = g.move(60f)
        assertFalse(g.commit(value, 800, 2000f, false))
        assertTrue(g.commit(value, 800, 0f, false))
        assertFalse(g.commit(value, 800, 0f, true))
    }
    @Test fun zeroCrossingKeepsThePreviewUntilDirectionIsClear() {
        val g = gesture()
        assertEquals(1, g.direction(5f, 1))
        assertEquals(1, g.direction(-5f, 1))
        assertEquals(-1, g.direction(11f, 1))
        assertEquals(1, g.direction(-11f, -1))
    }
    @Test fun catchingAnOverscrollKeepsItsPresentedPixels() {
        val g = gesture()
        g.begin(4f, 10f, false, .16f)
        assertEquals(4f, g.move(10f)*.16f, .001f)
        assertEquals(4.8f, g.move(15f)*.16f, .001f)
    }
}
