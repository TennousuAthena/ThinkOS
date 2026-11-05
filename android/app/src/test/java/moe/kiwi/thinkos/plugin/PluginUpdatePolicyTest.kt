package moe.kiwi.thinkos.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginUpdatePolicyTest {
    private fun refused(block: () -> Unit) { try { block(); fail("authority should be refused") } catch (_: IllegalArgumentException) {} }
    @Test fun apiTenFactoryCopyWithDeclaredPermissionMayRead() {
        PluginUpdatePolicy.requireRead(10, setOf("system.update"), true)
    }
    @Test fun oldApiAndUndeclaredCapabilityAreRefused() {
        refused { PluginUpdatePolicy.requireRead(9, setOf("system.update"), true) }
        refused { PluginUpdatePolicy.requireRead(10, emptySet(), true) }
    }
    @Test fun downloadedAndDevelopmentCopiesNeverGainSystemAuthority() {
        val declared = setOf("system.update", "network")
        assertEquals(setOf("network"), PluginAuthority.granted(declared, false))
        refused { PluginUpdatePolicy.requireRead(10, declared, false) }
        assertFalse(PluginEventPolicy.allows("update", 10, PluginAuthority.granted(declared, false)))
    }
    @Test fun updateEventsNeedApiAndGrantedAuthority() {
        assertFalse(PluginEventPolicy.allows("update", 9, setOf("system.update")))
        assertFalse(PluginEventPolicy.allows("update", 10, emptySet()))
        assertTrue(PluginEventPolicy.allows("update", 10, setOf("system.update")))
    }
    @Test fun onlyNativeTapsAndBooleanFormActivationMintTapTime() {
        assertTrue(PluginUpdatePolicy.userEvent("tap", arrayOf("check")))
        assertTrue(PluginUpdatePolicy.userEvent("form.change", arrayOf("update", true)))
        assertTrue(PluginUpdatePolicy.userEvent("form.change", arrayOf("enabled", false)))
        for (name in listOf("start", "update", "timer", "command", "form.layout", "form.navigate", "page.enter")) {
            assertFalse(name, PluginUpdatePolicy.userEvent(name, arrayOf("update", true)))
        }
        for (value in listOf("text", 3, mapOf("latitude" to 0))) assertFalse(PluginUpdatePolicy.userEvent("form.change", arrayOf("field", value)))
    }
    @Test fun exactGraceBoundaryAcceptsButMissingFutureAndExpiredTapsDoNot() {
        assertTrue(PluginUpdatePolicy.fresh(0, 5000, 5000))
        assertFalse(PluginUpdatePolicy.fresh(null, 5000, 5000))
        assertFalse(PluginUpdatePolicy.fresh(-1, 5000, 5000))
        assertFalse(PluginUpdatePolicy.fresh(5001, 5000, 5000))
        assertFalse(PluginUpdatePolicy.fresh(0, 5001, 5000))
        assertFalse(PluginUpdatePolicy.fresh(0, 5000, 6000))
    }
}
