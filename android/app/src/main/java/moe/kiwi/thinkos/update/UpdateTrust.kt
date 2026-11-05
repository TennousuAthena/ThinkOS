package moe.kiwi.thinkos.update

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** OS-owned TLS anchors, packaged with the APK. OTA signing keys are separate trust roots.
 * Keep the platform's native trust-manager implementation, including Android chain cleaning. */
internal object UpdateTrust {
    data class Material(val socketFactory: SSLSocketFactory, val manager: X509TrustManager)

    /** Fetcher first accesses this lazy value on its minimum-priority IO worker. */
    val default: Material by lazy { fromAnchors(platform().acceptedIssuers.toList(), bundledRoots()) }

    fun bundledRoots(): List<X509Certificate> =
        (UpdateTrust::class.java.getResourceAsStream("roots.pem") ?: throw IOException("Missing update CA roots")).use(::readRoots)

    internal fun platform(): X509TrustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
        init(null as KeyStore?)
        trustManagers.filterIsInstance<X509TrustManager>().singleOrNull() ?: throw CertificateException("Missing platform trust manager")
    }

    /** A snapshot of platform-accepted anchors plus our fixed CA subset. Never accepts a root from a server. */
    internal fun fromAnchors(platform: List<X509Certificate>, bundled: List<X509Certificate>): Material {
        require(bundled.isNotEmpty())
        val anchors = (platform + bundled).distinctBy(::fingerprint)
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            anchors.forEachIndexed { index, cert -> setCertificateEntry("ca-$index", cert) }
        }
        val manager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).run {
            init(store)
            trustManagers.filterIsInstance<X509TrustManager>().singleOrNull() ?: throw CertificateException("Missing update trust manager")
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }
        return Material(context.socketFactory, manager)
    }

    internal fun readRoots(input: InputStream): List<X509Certificate> {
        val bytes = ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, MAX_BUNDLE - output.size() + 1))
                if (count < 0) break
                if (count > MAX_BUNDLE - output.size()) throw CertificateException("Update CA bundle too large")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        if (bytes.any { (it.toInt() and 0xff) > 127 }) throw CertificateException("Non-ASCII update CA bundle")
        val pem = bytes.toString(Charsets.US_ASCII).lineSequence().filterNot { it.trimStart().startsWith('#') }.joinToString("\n")
        val matches = PEM.findAll(pem).toList()
        if (matches.isEmpty() || matches.size > MAX_ROOTS || PEM.replace(pem, "").isNotBlank()) throw CertificateException("Invalid update CA bundle")
        val factory = CertificateFactory.getInstance("X.509")
        val roots = matches.map { match ->
            val cert = factory.generateCertificate(match.value.byteInputStream(Charsets.US_ASCII)) as X509Certificate
            if (cert.basicConstraints < 0 || cert.subjectX500Principal != cert.issuerX500Principal ||
                cert.keyUsage?.let { it.size <= 5 || !it[5] } == true) throw CertificateException("Expected a self-signed CA root")
            cert.verify(cert.publicKey)
            cert
        }
        if (roots.distinctBy(::fingerprint).size != roots.size) throw CertificateException("Duplicate update CA root")
        return roots
    }

    internal fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256").digest(cert.encoded)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private const val MAX_BUNDLE = 64 * 1024
    private const val MAX_ROOTS = 16
    private val PEM = Regex("-----BEGIN CERTIFICATE-----[\\r\\n]+[A-Za-z0-9+/=\\r\\n]+-----END CERTIFICATE-----")
}
