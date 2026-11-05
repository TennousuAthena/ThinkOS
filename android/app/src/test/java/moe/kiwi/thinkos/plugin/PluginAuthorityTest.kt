package moe.kiwi.thinkos.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginAuthorityTest {
    @Test fun systemRoleFilteringUsesActualGrants() {
        val declared = setOf("system.apps", "system.device")
        assertFalse(PluginAuthority.rolesAllowed(listOf("launcher"), PluginAuthority.granted(declared, false)))
        assertFalse(PluginAuthority.rolesAllowed(listOf("panel"), PluginAuthority.granted(declared, false)))
        assertTrue(PluginAuthority.rolesAllowed(listOf("launcher"), PluginAuthority.granted(declared, true)))
        assertFalse(PluginAuthority.rolesAllowed(listOf("launcher"), setOf("system.device")))
        assertTrue(PluginAuthority.rolesAllowed(listOf("home", "sleep"), emptySet()))
        assertFalse(PluginAuthority.rolesAllowed(listOf("onboarding"), PluginAuthority.granted(setOf("system.settings"), false)))
        assertTrue(PluginAuthority.rolesAllowed(listOf("onboarding"), PluginAuthority.granted(setOf("system.settings"), true)))
    }
    @Test fun declaringSystemAuthorityDoesNotGrantItToAReplacementOrThirdParty() {
        val declared = setOf("network", "system.apps", "system.device", "system.settings")
        assertEquals(setOf("network"), PluginAuthority.granted(declared, false))
        assertEquals(declared, PluginAuthority.granted(declared, true))
        for (permission in declared.filter { it.startsWith("system.") }) {
            assertThrows(IllegalArgumentException::class.java) { PluginAuthority.requireSystem(permission, declared, false) }
            assertThrows(IllegalArgumentException::class.java) { PluginAuthority.requireSystem(permission, emptySet(), true) }
            PluginAuthority.requireSystem(permission, declared, true)
        }
    }
}
