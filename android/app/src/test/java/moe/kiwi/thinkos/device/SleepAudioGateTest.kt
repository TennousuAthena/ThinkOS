package moe.kiwi.thinkos.device

import org.junit.Assert.*
import org.junit.Test

class SleepAudioGateTest {
    private class Disk : SleepAudioGate.Persistence {
        var value: Map<Int, Int>? = null
        var writes = 0
        var cleared = 0
        var failWrite = false
        override fun read() = value?.toMap()
        override fun write(values: Map<Int, Int>) {
            if (failWrite) error("disk full")
            value = values.toMap(); writes++
        }
        override fun clear() { value = null; cleared++ }
    }
    private class Audio(val disk: Disk, initial: Int = 3) : SleepAudioGate.Volumes {
        val values = (1..5).associateWith { initial }.toMutableMap()
        val writes = mutableListOf<Pair<Int, Int>>()
        var ignoreWrite = false
        var ignoredStreams = emptySet<Int>()
        var beforeWrite: (Int, Int) -> Unit = { _, _ -> }
        override fun read(stream: Int) = values[stream]
        override fun maximum(stream: Int) = 7
        override fun write(stream: Int, index: Int): Boolean {
            if (index == 0) assertNotNull("durable recovery precedes muting", disk.value)
            beforeWrite(stream, index)
            writes += stream to index
            if (!ignoreWrite && stream !in ignoredStreams) values[stream] = index
            return true
        }
    }

    @Test fun savesFiveOriginalsBeforeZeroAndRepeatedSleepDoesNotOverwriteThem() {
        val disk = Disk(); val audio = Audio(disk)
        val original = audio.values.toMap()
        val gate = SleepAudioGate(audio, disk)
        gate.update(true)
        assertTrue(gate.sleeping)
        assertEquals(original, disk.value)
        assertEquals((1..5).map { it to 0 }, audio.writes)
        gate.update(true)
        assertEquals(1, disk.writes)
        assertEquals(5, audio.writes.size)
        assertEquals(original, disk.value)
    }

    @Test fun volumeKeyDuringSleepIsClampedWithoutRedundantZeroWrites() {
        val disk = Disk(); val audio = Audio(disk, 0); val gate = SleepAudioGate(audio, disk)
        gate.update(true)
        assertTrue(audio.writes.isEmpty())
        audio.values[3] = 4
        gate.update(true)
        assertEquals(listOf(3 to 0), audio.writes)
        gate.enforce()
        assertEquals(1, audio.writes.size)
    }

    @Test fun wakingReleasesFenceBeforeRestoreAndDoesNotTouchNewUserValues() {
        val disk = Disk(); val audio = Audio(disk); val gate = SleepAudioGate(audio, disk)
        gate.update(true)
        audio.values[2] = 5
        audio.beforeWrite = { _, index -> if (index != 0) assertFalse(gate.sleeping) }
        gate.update(false)
        assertEquals(5, audio.values[2])
        assertEquals(3, audio.values[3])
        assertNull(disk.value)
        assertEquals(1, disk.cleared)
    }

    @Test fun newDayProcessRestoresAnInterruptedSleepBeforeDeletingItsRecord() {
        val disk = Disk(); val audio = Audio(disk)
        SleepAudioGate(audio, disk).update(true)
        audio.values[5] = 6
        val restarted = SleepAudioGate(audio, disk)
        restarted.update(false)
        assertFalse(restarted.sleeping)
        assertEquals(6, audio.values[5])
        assertEquals((1..4).associateWith { 3 }, audio.values.filterKeys { it != 5 })
        assertNull(disk.value)
    }

    @Test fun newSleepProcessReusesOriginalRecordRatherThanSavingZero() {
        val disk = Disk(); val audio = Audio(disk)
        SleepAudioGate(audio, disk).update(true)
        audio.values[3] = 5 // A still-sleeping process restart must retain the original recovery target.
        val restarted = SleepAudioGate(audio, disk)
        restarted.update(true)
        assertEquals(1, disk.writes)
        assertEquals((1..5).associateWith { 3 }, disk.value)
        assertEquals(0, audio.values[3])
        restarted.update(false)
        assertTrue(audio.values.values.all { it == 3 })
    }

