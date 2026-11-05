package moe.kiwi.thinkos.update

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class UpdatePackageVerifierTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()
    private val packageName = "moe.kiwi.thinkos"
    private fun archive() = UpdatePackageVerifier.Archive(packageName, "0.2.0", 2000, listOf(fixture.signer), 27, 27, false)
    private fun bytes(native: List<String> = emptyList()): ByteArray = ByteArrayOutputStream().use { out ->
        ZipOutputStream(out).use { zip ->
            for (name in listOf("AndroidManifest.xml") + native) {
                zip.putNextEntry(ZipEntry(name)); zip.write("fixture".toByteArray()); zip.closeEntry()
            }
        }; out.toByteArray()
    }
    private fun catalog(raw: ByteArray) = fixture.verified(fixture.catalog().apply {
        getJSONObject("release").put("size", raw.size).put("sha256", UpdateCatalog.sha256(raw))
    })
    private fun file(raw: ByteArray) = temporary.newFile().apply { writeBytes(raw) }
    private fun verifier(reader: (File) -> UpdatePackageVerifier.Archive?) =
        UpdatePackageVerifier.forTest(packageName, { fixture.device }, reader)

    @Test fun installedBackupIdentityComesFromTheActualArchiveAndCurrentSigner() { runBlocking {
        val raw = bytes(listOf("lib/armeabi-v7a/libtest.so")); val file = file(raw)
        val current = archive().copy(versionCode = 1001, versionName = "0.1.1")
        val checked = verifier { current }.inspectInstalled(file)
        assertEquals(UpdateApkIdentity("0.1.1", 1001, UpdateCatalog.sha256(raw), raw.size.toLong(), fixture.signer), checked.identity)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { current.copy(signers = listOf("b".repeat(64))) }.inspectInstalled(file) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { current.copy(versionCode = 1000) }.inspectInstalled(file) } }
    } }

    @Test fun previousApksRequireTheirPinnedBytesNativeIdentityAndALowerVersion() { runBlocking {
        val raw = bytes(listOf("lib/armeabi-v7a/libtest.so")); val file = file(raw)
        val old = archive().copy(versionCode = 1000, versionName = "0.1.0")
        val expected = UpdateApkIdentity("0.1.0", 1000, UpdateCatalog.sha256(raw), raw.size.toLong(), fixture.signer)
        assertEquals(expected, verifier { old }.verifyPrevious(expected, file).identity)
        assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { old }.verifyInstalled(expected, file) } }
        for (wrong in listOf(expected.copy(sha256 = "f".repeat(64)), expected.copy(version = "0.2.0", code = 2000), expected.copy(signer = "b".repeat(64))))
            assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { old }.verifyPrevious(wrong, file) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { old.copy(packageName = "other.app") }.verifyPrevious(expected, file) } }
    } }

    @Test fun installedInspectionRejectsUnsupportedAbisAndChangesDuringNativeParsing() { runBlocking {
        val current = archive().copy(versionCode = 1001, versionName = "0.1.1")
        assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { current }.inspectInstalled(file(bytes(listOf("lib/arm64-v8a/libtest.so")))) } }
        val candidate = file(bytes())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { candidate.appendText("changed"); current }.inspectInstalled(candidate) } }
    } }

    @Test fun contentAndArchiveMatchBeforeAValidatedCandidateIsReturned() { runBlocking {
        val raw = bytes(listOf("lib/armeabi-v7a/libtest.so")); val catalog = catalog(raw); val file = file(raw)
        val checked = verifier { archive() }.verify(catalog, file)
        assertEquals(file.canonicalFile, checked.file); assertEquals(catalog.digest, checked.catalogDigest)
        assertEquals(UpdateCatalog.sha256(raw), checked.sha256); assertEquals(raw.size.toLong(), checked.bytes)
        assertEquals(2000, checked.versionCode); assertEquals(fixture.signer, checked.signer)
    } }

    @Test fun wrongHashSizeDirectoryOrSymlinkNeverReachesAndroidArchiveParsing() { runBlocking {
        val raw = bytes(); val catalog = catalog(raw)
        var parsed = 0
        val verifier = verifier { parsed++; archive() }
        for (file in listOf(file(raw + 1), file(raw.copyOf().apply { this[0] = (this[0].toInt() xor 1).toByte() }), temporary.newFolder())) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier.verify(catalog, file) } }
        }
        val link = temporary.root.toPath().resolve("candidate-link.apk")
        Files.createSymbolicLink(link, file(raw).toPath())
        assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier.verify(catalog, link.toFile()) } }
        assertEquals(0, parsed)
    } }

    @Test fun nullWrongPackageVersionSignerSdkAndSplitArchivesAreRejected() { runBlocking {
        val raw = bytes(); val catalog = catalog(raw); val file = file(raw)
        val normal = archive()
        val variants = listOf(null, normal.copy(packageName = "another.package"), normal.copy(versionName = "0.3.0"),
            normal.copy(versionCode = 1999), normal.copy(signers = emptyList()), normal.copy(signers = listOf("b".repeat(64))),
            normal.copy(signers = listOf(fixture.signer, fixture.signer)), normal.copy(minSdk = 28), normal.copy(targetSdk = 28), normal.copy(split = true))
        for (info in variants) assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { info }.verify(catalog, file) } }
    } }

    @Test fun installedIdentityIsReloadedAndMayInvalidateAnEarlierCatalog() { runBlocking {
        val raw = bytes(); val catalog = catalog(raw); val file = file(raw)
        var current = fixture.device
        var parsed = 0
        val verifier = UpdatePackageVerifier.forTest(packageName, { current }, { parsed++; archive() })
        for (changed in listOf(current.copy(versionCode = 2000), current.copy(signer = "b".repeat(64)), current.copy(name = "another"), current.copy(sdk = 28))) {
            current = changed
            assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier.verify(catalog, file) } }
        }
        assertEquals(0, parsed)
    } }

    @Test fun arm64OnlyMixedAbiAndMalformedNativePathsNeverBecomeReady() { runBlocking {
        for (native in listOf(listOf("lib/arm64-v8a/test.so"), listOf("lib/armeabi-v7a/ok.so", "lib/x86/wrong.so"),
                              listOf("lib/armeabi-v7a/nested/test.so"), listOf("lib/armeabi-v7a/not-a-library"))) {
            val raw = bytes(native)
            assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier { archive() }.verify(catalog(raw), file(raw)) } }
        }
    } }

    @Test fun aCandidateChangedDuringPlatformParsingFailsTheFinalHashCheck() { runBlocking {
        val raw = bytes(); val file = file(raw)
        val verifier = verifier { it.appendBytes(byteArrayOf(0)); archive() }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { verifier.verify(catalog(raw), file) } }
    } }

    @Test fun diskAndPlatformParsingRunAtMinimumPriorityAwayFromTheCaller() { runBlocking {
        val raw = bytes(); val caller = Thread.currentThread()
        val priorities = mutableListOf<Int>()
        val verifier = UpdatePackageVerifier.forTest(packageName, {
            assertNotSame(caller, Thread.currentThread()); priorities += Thread.currentThread().priority; fixture.device
        }, { priorities += Thread.currentThread().priority; archive() })
        verifier.verify(catalog(raw), file(raw))
        assertEquals(listOf(Thread.MIN_PRIORITY, Thread.MIN_PRIORITY), priorities)
    } }
}
