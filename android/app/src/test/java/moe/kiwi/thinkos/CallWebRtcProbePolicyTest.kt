package moe.kiwi.thinkos

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CallWebRtcProbePolicyTest {
    @get:Rule val temporary = TemporaryFolder()
    private val name = "libjingle_peerconnection_so.so"
    private fun library(folder: String = "test") = File(temporary.newFolder(folder), name).apply { writeText("not executed") }
    private fun map(path: String, address: String = "1000-2000") = "$address r-xp 00000000 00:01 12 $path\n"

    @Test fun onlyExactIndependentFixtureAndTestPackagesAreAccepted() {
        CallWebRtcProbePolicy.requirePackages("moe.kiwi.thinkos.regfixture", "moe.kiwi.thinkos.regfixture.test")
        for ((target, test) in listOf("moe.kiwi.thinkos" to "moe.kiwi.thinkos.test",
                "moe.kiwi.thinkos.regfixture" to "moe.kiwi.thinkos.test",
                "other.regfixture" to "other.regfixture.test")) {
            assertThrows(IllegalArgumentException::class.java) { CallWebRtcProbePolicy.requirePackages(target, test) }
        }
    }

    @Test fun eitherExistingMediaGrantRefusesWithoutChangingPermissions() {
        CallWebRtcProbePolicy.requireDeniedPermissions(true, true)
        for ((camera, microphone) in listOf(true to false, false to true, false to false)) {
            assertThrows(IllegalArgumentException::class.java) { CallWebRtcProbePolicy.requireDeniedPermissions(camera, microphone) }
        }
    }

    @Test fun multipleSegmentsOfExactlyTheVerifiedLibraryAreLoadEvidence() {
        val expected = library()
        val ordinary = "3000-4000 r--p 00000000 00:01 13 /system/lib/libc.so\n"
        assertEquals(expected.canonicalPath, CallWebRtcProbePolicy.requireNewMapping(ordinary,
            ordinary + map(expected.path) + map(expected.path, "2000-3000"), expected))
    }

    @Test fun identicalBasenameAtAnotherPathOrAlongsideExpectedIsRejected() {
        val expected = library(); val other = library("different")
        for (after in listOf(map(other.path), map(expected.path) + map(other.path, "3000-4000"))) {
            assertThrows(IllegalArgumentException::class.java) { CallWebRtcProbePolicy.requireNewMapping("", after, expected) }
        }
    }

    @Test fun loadedStandardOrPrefixedLibrariesRequireAnotherProcess() {
        val expected = library()
        for (prior in listOf(map(expected.path), map("/another/liblkjingle_peerconnection_so.so"))) {
            assertThrows(IllegalArgumentException::class.java) { CallWebRtcProbePolicy.requireNewMapping(prior, map(expected.path), expected) }
        }
    }

    @Test fun deletedRelativeMissingAndOtherVariantMappingsAreRejected() {
        val expected = library()
        for (after in listOf("", map(expected.path + " (deleted)"), map(name),
                map("/another/liblkjingle_peerconnection_so.so"), name)) {
            assertThrows(IllegalArgumentException::class.java) { CallWebRtcProbePolicy.requireNewMapping("", after, expected) }
        }
    }
    @Test fun absentAndEmptyScalabilityMetadataRemainDifferent() {
        assertNull(CallWebRtcProbePolicy.scalabilityModes(null))
        assertEquals(emptyList<String>(), CallWebRtcProbePolicy.scalabilityModes(emptyList<String>()))
    }

    @Test fun suppliedScalabilityModesAreCopiedWithoutLosingOrder() {
        val source = mutableListOf("L1T1", "L1T3", "S3T3")
        val observed = CallWebRtcProbePolicy.scalabilityModes(source)
        source.clear()
        assertEquals(listOf("L1T1", "L1T3", "S3T3"), observed)
        assertEquals(64, CallWebRtcProbePolicy.scalabilityModes(List(64) { "L1T1" })?.size)
    }

    @Test fun malformedScalabilityContainersAndEntriesStillFailClosed() {
        for (value in listOf<Any>("L1T1", setOf("L1T1"), listOf(1), listOf(null), List(65) { "L1T1" })) {
            assertThrows(IllegalArgumentException::class.java) { CallWebRtcProbePolicy.scalabilityModes(value) }
        }
    }

    @Test fun scalabilityTextUsesUtf8ByteBoundsAndRejectsControls() {
        assertEquals(listOf("汉".repeat(42)), CallWebRtcProbePolicy.scalabilityModes(listOf("汉".repeat(42))))
        for (mode in listOf("a".repeat(129), "汉".repeat(43), "L1T1\n", "L1\u0000T1")) {
            assertThrows(IllegalArgumentException::class.java) { CallWebRtcProbePolicy.scalabilityModes(listOf(mode)) }
        }
    }

}
