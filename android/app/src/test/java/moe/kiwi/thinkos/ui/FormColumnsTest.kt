package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.ui.form.FormColumns
import org.junit.Assert.*
import org.junit.Test

class FormColumnsTest {
    @Test fun portraitAndLegacyFormsRetainTheirExistingWidth() {
        for (density in listOf(1f, 1.625f, 2f)) {
            val portrait = FormColumns.plan((492 * density).toInt(), density, true)
            assertFalse(portrait.wide)
            assertEquals((492 * density).toInt(), portrait.content)
            val legacy = FormColumns.plan((788 * density).toInt(), density, false)
            assertFalse(legacy.wide)
            assertEquals((560 * density).toInt(), legacy.content)
        }
    }

    @Test fun breakpointAndDeviceLandscapeLeaveRoomForAnExpandedSlider() {
        val density = 1.625f
        val threshold = kotlin.math.ceil(700 * density.toDouble()).toInt()
        assertFalse(FormColumns.plan(threshold-1, density, true).wide)
        val atThreshold = FormColumns.plan(threshold, density, true)
        assertTrue(atThreshold.wide)
        assertEquals((240 * density).toInt(), atThreshold.navigation)
        // Minus/plus hit areas + thumb insets + a useful 200dp track fit beside the sidebar.
        assertTrue(atThreshold.content >= (324 * density).toInt())
        val device = FormColumns.plan(1280-(40*density).toInt(), density, true)
        assertTrue(device.wide)
        assertTrue(device.body <= 1280-(40*density).toInt())
        assertTrue(device.content >= (324*density).toInt())
    }
}
