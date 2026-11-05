package moe.kiwi.thinkos.update

import okhttp3.Request
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.cert.CertificateException

class UpdateTrustTest {
    @Test fun bundledRootsMatchThePinnedMozillaSubsetAndAreCompleteSelfSignedCas() {
        val roots = UpdateTrust.bundledRoots()
        val manifest = javaClass.getResourceAsStream("roots-source.json")!!.use { JSONObject(it.bufferedReader().readText()) }
        val declared = manifest.getJSONArray("certificates")
        assertEquals(16, roots.size); assertEquals(16, declared.length())
        assertEquals("MPL-2.0", manifest.getString("license"))
        assertEquals("796f1389bfea13c0ed78f663b765baaff54a508e5483786bd6ece73c5e3e3cbe", manifest.getString("source_sha256"))
        for ((index, root) in roots.withIndex()) {
            assertEquals(declared.getJSONObject(index).getString("der_sha256"), UpdateTrust.fingerprint(root))
            assertTrue(root.basicConstraints >= 0); assertEquals(root.subjectX500Principal, root.issuerX500Principal)
            root.verify(root.publicKey)
        }
    }

    @Test fun malformedEmptyOversizedAndDuplicateBundlesAreRejected() {
        val root = TestCertificates.pem(TestCertificates.root().cert)
        for (pem in listOf("", "not a certificate", "# comments only", "x".repeat(65_537), root + "trailing junk", root + root,
            root.replace("BEGIN CERTIFICATE", "BEGIN PUBLIC KEY"), "é" + root)) {
            assertThrows(CertificateException::class.java) { UpdateTrust.readRoots(pem.byteInputStream()) }
        }
    }

    @Test fun leafCertificatesAndCrossSignedCasCannotBecomeBundledTrustAnchors() {
        val root = TestCertificates.root()
        val leaf = TestCertificates.leaf(root)
        assertThrows(CertificateException::class.java) { UpdateTrust.readRoots(TestCertificates.pem(leaf.cert).byteInputStream()) }
        assertThrows(CertificateException::class.java) { UpdateTrust.readRoots(TestCertificates.pem(TestCertificates.intermediate(root).cert).byteInputStream()) }
        val text = TestCertificates.pem(root.cert)
        assertEquals(1, UpdateTrust.readRoots(("# comment\n" + text + "\n").byteInputStream()).size)
        val damaged = root.cert.encoded.copyOf().apply { this[lastIndex] = (this[lastIndex].toInt() xor 1).toByte() }
        val bad = "-----BEGIN CERTIFICATE-----\n" + java.util.Base64.getEncoder().encodeToString(damaged) + "\n-----END CERTIFICATE-----"
        assertThrows(GeneralSecurityException::class.java) { UpdateTrust.readRoots(bad.byteInputStream()) }
    }

    @Test fun theNativeTrustManagerAcceptsBothPlatformAndBundledRootsButRefusesAnotherCa() {
        val platform = TestCertificates.root(); val bundled = TestCertificates.root(); val stranger = TestCertificates.root()
        val material = UpdateTrust.fromAnchors(listOf(platform.cert), listOf(bundled.cert))
        for (ca in listOf(platform, bundled)) material.manager.checkServerTrusted(arrayOf(TestCertificates.leaf(ca).cert, ca.cert), "ECDHE_ECDSA")
        assertThrows(CertificateException::class.java) {
            material.manager.checkServerTrusted(arrayOf(TestCertificates.leaf(stranger).cert, stranger.cert), "ECDHE_ECDSA")
        }
        assertEquals(setOf(UpdateTrust.fingerprint(platform.cert), UpdateTrust.fingerprint(bundled.cert)), material.manager.acceptedIssuers.map(UpdateTrust::fingerprint).toSet())
    }

    @Test fun expiredLeafCertificatesStillFailNativeValidation() {
        val root = TestCertificates.root(); val expired = TestCertificates.leaf(root, expired = true)
        val material = UpdateTrust.fromAnchors(emptyList(), listOf(root.cert))
        assertThrows(CertificateException::class.java) { material.manager.checkServerTrusted(arrayOf(expired.cert, root.cert), "ECDHE_ECDSA") }
    }

    @Test fun defaultTrustIncludesThePlatformSnapshotAndEveryBundledRoot() {
        val actual = UpdateTrust.default.manager.acceptedIssuers.map(UpdateTrust::fingerprint).toSet()
        assertTrue(actual.containsAll(UpdateTrust.platform().acceptedIssuers.map(UpdateTrust::fingerprint)))
        assertTrue(actual.containsAll(UpdateTrust.bundledRoots().map(UpdateTrust::fingerprint)))
        assertFalse(UpdateTrust.default.manager.javaClass.name.contains("UpdateTrust"))
    }

    @Test(timeout = 10_000) fun aRealTlsConnectionRequiresABothTrustedAndHostnameMatchingCertificate() {
        val root = TestCertificates.root(); val leaf = TestCertificates.leaf(root)
        val material = UpdateTrust.fromAnchors(emptyList(), UpdateTrust.readRoots(TestCertificates.pem(root.cert).byteInputStream()))
        UpdateTestOrigin(TestCertificates.server(leaf, root), "https") { UpdateTestOrigin.reply(it, "secure".toByteArray()) }.use { origin ->
            val client = UpdateHttpClient.create("test", UpdateHttpClient.Timeouts(1000, 1000), trust = material)
            val url = origin.url().replace("127.0.0.1", "localhost")
            client.newCall(Request.Builder().url(url).build()).execute().use { assertEquals("secure", it.body!!.string()) }
            // The same valid chain has no IP SAN, so using the IP must fail hostname verification.
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(origin.url()).build()).execute().close() }
            client.connectionPool.evictAll()
            val normal = UpdateHttpClient.create("test", UpdateHttpClient.Timeouts(1000, 1000))
            assertThrows(IOException::class.java) { normal.newCall(Request.Builder().url(url).build()).execute().close() }
            normal.connectionPool.evictAll()
        }
    }

    @Test(timeout = 10_000) fun anExpiredLeafFailsARealTlsHandshake() {
        val root = TestCertificates.root(); val leaf = TestCertificates.leaf(root, expired = true)
        UpdateTestOrigin(TestCertificates.server(leaf, root), "https") { UpdateTestOrigin.reply(it, byteArrayOf(1)) }.use { origin ->
            val client = UpdateHttpClient.create("test", UpdateHttpClient.Timeouts(1000, 1000), trust = UpdateTrust.fromAnchors(emptyList(), listOf(root.cert)))
            assertThrows(IOException::class.java) { client.newCall(Request.Builder().url(origin.url().replace("127.0.0.1", "localhost")).build()).execute().close() }
            client.connectionPool.evictAll()
        }
    }
}
