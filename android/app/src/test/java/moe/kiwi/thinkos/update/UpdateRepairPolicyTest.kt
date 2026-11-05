package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Test

class UpdateRepairPolicyTest {
    @Test fun anOrdinaryAppOnlyDeviceDoesNotNeedKeysOrAnUpdaterOrDeviceOwner() {
        assertEquals("absent", UpdateRepairPolicy.root(UpdateRepairPresence.State.ABSENT, false, null))
        UpdateRepairPolicy.sessions(0)
    }
    @Test fun onlyKernelProvenAbsenceOrAnAuthenticatedIdleStatusMayPass() {
        data class Case(val presence: UpdateRepairPresence.State, val local: Boolean, val transaction: Boolean?, val expected: String?)
        val cases = listOf(
            Case(UpdateRepairPresence.State.ABSENT, true, null, null),
            Case(UpdateRepairPresence.State.UNKNOWN, false, null, null),
            Case(UpdateRepairPresence.State.PRESENT, false, null, null),
            Case(UpdateRepairPresence.State.PRESENT, false, true, null),
            Case(UpdateRepairPresence.State.PRESENT, false, false, "idle"),
            Case(UpdateRepairPresence.State.PRESENT, true, false, "idle"))
        for (case in cases) {
            if (case.expected == null) assertThrows(IllegalStateException::class.java) {
                UpdateRepairPolicy.root(case.presence, case.local, case.transaction)
            } else assertEquals(case.expected, UpdateRepairPolicy.root(case.presence, case.local, case.transaction))
        }
    }
    @Test fun evenAnUnsealedOrUnknownPackageSessionPreventsAutomaticReconstruction() {
        for (sessions in listOf(1, 2, 64)) assertThrows(IllegalStateException::class.java) { UpdateRepairPolicy.sessions(sessions) }
    }
    @Test fun pmNewVersionCannotAuthorizeRecoveryWhileTheOldCodeIsStillExecuting() {
        val installed = UpdateApkIdentity("0.2.0", 2000, "a".repeat(64), 123, "b".repeat(64))
        assertThrows(IllegalStateException::class.java) { UpdateRepairPolicy.executing(installed, "0.1.9", 1009) }
        UpdateRepairPolicy.executing(installed, "0.2.0", 2000)
    }
    @Test fun aSameCodeReplacementStillNeedsTheExecutingLoadedApkPathToMatchPm() {
        assertThrows(IllegalStateException::class.java) {
            UpdateRepairPolicy.source("/data/app/thinkos-old/base.apk", "/data/app/thinkos-new/base.apk")
        }
        UpdateRepairPolicy.source("/data/app/thinkos/base.apk", "/data/app/thinkos/base.apk")
    }
}
