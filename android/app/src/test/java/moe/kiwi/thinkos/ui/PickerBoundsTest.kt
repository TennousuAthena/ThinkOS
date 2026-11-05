package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.ui.form.PickerBounds
import moe.kiwi.thinkos.ui.form.PickerBounds.Frame
import moe.kiwi.thinkos.ui.form.PickerBounds.Padding
import org.junit.Assert.*
import org.junit.Test

class PickerBoundsTest {
    @Test fun visibleSideBarOnlyInsetsTheOverlapAndHiddenBarLeavesNoGap() {
        val editor = Frame(28, 0, 1252, 800)
        assertEquals(Padding(50, 0, 0, 0), PickerBounds.padding(editor, Frame(78, 0, 1280, 800)))
        assertEquals(Padding(0, 0, 50, 0), PickerBounds.padding(editor, Frame(0, 0, 1202, 800)))
        assertEquals(Padding(0, 0, 0, 0), PickerBounds.padding(editor, Frame(0, 0, 1280, 800)))
    }

    @Test fun aParentAlreadyInsideTheVisibleAreaIsNotInsetAgain() {
        assertEquals(Padding(0, 0, 0, 0), PickerBounds.padding(
            Frame(78, 24, 1280, 800), Frame(78, 24, 1280, 800)))
    }

    @Test fun keyboardOnlyReservesThePartNotAlreadyRemovedByResize() {
        val visible = Frame(0, 0, 800, 760)
        assertEquals(Padding(0, 0, 0, 520), PickerBounds.padding(Frame(0, 0, 800, 1280), visible))
        assertEquals(Padding(0, 0, 0, 0), PickerBounds.padding(Frame(0, 0, 800, 760), visible))
        assertEquals(Padding(0, 0, 0, 78), PickerBounds.padding(
            Frame(0, 0, 800, 1280), Frame(0, 0, 800, 1202)))
        assertEquals(Padding(0, 0, 0, 0), PickerBounds.padding(
            Frame(0, 0, 800, 1280), Frame(0, 0, 800, 1280)))
    }

    @Test fun screenOriginAndTopOcclusionAreAccountedFor() {
        assertEquals(Padding(0, 14, 0, 80), PickerBounds.padding(
            Frame(100, 10, 700, 900), Frame(0, 24, 800, 820)))
    }

    @Test fun emptyOrDisjointIntermediateFramesDoNotCollapseTheEditor() {
        assertNull(PickerBounds.padding(Frame(0, 0, 0, 0), Frame(0, 0, 1280, 800)))
        assertNull(PickerBounds.padding(Frame(0, 0, 1280, 800), Frame(0, 0, 0, 0)))
        assertNull(PickerBounds.padding(Frame(900, 0, 1280, 800), Frame(0, 0, 800, 1280)))
    }
}
