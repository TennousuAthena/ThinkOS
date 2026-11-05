package moe.kiwi.thinkos.update

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test

class UpdateCatalogTest {
    private val fixture = UpdateFixture()

    @Test fun authenticatedCatalogPreservesExactBytesAndExposesAnImmutableModel() {
        val json = fixture.catalog(Long.MAX_VALUE)
        val bytes = UpdateFixture.bytes(json); val signature = fixture.sign(bytes)
        val catalog = UpdateCatalog.read(bytes, signature, fixture.keys(), "stable")
        assertEquals(Long.MAX_VALUE, catalog.document.seq)
        assertArrayEquals(bytes, catalog.bytes()); assertArrayEquals(signature, catalog.signature())
        bytes.fill(0); signature.fill(0); catalog.bytes().fill(0)
        assertTrue(catalog.bytes().first().toInt() != 0)
        assertThrows(UnsupportedOperationException::class.java) { (catalog.document.release.urls as MutableList<String>).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (catalog.document.release.notes as MutableMap<String, String>)["zh-Hans"] = "changed" }
        assertEquals(fixture.payload.size.toLong(), catalog.apk().bytes)
    }

    @Test fun invalidSignaturesAreRejectedBeforeEvenMalformedJsonCanBeRead() {
        val malformed = "not json".toByteArray()
        assertThrows(SignedDocument.Rejected::class.java) { UpdateCatalog.read(malformed, ByteArray(71), fixture.keys(), "stable") }
        val json = UpdateFixture.bytes(fixture.catalog())
        assertThrows(SignedDocument.Rejected::class.java) { UpdateCatalog.read(json, fixture.sign(json, fixture.registry), fixture.keys(), "stable") }
        assertThrows(IllegalArgumentException::class.java) { UpdateCatalog.read(malformed, fixture.sign(malformed), fixture.keys(), "stable") }
    }

    @Test fun signedButWrongChannelVersionCodeExpiryAndUnknownFieldsAreRejected() {
        val variants = listOf(fixture.catalog().put("unexpected", 1), fixture.catalog().put("schema", 2), fixture.catalog().put("expires", 99),
            fixture.catalog().apply { getJSONObject("release").put("code", 2001) }, fixture.catalog().apply { getJSONObject("release").put("size", 67_108_865) },
            fixture.catalog().apply { getJSONObject("release").put("signer", "bad") })
        for (json in variants) assertThrows(IllegalArgumentException::class.java) { fixture.verified(json) }
        val beta = UpdateFixture.bytes(fixture.catalog(channel = "beta"))
        assertThrows(IllegalArgumentException::class.java) { UpdateCatalog.read(beta, fixture.sign(beta), fixture.keys(), "stable") }
    }

    @Test fun aValidSignedReleaseStillNeedsMatchingDeviceSdkNewerVersionAndSigner() {
        val catalog = fixture.verified()
        assertNull(catalog.ineligible(fixture.device))
        for ((device, reason) in listOf(fixture.device.copy(name = "another") to UpdateCatalog.Ineligible.DEVICE,
            fixture.device.copy(sdk = 28) to UpdateCatalog.Ineligible.SDK, fixture.device.copy(versionCode = 2000) to UpdateCatalog.Ineligible.VERSION,
            fixture.device.copy(versionCode = 2001) to UpdateCatalog.Ineligible.VERSION, fixture.device.copy(signer = "b".repeat(64)) to UpdateCatalog.Ineligible.SIGNER)) {
            assertEquals(reason, catalog.ineligible(device))
        }
    }

    @Test fun expiredMetadataWarnsOnlyAndFutureRuntimeApiDoesNotPreventUpdatingTheRuntime() {
        val catalog = fixture.verified(fixture.catalog().apply { getJSONObject("release").put("api", 10) })
        assertFalse(catalog.stale(null)); assertFalse(catalog.stale(199)); assertTrue(catalog.stale(200)); assertTrue(catalog.stale(201))
        assertNull(catalog.ineligible(fixture.device))
    }

    @Test fun credentialFragmentAndNonHttpUrlsAreRejectedButPrivateMirrorsRemainPossible() {
        for (url in listOf("https://user:secret@host.test/a", "https://host.test/a#fragment", "file:///tmp/a", "https://", "https://host.test/a b")) {
            val json = fixture.catalog(url = url)
            assertThrows(Exception::class.java) { fixture.verified(json) }
        }
        assertNull(fixture.verified(fixture.catalog(url = "http://127.0.0.1:8099/a.apk?q=a%26b")).ineligible(fixture.device))
    }

