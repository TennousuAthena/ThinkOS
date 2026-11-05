package moe.kiwi.thinkos

import android.content.Context
import okhttp3.ConnectionSpec
import okhttp3.CookieJar
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.Proxy
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/** Test APK only. Native system trust and default hostname verification;
 * no bundled OTA roots, custom verifier, proxy, redirects, credentials or cookies. */
internal object CertTlsProbe {
    fun run(context: Context): JSONArray {
        val fixture = context.assets.open("sites.json").use {
            val raw = it.readBytes()
            check(raw.size <= 32 * 1024)
            JSONObject(raw.toString(Charsets.UTF_8))
        }
        val sites = fixture.getJSONArray("sites")
        check(sites.length() in 1..8)
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val roots = store.aliases().toList().filter { it.startsWith("system:") }.associate { alias ->
            val root = store.getCertificate(alias) as X509Certificate
            fingerprint(root) to root.subjectX500Principal.name
        }
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY).cookieJar(CookieJar.NO_COOKIES)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS)
            .build()
        val observations = JSONArray()
        try {
            for (index in 0 until sites.length()) {
                val site = sites.getJSONObject(index)
                val request = Request.Builder().url(site.getString("url"))
                    .header("User-Agent", "ThinkOS/${BuildConfig.VERSION_NAME}").header("Accept-Encoding", "identity").build()
                check(request.url.isHttps && request.url.port == 443 && request.url.username.isEmpty() && request.url.password.isEmpty())
                val observation = JSONObject().put("id", site.getString("id"))
                    .put("url", request.url.toString()).put("expected_root_sha256", site.getString("root_sha256"))
                try {
                    client.newCall(request).execute().use { response ->
                        val handshake = response.handshake ?: error("No TLS handshake")
                        val chain = handshake.peerCertificates.map { cert ->
                            cert as X509Certificate
                            JSONObject().put("subject", cert.subjectX500Principal.name).put("issuer", cert.issuerX500Principal.name)
                                .put("der_sha256", fingerprint(cert))
                        }
                        check(chain.size in 1..16)
                        val final = chain.last().getString("der_sha256")
                        observation.put("outcome", "accepted").put("http_status", response.code)
                            .put("tls_version", handshake.tlsVersion.javaName).put("cipher_suite", handshake.cipherSuite.javaName)
                            .put("verified_chain", JSONArray(chain)).put("anchor_sha256", if (final in roots) final else JSONObject.NULL)
                            .put("expected_root_is_anchor", final == site.getString("root_sha256") && final in roots)
                    }
                } catch (error: Exception) {
                    val causes = generateSequence(error as Throwable?) { it.cause }.take(8).toList()
                    observation.put("outcome", if (causes.any { it is SSLException }) "tls_rejected" else "network_error")
                        .put("causes", JSONArray(causes.map { it.javaClass.simpleName + ": " + (it.message ?: "").take(600) }))
                }
                observations.put(observation)
            }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
        }
        return observations
    }

    private fun fingerprint(cert: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
}
