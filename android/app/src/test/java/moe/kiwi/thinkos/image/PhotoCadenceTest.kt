package moe.kiwi.thinkos.image

import moe.kiwi.thinkos.anim.FrameGovernor
import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoCadenceTest {
    @Test fun sceneTicksStayIdleWhileIndependentNativeAnimationsCanRun() {
        val governor = FrameGovernor().apply { attention = Attention.AMBIENT }
        assertEquals(3,governor.fps)
        governor.attention = Attention.AWAKE
        assertEquals(1,governor.fps)
        governor.activeAnimations = true
        assertEquals(60,governor.fps)
        governor.activeAnimations = false; governor.attention = Attention.ASLEEP
        assertEquals(1,governor.fps)
        governor.attention = Attention.AMBIENT; governor.ambientAnimations = true
        assertEquals(3,governor.fps)
    }
}
