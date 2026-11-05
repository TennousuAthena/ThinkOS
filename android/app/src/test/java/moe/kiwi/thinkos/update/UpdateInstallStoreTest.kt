package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

class UpdateInstallStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()
    private val limits = UpdateInstallStore.Limits(3, 600_000, 86_400_000, 3)
    private val start = UpdateInstallTime(1000, 1, 100_000)
    private fun id(number: Int) = number.toString(16).padStart(32, '0')
    private fun source() = temporary.newFile().apply { writeText("old signed APK stand-in") }
    private fun identity(file: File) = UpdateApkIdentity("0.1.1", 1001, UpdateCatalog.sha256(file.readBytes()), file.length(), fixture.signer)
    private fun catalog(code: Int = 2000) = fixture.verified(fixture.catalog(code.toLong()).apply {
        getJSONObject("release").put("code", code).put("version", "${code / 1_000_000}.${code / 1000 % 1000}.${code % 1000}")
    })
    private fun proof(catalog: UpdateCatalog) = UpdatePackageVerifier.Verified(File(temporary.root, "target.apk"), catalog.digest,
        catalog.document.release.sha256, catalog.document.release.size, catalog.document.release.code, catalog.document.release.signer)
    private fun prepare(store: UpdateInstallStore, file: File, code: Int = 2000, transaction: Int = 1, policy: UpdateInstallStore.Limits = limits): UpdatePending {
        val catalog = catalog(code)
        return store.prepare(catalog, proof(catalog), identity(file), file, "automatic", policy, start, id(transaction))
    }
    private fun running(value: UpdateApkIdentity) = UpdateInstallStore.Running(value.version, value.code, value.signer)

    @Test fun backupIsCompleteBeforeTheJournalAndExistingTransactionsCannotBeOverwritten() {
        val store = UpdateInstallStore(temporary.newFolder()); val file = source(); val p = prepare(store, file)
        assertArrayEquals(file.readBytes(), store.previous().readBytes()); assertEquals(p, store.snapshot().pending)
        assertThrows(IllegalArgumentException::class.java) { prepare(store, file, transaction = 2) }
        val staged = store.session(p.transaction_id, 23)
        assertEquals("INSTALLING", staged.phase); assertEquals(staged, store.session(p.transaction_id, 23))
        assertThrows(IllegalArgumentException::class.java) { store.session(p.transaction_id, 24) }
        assertEquals(UpdateInstallStore.Action.WAITING, store.startup(running(p.from), start, id(9)).action)
        assertEquals(0, store.snapshot().pending!!.attempts)
    }

    @Test fun mismatchedVerificationAndChangedSourceCannotPublishPendingOrReplaceAnOldBackup() {
        val root = temporary.newFolder(); val file = source(); val store = UpdateInstallStore(root); val c = catalog()
        File(root, "previous.apk").writeText("orphan backup")
        assertThrows(IllegalArgumentException::class.java) { store.prepare(c, proof(c).copy(catalogDigest = "f".repeat(64)), identity(file), file, "manual", limits, start, id(1)) }
        val expected = identity(file); file.writeText("bad signed APK stand-in")
        assertThrows(IllegalArgumentException::class.java) { store.prepare(c, proof(c), expected, file, "manual", limits, start, id(1)) }
        assertNull(store.snapshot().pending); assertEquals("orphan backup", File(root, "previous.apk").readText())
        assertTrue(root.list()!!.none { it.startsWith("previous-") })
    }

    @Test fun fourthMainStartupRequestsOneRollbackAndDoesNotCountFailuresPerCrash() {
        val root = temporary.newFolder(); val file = source(); var store = UpdateInstallStore(root); val p = prepare(store, file)
        store.session(p.transaction_id, 23)
        repeat(3) { index ->
            store = UpdateInstallStore(root)
            val trial = store.startup(running(p.to), start.copy(elapsed = 2000L + index), id(index+10))
            assertEquals(UpdateInstallStore.Action.TRIAL, trial.action); assertEquals(index+1, trial.pending!!.attempts)
        }
        val rollback = store.startup(running(p.to), start.copy(elapsed = 3000), id(14))
        assertEquals(UpdateInstallStore.Action.ROLLBACK, rollback.action)
        assertTrue(store.blocked(p.to.code)); assertEquals(1, store.snapshot().history.failures.single().count)
        repeat(3) { assertEquals(UpdateInstallStore.Action.ROLLBACK_PENDING, store.startup(running(p.to), start, id(20+it)).action) }
        assertEquals(4, store.snapshot().pending!!.attempts)
        store.session(p.transaction_id, 24, rollback = true)
        assertThrows(IllegalArgumentException::class.java) { store.session(p.transaction_id, 25, rollback = true) }
        assertEquals(UpdateInstallStore.Action.ROLLED_BACK, store.startup(running(p.from), start, id(30)).action)
        assertNull(store.snapshot().pending); assertFalse(store.previous().exists())
        assertEquals(1, store.snapshot().history.failures.single().count); assertEquals("rolled_back", store.snapshot().history.result!!.kind)
    }

    @Test fun foreignVersionsDoNotBecomeTrialsAndTheFourthAttemptNeverStartsAnotherTrial() {
        val store = UpdateInstallStore(temporary.newFolder()); val p = prepare(store, source())
        val other = UpdateInstallStore.Running("0.9.0", 9000, p.to.signer)
        assertEquals(UpdateInstallStore.Action.MISMATCH, store.startup(other, start, id(2)).action)
        assertEquals(0, store.snapshot().pending!!.attempts)
        val changedSigner = running(p.to).copy(signer = "b".repeat(64))
        assertEquals(UpdateInstallStore.Action.MISMATCH, store.startup(changedSigner, start, id(3)).action)
    }

    @Test fun concurrentJournalInstancesSerializeAttemptsAndOnlyOneRollbackDecision() {
        val root = temporary.newFolder(); val store = UpdateInstallStore(root); val p = prepare(store, source())
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results = (0 until 20).map { index -> pool.submit<UpdateInstallStore.Startup> {
                UpdateInstallStore(root).startup(running(p.to), start, id(50+index))
            } }.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(listOf(1, 2, 3), results.filter { it.action == UpdateInstallStore.Action.TRIAL }.map { it.pending!!.attempts }.sorted())
            assertEquals(1, results.count { it.action == UpdateInstallStore.Action.ROLLBACK })
            assertEquals(16, results.count { it.action == UpdateInstallStore.Action.ROLLBACK_PENDING })
            assertEquals(4, store.snapshot().pending!!.attempts)
            assertEquals(1, store.snapshot().history.failures.single().count)
        } finally { pool.shutdownNow() }
    }

    @Test fun healthWaitStartsAfterBothReadinessSignalsAndRequiresTheActualTargetIdentity() {
        val root = temporary.newFolder(); val store = UpdateInstallStore(root); val p = prepare(store, source())
        store.session(p.transaction_id, 23); val trial = store.startup(running(p.to), start, id(10)).pending!!
        var now = start; val gate = UpdateHealthGate(trial) { now }
        gate.frame(); now = start.copy(elapsed = 6000); assertNull(gate.proof()); gate.catalog()
        now = start.copy(elapsed = 601000); assertNull(gate.proof())
        now = start.copy(elapsed = 606000); val proof = gate.proof()!!
        assertFalse(store.healthy(p.to.copy(sha256 = "f".repeat(64)), proof)); assertNotNull(store.snapshot().pending)
        assertTrue(store.healthy(p.to, proof)); assertNull(store.snapshot().pending); assertFalse(store.previous().exists())
        assertEquals("updated", store.snapshot().history.result!!.kind)
        assertEquals(p.transaction_id, store.startup(running(p.to), now, id(11)).clearRecovery)
        store.acknowledgeRecovery(p.transaction_id)
        assertNull(store.startup(running(p.to), now, id(12)).clearRecovery)
    }

    @Test fun staleProofWallClockChangesAndActivityReadinessRepetitionCannotShortenHealth() {
        val store = UpdateInstallStore(temporary.newFolder()); val p = prepare(store, source()); store.session(p.transaction_id, 2)
        var now = start; val one = store.startup(running(p.to), now, id(10)).pending!!; val gate = UpdateHealthGate(one) { now }
        gate.frame(); gate.catalog(); now = start.copy(elapsed = start.elapsed + 5, wall = Long.MAX_VALUE); assertNull(gate.proof())
        now = start.copy(elapsed = start.elapsed - 1); assertNull(gate.proof())
        now = start.copy(elapsed = start.elapsed + 600000, boot = 2); assertNull(gate.proof())
        now = start.copy(elapsed = start.elapsed + 600000); gate.frame(); gate.catalog(); assertEquals(0L, gate.remaining())
        store.startup(running(p.to), now, id(11)); assertFalse(store.healthy(p.to, gate.proof()!!))
    }

    @Test fun committedHealthyHistoryFinishesInterruptedCleanupBeforeAnyAttemptIncrement() {
        val root = temporary.newFolder(); val store = UpdateInstallStore(root); val file = source(); val p = prepare(store, file)
        store.session(p.transaction_id, 2); var trial = p
        repeat(3) { trial = store.startup(running(p.to), start, id(10+it)).pending!! }
        var now = start; val gate = UpdateHealthGate(trial) { now }; gate.frame(); gate.catalog(); now = start.copy(elapsed = start.elapsed + 600000)
        assertTrue(store.healthy(p.to, gate.proof()!!))
        // Simulate power loss after history fsync but before either unlink.
        File(root, "pending.json").writeText(UpdateModels.writeUpdatePending(trial).toString()); File(root, "previous.apk").writeBytes(file.readBytes())
        val recovered = UpdateInstallStore(root).startup(running(p.to), now, id(20))
        assertEquals(UpdateInstallStore.Action.COMPLETED, recovered.action); assertEquals(p.transaction_id, recovered.clearRecovery)
        assertNull(store.snapshot().pending); assertFalse(store.blocked(p.to.code))
        // The other cleanup cut: pending was already unlinked when power went.
        File(root, "previous.apk").writeBytes(file.readBytes())
        assertEquals(UpdateInstallStore.Action.NONE, UpdateInstallStore(root).startup(running(p.to), now, id(21)).action)
        assertFalse(store.previous().exists())
    }

    @Test fun anInstallFailureIsCountedOnceAndTheThirdIndependentTransactionBlocksTheVersion() {
        val store = UpdateInstallStore(temporary.newFolder()); val file = source()
        repeat(3) { index ->
            val p = prepare(store, file, transaction = index+1)
            assertTrue(store.fail(p.transaction_id, "install", start)); assertFalse(store.fail(p.transaction_id, "install", start))
            assertEquals(index+1, store.snapshot().history.failures.single().count)
        }
        assertTrue(store.autoBlocked(2000, 3)); assertFalse(store.blocked(2000))
        assertThrows(IllegalArgumentException::class.java) { prepare(store, file, transaction = 4) }
        val c = catalog(); val manual = store.prepare(c, proof(c), identity(file), file, "manual", limits, start, id(4))
        assertTrue(store.cancelUnsubmitted(manual.transaction_id)); assertEquals(3, store.snapshot().history.failures.single().count)
    }

    @Test fun rollbackFailureDoesNotDoubleCountTheCrashLoopOrAuthorizeASecondRollback() {
        val store = UpdateInstallStore(temporary.newFolder()); val p = prepare(store, source()); store.session(p.transaction_id, 2)
        repeat(4) { store.startup(running(p.to), start, id(10+it)) }
        assertTrue(store.fail(p.transaction_id, "rollback_failed", start, rollback = true))
        assertEquals(1, store.snapshot().history.failures.single().count); assertTrue(store.blocked(p.to.code))
        assertEquals("rollback_failed", store.snapshot().history.result!!.kind); assertNull(store.snapshot().pending)
    }

    @Test fun blacklistsOutliveTheBoundedRecentHistory() {
        val store = UpdateInstallStore(temporary.newFolder()); val file = source()
        val first = prepare(store, file)
        repeat(4) { store.startup(running(first.to), start, id(10+it)) }
        store.startup(running(first.from), start, id(20))
        repeat(64) { index -> val p = prepare(store, file, code = 2001+index, transaction = index+2); store.fail(p.transaction_id, "install", start) }
        assertEquals(64, store.snapshot().history.failures.size); assertFalse(store.snapshot().history.failures.any { it.code == 2000 })
        assertTrue(UpdateInstallStore(store.previous().parentFile!!).blocked(2000))
        assertThrows(IllegalArgumentException::class.java) { prepare(store, file, transaction = 100) }
    }

    @Test fun corruptRecordsAndSymlinkDirectoriesFailClosedWithoutTouchingOutsideFiles() {
        val root = temporary.newFolder(); val store = UpdateInstallStore(root)
        for (text in listOf("{}", "", "x".repeat(UpdateInstallStore.MAX_BYTES+1), "{\"schema\":1,\"schema\":1}")) {
            File(root, "pending.json").writeText(text); assertThrows(UpdateInstallStore.Corrupt::class.java) { store.snapshot() }
        }
        File(root, "pending.json").delete(); val outside = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(File(root, "previous.apk").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { prepare(store, source()) }; assertEquals("keep", outside.readText())
        val link = File(temporary.root, "linked"); Files.createSymbolicLink(link.toPath(), root.toPath())
        assertThrows(IOException::class.java) { UpdateInstallStore(link).snapshot() }
    }

    @Test fun updateNoticesUseMonotonicTimeAndExpireWithoutInventedCrossBootAge() {
        val result = UpdateInstallResult(id(1), "updated", 2000, "0.2.0", start, 60000, emptyMap(), needs_recovery_clear = false)
        assertNotNull(UpdateInstallStore.recent(result, start.copy(elapsed = start.elapsed+59999, wall = Long.MAX_VALUE)))
        assertNull(UpdateInstallStore.recent(result, start.copy(elapsed = start.elapsed+60000)))
        assertNull(UpdateInstallStore.recent(result, start.copy(boot = 2, wall = null)))
        assertNotNull(UpdateInstallStore.recent(result, start.copy(boot = 2, wall = start.wall!!+1000)))
    }
}
