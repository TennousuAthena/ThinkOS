package moe.kiwi.thinkos.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceOrientationTest {
    @Test fun landscapeUsesTheUprightMountedPanelAndOtherRequestsArePreserved() {
        assertEquals(8, DeviceOrientation.LANDSCAPE)
        assertEquals(8, DeviceOrientation.forced(0))
        for (request in listOf(1, 6, 7, 8, 9, 10, -1)) assertEquals(request, DeviceOrientation.forced(request))
    }
}
