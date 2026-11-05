package moe.kiwi.thinkos.update

import java.security.AlgorithmParameters
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec

/** Authenticates raw document bytes before any decoder runs (docs/34 §3).
 * The caller supplies a purpose-specific trusted key; a key from a download is
 * never a trust root. No network, clock, parser or self-written crypto here. */
internal object SignedDocument {
    /** Protocol bounds, shared by OTA and registry documents. Not user settings. */
    enum class Kind(val maxBytes: Int) { CATALOG(1024 * 1024), MANIFEST(64 * 1024) }
    private const val MAX_SIGNATURE_BYTES = 1024
    private val p256: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    class Rejected(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

    /** The decoder receives an owned snapshot only after successful verification.
     * Changes to the caller's input buffers cannot change the bytes it parses. */
    fun <T> read(kind: Kind, bytes: ByteArray, signature: ByteArray, trustedKey: PublicKey, decode: (ByteArray) -> T): T {
        return read(kind, bytes, signature, listOf(trustedKey), decode)
    }

    /** Active/backup and at most one debug key. Decoder failures never cause another key to be tried. */
    fun <T> read(kind: Kind, bytes: ByteArray, signature: ByteArray, trustedKeys: List<PublicKey>, decode: (ByteArray) -> T): T {
        if (bytes.isEmpty() || bytes.size > kind.maxBytes) throw Rejected("Document size exceeds protocol bounds")
        if (signature.isEmpty() || signature.size > MAX_SIGNATURE_BYTES) throw Rejected("Signature size exceeds protocol bounds")
        val document = bytes.copyOf()
        val signed = signature.copyOf()
        val keys = trustedKeys.toList()
        if (keys.size !in 1..3) throw Rejected("Missing or excessive trusted signing keys")
        val accepted = try {
            keys.forEach(::requireP256)
            keys.any { key ->
                Signature.getInstance("SHA256withECDSA").run {
                    initVerify(key)
                    update(document)
                    verify(signed)
                }
            }
        } catch (error: GeneralSecurityException) {
            throw Rejected("Unable to verify document signature", error)
        }
        if (!accepted) throw Rejected("Invalid document signature")
        return decode(document)
    }

    internal fun requireP256(key: PublicKey): ECPublicKey {
        val ec = key as? ECPublicKey ?: throw Rejected("Expected a P-256 public key")
        val parameters = ec.params ?: throw Rejected("Missing EC parameters")
        if (parameters.curve != p256.curve || parameters.generator != p256.generator ||
            parameters.order != p256.order || parameters.cofactor != p256.cofactor) throw Rejected("Expected a P-256 public key")
        return ec
    }
}
