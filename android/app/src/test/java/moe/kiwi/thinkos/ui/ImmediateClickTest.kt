package moe.kiwi.thinkos.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmediateClickTest {
    @Test fun aSinglePressedPointerInsideAnEnabledClickableViewCanClick() {
        assertTrue(eligible())
    }

    @Test fun ineligibleGesturesKeepTheNativePath() {
        assertFalse(eligible(pressed = false))
        assertFalse(eligible(inside = false))
        assertFalse(eligible(singlePointer = false))
        assertFalse(eligible(cancelled = true))
        assertFalse(eligible(longPressed = true))
        assertFalse(eligible(enabled = false))
        assertFalse(eligible(clickable = false))
    }

    @Test fun touchBoundsIncludeThePlatformSlop() {
        assertTrue(ImmediateClick.inside(-8f, 47f, 100, 40, 8f))
        assertFalse(ImmediateClick.inside(-8.1f, 0f, 100, 40, 8f))
        assertFalse(ImmediateClick.inside(108f, 0f, 100, 40, 8f))
        assertFalse(ImmediateClick.inside(0f, 48f, 100, 40, 8f))
    }

    private fun eligible(pressed: Boolean = true, inside: Boolean = true, singlePointer: Boolean = true,
                         cancelled: Boolean = false, longPressed: Boolean = false,
                         enabled: Boolean = true, clickable: Boolean = true): Boolean =
        ImmediateClick.canClick(pressed, inside, singlePointer, cancelled, longPressed, enabled, clickable)
}
