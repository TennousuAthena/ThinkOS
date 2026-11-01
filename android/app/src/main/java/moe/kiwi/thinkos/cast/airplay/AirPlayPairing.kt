package moe.kiwi.thinkos.cast.airplay

import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The device's AirPlay identity: an Ed25519 key that stays the same across restarts, so a sender that remembers it keeps trusting
 * the device. It only identifies, it protects nothing: pairing here is anonymous, see [AirPlayPairing].
 */
internal class AirPlayIdentity(private val seed: ByteArray) {
    init { require(seed.size == 32) { "An identity seed is 32 bytes" } }
    val publicKey: ByteArray by lazy { Curve25519.ed25519PublicKey(seed) }
    fun sign(message: ByteArray): ByteArray = Curve25519.ed25519Sign(seed, message)

    companion object {
        /** Reads the seed from [file], creating it (owner-only) the first time. */
        fun load(file: File): AirPlayIdentity {
            val existing = runCatching { file.readBytes() }.getOrNull()
            if (existing != null && existing.size == 32) return AirPlayIdentity(existing)
            val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
            file.parentFile?.mkdirs()
            file.writeBytes(seed)
            runCatching { file.setReadable(false, false); file.setReadable(true, true); file.setWritable(false, false); file.setWritable(true, true) }
            return AirPlayIdentity(seed)
        }
    }
}

/**
 * The two-step handshake an AirPlay sender runs before it will talk to a receiver (the raw, pre-HomeKit form that RPiPlay and
 * UxPlay document):
 *
 *  - `/pair-setup`: the sender gives its 32-byte Ed25519 key, the receiver answers with its own.
 *  - `/pair-verify` M1: the sender gives an X25519 key and its Ed25519 key; the receiver answers with its X25519 key and
 *    a signature over both X25519 keys, encrypted with AES-CTR under keys derived from the shared secret.
 *  - `/pair-verify` M2: the sender's signature, which must verify under the Ed25519 key it gave in M1.
 *
 * Nothing is remembered about the sender afterwards, and nothing it proves is kept secret: the receiver accepts any sender that
 * completes the handshake. The access decision is the person's setting, not this exchange. One instance per connection.
 */
internal class AirPlayPairing(private val identity: AirPlayIdentity, private val random: SecureRandom = SecureRandom()) {
    private var oursEcdh: ByteArray? = null
    private var theirsEcdh: ByteArray? = null
    private var theirsEd: ByteArray? = null
    private var cipher: Cipher? = null

    /** The X25519 secret of a completed `pair-verify` M1; mirroring derives its stream keys from it. Null before that. */
    @Volatile var sharedSecret: ByteArray? = null
        private set

    class Refused(message: String) : Exception(message)

    fun pairSetup(body: ByteArray): ByteArray {
        if (body.size != 32) throw Refused("pair-setup takes a 32-byte key, got ${body.size}")
        return identity.publicKey
    }

    fun pairVerify(body: ByteArray): ByteArray {
        if (body.size < 4) throw Refused("pair-verify body too short")
        return when (body[0].toInt()) {
            1 -> first(body)
            0 -> second(body)
            else -> throw Refused("unknown pair-verify state")
        }
    }

    private fun first(body: ByteArray): ByteArray {
        if (body.size != 4 + 32 + 32) throw Refused("pair-verify M1 has the wrong size")
        val theirs = body.copyOfRange(4, 36)
        theirsEcdh = theirs; theirsEd = body.copyOfRange(36, 68)
        val private = ByteArray(32).also { random.nextBytes(it) }
        val ours = Curve25519.x25519Base(private)
        oursEcdh = ours
        val secret = Curve25519.x25519(private, theirs)
        if (secret.all { it == 0.toByte() }) throw Refused("degenerate shared secret")
        sharedSecret = secret
        val aes = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive("Pair-Verify-AES-Key", secret), "AES"), IvParameterSpec(derive("Pair-Verify-AES-IV", secret)))
        }
        cipher = aes
        return ours + aes.update(identity.sign(ours + theirs))
    }

    private fun second(body: ByteArray): ByteArray {
        if (body.size != 4 + 64) throw Refused("pair-verify M2 has the wrong size")
        val aes = cipher ?: throw Refused("pair-verify M2 before M1")
        val ours = oursEcdh ?: throw Refused("pair-verify M2 before M1")
        val theirs = theirsEcdh ?: throw Refused("pair-verify M2 before M1")
        val ed = theirsEd ?: throw Refused("pair-verify M2 before M1")
        val signature = aes.update(body.copyOfRange(4, 68))          // the keystream goes on where M1 left it
        if (!Curve25519.ed25519Verify(ed, theirs + ours, signature)) throw Refused("pair-verify signature does not match")
        return ByteArray(0)
    }

    private fun derive(salt: String, secret: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(salt.toByteArray(Charsets.US_ASCII) + secret).copyOf(16)
}
