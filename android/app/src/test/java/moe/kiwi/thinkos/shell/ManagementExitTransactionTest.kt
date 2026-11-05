package moe.kiwi.thinkos.shell

import org.junit.Assert.*
import org.junit.Test

class ManagementExitTransactionTest {
    private class Fixture : ManagementExitTransaction.Ports {
        var owner = true
        var record: ManagementExitTransaction.Record? = null
        var fail: String? = null
        var clearBeforeThrow = false
        var selfAdminActive = true
        var keepAdminAfterClear = false
        var removeAdminAsync = false
        var removeAdminBeforeThrow = false
        val otherAdmins = mutableSetOf("another.package/AdminReceiver")
        var adminReads = 0
        var failRestore = false
        var ownershipReads = 0
        val calls = mutableListOf<String>()
        private fun action(name: String) {
            calls += name
            if (fail == name) throw IllegalStateException(name)
        }
        override fun prepare() {
            // AtomicFile semantics: a failed write leaves the previous record,
            // and no policy work may happen until this durable write succeeds.
            action("prepare"); record = ManagementExitTransaction.Record.PENDING
        }
        override fun isOwner(): Boolean {
            ownershipReads++
            action(if (ownershipReads == 1) "owner-before" else "owner-after")
            return owner
        }
        override fun releasePolicies() = action("release-policies-and-home")
        override fun clearOwner() {
            if (clearBeforeThrow) owner = false
            action("clear-owner"); owner = false
            if (!keepAdminAfterClear) selfAdminActive = false
        }
        override fun isSelfAdminActive(): Boolean {
            adminReads++
            action(if (adminReads == 1) "admin-before" else "admin-after")
            return selfAdminActive
        }
        override fun removeSelfAdmin() {
            check(!owner) { "Cannot remove an owner admin" }
            if (removeAdminBeforeThrow) selfAdminActive = false
            action("remove-self-admin")
            if (!removeAdminAsync) selfAdminActive = false
        }
        override fun restorePolicies() {
            action("restore-home-and-policies")
            if (failRestore) throw IllegalStateException("restore failed")
        }
        override fun abort() { action("abort"); record = null }
        override fun commit() { action("commit"); record = ManagementExitTransaction.Record.RELEASED }
        override fun finish() = action("stop-recovery-and-watchdog")
    }

