package moe.kiwi.thinkos.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.ArrayDeque
import kotlin.math.abs

class PracticeDemonstrationTest {
    private fun path(name: String, reverse: Boolean = false) =
        PracticeDemonstration.path(name, 800, 1280, 1.625f, reverse)!!

    @Test fun sixDemonstrationsShowTheirPhysicalDirectionsAndLongPressStaysStill() {
        assertTrue(path("swipe").toX > path("swipe").fromX)
        assertTrue(path("variant").toY > path("variant").fromY)
        assertTrue(path("panel").toY > path("panel").fromY)
        assertTrue(path("panel").fromY < 20)
        assertTrue(path("back").toX > path("back").fromX)
        assertTrue(path("back").fromX < 20)
        assertTrue(path("home").toY < path("home").fromY)
        assertTrue(path("home").fromY > 1260)
        val hold = path("long-press")
        assertTrue(hold.press)
        assertEquals(hold.fromX, hold.toX, 0f)
        assertEquals(hold.fromY, hold.toY, 0f)
    }

    @Test fun onlyPageAndVariantAlternateWhilePhysicalEdgesNeverMirror() {
        for (name in listOf("swipe", "variant")) {
            val a = path(name); val b = path(name, true)
            assertEquals(a.fromX, b.toX, 0f); assertEquals(a.fromY, b.toY, 0f)
            assertEquals(a.toX, b.fromX, 0f); assertEquals(a.toY, b.fromY, 0f)
        }
        for (name in listOf("panel", "back", "home", "long-press")) assertEquals(path(name), path(name, true))
    }

    @Test fun portraitAndLandscapePathsStayBoundedWithoutCrossingCenteredEdgeCaptions() {
        for ((w, h) in listOf(800 to 1280, 1280 to 800, 320 to 480)) {
            for (name in listOf("swipe", "variant", "panel", "back", "home", "long-press")) {
                val p = PracticeDemonstration.path(name, w, h, 1.625f, false)!!
                assertTrue(p.fromX in 0f..w.toFloat() && p.toX in 0f..w.toFloat())
                assertTrue(p.fromY in 0f..h.toFloat() && p.toY in 0f..h.toFloat())
                assertTrue(abs(p.toX - p.fromX) <= minOf(w, h) / 3f + .01f)
                assertTrue(abs(p.toY - p.fromY) <= minOf(w, h) / 3f + .01f)
                if (name in listOf("panel", "home")) assertTrue(abs(p.fromX - w / 2f) >= w / 4f)
            }
        }
        assertNull(PracticeDemonstration.path("invalid", 800, 1280, 1f, false))
        assertNull(PracticeDemonstration.path("home", 0, 1280, 1f, false))
    }

    @Test fun touchesCompletionAndEveryVisibilityGateRemovePermissionToAnimate() {
        fun allowed(complete: Boolean = false, touching: Boolean = false, attached: Boolean = true,
                    shown: Boolean = true, focused: Boolean = true, visible: Boolean = true, motion: Boolean = true) =
            PracticeDemonstration.eligible("swipe", complete, touching, attached, shown, focused, visible, motion)
        assertTrue(allowed())
        assertFalse(allowed(complete = true)); assertFalse(allowed(touching = true))
        assertFalse(allowed(attached = false)); assertFalse(allowed(shown = false))
        assertFalse(allowed(focused = false)); assertFalse(allowed(visible = false)); assertFalse(allowed(motion = false))
        assertFalse(PracticeDemonstration.eligible(null, false, false, true, true, true, true, true))
    }

    @Test fun cancelledOrReplacedAnimationEndCannotQueueAnotherCycle() {
        val model = PracticeDemonstration()
        val queue = ArrayDeque<() -> Unit>()
        var eligible = true
        fun end(ticket: Long) { if (model.accepts(ticket, eligible)) queue.add {} }
        val old = model.begin()
        model.cancel() // DOWN, hide, completion, focus loss, detach or stop.
        end(old)
        assertTrue(queue.isEmpty())
        val current = model.begin()
        end(old)
        assertTrue(queue.isEmpty())
        eligible = false
        end(current)
        assertTrue(queue.isEmpty())
        eligible = true
        end(current)
        assertEquals(1, queue.size)
    }
}
