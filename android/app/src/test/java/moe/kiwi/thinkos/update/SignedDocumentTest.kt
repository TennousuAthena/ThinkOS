package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** All keys are ephemeral test fixtures generated in memory. No trust-root
 * private key, certificate or keystore is written to files or logs. */
class SignedDocumentTest {
    private fun ec(curve: String = "secp256r1"): KeyPair = KeyPairGenerator.getInstance("EC")
        .apply { initialize(ECGenParameterSpec(curve)) }.generateKeyPair()
    private val key = ec()
    private val message = "{\"seq\":7,\"code\":1001}".toByteArray(Charsets.UTF_8)
    private fun sign(bytes: ByteArray, signing: KeyPair = key): ByteArray = Signature.getInstance("SHA256withECDSA").run {
        initSign(signing.private); update(bytes); sign()
    }
    private fun rejects(bytes: ByteArray, signed: ByteArray, publicKey: java.security.PublicKey = key.public,
                        kind: SignedDocument.Kind = SignedDocument.Kind.MANIFEST) {
        var decoded = false
        assertThrows(SignedDocument.Rejected::class.java) {
            SignedDocument.read(kind, bytes, signed, publicKey) { decoded = true; it }
        }
        assertFalse("Untrusted bytes must never reach a parser", decoded)
    }

    @Test fun validRawBytesReachTheDecoderExactlyOnce() {
        var calls = 0
        val decoded = SignedDocument.read(SignedDocument.Kind.MANIFEST, message, sign(message), key.public) {
            calls++; String(it, Charsets.UTF_8)
        }
        assertEquals(1, calls)
        assertEquals(String(message, Charsets.UTF_8), decoded)
    }

    @Test fun whitespaceOrOneChangedByteNeedsANewSignature() {
        val signed = sign(message)
        rejects(message + ' '.code.toByte(), signed)
        val changed = message.copyOf().also { it[7] = '8'.code.toByte() }
        rejects(changed, signed)
    }

    @Test fun anotherP256KeyCannotAuthenticateTheDocument() {
        rejects(message, sign(message), ec().public)
    }

    @Test fun otherCurvesAndAlgorithmsAreRejectedEvenWithValidSignatures() {
        val other = ec("secp384r1")
        rejects(message, sign(message, other), other.public)
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rsaSignature = Signature.getInstance("SHA256withRSA").run { initSign(rsa.private); update(message); sign() }
        rejects(message, rsaSignature, rsa.public)
    }

    @Test fun emptyOrOversizedInputsNeverReachTheDecoder() {
        rejects(ByteArray(0), sign(ByteArray(0)))
        rejects(message, ByteArray(0))
        rejects(message, ByteArray(1025))
        val manifest = ByteArray(SignedDocument.Kind.MANIFEST.maxBytes + 1) { 1 }
        rejects(manifest, sign(manifest))
        val catalog = ByteArray(SignedDocument.Kind.CATALOG.maxBytes + 1) { 1 }
        rejects(catalog, sign(catalog), kind = SignedDocument.Kind.CATALOG)
    }

    @Test fun documentAtTheCatalogBoundaryStillAuthenticates() {
        val bytes = ByteArray(SignedDocument.Kind.CATALOG.maxBytes) { 9 }
        val result = SignedDocument.read(SignedDocument.Kind.CATALOG, bytes, sign(bytes), key.public) { it.size }
        assertEquals(bytes.size, result)
    }

    @Test fun malformedOrTruncatedDerIsRejectedBeforeParsing() {
        val signed = sign(message)
        rejects(message, signed.copyOf(signed.size - 1))
        for (size in listOf(1, 2, 8, 64, 71, 128, 1024)) rejects(message, ByteArray(size) { 0x7f })
    }

    @Test fun theDecoderOwnsASnapshotIndependentOfTheCallersBuffer() {
        val original = message.copyOf()
        val signed = sign(original)
        SignedDocument.read(SignedDocument.Kind.MANIFEST, original, signed, key.public) { verified ->
            assertNotSame(original, verified)
            original.fill(0); signed.fill(0)
            assertArrayEquals(message, verified)
        }
    }

    @Test fun decoderErrorsRemainDecoderErrorsAfterAuthentication() {
        val expected = IllegalStateException("Invalid manifest fields")
        val error = assertThrows(IllegalStateException::class.java) {
            SignedDocument.read(SignedDocument.Kind.MANIFEST, message, sign(message), key.public) { throw expected }
        }
        assertSame(expected, error)
    }
}
