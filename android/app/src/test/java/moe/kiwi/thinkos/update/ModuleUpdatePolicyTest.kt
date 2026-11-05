package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** No Android/socket/root execution: policy and durable intent safety only. */
class ModuleUpdatePolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val transaction = "a".repeat(32)
    private val digest = "b".repeat(64)
    private val signer = "c".repeat(64)
    private fun module(id: String = "timesync", policy: String = "always", code: Int = 2) = UpdateModule(
        id, if (id == "thinkos-immersive") "vector" else "magisk", "v$code", code, policy, "d".repeat(64), 10,
        listOf("https://example.invalid/module.zip"), listOf("https://example.invalid/module.manifest"))
    private fun root(code: Int = 1, floor: Int = 0, staged: Int = 0, active: Boolean? = true,
                     transactionOpen: Boolean = false, phase: Int = 0, id: String = transaction) =
        UpdaterStatusDocument(1, 2, transactionOpen, id, phase, 0, false,
            listOf(UpdaterModuleStatus("timesync", code, floor, staged, active)))
    private fun pending(selected: List<ModuleUpdateTarget> = listOf(ModuleUpdateTarget("timesync", 2))) = ModuleUpdatePending(
        schema = 1, id = transaction, root_id = "e".repeat(32), previous_root_id = "",
        catalog_digest = digest, channel = "stable",
        from = UpdateApkIdentity("0.1.0", 1000, "1".repeat(64), 10, signer),
        to = UpdateApkIdentity("0.2.0", 2000, "2".repeat(64), 20, signer),
        mode = "manual", phase = "ACTIVE", operation = "", selected = selected, staged = emptyList(),
        created = UpdateInstallTime(1000, 7, null), attempts = 0, max_attempts = 3, health_ms = 600_000, poll_ms = 5000,
        reboot_boot = 7)

    @Test fun apkOnlyRootAdmissionIsBoundToVerifiedReadyCatalogKeysAndIdleOrOwnedTransaction() {
        assertTrue(ModuleUpdatePolicy.rootAdmission(digest, digest, 2, false, null, true, false))
        assertFalse(ModuleUpdatePolicy.rootAdmission(digest, null, 2, false, null, true, false))
        assertFalse(ModuleUpdatePolicy.rootAdmission(digest, "f".repeat(64), 2, false, null, true, false))
        assertFalse(ModuleUpdatePolicy.rootAdmission(digest, digest, 0, false, null, true, false))
        assertFalse(ModuleUpdatePolicy.rootAdmission(digest, digest, 2, true, null, true, false))
        assertFalse(ModuleUpdatePolicy.rootAdmission(digest, digest, 2, false, null, false, false))
        assertTrue(ModuleUpdatePolicy.rootAdmission(digest, digest, 2, true, digest, true, false))
        assertTrue(ModuleUpdatePolicy.bootReady(pending(emptyList()).copy(reboot_boot = null), 7))
    }

    @Test fun alwaysInstallsMissingButInstalledAndDisabledFeatureDoNot() {
        assertEquals(1, ModuleUpdatePolicy.select(listOf(module()), root(code = 0), emptySet()).size)
        assertTrue(ModuleUpdatePolicy.select(listOf(module(policy = "installed")), root(code = 0), emptySet()).isEmpty())
        assertTrue(ModuleUpdatePolicy.select(listOf(module(policy = "feature")), root(), emptySet()).isEmpty())
        assertEquals(1, ModuleUpdatePolicy.select(listOf(module(policy = "feature")), root(), setOf("timesync")).size)
    }

    @Test fun unknownInstalledStateKeylessDaemonAndForeignTransactionsFailClosed() {
        for (status in listOf(root(code = -1), root().copy(modules = emptyList()), root().copy(keys = 0),
            root(transactionOpen = true), root().let { it.copy(modules = it.modules + it.modules) })) {
            assertThrows(IllegalArgumentException::class.java) { ModuleUpdatePolicy.select(listOf(module()), status, emptySet()) }
        }
    }

    @Test fun installedAndRootFloorBothPreventStagingOlderOrEqualCode() {
        assertTrue(ModuleUpdatePolicy.select(listOf(module()), root(code = 2), emptySet()).isEmpty())
        assertTrue(ModuleUpdatePolicy.select(listOf(module()), root(code = 1, floor = 2), emptySet()).isEmpty())
        assertEquals(1, ModuleUpdatePolicy.select(listOf(module(code = 3)), root(code = 1, floor = 2), emptySet()).size)
    }

    @Test fun moduleChangeRequiresRealLaterBootButApkOnlyNeverRequestsReboot() {
        val value = pending()
        assertFalse(ModuleUpdatePolicy.bootReady(value, null))
        assertFalse(ModuleUpdatePolicy.bootReady(value, 7))
        assertFalse(ModuleUpdatePolicy.bootReady(value, 6))
        assertTrue(ModuleUpdatePolicy.bootReady(value, 8))
        assertFalse(ModuleUpdatePolicy.bootReady(value.copy(created = value.created.copy(boot = null)), 8))
        assertFalse(ModuleUpdatePolicy.bootReady(value.copy(reboot_boot = null), 8))
        assertFalse(ModuleUpdatePolicy.bootReady(value.copy(reboot_boot = 9), 8))
        assertFalse(ModuleUpdatePolicy.bootReady(value.copy(reboot_boot = 9), 9))
        assertTrue(ModuleUpdatePolicy.bootReady(value.copy(reboot_boot = 9), 10))
        assertTrue(ModuleUpdatePolicy.bootReady(pending(emptyList()), 7))
    }

    @Test fun stagedIsNotInstalledAndMissingActiveEvidenceIsNeverHealthy() {
        val value = pending().copy(staged = listOf("timesync"))
        val actual = root(code = 2, staged = 2, transactionOpen = true, phase = 4, id = value.root_id)
        assertTrue(ModuleUpdatePolicy.modulesActive(value, actual))
        for (bad in listOf(actual.copy(id = transaction), actual.copy(phase = 2), actual.copy(phase = 3), actual.copy(transaction = false),
            actual.copy(rollback_attempted = true), actual.copy(modules = listOf(actual.modules.single().copy(code = 1))),
            actual.copy(modules = listOf(actual.modules.single().copy(active = null))),
            actual.copy(modules = listOf(actual.modules.single().copy(active = false))))) {
            assertFalse(ModuleUpdatePolicy.modulesActive(value, bad))
        }
    }

    @Test fun sameApkRequiresFullIdentityEqualityAndIntentNeverChangesTarget() {
        val value = pending()
        assertThrows(IllegalArgumentException::class.java) {
            ModuleUpdateStore.validate(value.copy(to = value.from.copy(sha256 = "3".repeat(64))))
        }
        ModuleUpdateStore.validate(value.copy(to = value.from))
        assertThrows(IllegalArgumentException::class.java) {
            ModuleUpdateStore.validate(value.copy(operation = "STAGE", operation_id = "unknown"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ModuleUpdateStore.validate(value.copy(root_id = "", operation = "INSTALL_APK"))
        }
    }

    @Test fun healthRequiresBothReadinessSignalsFullMonotonicWindowAndMatchingLiveRootEvidence() {
        val value = pending().copy(phase = "TRIAL", staged = listOf("timesync"), attempts = 1,
            trial_id = "f".repeat(32), trial_started = UpdateInstallTime(2000, 8, null))
        val actual = root(code = 2, staged = 2, transactionOpen = true, phase = 4, id = value.root_id)
        val ready = UpdateInstallTime(3000, 8, null)
        val now = UpdateInstallTime(603000, 8, 1)
        assertTrue(ModuleUpdatePolicy.healthReady(value, actual, ready, now, true, true))
        assertFalse(ModuleUpdatePolicy.healthReady(value, actual, ready, now.copy(elapsed = 602999), true, true))
        assertFalse(ModuleUpdatePolicy.healthReady(value, actual, ready, now, false, true))
        assertFalse(ModuleUpdatePolicy.healthReady(value, actual, ready, now, true, false))
        assertFalse(ModuleUpdatePolicy.healthReady(value, actual, ready, now.copy(boot = 9), true, true))
        assertFalse(ModuleUpdatePolicy.healthReady(value, actual, ready, now.copy(elapsed = 1), true, true))
        assertFalse(ModuleUpdatePolicy.healthReady(value, actual.copy(phase = 3), ready, now, true, true))
    }

    @Test fun unknownSubmittedOperationIsNotTerminalOrAnInstallationSuccess() {
        val value = pending().copy(phase = "UNKNOWN", operation = "INSTALL_APK")
        ModuleUpdateStore.validate(value)
        assertFalse(ModuleUpdateStore.terminal(value))
        assertFalse(ModuleUpdateStore.terminal(value.copy(phase = "FAILED")))
        assertTrue(ModuleUpdateStore.terminal(value.copy(phase = "FAILED", operation = "")))
    }

    @Test fun crashAfterBeginIntentBeforeSendIsTerminalWithoutReplayingBegin() {
        val value = pending().copy(root_id = "", previous_root_id = transaction, phase = "PREPARED", operation = "BEGIN")
        val rejected = root(transactionOpen = false, id = transaction)
        assertEquals(ModuleUpdatePolicy.Begin.NOT_ACCEPTED, ModuleUpdatePolicy.recoverBegin(value, rejected))
        val terminal = value.copy(phase = "CANCELLED", operation = "", reason = "interrupted")
        ModuleUpdateStore.validate(terminal)
        assertTrue(ModuleUpdateStore.terminal(terminal))
        assertEquals(ModuleUpdatePolicy.Begin.ADOPT,
            ModuleUpdatePolicy.recoverBegin(value, root(transactionOpen = true, phase = 1, id = "e".repeat(32))))
        assertEquals(ModuleUpdatePolicy.Begin.CONFLICT,
            ModuleUpdatePolicy.recoverBegin(value, root(transactionOpen = true, phase = 3, id = "e".repeat(32))))
        assertEquals(ModuleUpdatePolicy.Begin.CONFLICT,
            ModuleUpdatePolicy.recoverBegin(value, root(transactionOpen = true, phase = 1, id = transaction)))
    }

    @Test fun rollbackIntentResumesOnlyWhenThisRootTransactionProvesRollbackNeverStarted() {
        val value = pending().copy(operation = "ROLLBACK")
        val actual = root(transactionOpen = true, phase = 2, id = value.root_id)
        for (phase in listOf(1, 2, 6)) assertEquals(ModuleUpdatePolicy.Rollback.START,
            ModuleUpdatePolicy.recoverRollback(value, actual.copy(phase = phase)))
        for (phase in listOf(1, 2, 5, 6)) assertEquals(ModuleUpdatePolicy.Rollback.WAIT,
            ModuleUpdatePolicy.recoverRollback(value, actual.copy(phase = phase, rollback_attempted = true)))
        assertEquals(ModuleUpdatePolicy.Rollback.WAIT, ModuleUpdatePolicy.recoverRollback(value, actual.copy(phase = 3)))
        assertEquals(ModuleUpdatePolicy.Rollback.WAIT, ModuleUpdatePolicy.recoverRollback(value, actual.copy(keys = 0)))
        assertEquals(ModuleUpdatePolicy.Rollback.CONFLICT, ModuleUpdatePolicy.recoverRollback(value, actual.copy(id = transaction)))
        assertEquals(ModuleUpdatePolicy.Rollback.TERMINAL,
            ModuleUpdatePolicy.recoverRollback(value, actual.copy(transaction = false, result = 2)))
        assertEquals(ModuleUpdatePolicy.Rollback.WAIT,
            ModuleUpdatePolicy.recoverRollback(value, actual.copy(transaction = false, result = 3)))
    }

    @Test fun anExternalBootBeforeOurRebootIntentCannotAcknowledgeRebootOrEarnModuleHealth() {
        val value = pending().copy(operation = "REBOOT", reboot_boot = 9)
        val actual = root(code = 2, staged = 2, transactionOpen = true, phase = 4, id = value.root_id)
        assertFalse(ModuleUpdatePolicy.rebootAcknowledged(value, actual, 8))
        assertFalse(ModuleUpdatePolicy.rebootAcknowledged(value, actual, 9))
        assertTrue(ModuleUpdatePolicy.rebootAcknowledged(value, actual, 10))
        assertFalse(ModuleUpdatePolicy.rebootAcknowledged(value, actual.copy(phase = 2), 10))
        assertFalse(ModuleUpdatePolicy.rebootAcknowledged(value, actual.copy(id = transaction), 10))
        assertFalse(ModuleUpdatePolicy.modulesActive(value, actual.copy(phase = 2)))
    }

    @Test fun unconfirmedHealthyIntentNeverExemptsNewMainProcessesFromAttemptCounting() {
        val value = pending().copy(phase = "HEALTHY_PENDING", operation = "HEALTHY", attempts = 2)
        val third = ModuleUpdatePolicy.startup(value, UpdateInstallTime(2000, 8, null), "f".repeat(32))
        assertEquals(3, third.attempts)
        assertEquals("HEALTHY_PENDING", third.phase)
        assertEquals("HEALTHY", third.operation)
        val fourth = ModuleUpdatePolicy.startup(third, UpdateInstallTime(3000, 8, null), "1".repeat(32))
        assertEquals(4, fourth.attempts)
        assertEquals("UNKNOWN", fourth.phase)
        assertEquals("crash_loop", fourth.reason)
        assertFalse(ModuleUpdateStore.terminal(fourth))
    }

    @Test fun explicitRejectedHealthCanRollbackOnlyItsStillActiveAuthenticatedTransaction() {
        val value = pending().copy(phase = "HEALTHY_PENDING", operation = "HEALTHY")
        val actual = root(transactionOpen = true, phase = 4, id = value.root_id)
        assertTrue(ModuleUpdatePolicy.rollbackRejectedHealth(value, actual))
        for (bad in listOf(actual.copy(transaction = false, result = 1), actual.copy(id = transaction),
            actual.copy(rollback_attempted = true), actual.copy(phase = 3), actual.copy(keys = 0))) {
            assertFalse(ModuleUpdatePolicy.rollbackRejectedHealth(value, bad))
        }
    }

    @Test fun lostHealthyReceiptNeedsExactCurrentApkAndHistoricalFloorWithoutUndoingLaterUserDisable() {
        val value = pending().copy(phase = "HEALTHY_PENDING", operation = "HEALTHY")
        val actual = root(code = 2, floor = 2, staged = 2, active = true, transactionOpen = false, phase = 0, id = value.root_id).copy(result = 1)
        assertTrue(ModuleUpdatePolicy.completedHealthy(value, actual, value.to))
        assertFalse(ModuleUpdatePolicy.completedHealthy(value, actual, value.from))
        assertFalse(ModuleUpdatePolicy.completedHealthy(value, actual, value.to.copy(sha256 = "9".repeat(64))))
        assertFalse(ModuleUpdatePolicy.completedHealthy(value, actual, value.to.copy(code = 3000, version = "0.3.0")))
        assertFalse(ModuleUpdatePolicy.completedHealthy(value, actual.copy(id = transaction), value.to))
        assertFalse(ModuleUpdatePolicy.completedHealthy(value, actual.copy(modules = listOf(actual.modules.single().copy(floor = 1))), value.to))
        assertTrue(ModuleUpdatePolicy.completedHealthy(value, actual.copy(modules = listOf(actual.modules.single().copy(active = false))), value.to))
    }

    @Test fun durableRecordKeepsOutstandingOperationAfterReopenAndRejectsTampering() {
        val directory = temporary.newFolder()
        val value = pending().copy(operation = "INSTALL_APK")
        UpdateFiles(directory, "fixture.lock").locked { root ->
            UpdateFiles.write(root, "module-pending.json", UpdateModels.writeModuleUpdatePending(value).toString().toByteArray(), 65536)
        }
        val store = ModuleUpdateStore(directory)
        assertEquals("INSTALL_APK", store.snapshot()!!.operation)
        assertThrows(IllegalArgumentException::class.java) {
            store.change(value.id) { it.copy(to = it.to.copy(sha256 = "4".repeat(64))) }
        }
        assertEquals(value, ModuleUpdateStore(directory).snapshot())
        store.change(value.id) { it.copy(phase = "FAILED", operation = "", reason = "rolled_back") }
        assertTrue(ModuleUpdateStore(directory).blocked(value.catalog_digest))
        assertFalse(ModuleUpdateStore(directory).blocked("f".repeat(64)))
    }

    @Test fun unavailableOptionalUpdaterAllowsNewApkFallbackButActiveOrCompletedTransactionHasNoDuplicateActions() {
        val fixture = UpdateFixture()
        val catalog = fixture.verified(fixture.catalog().apply { getJSONObject("release").getJSONArray("modules").put(fixture.module()) })
        val status = UpdateController.Status(UpdateStateDocument(1, "stable", "READY", catalog.document.release.size, catalog.document.release.size,
            catalog_digest = catalog.digest), configured = true, installable = true,
            offer = UpdateSession.Offer(catalog.digest, catalog.document.release, null))
        val environment = UpdateSession.Environment(moe.kiwi.thinkos.core.Attention.AWAKE, false, false)
        val policy = UpdatePolicy(true, "stable", listOf("https://example.invalid/catalog.json"), 600, 3600, 24000, 2000, 60, 3600, 5000, 2024, 1000, 1000)
        fun read(view: ModuleUpdateRuntime.View) = UpdatePublicStatus.read(status, environment, policy, "0.1.1", 1001,
            java.util.Locale.SIMPLIFIED_CHINESE, view)
        val fallback = read(ModuleUpdateRuntime.View())
        assertTrue(fallback.can_install)
        assertEquals("requires_updater", fallback.components.single().state)
        for (view in listOf(ModuleUpdateRuntime.View(active = true), ModuleUpdateRuntime.View(active = true, error = "interrupted"))) {
            val blocked = read(view)
            assertFalse(blocked.can_check); assertFalse(blocked.can_download); assertFalse(blocked.can_install)
        }
        val complete = read(ModuleUpdateRuntime.View(completedDigest = catalog.digest))
        assertEquals("up_to_date", complete.phase)
        assertFalse(complete.can_install); assertTrue(complete.can_check)
        assertEquals(complete, UpdateModels.readUpdateStatusDocument(UpdateModels.writeUpdateStatusDocument(complete)))
    }
}
