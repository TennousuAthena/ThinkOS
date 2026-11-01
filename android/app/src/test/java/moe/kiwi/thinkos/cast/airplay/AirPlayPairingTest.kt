package moe.kiwi.thinkos.cast.airplay

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** The pairing handshake with the test playing the sender. */
class AirPlayPairingTest {
    private val identity = AirPlayIdentity(ByteArray(32) { (it * 3).toByte() })

    private fun derive(salt: String, secret: ByteArray) = MessageDigest.getInstance("SHA-512").digest(salt.toByteArray() + secret).copyOf(16)

    private class Sender {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ed = Curve25519.ed25519PublicKey(seed)
        val ecdhPrivate = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val ecdh = Curve25519.x25519Base(ecdhPrivate)
    }

    @Test fun pairSetupReturnsTheDevicesKey() {
        val pairing = AirPlayPairing(identity)
        assertArrayEquals(identity.publicKey, pairing.pairSetup(ByteArray(32)))
        assertThrows(AirPlayPairing.Refused::class.java) { pairing.pairSetup(ByteArray(31)) }
    }

    @Test fun aSenderCompletesPairVerifyAndCanCheckTheDevicesSignature() {
        val sender = Sender()
        val pairing = AirPlayPairing(identity)
        val m1 = pairing.pairVerify(byteArrayOf(1, 0, 0, 0) + sender.ecdh + sender.ed)
        assertEquals(96, m1.size)
        val theirs = m1.copyOfRange(0, 32)
        val secret = Curve25519.x25519(sender.ecdhPrivate, theirs)
        val aes = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(derive("Pair-Verify-AES-Key", secret), "AES"), IvParameterSpec(derive("Pair-Verify-AES-IV", secret)))
        }
        val deviceSignature = aes.update(m1.copyOfRange(32, 96))
        assertTrue("the device signs (its key, the sender's key) with its identity", Curve25519.ed25519Verify(identity.publicKey, theirs + sender.ecdh, deviceSignature))
        // M2: the sender signs (its key, the device's key); the keystream continues where M1 left it.
        val enc = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(derive("Pair-Verify-AES-Key", secret), "AES"), IvParameterSpec(derive("Pair-Verify-AES-IV", secret)))
        }
        enc.update(ByteArray(64))                                        // skip the 64 bytes the device used
        val m2 = byteArrayOf(0, 0, 0, 0) + enc.update(Curve25519.ed25519Sign(sender.seed, sender.ecdh + theirs))
        assertEquals(0, pairing.pairVerify(m2).size)
    }

    @Test fun aWrongSignatureIsRefused() {
        val sender = Sender()
        val pairing = AirPlayPairing(identity)
        pairing.pairVerify(byteArrayOf(1, 0, 0, 0) + sender.ecdh + sender.ed)
        assertThrows(AirPlayPairing.Refused::class.java) { pairing.pairVerify(byteArrayOf(0, 0, 0, 0) + ByteArray(64) { 7 }) }
    }

    @Test fun m2BeforeM1AndBadSizesAreRefused() {
        assertThrows(AirPlayPairing.Refused::class.java) { AirPlayPairing(identity).pairVerify(byteArrayOf(0, 0, 0, 0) + ByteArray(64)) }
        assertThrows(AirPlayPairing.Refused::class.java) { AirPlayPairing(identity).pairVerify(byteArrayOf(1, 0, 0, 0) + ByteArray(10)) }
        assertThrows(AirPlayPairing.Refused::class.java) { AirPlayPairing(identity).pairVerify(byteArrayOf(9, 0, 0, 0)) }
        assertThrows(AirPlayPairing.Refused::class.java) { AirPlayPairing(identity).pairVerify(ByteArray(2)) }
    }

    @Test fun aZeroKeyCannotForceAKnownSecret() {
        assertThrows(AirPlayPairing.Refused::class.java) { AirPlayPairing(identity).pairVerify(byteArrayOf(1, 0, 0, 0) + ByteArray(32) + ByteArray(32)) }
    }

    @Test fun theIdentityIsKeptAcrossRestarts() {
        val file = java.io.File.createTempFile("airplay-identity", ".bin").also { it.delete() }
        try {
            val a = AirPlayIdentity.load(file); val b = AirPlayIdentity.load(file)
            assertArrayEquals(a.publicKey, b.publicKey)
            assertEquals(32, file.length())
        } finally { file.delete() }
    }
}
