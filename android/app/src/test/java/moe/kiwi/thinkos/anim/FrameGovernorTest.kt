package moe.kiwi.thinkos.anim

import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameGovernorTest {
    @Test fun ambientTouchRaisesTheCadenceUntilTheGestureEnds() {
        val governor = FrameGovernor().apply { attention = Attention.AMBIENT }
        assertEquals(3, governor.fps)
        governor.touching = true
        assertEquals(60, governor.fps)
        governor.touching = false
        assertEquals(3, governor.fps)
    }

    @Test fun sleepAndReducedMotionTakePriorityOverTouch() {
        val governor = FrameGovernor().apply {
            attention = Attention.ASLEEP
            touching = true
        }
        assertEquals(1, governor.fps)
        governor.activeAnimations = true
        assertEquals(5, governor.fps)
        governor.attention = Attention.AMBIENT
        governor.reducedMotion = true
        assertEquals(5, governor.fps)
    }
}
