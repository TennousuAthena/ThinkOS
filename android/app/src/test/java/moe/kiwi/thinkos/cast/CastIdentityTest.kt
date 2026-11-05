package moe.kiwi.thinkos.cast

import moe.kiwi.thinkos.cast.airplay.AirPlayRecord
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import java.util.concurrent.Executors

class CastIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun file(directory: String = "cast") = File(temporary.root, "$directory/identity-seed")

    @Test fun aSeedIsStableAcrossIndependentLoadsAndDoesNotTouchThePairingKey() {
        val file = file()
        file.parentFile.mkdirs()
        val pairing = File(file.parentFile, "airplay-identity").apply { writeBytes(ByteArray(32) { 19 }) }
        val first = CastIdentity.load(file)
        val stored = file.readBytes()
        val second = CastIdentity.load(file)
        assertEquals(32, stored.size)
        assertArrayEquals(stored, file.readBytes())
        assertEquals(first.uuid, second.uuid)
        assertEquals(AirPlayRecord.deviceId(first.airplaySeed), AirPlayRecord.deviceId(second.airplaySeed))
        assertArrayEquals(ByteArray(32) { 19 }, pairing.readBytes())
    }

    @Test fun independentInstallationsHaveDifferentDlnaAndAirplayIds() {
        val first = CastIdentity.load(file("first"))
        val second = CastIdentity.load(file("second"))
        assertNotEquals(first.uuid, second.uuid)
        assertNotEquals(AirPlayRecord.deviceId(first.airplaySeed), AirPlayRecord.deviceId(second.airplaySeed))
        assertEquals(3, UUID.fromString(first.uuid).version())
        assertEquals(2, AirPlayRecord.deviceId(first.airplaySeed).substringBefore(':').toInt(16) and 3)
    }

    @Test fun truncatedAndOversizedSeedsAreReplacedAndThenStable() {
        val file = file()
        val initial = CastIdentity.load(file)
        for (size in listOf(0, 1, 31, 33, 1_048_576)) {
            file.writeBytes(ByteArray(size) { 7 })
            val restored = CastIdentity.load(file)
            assertEquals(32, file.length())
            assertNotEquals(initial.uuid, restored.uuid)
            assertEquals(restored.uuid, CastIdentity.load(file).uuid)
        }
        assertEquals(listOf("identity-seed"), file.parentFile.list()!!.sorted())
    }

    @Test fun newAndExistingSeedsAreOwnerReadWriteOnly() {
        val file = file()
        CastIdentity.load(file)
        val expected = setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
        assertEquals(expected, Files.getPosixFilePermissions(file.toPath()))
        file.setReadable(true, false); file.setWritable(true, false); file.setExecutable(true, false)
        val before = file.readBytes()
        CastIdentity.load(file)
        assertEquals(expected, Files.getPosixFilePermissions(file.toPath()))
        assertArrayEquals(before, file.readBytes())
    }

    @Test fun concurrentFirstUsePublishesOneIdentity() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val values = pool.invokeAll(List(12) { java.util.concurrent.Callable { CastIdentity.load(file()).uuid } }).map { it.get() }
            assertEquals(1, values.toSet().size)
            assertEquals(values.first(), CastIdentity.load(file()).uuid)
        } finally { pool.shutdownNow() }
    }

    @Test fun storageFailuresDoNotInventAnInMemoryFallbackIdentity() {
        val directory = temporary.newFolder("not-a-seed")
        assertThrows(IOException::class.java) { CastIdentity.load(directory) }
        val parentFile = temporary.newFile("not-a-directory")
        assertThrows(IOException::class.java) { CastIdentity.load(File(parentFile, "identity-seed")) }
    }
}
