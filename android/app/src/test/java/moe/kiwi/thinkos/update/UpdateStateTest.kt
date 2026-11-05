package moe.kiwi.thinkos.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

class UpdateStateTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()
    private val time = UpdateState.Time(3, 1000, 1_000_000)
    private fun token(catalog: UpdateCatalog) = UpdatePackageVerifier.Verified(File(temporary.root, "candidate.apk"),
        catalog.digest, catalog.document.release.sha256, catalog.document.release.size, catalog.document.release.code, catalog.document.release.signer)

    @Test fun theSixPhasesPersistAndReadyTimingDoesNotResetOnAnIdenticalCatalog() {
        val root = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val catalog = fixture.verified()
        ledger.accept(catalog); val state = UpdateState(root, ledger)
        assertEquals("IDLE", state.snapshot("stable").phase)
        assertEquals("AVAILABLE", state.available(catalog).phase)
        assertEquals("DOWNLOADING", state.downloading(catalog, 0).phase)
        state.progress(catalog, 100)
        val ready = state.ready(catalog, token(catalog), time)
        assertEquals("READY", ready.phase); assertEquals(ready, UpdateState(root, ledger).snapshot("stable"))
        assertEquals(ready, state.available(catalog)); assertEquals(ready, state.ready(catalog, token(catalog), time.copy(elapsed = 9000)))
        assertEquals("INSTALLING", state.installing(catalog, token(catalog)).phase)
        assertEquals("FAILED", state.failed("stable", UpdateState.Error.INTERRUPTED).phase)
        assertEquals("IDLE", state.idle("stable").phase); assertEquals(1L, state.snapshot("stable").seq)
    }

    @Test fun wrongProofBackwardsProgressAndIncompleteModuleReadinessAreRejected() {
        val ledger = UpdateSequenceStore(temporary.newFolder()); val state = UpdateState(temporary.newFolder(), ledger); val catalog = fixture.verified()
        ledger.accept(catalog); state.available(catalog); state.downloading(catalog, 10)
        assertThrows(IllegalArgumentException::class.java) { state.progress(catalog, 9) }
        assertThrows(IllegalArgumentException::class.java) { state.installing(catalog, token(catalog)) }
        assertThrows(IllegalArgumentException::class.java) { state.ready(catalog, token(catalog).copy(catalogDigest = "f".repeat(64)), time) }
        val modules = fixture.verified(fixture.catalog(2).apply { getJSONObject("release").put("modules", org.json.JSONArray().put(fixture.module())) })
        ledger.accept(modules); state.available(modules); state.downloading(modules, 0)
        assertThrows(IllegalArgumentException::class.java) { state.ready(modules, token(modules), time) }
    }

    @Test fun oldCatalogsCannotMoveTheStateOrFloorBackwards() {
        val ledger = UpdateSequenceStore(temporary.newFolder()); val state = UpdateState(temporary.newFolder(), ledger)
        val old = fixture.verified(fixture.catalog(8)); val newer = fixture.verified(fixture.catalog(9))
        ledger.accept(old); state.available(old); ledger.accept(newer); state.available(newer)
        assertThrows(UpdateSequenceStore.Rollback::class.java) { state.available(old) }
        assertThrows(UpdateSequenceStore.Rollback::class.java) { state.rebuild(old) }
        assertEquals(9L, state.snapshot("stable").seq); assertEquals(9L, ledger.snapshot().stable!!.seq)
        assertEquals("IDLE", state.snapshot("beta").phase)
    }

    @Test fun corruptProgressCanOnlyBeRebuiltFromCurrentAuthenticatedMetadataAsAvailable() {
        val root = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val state = UpdateState(root, ledger)
        val catalog = fixture.verified(); ledger.accept(catalog)
        val file = File(root, "state-stable.json")
        for (text in listOf("", "{}", "{\"schema\":1}", "x".repeat(16_385),
            "{\"schema\":1,\"channel\":\"stable\",\"phase\":\"READY\",\"downloaded\":1,\"size\":1}")) {
            file.writeText(text)
            assertThrows(UpdateState.Corrupt::class.java) { state.snapshot("stable") }
            assertEquals("AVAILABLE", state.rebuild(catalog).phase)
        }
        state.downloading(catalog, 0); state.ready(catalog, token(catalog), time); state.installing(catalog, token(catalog))
        assertThrows(IllegalArgumentException::class.java) { state.rebuild(catalog) }
    }

    @Test fun aReadyHintCannotOverrideTheVerifiedFileSize() {
        val root = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val state = UpdateState(root, ledger)
        val catalog = fixture.verified(); ledger.accept(catalog); state.available(catalog); state.downloading(catalog, 0); state.ready(catalog, token(catalog), time)
        val file = File(root, "state-stable.json"); val changed = JSONObject(file.readText()).put("size", 1).put("downloaded", 1)
        file.writeText(changed.toString())
        val corrected = state.ready(catalog, token(catalog), time.copy(elapsed = 9000))
        assertEquals(catalog.document.release.size, corrected.size); assertEquals(9000L, corrected.ready_elapsed)
    }

    @Test fun readyWaitUsesTheSameBootsMonotonicClockAndOnlyTrustedWallTimeAcrossBoots() {
        val ledger = UpdateSequenceStore(temporary.newFolder()); val state = UpdateState(temporary.newFolder(), ledger)
        val catalog = fixture.verified(); ledger.accept(catalog); state.available(catalog); state.downloading(catalog, 0)
        val ready = state.ready(catalog, token(catalog), time)
        assertEquals(500L, UpdateState.waitingReadyMillis(ready, time.copy(elapsed = 1500, wall = 0)))
        assertEquals(0L, UpdateState.waitingReadyMillis(ready, UpdateState.Time(4, 2000, null)))
        assertEquals(9000L, UpdateState.waitingReadyMillis(ready, UpdateState.Time(4, 100, time.wall!!+9000)))
        assertEquals(0L, UpdateState.waitingReadyMillis(ready, UpdateState.Time(null, 9999, null)))
    }

    @Test fun fileLocksAndSymlinksCannotBeBypassedByStateOrRepair() {
        val root = temporary.newFolder(); val ledger = UpdateSequenceStore(temporary.newFolder()); val state = UpdateState(root, ledger)
        val catalog = fixture.verified(); ledger.accept(catalog)
        RandomAccessFile(File(root, "workflow.lock"), "rw").use { handle -> handle.channel.lock().use {
            assertThrows(IOException::class.java) { state.available(catalog) }
        } }
        val outside = temporary.newFile().apply { writeText("keep") }
        Files.createSymbolicLink(File(root, "state-stable.json").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { state.rebuild(catalog) }; assertEquals("keep", outside.readText())
    }
}
