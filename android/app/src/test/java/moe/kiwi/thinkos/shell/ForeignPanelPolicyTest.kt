package moe.kiwi.thinkos.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForeignPanelPolicyTest {
    private fun allowed(foreign: Boolean = true, resumed: Boolean = false, enabled: Boolean = true,
                        panel: Boolean = true, safe: Boolean = false, permission: Boolean = true,
                        protocol: String = "1", policy: String? = "immersive.status=*") =
        ForeignPanelPolicy.available(foreign, resumed, enabled, panel, safe, permission, protocol, policy)
    @Test fun everyRuntimeAndPermissionGateIsRequired() {
        assertTrue(allowed())
        assertFalse(allowed(foreign = false)); assertFalse(allowed(resumed = true)); assertFalse(allowed(enabled = false))
        assertFalse(allowed(panel = false)); assertFalse(allowed(safe = true)); assertFalse(allowed(permission = false))
    }
    @Test fun permissionAndAHiddenBarDoNotReplaceTheOptionalModule() {
        for (version in listOf("", "0", "3", "01", "1\n")) assertFalse(allowed(protocol = version))
    }
    @Test fun onlyAnUnexcludedGlobalStatusPolicyAllowsThePhysicalTopEdge() {
        for (value in listOf(null, "", "null", "immersive.navigation=*", "immersive.full=*", "immersive.status=some.app", "immersive.status=*,-some.app"))
            assertFalse(value, allowed(policy = value))
        assertTrue(allowed(policy = "immersive.navigation=one.app:immersive.status=*:immersive.preconfirms=*"))
    }
    @Test fun globalShellRequiresTheFullSystemBarPolicy() {
        assertTrue(allowed(protocol = "2", policy = "immersive.full=*"))
        assertTrue(allowed(protocol = "2", policy = "immersive.full=*", foreign = false))
        assertFalse(allowed(protocol = "2", policy = "immersive.full=*", resumed = true))
        assertFalse(allowed(protocol = "2", policy = "immersive.status=*"))
        assertFalse(allowed(protocol = "1", policy = "immersive.full=*"))
    }
    @Test fun requiredGlobalNavigationIsIndependentOfThePanelToggle() {
        assertTrue(ForeignPanelPolicy.globalNavigation("2", true, "immersive.full=*"))
        assertFalse(ForeignPanelPolicy.globalNavigation("1", true, "immersive.full=*"))
        assertFalse(ForeignPanelPolicy.globalNavigation("2", false, "immersive.full=*"))
        assertFalse(ForeignPanelPolicy.globalNavigation("2", true, "immersive.status=*"))
    }
}
