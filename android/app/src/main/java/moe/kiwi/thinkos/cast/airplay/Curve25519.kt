package moe.kiwi.thinkos.cast.airplay

import java.math.BigInteger
import java.security.MessageDigest

/**
 * X25519 (RFC 7748) and Ed25519 (RFC 8032), the two curve operations AirPlay's pairing handshake uses. Android 8.1 offers neither,
 * and one handshake per connection does not justify a cryptography library, so this is a plain BigInteger implementation: short,
 * checked against the RFC test vectors (Curve25519Test), not fast, and not hardened against timing attacks. That is acceptable here:
 * the pairing is anonymous (the receiver learns nothing it could keep secret, and accepts any sender), it exists only because the
 * sender will not continue without it.
 */
internal object Curve25519 {
    private val P = BigInteger.ONE.shiftLeft(255) - BigInteger.valueOf(19)
    private val TWO = BigInteger.valueOf(2)

    private fun inv(x: BigInteger) = x.modPow(P - TWO, P)

    private fun decodeLe(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    private fun encodeLe(value: BigInteger, size: Int = 32): ByteArray {
        val be = value.toByteArray()
        val out = ByteArray(size)
        for (i in 0 until minOf(size, be.size)) out[i] = be[be.size - 1 - i]
        return out
    }

    // ---- X25519 ---------------------------------------------------------------------------------------------------------------------

    private val A24 = BigInteger.valueOf(121665)

    fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
        require(scalar.size == 32 && u.size == 32) { "X25519 takes 32-byte values" }
        val k = scalar.copyOf()
        k[0] = (k[0].toInt() and 248).toByte(); k[31] = (k[31].toInt() and 127 or 64).toByte()
        val kInt = decodeLe(k)
        val uMasked = u.copyOf().also { it[31] = (it[31].toInt() and 127).toByte() }
        val x1 = decodeLe(uMasked) .mod(P)
        var x2 = BigInteger.ONE; var z2 = BigInteger.ZERO; var x3 = x1; var z3 = BigInteger.ONE
        var swap = false
        for (t in 254 downTo 0) {
            val bit = kInt.testBit(t)
            if (swap != bit) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
            swap = bit
            val a = (x2 + z2).mod(P); val aa = (a * a).mod(P)
            val b = (x2 - z2).mod(P); val bb = (b * b).mod(P)
            val e = (aa - bb).mod(P)
            val c = (x3 + z3).mod(P); val d = (x3 - z3).mod(P)
            val da = (d * a).mod(P); val cb = (c * b).mod(P)
            x3 = (da + cb).pow(2).mod(P)
            z3 = (x1 * (da - cb).pow(2)).mod(P)
            x2 = (aa * bb).mod(P)
            z2 = (e * (aa + A24 * e)).mod(P)
        }
        if (swap) { val tx = x2; x2 = x3; x3 = tx; val tz = z2; z2 = z3; z3 = tz }
        return encodeLe((x2 * inv(z2)).mod(P))
    }

    fun x25519Base(scalar: ByteArray): ByteArray = x25519(scalar, ByteArray(32).also { it[0] = 9 })

    // ---- Ed25519 --------------------------------------------------------------------------------------------------------------------

    private val L = BigInteger.ONE.shiftLeft(252) + BigInteger("27742317777372353535851937790883648493")
    private val D = (BigInteger.valueOf(-121665) * inv(BigInteger.valueOf(121666))).mod(P)
    private val SQRT_M1 = TWO.modPow((P - BigInteger.ONE).shiftRight(2), P)

    /** A point in extended coordinates (X, Y, Z, T) with x = X/Z, y = Y/Z, xy = T/Z. */
    private class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    private fun add(p: Point, q: Point): Point {
        val a = ((p.y - p.x) * (q.y - q.x)).mod(P)
        val b = ((p.y + p.x) * (q.y + q.x)).mod(P)
        val c = (TWO * p.t * q.t * D).mod(P)
        val d = (TWO * p.z * q.z).mod(P)
        val e = b - a; val f = d - c; val g = d + c; val h = b + a
        return Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    }

    private fun multiply(scalar: BigInteger, point: Point): Point {
        var result = IDENTITY
        var addend = point
        var k = scalar
        while (k.signum() > 0) {
            if (k.testBit(0)) result = add(result, addend)
            addend = add(addend, addend)
            k = k.shiftRight(1)
        }
        return result
    }

    private fun encode(p: Point): ByteArray {
        val zi = inv(p.z)
        val x = (p.x * zi).mod(P); val y = (p.y * zi).mod(P)
        val out = encodeLe(y)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    private fun decode(bytes: ByteArray): Point? {
        if (bytes.size != 32) return null
        val sign = bytes[31].toInt() and 0x80 != 0
        val y = decodeLe(bytes.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() })
        if (y >= P) return null
        val y2 = (y * y).mod(P)
        val x2 = ((y2 - BigInteger.ONE) * inv(D * y2 + BigInteger.ONE)).mod(P)
        if (x2.signum() == 0) return if (sign) null else Point(BigInteger.ZERO, y, BigInteger.ONE, BigInteger.ZERO)
        var x = x2.modPow((P + BigInteger.valueOf(3)).shiftRight(3), P)
        if ((x * x - x2).mod(P).signum() != 0) x = (x * SQRT_M1).mod(P)
        if ((x * x - x2).mod(P).signum() != 0) return null
        if (x.testBit(0) != sign) x = P - x
        return Point(x, y, BigInteger.ONE, (x * y).mod(P))
    }

    private val BASE: Point by lazy {
        val y = (BigInteger.valueOf(4) * inv(BigInteger.valueOf(5))).mod(P)
        val encoded = encodeLe(y)                       // the base point has an even x
        decode(encoded)!!
    }

    private fun sha512(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").also { md -> parts.forEach(md::update) }.digest()

    private fun clamp(h: ByteArray): BigInteger {
        val a = h.copyOf(32)
        a[0] = (a[0].toInt() and 248).toByte(); a[31] = (a[31].toInt() and 127 or 64).toByte()
        return decodeLe(a)
    }

    fun ed25519PublicKey(seed: ByteArray): ByteArray {
        require(seed.size == 32) { "An Ed25519 seed is 32 bytes" }
        return encode(multiply(clamp(sha512(seed)), BASE))
    }

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        require(seed.size == 32) { "An Ed25519 seed is 32 bytes" }
        val h = sha512(seed)
        val a = clamp(h)
        val publicKey = encode(multiply(a, BASE))
        val r = decodeLe(sha512(h.copyOfRange(32, 64), message)).mod(L)
        val rEncoded = encode(multiply(r, BASE))
        val k = decodeLe(sha512(rEncoded, publicKey, message)).mod(L)
        val s = (r + k * a).mod(L)
        return rEncoded + encodeLe(s)
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        if (publicKey.size != 32 || signature.size != 64) return false
        val a = decode(publicKey) ?: return false
        val r = decode(signature.copyOfRange(0, 32)) ?: return false
        val s = decodeLe(signature.copyOfRange(32, 64))
        if (s >= L) return false
        val k = decodeLe(sha512(signature.copyOfRange(0, 32), publicKey, message)).mod(L)
        val left = multiply(s, BASE)
        val right = add(r, multiply(k, a))
        return encode(left).contentEquals(encode(right))
    }
}