    @Test fun durableRequestPrecedesPolicyRemovalAndCommitFollowsRealOwnershipCheck() {
        val fixture = Fixture()
        val result = ManagementExitTransaction.run(fixture)
        assertTrue(result.released); assertTrue(result.complete); assertNull(result.failure)
        assertEquals(listOf("prepare", "owner-before", "release-policies-and-home", "clear-owner", "owner-after", "admin-before", "admin-after", "commit", "stop-recovery-and-watchdog"), fixture.calls)
        assertEquals(ManagementExitTransaction.Record.RELEASED, fixture.record)
    }
    @Test fun failedAtomicPrepareDoesNotTouchOwnershipOrPolicies() {
        val fixture = Fixture().apply { fail = "prepare" }
        val result = ManagementExitTransaction.run(fixture)
        assertFalse(result.released); assertFalse(result.complete)
        assertEquals(listOf("prepare"), fixture.calls); assertNull(fixture.record); assertTrue(fixture.owner)
    }
    @Test fun clearThrowingAfterIdentityRemovalNeverRecreatesOwnerOrPolicies() {
        val fixture = Fixture().apply { fail = "clear-owner"; clearBeforeThrow = true }
        val result = ManagementExitTransaction.run(fixture)
        assertTrue(result.released); assertFalse(result.complete); assertNotNull(result.failure)
        assertFalse(fixture.owner); assertFalse(fixture.calls.contains("restore-home-and-policies"))
        assertEquals(ManagementExitTransaction.Record.RELEASED, fixture.record)
        assertEquals("stop-recovery-and-watchdog", fixture.calls.last())
    }
    @Test fun clearFailureWhileStillOwnerRestoresHomeAndPoliciesWithoutStoppingRecovery() {
        val fixture = Fixture().apply { fail = "clear-owner" }
        val result = ManagementExitTransaction.run(fixture)
        assertFalse(result.released); assertFalse(result.complete); assertTrue(fixture.owner)
        assertEquals(listOf("restore-home-and-policies", "abort"), fixture.calls.takeLast(2))
        assertFalse(fixture.calls.contains("commit")); assertFalse(fixture.calls.contains("stop-recovery-and-watchdog"))
        assertNull(fixture.record)
    }
    @Test fun policyFailureCannotSkipToClearingIdentity() {
        val fixture = Fixture().apply { fail = "release-policies-and-home" }
        assertFalse(ManagementExitTransaction.run(fixture).released)
        assertFalse(fixture.calls.contains("clear-owner")); assertNull(fixture.record)
    }
    @Test fun uncertainOwnershipDoesNotClaimSuccessOrTryToRecreateAnOwner() {
        val fixture = Fixture().apply { fail = "owner-after" }
        assertFalse(ManagementExitTransaction.run(fixture).released)
        assertFalse(fixture.owner)
        assertEquals(ManagementExitTransaction.Record.PENDING, fixture.record)
        assertFalse(fixture.calls.contains("restore-home-and-policies")); assertFalse(fixture.calls.contains("commit"))
    }
    @Test fun failedAtomicCommitKeepsPendingIdentityExitRecoverableAfterRestart() {
        val fixture = Fixture().apply { fail = "commit" }
        val result = ManagementExitTransaction.run(fixture)
        assertTrue(result.released); assertFalse(result.complete)
        assertEquals(ManagementExitTransaction.Record.PENDING, fixture.record)
        assertTrue(ManagementExitTransaction.suppressRecovery(fixture.record, fixture.owner))
        assertEquals("stop-recovery-and-watchdog", fixture.calls.last())
    }
    @Test fun aPartialLocalCleanupIsVisibleAndNeverRestoresClearedManagement() {
        val fixture = Fixture().apply { fail = "stop-recovery-and-watchdog" }
        val result = ManagementExitTransaction.run(fixture)
        assertTrue(result.released); assertFalse(result.complete)
        assertFalse(fixture.calls.contains("restore-home-and-policies"))
        assertTrue(ManagementExitTransaction.suppressRecovery(fixture.record, fixture.owner))
    }
    @Test fun aNonOwnerMustAlsoRemoveItsOwnRemainingAdminBeforeCompletion() {
        val fixture = Fixture().apply { owner = false }
        assertTrue(ManagementExitTransaction.run(fixture).complete)
        assertFalse(fixture.calls.contains("clear-owner")); assertFalse(fixture.calls.contains("release-policies-and-home"))
        assertTrue(fixture.calls.contains("remove-self-admin")); assertFalse(fixture.selfAdminActive)
    }
    @Test fun failedPolicyRestoreRemainsPendingAndRetryableWithoutClaimingExit() {
        val fixture = Fixture().apply { fail = "clear-owner"; failRestore = true }
        val result = ManagementExitTransaction.run(fixture)
        assertFalse(result.released); assertFalse(result.complete); assertTrue(fixture.owner)
        assertEquals(ManagementExitTransaction.Record.PENDING, fixture.record)
        assertFalse(fixture.calls.contains("abort")); assertFalse(fixture.calls.contains("stop-recovery-and-watchdog"))
        assertFalse(ManagementExitTransaction.suppressRecovery(fixture.record, fixture.owner))
    }
    @Test fun bootServiceDestroyAndRecoveryUseTheSamePersistedIdentityRule() {
        assertFalse(ManagementExitTransaction.suppressRecovery(null, false))
        assertFalse(ManagementExitTransaction.suppressRecovery(null, true))
        for (record in ManagementExitTransaction.Record.values()) {
            assertTrue(ManagementExitTransaction.suppressRecovery(record, false))
            // A later explicit owner setup invalidates an old exit record.
            assertFalse(ManagementExitTransaction.suppressRecovery(record, true))
        }
    }
    @Test fun backupFailureThenRetryCannotPromiseUninstallWhileOwnAdminRemainsActive() {
        val fixture = Fixture().apply {
            fail = "clear-owner"; clearBeforeThrow = true; removeAdminAsync = true
        }
        val first = ManagementExitTransaction.run(fixture)
        assertTrue(first.released); assertFalse(first.complete)
        assertFalse(fixture.owner); assertTrue(fixture.selfAdminActive)
        assertEquals(ManagementExitTransaction.Record.RELEASED, fixture.record)
        fixture.fail = null
        val retry = ManagementExitTransaction.run(fixture)
        assertTrue(retry.released); assertFalse(retry.complete)
        assertTrue(fixture.selfAdminActive)
        assertEquals(1, fixture.calls.count { it == "clear-owner" })
        assertFalse(fixture.calls.contains("restore-home-and-policies"))
        // Model the system's later ordered-broadcast completion, not a host
        // promise that removeActiveAdmin completed synchronously.
        fixture.selfAdminActive = false
        val completed = ManagementExitTransaction.run(fixture)
        assertTrue(completed.released); assertTrue(completed.complete)
    }
    @Test fun normalOwnerClearAlsoWaitsForActualAsynchronousAdminInactivity() {
        val fixture = Fixture().apply { keepAdminAfterClear = true; removeAdminAsync = true }
        val pending = ManagementExitTransaction.run(fixture)
        assertTrue(pending.released); assertFalse(pending.complete); assertNull(pending.failure)
        assertTrue(fixture.selfAdminActive)
        assertEquals(2, fixture.adminReads)
        assertEquals("stop-recovery-and-watchdog", fixture.calls.last())
        assertTrue(ManagementExitTransaction.suppressRecovery(fixture.record, fixture.owner))
        fixture.selfAdminActive = false
        assertTrue(ManagementExitTransaction.run(fixture).complete)
    }
    @Test fun aNonOwnerWithAnAsynchronouslyRemovingAdminRemainsPartialOnEveryRetry() {
        val fixture = Fixture().apply { owner = false; removeAdminAsync = true }
        repeat(2) {
            val outcome = ManagementExitTransaction.run(fixture)
            assertTrue(outcome.released); assertFalse(outcome.complete)
            assertTrue(fixture.selfAdminActive)
        }
        assertEquals(2, fixture.calls.count { it == "remove-self-admin" })
        assertFalse(fixture.calls.contains("clear-owner"))
        assertFalse(fixture.calls.contains("restore-home-and-policies"))
    }
    @Test fun selfAdminRemovalFailureStillCommitsIdentityExitButNeverClaimsCompletion() {
        val fixture = Fixture().apply { owner = false; fail = "remove-self-admin" }
        val outcome = ManagementExitTransaction.run(fixture)
        assertTrue(outcome.released); assertFalse(outcome.complete); assertNotNull(outcome.failure)
        assertTrue(fixture.selfAdminActive)
        assertEquals(2, fixture.adminReads)
        assertEquals(ManagementExitTransaction.Record.RELEASED, fixture.record)
        assertEquals("stop-recovery-and-watchdog", fixture.calls.last())
    }
    @Test fun removalThrowingAfterActualAdminRemovalIsVisibleUntilFreshRetryVerifiesCompletion() {
        val fixture = Fixture().apply {
            owner = false; fail = "remove-self-admin"; removeAdminBeforeThrow = true
        }
        val outcome = ManagementExitTransaction.run(fixture)
        assertTrue(outcome.released); assertFalse(outcome.complete); assertNotNull(outcome.failure)
        assertFalse(fixture.selfAdminActive)
        fixture.fail = null
        val retry = ManagementExitTransaction.run(fixture)
        assertTrue(retry.complete); assertNull(retry.failure)
        assertEquals(1, fixture.calls.count { it == "remove-self-admin" })
    }
    @Test fun adminReadFailuresCannotBecomeUninstallPromises() {
        for (failure in listOf("admin-before", "admin-after")) {
            val fixture = Fixture().apply { owner = false; fail = failure }
            val outcome = ManagementExitTransaction.run(fixture)
            assertTrue(outcome.released); assertFalse(outcome.complete); assertNotNull(outcome.failure)
            assertEquals(ManagementExitTransaction.Record.RELEASED, fixture.record)
            assertEquals("stop-recovery-and-watchdog", fixture.calls.last())
        }
    }
    @Test fun stillOwnedOrUnknownOwnerNeverChecksOrRemovesAnAdmin() {
        for (failure in listOf("clear-owner", "owner-before", "owner-after")) {
            val fixture = Fixture().apply { fail = failure }
            assertFalse(ManagementExitTransaction.run(fixture).released)
            assertEquals(0, fixture.adminReads)
            assertFalse(fixture.calls.contains("remove-self-admin"))
        }
    }
    @Test fun anAlreadyInactiveSelfAdminDoesNotRemoveOtherAdminsOrRepeatRemoval() {
        val fixture = Fixture().apply { owner = false; selfAdminActive = false }
        val others = fixture.otherAdmins.toSet()
        val outcome = ManagementExitTransaction.run(fixture)
        assertTrue(outcome.released); assertTrue(outcome.complete)
        assertFalse(fixture.calls.contains("remove-self-admin"))
        assertEquals(others, fixture.otherAdmins)
    }
    @Test fun selfAdminRemovalNeverRequiresOrMutatesUnrelatedAdministrators() {
        val fixture = Fixture().apply { owner = false }
        val others = fixture.otherAdmins.toSet()
        val outcome = ManagementExitTransaction.run(fixture)
        assertTrue(outcome.complete); assertFalse(fixture.selfAdminActive)
        assertEquals(others, fixture.otherAdmins)
        assertEquals(1, fixture.calls.count { it == "remove-self-admin" })
    }
    @Test fun actualAdminInactivityCannotHideUnrelatedExitCleanupFailures() {
        for (failure in listOf("commit", "stop-recovery-and-watchdog")) {
            val fixture = Fixture().apply { owner = false; selfAdminActive = false; fail = failure }
            val outcome = ManagementExitTransaction.run(fixture)
            assertTrue(outcome.released); assertFalse(outcome.complete); assertNotNull(outcome.failure)
            assertFalse(fixture.calls.contains("remove-self-admin"))
        }
    }
}
