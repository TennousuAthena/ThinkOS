package moe.kiwi.thinkos.update

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.FileNotFoundException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class UpdateKeysTest {
    private val fixture = UpdateFixture()
    private val message = "{\"seq\":1}".toByteArray()

    @Test fun activeAndBackupKeysVerifyOnlyTheirOwnProductionRole() {
        val keys = fixture.keys()
        for ((role, good, bad) in listOf(Triple(UpdateKeys.Role.CHANNEL, listOf(fixture.active, fixture.backup), fixture.registry),
            Triple(UpdateKeys.Role.REGISTRY, listOf(fixture.registry, fixture.registryBackup), fixture.active))) {
            for (pair in good) assertEquals(1, keys.read(role, SignedDocument.Kind.CATALOG, message, fixture.sign(message, pair)) { 1 })
            var decoded = false
            assertThrows(SignedDocument.Rejected::class.java) { keys.read(role, SignedDocument.Kind.CATALOG, message, fixture.sign(message, bad)) { decoded = true } }
            assertFalse(decoded)
        }
    }

    @Test fun debugTestKeyIsAdditionalAndNeverLoadedByTheReleasePath() {
        val visited = mutableListOf<String>()
        fun open(path: String) = (if (path.endsWith("debug-keys.json")) UpdateFixture.bytes(fixture.debug()) else UpdateFixture.bytes(fixture.production()))
            .inputStream().also { visited.add(path) }
        val release = UpdateKeys.loadAssets(::open, false)
        assertEquals(listOf("update/keys.json"), visited)
        val signature = fixture.sign(message, fixture.test)
        assertThrows(SignedDocument.Rejected::class.java) { release.read(UpdateKeys.Role.CHANNEL, SignedDocument.Kind.CATALOG, message, signature) { } }
        val debug = UpdateKeys.loadAssets(::open, true)
        for (role in UpdateKeys.Role.entries) assertEquals(1, debug.read(role, SignedDocument.Kind.CATALOG, message, signature) { 1 })
    }

    @Test fun absentDebugAssetIsOptionalButMissingProductionKeysNeverFallBack() {
        val keys = UpdateKeys.loadAssets({ path -> if (path.endsWith("debug-keys.json")) throw FileNotFoundException() else UpdateFixture.bytes(fixture.production()).inputStream() }, true)
        assertEquals(1, keys.read(UpdateKeys.Role.CHANNEL, SignedDocument.Kind.CATALOG, message, fixture.sign(message)) { 1 })
        assertThrows(FileNotFoundException::class.java) { UpdateKeys.loadAssets({ throw FileNotFoundException() }, true) }
    }

    @Test fun duplicateIdsAndKeyReuseAcrossRolesAreRejected() {
        for (sameId in listOf(false, true)) {
            val json = fixture.production()
            json.getJSONObject("registry").put("active", fixture.key(if (sameId) "channel" else "other", if (sameId) fixture.registry else fixture.active))
            assertThrows(IllegalArgumentException::class.java) { UpdateKeys.parse(UpdateFixture.bytes(json)) }
        }
        assertThrows(IllegalArgumentException::class.java) { UpdateKeys.parse(UpdateFixture.bytes(fixture.production()), UpdateFixture.bytes(fixture.debug().put("test", fixture.key("debug", fixture.active)))) }
    }

    @Test fun wrongCurvesAlgorithmsMalformedAndNoncanonicalSpkiAreRejected() {
        val other = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val variants = listOf(fixture.key("channel", other), fixture.key("channel", fixture.active).put("algorithm", "RSA"),
            fixture.key("channel", fixture.active).put("spki", "a".repeat(16)),
            fixture.key("channel", fixture.active).put("spki", fixture.key("x", fixture.active).getString("spki").trimEnd('=')),
            fixture.key("channel", fixture.active).put("spki", java.util.Base64.getEncoder().encodeToString(fixture.active.public.encoded + 0.toByte())))
        for (entry in variants) {
            val json = fixture.production(); json.getJSONObject("channel").put("active", entry)
            assertThrows(Exception::class.java) { UpdateKeys.parse(UpdateFixture.bytes(json)) }
        }
    }

    @Test fun keyAssetsHaveClosedFieldsAndHardBounds() {
        assertThrows(IllegalArgumentException::class.java) { UpdateKeys.parse(UpdateFixture.bytes(fixture.production().put("downloaded", JSONObject()))) }
        assertThrows(IllegalArgumentException::class.java) { UpdateKeys.parse(ByteArray(65_537)) }
        assertThrows(IllegalArgumentException::class.java) { UpdateKeys.loadAssets({ ByteArray(65_537).inputStream() }, false) }
        assertThrows(IllegalArgumentException::class.java) { UpdateKeys.parse(UpdateFixture.bytes(fixture.production()), ByteArray(4097)) }
    }

    @Test fun decodingIsAfterAuthenticationAndRunsOnceEvenWhenItFails() {
        var calls = 0
        val keys = fixture.keys()
        val sentinel = IllegalStateException("decoder")
        val error = assertThrows(IllegalStateException::class.java) {
            keys.read(UpdateKeys.Role.CHANNEL, SignedDocument.Kind.CATALOG, message, fixture.sign(message, fixture.backup)) { calls++; throw sentinel }
        }
        assertSame(sentinel, error); assertEquals(1, calls)
    }
}