    @Test fun interruptedEntryWithNoZeroWritesPreservesCurrentValues() {
        val disk = Disk().apply { value = (1..5).associateWith { 2 } }
        val audio = Audio(disk, 4)
        SleepAudioGate(audio, disk).update(false)
        assertTrue(audio.writes.isEmpty())
        assertTrue(audio.values.values.all { it == 4 })
        assertNull(disk.value)
    }

    @Test fun missingOrOutOfRangeStreamsFailBeforeAnyVolumeWrite() {
        for (record in listOf(mapOf(3 to 2), (1..5).associateWith { 8 })) {
            val disk = Disk().apply { value = record }; val audio = Audio(disk)
            try { SleepAudioGate(audio, disk).update(false); fail("invalid record accepted") }
            catch (_: IllegalArgumentException) { }
            assertTrue(audio.writes.isEmpty())
            assertEquals(record, disk.value)
        }
    }

    @Test fun failedAtomicSaveCannotMuteAndLoseTheOriginalValues() {
        val disk = Disk().apply { failWrite = true }; val audio = Audio(disk)
        try { SleepAudioGate(audio, disk).update(true); fail("failed write accepted") }
        catch (_: IllegalStateException) { }
        assertTrue(audio.writes.isEmpty())
        assertTrue(audio.values.values.all { it == 3 })
    }

    @Test fun noRecordAndDayDoNotReadOrChangeVolumes() {
        val disk = Disk(); val audio = Audio(disk)
        SleepAudioGate(audio, disk).update(false)
        assertTrue(audio.writes.isEmpty())
        assertEquals(0, disk.writes)
        assertEquals(0, disk.cleared)
    }

    @Test fun aSilentAndroidRestoreFailureRetainsItsJournalForRetry() {
        val disk = Disk(); val audio = Audio(disk); val gate = SleepAudioGate(audio, disk)
        gate.update(true)
        audio.ignoreWrite = true
        gate.update(false)
        assertFalse(gate.sleeping)
        assertNotNull(disk.value)
        assertEquals(0, disk.cleared)
        audio.ignoreWrite = false
        gate.update(false)
        assertTrue(audio.values.values.all { it == 3 })
        assertNull(disk.value)
    }

    @Test fun secondSleepAfterPartialWakePreservesNewNonzeroUserVolume() {
        val disk = Disk(); val audio = Audio(disk); val gate = SleepAudioGate(audio, disk)
        gate.update(true)
        audio.ignoredStreams = setOf(1)
        gate.update(false)
        assertFalse(gate.sleeping)
        assertEquals(0, audio.values[1])
        assertNotNull(disk.value)
        audio.values[3] = 5
        gate.update(true)
        assertEquals(2, disk.writes)
        assertEquals(3, disk.value?.get(1)) // The still-zero failed stream keeps its original target.
        assertEquals(5, disk.value?.get(3)) // A later DAY change is saved before being muted again.
        assertEquals(0, audio.values[3])
        audio.ignoredStreams = emptySet()
        gate.update(false)
        assertEquals(3, audio.values[1])
        assertEquals(5, audio.values[3])
        assertNull(disk.value)
    }

    @Test fun journalCodecIsFixedBoundedAndRejectsDuplicateUnknownOrTrailingData() {
        val record = (1..5).associateWith { it }
        val text = SleepAudioJournal.encode(record)
        assertEquals(record, SleepAudioJournal.decode(text))
        val bad = listOf(text + "extra", text.replace("2=2", "3=2"), text.replace("1=1", "1=-1"),
            text.replace("1=1", "1=99999"), text.replace("1=1", "1=01"), "x".repeat(257))
        for (value in bad) {
            try { SleepAudioJournal.decode(value); fail("malformed record accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
}
