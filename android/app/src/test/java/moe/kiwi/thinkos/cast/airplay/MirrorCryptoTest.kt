package moe.kiwi.thinkos.cast.airplay

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class MirrorCryptoTest {
    private val aesKey = ByteArray(16) { (it + 1).toByte() }
    private val secret = ByteArray(32) { (it * 7).toByte() }

    private fun sha512(b: ByteArray) = MessageDigest.getInstance("SHA-512").digest(b)

    @Test fun theVideoKeystreamIsOneCtrStreamAcrossPayloads() {
        // The sender side of the same arithmetic, written out independently.
        val e = sha512(aesKey + secret).copyOf(16)
        val key = sha512("AirPlayStreamKey18446744073709551615".toByteArray() + e).copyOf(16)
        val iv = sha512("AirPlayStreamIV18446744073709551615".toByteArray() + e).copyOf(16)
        val sender = Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        val first = ByteArray(1000) { it.toByte() }; val second = ByteArray(333) { (it * 3).toByte() }
        val sent1 = sender.update(first); val sent2 = sender.update(second)
        val receiver = MirrorCrypto.videoCipher(aesKey, secret, -1L)               // the id is an unsigned 64-bit number: -1 is 18446744073709551615
        assertArrayEquals(first, receiver.update(sent1)); assertArrayEquals(second, receiver.update(sent2))
    }

    @Test fun theStreamConnectionIdChangesTheKeys() {
        val a = MirrorCrypto.videoCipher(aesKey, secret, 1).update(ByteArray(16)); val b = MirrorCrypto.videoCipher(aesKey, secret, 2).update(ByteArray(16))
        assertFalse(a.contentEquals(b))
    }

    @Test fun audioPacketsAreCbcPerPacketFromTheSameIvWithAClearTail() {
        val key = MirrorCrypto.audioKey(aesKey, secret)
        assertArrayEquals(sha512(aesKey + secret).copyOf(16), key)
        val iv = ByteArray(16) { (0xA0 + it).toByte() }
        val plain = ByteArray(37) { (it * 5).toByte() }                              // two whole blocks and a tail of five bytes
        val sender = Cipher.getInstance("AES/CBC/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        val sent = sender.doFinal(plain, 0, 32) + plain.copyOfRange(32, 37)
        assertArrayEquals(plain, MirrorCrypto.decryptAudio(key, iv, sent))
        assertArrayEquals(plain, MirrorCrypto.decryptAudio(key, iv, sent))           // and again: nothing is carried from packet to packet
        val tiny = byteArrayOf(1, 2, 3); assertArrayEquals(tiny, MirrorCrypto.decryptAudio(key, iv, tiny))
    }

    @Test fun avccBecomesAnnexB() {
        val frame = byteArrayOf(0, 0, 0, 3, 0x65, 1, 2, 0, 0, 0, 2, 0x41, 9)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x65, 1, 2, 0, 0, 0, 1, 0x41, 9), Avc.avccToAnnexB(frame))
        assertTrue(Avc.avccToAnnexB(byteArrayOf(0, 0, 0, 99, 1, 2)).isEmpty())                    // a length that does not fit: nothing, not garbage
        assertTrue(Avc.avccToAnnexB(byteArrayOf(0, 0, 0, 0, 1)).isEmpty())
        assertTrue(Avc.avccToAnnexB(ByteArray(0)).isEmpty())
    }

    @Test fun theAvcCRecordYieldsSpsAndPps() {
        val sps = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1F, 0x8C.toByte()); val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
        val record = byteArrayOf(1, 0x42, 0xC0.toByte(), 0x1F, 0xFF.toByte(), 0xE1.toByte(), 0, sps.size.toByte()) + sps + byteArrayOf(1, 0, pps.size.toByte()) + pps
        val config = Avc.parseConfig(record)!!
        assertArrayEquals(sps, config.sps); assertArrayEquals(pps, config.pps)
        assertNull(Avc.parseConfig(ByteArray(5))); assertNull(Avc.parseConfig(record.copyOf(record.size - 2)))
        assertNull(Avc.parseConfig(record.also { it[0] = 2 }))
    }

    @Test fun keyframesAreRecognised() {
        assertEquals(listOf(7, 8, 5), Avc.nalTypes(byteArrayOf(0, 0, 0, 1, 0x67, 1, 0, 0, 0, 1, 0x68, 2, 0, 0, 1, 0x65, 3)))
        assertTrue(Avc.isKeyframe(byteArrayOf(0, 0, 0, 1, 0x65, 1))); assertFalse(Avc.isKeyframe(byteArrayOf(0, 0, 0, 1, 0x41, 1)))
    }
}
