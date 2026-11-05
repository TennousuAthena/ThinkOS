package moe.kiwi.thinkos.plugin

import org.junit.Assert.*
import org.junit.Test

class DeviceModePolicyTest {
    @Test fun apiTenOrdinaryPluginsMayReadButOlderApisCannot() {
        DeviceModePolicy.requireRead(10)
        denied { DeviceModePolicy.requireRead(9) }
    }
    @Test fun declaredSystemPermissionAloneCannotImpersonateFactoryControl() {
        denied { DeviceModePolicy.requireWrite(10, setOf("system.device"), false, "sleep", 100, 100) }
        denied { DeviceModePolicy.requireWrite(10, emptySet(), true, "sleep", 100, 100) }
    }
    @Test fun onlyThreeExactLowercaseModesArePublic() {
        for (value in listOf("day", "redshift", "sleep"))
            DeviceModePolicy.requireWrite(10, setOf("system.device"), true, value, 100, 100)
        for (value in listOf("auto", "night", "SLEEP", "", "sleep "))
            denied { DeviceModePolicy.requireWrite(10, setOf("system.device"), true, value, 100, 100) }
    }
    @Test fun timerMissingStaleOrFutureCreditCannotChangeMode() {
        for ((tap, now) in listOf(null to 100L, 0L to 5001L, 101L to 100L))
            denied { DeviceModePolicy.requireWrite(10, setOf("system.device"), true, "sleep", tap, now) }
        DeviceModePolicy.requireWrite(10, setOf("system.device"), true, "sleep", 100, 5100)
    }
    private fun denied(action: () -> Unit) {
        try { action(); fail("unauthorized mode access accepted") } catch (_: IllegalArgumentException) { }
    }
}
