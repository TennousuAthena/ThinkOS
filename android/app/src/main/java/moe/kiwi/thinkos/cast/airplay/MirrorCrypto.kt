package moe.kiwi.thinkos.cast.airplay

import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * What an AirPlay mirror stream is wrapped in, and how to take it off (the scheme RPiPlay documents in lib/mirror_buffer.c and
 * lib/raop_buffer.c). Pure functions: tested against the senders' side of the same arithmetic.
 */
internal object MirrorCrypto {
    /**
     * Video: one AES-128-CTR keystream over the concatenated payloads of a connection, key and IV from
     * SHA-512("AirPlayStreamKey<id>" ‖ e)[:16] and SHA-512("AirPlayStreamIV<id>" ‖ e)[:16], with e = SHA-512(aesKey ‖ ecdhSecret)[:16] and
     * `<id>` the unsigned decimal streamConnectionID of the SETUP.
     */
    fun videoCipher(aesKey: ByteArray, ecdhSecret: ByteArray, streamConnectionId: Long): Cipher {
        val e = sha512(aesKey + ecdhSecret).copyOf(16)
        val id = java.lang.Long.toUnsignedString(streamConnectionId)
        val key = sha512("AirPlayStreamKey$id".toByteArray(Charsets.US_ASCII) + e).copyOf(16)
        val iv = sha512("AirPlayStreamIV$id".toByteArray(Charsets.US_ASCII) + e).copyOf(16)
        return Cipher.getInstance("AES/CTR/NoPadding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
    }

    /** Audio: AES-128-CBC per packet with key SHA-512(aesKey ‖ ecdhSecret)[:16] and the IV from the SETUP (`eiv`). */
    fun audioKey(aesKey: ByteArray, ecdhSecret: ByteArray): ByteArray = sha512(aesKey + ecdhSecret).copyOf(16)

    /** Each packet starts from the same IV; only whole 16-byte blocks are encrypted, a shorter tail is left as it is. */
    fun decryptAudio(key: ByteArray, iv: ByteArray, payload: ByteArray): ByteArray {
        val whole = payload.size / 16 * 16
        if (whole == 0) return payload
        val out = payload.copyOf()
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv.copyOf(16)))
        cipher.doFinal(payload, 0, whole, out, 0)
        return out
    }

    private fun sha512(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(bytes)
}

/** H.264 as the sender packs it (4-byte length before each NAL) and as the transport stream wants it (start codes). */
internal object Avc {
    val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** A frame as sent: a sequence of (4-byte big-endian length, NAL). Stops at the first length that does not fit. */
    fun avccToAnnexB(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 16)
        var i = 0
        while (i + 4 <= data.size) {
            val len = ((data[i].toInt() and 0xff) shl 24) or ((data[i + 1].toInt() and 0xff) shl 16) or ((data[i + 2].toInt() and 0xff) shl 8) or (data[i + 3].toInt() and 0xff)
            i += 4
            if (len <= 0 || len > data.size - i) break
            out.write(START_CODE); out.write(data, i, len)
            i += len
        }
        return out.toByteArray()
    }

    class Config(val sps: ByteArray, val pps: ByteArray)

    /** The `avcC` record that opens a mirror stream: one SPS and one PPS are read from it (more are ignored). Null when it is not one. */
    fun parseConfig(payload: ByteArray): Config? {
        if (payload.size < 11 || payload[0].toInt() != 1) return null
        val spsSize = ((payload[6].toInt() and 0xff) shl 8) or (payload[7].toInt() and 0xff)
        val ppsCountAt = 8 + spsSize
        if (spsSize <= 0 || ppsCountAt + 3 > payload.size) return null
        val ppsSize = ((payload[ppsCountAt + 1].toInt() and 0xff) shl 8) or (payload[ppsCountAt + 2].toInt() and 0xff)
        val ppsAt = ppsCountAt + 3
        if (ppsSize <= 0 || ppsAt + ppsSize > payload.size) return null
        return Config(payload.copyOfRange(8, 8 + spsSize), payload.copyOfRange(ppsAt, ppsAt + ppsSize))
    }

    /** NAL unit types present in an Annex-B access unit. */
    fun nalTypes(annexB: ByteArray): List<Int> {
        val types = ArrayList<Int>()
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) { types += annexB[i + 3].toInt() and 0x1f; i += 3 } else i++
        }
        return types
    }

    fun isKeyframe(annexB: ByteArray): Boolean = 5 in nalTypes(annexB)
}
