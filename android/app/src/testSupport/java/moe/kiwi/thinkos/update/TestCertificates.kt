package moe.kiwi.thinkos.update

import java.math.BigInteger
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.security.auth.x500.X500Principal

/** Minimal test-only DER encoding. Ephemeral signing keys never leave memory. */
internal object TestCertificates {
    data class Identity(val keys: KeyPair, val cert: X509Certificate)
    fun root() = issue(null, true)
    fun intermediate(root: Identity) = issue(root, true)
    fun leaf(root: Identity, hostname: String = "localhost", expired: Boolean = false) = issue(root, false, hostname, expired)

    private fun issue(issuer: Identity?, ca: Boolean, hostname: String = "localhost", expired: Boolean = false): Identity {
        val keys = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
        val name = X500Principal("CN=${if (ca) "ThinkOS ephemeral CA ${UUID.randomUUID()}" else hostname}").encoded
        val signatureAlgorithm = seq(oid("1.2.840.10045.4.3.2"))
        val now = System.currentTimeMillis()
        fun utc(millis: Long) = der(0x17, SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(millis)).toByteArray())
        fun extension(oid: String, content: ByteArray, critical: Boolean = false) =
            if (critical) seq(oid(oid), der(1, byteArrayOf(-1)), der(4, content)) else seq(oid(oid), der(4, content))
        val extensions = mutableListOf(
            extension("2.5.29.19", if (ca) seq(der(1, byteArrayOf(-1))) else seq(), true),
            extension("2.5.29.15", der(3, if (ca) byteArrayOf(1, 6) else byteArrayOf(7, 0x80.toByte())), true),
        )
        if (!ca) {
            extensions.add(extension("2.5.29.37", seq(oid("1.3.6.1.5.5.7.3.1"))))
            extensions.add(extension("2.5.29.17", seq(der(0x82, hostname.toByteArray()))))
        }
        val tbs = seq(der(0xa0, der(2, byteArrayOf(2))), der(2, BigInteger(1, UUID.randomUUID().toString().replace("-", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()).toByteArray()),
            signatureAlgorithm, issuer?.cert?.subjectX500Principal?.encoded ?: name,
            seq(utc(now - 3 * DAY), utc(now + if (expired) -DAY else DAY)), name, keys.public.encoded,
            der(0xa3, seq(*extensions.toTypedArray())))
        val signed = Signature.getInstance("SHA256withECDSA").run { initSign(issuer?.keys?.private ?: keys.private); update(tbs); sign() }
        val encoded = seq(tbs, signatureAlgorithm, der(3, byteArrayOf(0) + signed))
        val cert = CertificateFactory.getInstance("X.509").generateCertificate(encoded.inputStream()) as X509Certificate
        cert.verify(issuer?.keys?.public ?: keys.public)
        return Identity(keys, cert)
    }

    fun server(identity: Identity, issuer: Identity): ServerSocket {
        val password = UUID.randomUUID().toString().toCharArray()
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null); setKeyEntry("leaf", identity.keys.private, password, arrayOf(identity.cert, issuer.cert))
        }
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, password) }
        password.fill('\u0000')
        return SSLContext.getInstance("TLS").apply { init(keys.keyManagers, null, null) }.serverSocketFactory
            .createServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    }

    fun pem(cert: X509Certificate) = "-----BEGIN CERTIFICATE-----\n" +
        java.util.Base64.getMimeEncoder(64, byteArrayOf(10)).encodeToString(cert.encoded) + "\n-----END CERTIFICATE-----\n"

    private fun seq(vararg bytes: ByteArray) = der(0x30, bytes.fold(byteArrayOf()) { result, next -> result + next })
    private fun der(tag: Int, bytes: ByteArray): ByteArray {
        val length = if (bytes.size < 128) byteArrayOf(bytes.size.toByte()) else {
            val count = if (bytes.size <= 255) 1 else 2
            byteArrayOf((0x80 + count).toByte()) + (count - 1 downTo 0).map { (bytes.size ushr (it * 8)).toByte() }.toByteArray()
        }
        return byteArrayOf(tag.toByte()) + length + bytes
    }
    private fun oid(value: String): ByteArray {
        val arcs = value.split('.').map(String::toInt)
        val bytes = mutableListOf((40 * arcs[0] + arcs[1]).toByte())
        for (arc in arcs.drop(2)) {
            var rest = arc
            val encoded = mutableListOf((rest and 127).toByte()); rest = rest ushr 7
            while (rest > 0) { encoded.add(0, ((rest and 127) or 128).toByte()); rest = rest ushr 7 }
            bytes.addAll(encoded)
        }
        return der(6, bytes.toByteArray())
    }
    private const val DAY = 24L * 60 * 60 * 1000
}
