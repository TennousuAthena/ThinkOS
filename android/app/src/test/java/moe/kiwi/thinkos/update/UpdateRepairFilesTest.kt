package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

/** Filesystem/lock recovery regressions. No Android, installation, network or production roots. */
class UpdateRepairFilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()
    private val now = UpdateInstallTime(5_000, 1, 100_000)
    private fun identity() = UpdateApkIdentity("0.1.1", 1001, "a".repeat(64), 20, fixture.signer)
    private fun evidence() = UpdateRepairFiles.Evidence(identity(), "absent", now)
    private fun raw(root: File) = File(root, "pending.json")
    private fun history(root: File) = File(root, "install-history.json").apply {
        writeText(UpdateModels.writeUpdateInstallHistory(UpdateInstallHistory(1, emptyList())).toString())
    }
    private fun corrupt(root: File, bytes: ByteArray = "{\"schema\":".toByteArray()): UpdateInstallStore {
        raw(root).writeBytes(bytes)
        history(root)
        File(root, "previous.apk").writeText("unverified predecessor; never execute")
        return UpdateInstallStore(root)
    }
    private fun stamp(root: File, name: String) = UpdateRepairFiles.stamp(root.canonicalFile, name)
    private fun archive(root: File, id: String) = File(root, "install-quarantine/$id")

    @Test fun emptyTruncatedDuplicateAndOversizedPendingHaveAnActionableIsolationPath() {
        val malformed = listOf(ByteArray(0), "{\"schema\":".toByteArray(),
            "{\"schema\":1,\"schema\":1}".toByteArray(), ByteArray(UpdateInstallStore.MAX_BYTES + 1) { ' '.code.toByte() })
        for (bytes in malformed) {
            val root = temporary.newFolder()
            val store = corrupt(root, bytes)
            val oldPrevious = File(root, "previous.apk").readBytes()
            val history = File(root, "install-history.json").readBytes()
            val snapshot = store.repairSnapshot()
            assertTrue(snapshot.corruptPending); assertFalse(snapshot.corruptHistory)
            val isolated = store.isolateCorrupt(snapshot, ::evidence)
            assertEquals("ISOLATED", isolated.state)
            assertFalse(raw(root).exists()); assertFalse(File(root, "previous.apk").exists())
            assertArrayEquals(bytes, File(archive(root, isolated.id), "pending.json").readBytes())
            assertArrayEquals(oldPrevious, File(archive(root, isolated.id), "previous.apk").readBytes())
            assertArrayEquals(history, File(root, "install-history.json").readBytes())
            assertNull(store.snapshot().pending)
        }
    }

    @Test fun anotherCorruptRecordOrAReplacedInodeCannotBeRemovedWithAStaleTicket() {
        for (replace in listOf(false, true)) {
            val root = temporary.newFolder(); val store = corrupt(root)
            val before = store.repairSnapshot()
            val replacement = "different incomplete JSON".toByteArray()
            if (replace) {
                val file = File(root, "replacement").apply { writeBytes(replacement) }
                Files.move(file.toPath(), raw(root).toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            } else raw(root).writeBytes(replacement)
            var proved = false
            assertThrows(UpdateRepairFiles.Changed::class.java) {
                store.isolateCorrupt(before, { proved = true; evidence() })
            }
            assertFalse(proved)
            assertArrayEquals(replacement, raw(root).readBytes())
            assertFalse(File(root, UpdateRepairFiles.MARKER).exists())
        }
    }

    @Test fun aConcurrentValidNewTransactionIsRetainedWithoutCallingRecoveryEvidence() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val before = store.repairSnapshot()
        val catalog = fixture.verified()
        val to = catalog.document.release.let { UpdateApkIdentity(it.version, it.code, it.sha256, it.size, it.signer) }
        val pending = UpdatePending(1, "2".repeat(32), "PREPARED", "stable", catalog.digest, catalog.document.seq,
            identity(), to, "manual", 0, 3, 600_000, 86_400_000, 3, now, catalog.document.release.notes)
        raw(root).writeText(UpdateModels.writeUpdatePending(pending).toString())
        var called = false
        assertThrows(UpdateRepairFiles.Changed::class.java) { store.isolateCorrupt(before, { called = true; evidence() }) }
        assertFalse(called)
        assertEquals(pending, store.snapshot().pending)
        assertFalse(File(root, UpdateRepairFiles.MARKER).exists())
    }

    @Test fun pendingChangesDuringTheExternalProofAreRejectedBeforeCreatingAnArchive() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val before = store.repairSnapshot()
        assertThrows(UpdateRepairFiles.Changed::class.java) {
            store.isolateCorrupt(before, { raw(root).writeText("changed while checking sessions"); evidence() })
        }
        assertEquals("changed while checking sessions", raw(root).readText())
        assertFalse(File(root, "install-quarantine").exists())
        assertFalse(File(root, UpdateRepairFiles.MARKER).exists())
    }

    @Test fun evidenceFailureCannotMoveRawFilesAndHistoryDamageIsNotResetToEmpty() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val original = raw(root).readBytes()
        assertThrows(IOException::class.java) { store.isolateCorrupt(store.repairSnapshot(), { throw IOException("PM unavailable") }) }
        assertArrayEquals(original, raw(root).readBytes())
        assertTrue(File(root, "previous.apk").isFile)
        File(root, "install-history.json").writeText("{bad history")
        val badHistory = store.repairSnapshot()
        assertTrue(badHistory.corruptHistory)
        assertThrows(UpdateInstallStore.Corrupt::class.java) { store.isolateCorrupt(badHistory, ::evidence) }
        assertEquals("{bad history", File(root, "install-history.json").readText())
        assertArrayEquals(original, raw(root).readBytes())
    }

    @Test fun interruptionAfterMovingPreviousResumesTheSameArchiveAndNeverUsesThatApk() {
        val root = temporary.newFolder(); var store = corrupt(root)
        val previous = File(root, "previous.apk").readBytes()
        var activeChecks = 0
        assertThrows(IOException::class.java) {
            store.isolateCorrupt(store.repairSnapshot(), ::evidence) {
                activeChecks++
                if (activeChecks == 3) throw IOException("simulated process interruption")
            }
        }
        val marker = requireNotNull(UpdateRepairFiles.marker(root.canonicalFile))
        assertEquals("PREPARED", marker.state)
        assertTrue(raw(root).isFile); assertFalse(File(root, "previous.apk").exists())
        assertArrayEquals(previous, File(archive(root, marker.id), "previous.apk").readBytes())
        store = UpdateInstallStore(root)
        val completed = store.isolateCorrupt(store.repairSnapshot(), ::evidence)
        assertEquals(marker.id, completed.id)
        assertEquals("ISOLATED", completed.state)
        assertArrayEquals(previous, File(archive(root, marker.id), "previous.apk").readBytes())
        assertFalse(raw(root).exists())
    }

    @Test fun interruptedLastMarkerWriteCanFinishAfterTheArchiveReceiptAlreadyCommitted() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val complete = store.isolateCorrupt(store.repairSnapshot(), ::evidence)
        UpdateFiles.write(root.canonicalFile, UpdateRepairFiles.MARKER,
            UpdateModels.writeUpdateRepairRecord(complete.copy(state = "PREPARED")).toString().toByteArray(), 16 * 1024)
        val resumed = store.isolateCorrupt(store.repairSnapshot(), ::evidence)
        assertEquals(complete, resumed)
        assertEquals(1, File(root, "install-quarantine").list()!!.size)
    }

    @Test fun recoveryNeverClearsIndependentBlacklistsOrSequenceFloorsOrItsRawEvidence() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val failure = UpdateInstallFailure(2000, "0.2.0", 1, true, "f".repeat(32), "crash_loop")
        File(root, "failure-2000.json").writeText(UpdateModels.writeUpdateFailureDocument(UpdateFailureDocument(1, failure)).toString())
        val ledger = File(root, "ledger").apply { mkdir() }
        val floor = File(ledger, "sequences.json").apply { writeText("untouched independent authority") }
        val failureBytes = File(root, "failure-2000.json").readBytes()
        val result = store.isolateCorrupt(store.repairSnapshot(), ::evidence)
        store.startup(UpdateInstallStore.Running("0.1.1", 1001, fixture.signer), now, "9".repeat(32))
        assertTrue(store.blocked(2000))
        assertArrayEquals(failureBytes, File(root, "failure-2000.json").readBytes())
        assertEquals("untouched independent authority", floor.readText())
        assertTrue(File(archive(root, result.id), "pending.json").isFile)
        assertTrue(File(archive(root, result.id), "previous.apk").isFile)
        assertFalse(store.repairAllowsRecoveryClear("0".repeat(32)))
    }

    @Test fun onlyANewPreparedTransactionWithRealTrialHealthMayRetireTheRepairFence() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val installed = temporary.newFile().apply { writeText("installed APK stand-in") }
        val current = UpdateApkIdentity("0.1.1", 1001, UpdateCatalog.sha256(installed.readBytes()), installed.length(), fixture.signer)
        val record = store.isolateCorrupt(store.repairSnapshot(), { evidence().copy(current = current) })
        val id = "8".repeat(32)
        val catalog = fixture.verified()
        val release = catalog.document.release
        val verified = UpdatePackageVerifier.Verified(File(root, "target.apk"), catalog.digest,
            release.sha256, release.size, release.code, release.signer)
        val pending = store.prepare(catalog, verified, current, installed, "manual",
            UpdateInstallStore.Limits(3, 600_000, 86_400_000, 3), now, id)
        assertFalse(store.repairAllowsRecoveryClear("7".repeat(32)))
        assertTrue(store.repairAllowsRecoveryClear(id))
        store.session(id, 123)
        val startup = store.startup(UpdateInstallStore.Running(pending.to.version, pending.to.code, pending.to.signer), now, "9".repeat(32))
        val trial = requireNotNull(startup.pending)
        val proof = UpdateHealthGate.Proof(id, requireNotNull(trial.trial_id), now, now.copy(elapsed = now.elapsed + 600_000))
        assertTrue(store.healthy(pending.to, proof))
        assertTrue(File(root, UpdateRepairFiles.MARKER).exists())
        var recoveryClears = 0
        assertFalse(store.acknowledgeRecovery("7".repeat(32)) { recoveryClears++; true })
        assertEquals(0, recoveryClears)
        assertTrue(File(root, UpdateRepairFiles.MARKER).exists())
        assertFalse(store.acknowledgeRecovery(id) { recoveryClears++; false })
        assertTrue(File(root, UpdateRepairFiles.MARKER).exists())
        assertTrue(requireNotNull(store.snapshot().history.result).needs_recovery_clear)
        assertTrue(store.acknowledgeRecovery(id) { recoveryClears++; true })
        assertEquals(2, recoveryClears)
        assertFalse(File(root, UpdateRepairFiles.MARKER).exists())
        assertFalse(requireNotNull(store.snapshot().history.result).needs_recovery_clear)
        assertTrue(File(archive(root, record.id), "pending.json").exists())
        assertTrue(File(archive(root, record.id), "previous.apk").exists())
    }

    @Test fun aCrashAfterFenceRetirementBeforeHistoryAckCanReplayTheHealthyAcknowledgement() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val record = store.isolateCorrupt(store.repairSnapshot(), ::evidence)
        val id = "8".repeat(32)
        UpdateRepairFiles.bind(root.canonicalFile, id, identity())
        val result = UpdateInstallResult(id, "updated", 2000, "0.2.0", now, 86_400_000,
            mapOf("zh-Hans" to ""), needs_recovery_clear = true)
        history(root).writeText(UpdateModels.writeUpdateInstallHistory(UpdateInstallHistory(1, emptyList(), result)).toString())
        // Correct-order crash boundary: unlink is durable; needs_recovery_clear is still true.
        UpdateRepairFiles.acknowledged(root.canonicalFile, id)
        val restarted = UpdateInstallStore(root)
        val startup = restarted.startup(UpdateInstallStore.Running("0.2.0", 2000, fixture.signer), now, "9".repeat(32))
        assertEquals(id, startup.clearRecovery)
        assertTrue(restarted.repairAllowsRecoveryClear(id))
        restarted.acknowledgeRecovery(id)
        assertFalse(requireNotNull(restarted.snapshot().history.result).needs_recovery_clear)
        assertTrue(File(archive(root, record.id), "pending.json").exists())
    }

    @Test fun anOldHealthyHistoryCannotClearRecoveryForANewerIsolatedIncident() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val oldId = "6".repeat(32)
        val result = UpdateInstallResult(oldId, "updated", 1001, "0.1.1", now, 86_400_000,
            mapOf("zh-Hans" to ""), needs_recovery_clear = true)
        history(root).writeText(UpdateModels.writeUpdateInstallHistory(UpdateInstallHistory(1, emptyList(), result)).toString())
        val isolated = store.isolateCorrupt(store.repairSnapshot(), ::evidence)
        var cleared = false
        assertFalse(store.acknowledgeRecovery(oldId) { cleared = true; true })
        assertFalse(cleared)
        assertEquals(isolated, UpdateRepairFiles.marker(root.canonicalFile))
        assertTrue(requireNotNull(store.snapshot().history.result).needs_recovery_clear)
    }

    @Test fun symlinksDirectoriesAndUnexpectedlyHugeEvidenceAreRefusedBeforeCleanup() {
        val root = temporary.newFolder(); val store = corrupt(root)
        val other = temporary.newFile().apply { writeText("do not touch") }
        raw(root).delete(); Files.createSymbolicLink(raw(root).toPath(), other.toPath())
        assertThrows(Exception::class.java) { store.repairSnapshot() }
        assertEquals("do not touch", other.readText())
        raw(root).delete(); raw(root).mkdir()
        assertThrows(Exception::class.java) { store.repairSnapshot() }
        raw(root).delete()
        RandomAccessFile(raw(root), "rw").use { it.setLength(64L * 1024 * 1024 + 1) }
        assertThrows(Exception::class.java) { store.repairSnapshot() }
        assertEquals(64L * 1024 * 1024 + 1, raw(root).length())
        assertFalse(File(root, UpdateRepairFiles.MARKER).exists())
    }
}
