package moe.kiwi.thinkos.plugin

import org.junit.Assert.*
import org.junit.Test

class PluginEventPolicyTest {
    @Test fun castChangesRequireBothNewApiAndMediaAuthority() {
        assertFalse(PluginEventPolicy.allows("cast", 8, setOf("media")))
        assertFalse(PluginEventPolicy.allows("cast", 9, emptySet()))
        assertTrue(PluginEventPolicy.allows("cast", 9, setOf("media")))
    }

    @Test fun existingSensitiveEventsKeepTheirAuthority() {
        for ((event, permission) in listOf("presence" to "presence", "gesture" to "presence", "location" to "location", "device" to "system.device")) {
            assertFalse(PluginEventPolicy.allows(event, 8, emptySet()))
            assertTrue(PluginEventPolicy.allows(event, 8, setOf(permission)))
        }
        assertTrue(PluginEventPolicy.allows("back", 8, emptySet()))
        assertTrue(PluginEventPolicy.allows("page.enter", 1, emptySet()))
    }

    @Test fun appChangesNeedApiNineAndGrantedSystemAuthority() {
        assertFalse(PluginEventPolicy.allows("apps", 8, setOf("system.apps")))
        assertFalse(PluginEventPolicy.allows("apps", 9, emptySet()))
        assertTrue(PluginEventPolicy.allows("apps", 9, setOf("system.apps")))
    }
}
