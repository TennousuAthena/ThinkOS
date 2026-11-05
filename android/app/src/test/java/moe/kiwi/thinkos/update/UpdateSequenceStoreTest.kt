package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

class UpdateSequenceStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()

    @Test fun floorsSurviveNewInstancesAndStableAndBetaAdvanceIndependently() {
        val root = File(temporary.newFolder(), "new/ledger")
        UpdateSequenceStore(root).accept(fixture.verified(fixture.catalog(7)))
        UpdateSequenceStore(root).accept(fixture.verified(fixture.catalog(Long.MAX_VALUE, "beta")))
        val snapshot = UpdateSequenceStore(root).snapshot()
        assertEquals(7L, snapshot.stable!!.seq); assertEquals(Long.MAX_VALUE, snapshot.beta!!.seq)
        assertThrows(UpdateSequenceStore.Rollback::class.java) { UpdateSequenceStore(root).accept(fixture.verified(fixture.catalog(6))) }
        assertEquals(snapshot, UpdateSequenceStore(root).snapshot())
    }

    @Test fun sameSequenceAcceptsTheSameRawBytesButRefusesResignedChangedContent() {
        val root = temporary.newFolder(); val bytes = UpdateFixture.bytes(fixture.catalog(9)); val keys = fixture.keys()
        val original = UpdateCatalog.read(bytes, fixture.sign(bytes), keys, "stable")
        val store = UpdateSequenceStore(root); store.accept(original)
        val before = File(root, "sequences.json").readBytes()
        store.accept(UpdateCatalog.read(bytes, fixture.sign(bytes, fixture.backup), keys, "stable"))
        assertArrayEquals(before, File(root, "sequences.json").readBytes())
        val changed = byteArrayOf(32) + bytes
        assertThrows(UpdateSequenceStore.Rollback::class.java) { store.accept(UpdateCatalog.read(changed, fixture.sign(changed), keys, "stable")) }
        assertArrayEquals(before, File(root, "sequences.json").readBytes())
    }

    @Test fun corruptEmptyOversizedAndUnknownLedgerFieldsNeverResetTheFloor() {
        for (text in listOf("", "{", "{}", "{\"schema\":1}", "{\"schema\":1,\"unknown\":0}", " ".repeat(16_385))) {
            val root = temporary.newFolder(); val file = File(root, "sequences.json"); file.writeText(text)
            assertThrows(IOException::class.java) { UpdateSequenceStore(root).accept(fixture.verified()) }
            assertEquals(text, file.readText())
        }
    }

    @Test fun aHeldCrossProcessFileLockCannotBeBypassedByAnotherInstance() {
        val root = temporary.newFolder()
        RandomAccessFile(File(root, "sequences.lock"), "rw").use { handle -> handle.channel.lock().use {
            assertThrows(IOException::class.java) { UpdateSequenceStore(root).accept(fixture.verified()) }
            assertFalse(File(root, "sequences.json").exists())
        } }
    }

    @Test fun symlinksAndUnwritableStorageNeverReplaceAnotherFile() {
        val root = temporary.newFolder(); val outside = temporary.newFile(); outside.writeText("keep")
        Files.createSymbolicLink(File(root, "sequences.json").toPath(), outside.toPath())
        assertThrows(IOException::class.java) { UpdateSequenceStore(root).accept(fixture.verified()) }
        assertEquals("keep", outside.readText())
        assertThrows(IOException::class.java) { UpdateSequenceStore(temporary.newFile()).accept(fixture.verified()) }
    }
}
