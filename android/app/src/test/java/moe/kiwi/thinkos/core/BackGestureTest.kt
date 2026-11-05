package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.core.BackGesture.Verdict.BACK
import moe.kiwi.thinkos.core.BackGesture.Verdict.PAGE
import moe.kiwi.thinkos.core.BackGesture.Verdict.PULL
import moe.kiwi.thinkos.core.BackGesture.Verdict.WAIT
import moe.kiwi.thinkos.core.BackGesture.Verdict.UP
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackGestureTest {
    private val slop = 8f

    @Test fun onlyTheLeftmostStripStartsABackSwipe() {
        assertTrue(BackGesture.startsAtEdge(0f, 24f))
        assertTrue(BackGesture.startsAtEdge(24f, 24f))
        assertFalse(BackGesture.startsAtEdge(24.5f, 24f))
        assertFalse(BackGesture.startsAtEdge(400f, 24f))
        assertFalse(BackGesture.startsAtEdge(-1f, 24f))
    }

    @Test fun aSmallMovementDecidesNothing() {
        assertEquals(WAIT, BackGesture.classify(5f, 3f, slop, back = true, pull = true))
    }

    @Test fun movingRightFromTheEdgeIsBack() {
        assertEquals(BACK, BackGesture.classify(20f, 4f, slop, back = true, pull = false))
    }

    @Test fun movingLeftOrDiagonallyDownFromTheEdgeIsLeftToThePage() {
        assertEquals(PAGE, BackGesture.classify(-20f, 0f, slop, back = true, pull = false))
        assertEquals(PAGE, BackGesture.classify(10f, 40f, slop, back = true, pull = false))
    }

    @Test fun inTheTopLeftCornerWhicheverDirectionShowsFirstWins() {
        assertEquals(BACK, BackGesture.classify(20f, 4f, slop, back = true, pull = true))
        assertEquals(PULL, BackGesture.classify(4f, 20f, slop, back = true, pull = true))
    }

    @Test fun aPullDownFromTheTopEdgeIsNotBackWhenBackCannotStartThere() {
        assertEquals(PULL, BackGesture.classify(2f, 30f, slop, back = false, pull = true))
        assertEquals(PAGE, BackGesture.classify(30f, 2f, slop, back = false, pull = true))
    }

    @Test fun aDragExactlyAtRightAnglesIsNeitherBackNorPull() {
        assertEquals(PAGE, BackGesture.classify(20f, 20f, slop, back = true, pull = true))
    }

    @Test fun backCommitsPastAFifthOfTheWidthOrOnAFastFling() {
        val width = 800f; val density = 2f
        assertFalse(BackGesture.commits(100f, 0f, width, density))
        assertTrue(BackGesture.commits(177f, 0f, width, density))
        assertTrue(BackGesture.commits(40f, 1_500f, width, density))
        assertFalse(BackGesture.commits(40f, 1_000f, width, density))
        // A fling to the left never goes back, and neither does a swipe that was pulled back to the start.
        assertFalse(BackGesture.commits(0f, 3_000f, width, density))
        assertFalse(BackGesture.commits(-50f, -2_000f, width, density))
    }

    @Test fun progressRunsFromZeroToOneAtTheCommitDistance() {
        assertEquals(0f, BackGesture.progress(0f, 800f), 0f)
        assertEquals(.5f, BackGesture.progress(88f, 800f), .001f)
        assertEquals(1f, BackGesture.progress(500f, 800f), 0f)
        assertEquals(0f, BackGesture.progress(50f, 0f), 0f)
    }

    @Test fun upFromThePhysicalBottomWinsOnlyWithUpwardDominance() {
        assertEquals(UP, BackGesture.classify(3f, -30f, slop, back = false, pull = false, up = true))
        assertEquals(PAGE, BackGesture.classify(3f, -30f, slop, back = false, pull = false))
        assertEquals(PAGE, BackGesture.classify(3f, 30f, slop, back = false, pull = false, up = true))
        assertEquals(PAGE, BackGesture.classify(-30f, -3f, slop, back = false, pull = false, up = true))
        assertEquals(WAIT, BackGesture.classify(2f, -6f, slop, back = true, pull = false, up = true))
    }

    @Test fun bottomLeftDirectionAndVerticalCommitUseThePhysicalAxes() {
        assertEquals(UP, BackGesture.classify(10f, -30f, slop, back = true, pull = false, up = true))
        assertEquals(BACK, BackGesture.classify(30f, -10f, slop, back = true, pull = false, up = true))
        assertEquals(PAGE, BackGesture.classify(30f, -30f, slop, back = true, pull = false, up = true))
        assertFalse(BackGesture.commits(200f, 0f, 1280f, 1.6f))
        assertTrue(BackGesture.commits(282f, 0f, 1280f, 1.6f))
        assertTrue(BackGesture.commits(40f, 1200f, 1280f, 1.6f))
        assertFalse(BackGesture.commits(40f, -1200f, 1280f, 1.6f))
    }
}