    @Test fun announcedMirrorsMustBeThisChannelsCatalogOnValidUrlsAndStayWithinTheBounds() {
        fun read(vararg mirrors: String) = fixture.verified(fixture.catalog().put("mirrors", JSONArray(mirrors.toList())))
        assertNull(fixture.verified(fixture.catalog()).document.mirrors)
        val good = "https://mirror.example/org/repo/-/releases/download/ota-stable/stable.json"
        assertEquals(listOf(good, "http://127.0.0.1:8099/ota/stable.json"), read(good, "http://127.0.0.1:8099/ota/stable.json").document.mirrors)
        // Like APK URLs, cleartext to a public name is parsed but refused by the transport (RequestDns) before any connection.
        for (bad in listOf("https://mirror.example/ota-stable/beta.json", "https://mirror.example/ota-stable/stable.json#x",
                "https://user:pw@mirror.example/stable.json", "ftp://mirror.example/stable.json",
                "https://mirror.example/stable.jsonx", "https://mirror.example/", "")) {
            assertThrows(bad, Exception::class.java) { read(bad) }
        }
        assertThrows(Exception::class.java) { read(*Array(9) { "https://m$it.example/stable.json" }) }
        assertThrows(Exception::class.java) { read(good, good) }
    }

    @Test fun moduleIdsKindsPoliciesAndDuplicatesAreCheckedBeforeDownloads() {
        val valid = fixture.catalog().apply { getJSONObject("release").put("modules", JSONArray().put(fixture.module())) }
        assertEquals("thinkos-cast", fixture.verified(valid).document.release.modules.single().id)
        val badModules = listOf(fixture.module("third-party"), fixture.module("thinkos-cast", "vector"),
            fixture.module("thinkos-immersive", "magisk"), fixture.module().put("policy", "anything"), fixture.module().put("size", 16_777_217))
        for (module in badModules) {
            val json = fixture.catalog().apply { getJSONObject("release").put("modules", JSONArray().put(module)) }
            assertThrows(IllegalArgumentException::class.java) { fixture.verified(json) }
        }
        val duplicate = fixture.catalog().apply { getJSONObject("release").put("modules", JSONArray().put(fixture.module()).put(fixture.module())) }
        assertThrows(IllegalArgumentException::class.java) { fixture.verified(duplicate) }
    }

    @Test fun officialCaAndPowerModulesAreAcceptedOnlyAsMagiskArtifacts() {
        for (id in listOf("thinkos-certs", "thinkos-power")) {
            val json = fixture.catalog().apply { getJSONObject("release").put("modules", JSONArray().put(fixture.module(id))) }
            assertEquals(id, fixture.verified(json).document.release.modules.single().id)
            json.getJSONObject("release").getJSONArray("modules").getJSONObject(0).put("kind", "vector")
            assertThrows(IllegalArgumentException::class.java) { fixture.verified(json) }
        }
    }

    @Test fun versionCodeArithmeticCoversTheFullAndroidIntRangeWithoutOverflow() {
        assertEquals(1001, UpdateCatalog.versionCode("0.1.1")); assertEquals(Int.MAX_VALUE, UpdateCatalog.versionCode("2147.483.647"))
        for (version in listOf("2147.483.648", "2148.0.0", "0.1000.0", "0.0.1000", "01.2.3", "1.2", "1.2.3-beta", "0.0.0", "999999999999999999999.0.0")) {
            assertThrows(IllegalArgumentException::class.java) { UpdateCatalog.versionCode(version) }
        }
    }

    @Test fun schemaBoundsIncludeUniqueUrlsExactIntegerTypesAndUnicodeCodePoints() {
        val json = fixture.catalog().apply { getJSONObject("release").getJSONObject("notes").put("zh-Hans", "😀".repeat(2048)) }
        assertEquals(4096, fixture.verified(json).document.release.notes.getValue("zh-Hans").length)
        for (change in listOf<(org.json.JSONObject) -> Unit>(
            { it.getJSONObject("release").put("urls", JSONArray().put("https://a.test/a").put("https://a.test/a")) },
            { it.getJSONObject("release").put("device", JSONArray().put("starfire").put("starfire")) },
            { it.getJSONObject("release").getJSONObject("notes").put("other", "bad") },
            { it.put("seq", "1") }, { it.getJSONObject("release").put("code", 2000.5) })) {
            assertThrows(IllegalArgumentException::class.java) { fixture.verified(fixture.catalog().apply(change)) }
        }
    }
}
