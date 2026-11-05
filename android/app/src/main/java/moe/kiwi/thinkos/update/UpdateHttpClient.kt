package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.net.LocalNetworkPolicy
import moe.kiwi.thinkos.net.RequestDns
import okhttp3.Authenticator
import okhttp3.CookieJar
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit

/** Dedicated transport: no plugin/media settings, device proxy, credentials or shared cookie jar. */
internal object UpdateHttpClient {
    /** Supplied by the settings adapter (documented defaults: 15s/30s). */
    data class Timeouts(val connectMillis: Long, val readMillis: Long) {
        init { require(connectMillis in 1..Int.MAX_VALUE.toLong() && readMillis in 1..Int.MAX_VALUE.toLong()) }
    }

    fun create(version: String, timeouts: Timeouts, resolver: Dns = Dns.SYSTEM, trust: UpdateTrust.Material = UpdateTrust.default): OkHttpClient {
        require(version.matches(Regex("[A-Za-z0-9._+\\-]{1,64}")))
        val dns = RequestDns(resolver)
        return OkHttpClient.Builder()
            .sslSocketFactory(trust.socketFactory, trust.manager)
            .dns(dns).proxy(Proxy.NO_PROXY).cache(null)
            .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
            .connectTimeout(timeouts.connectMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeouts.readMillis, TimeUnit.MILLISECONDS)
            .addInterceptor { chain ->
                val original = chain.request()
                if (original.method != "GET") throw IOException("Updates use GET only")
                // Build from an allowlist, including after redirects. Never forward device IDs or tokens.
                var request = Request.Builder().url(original.url).get()
                    .header("User-Agent", "ThinkOS/$version").header("Accept-Encoding", "identity")
                    .apply {
                        original.header("Range")?.let { header("Range", it) }
                        original.header("If-None-Match")?.let { header("If-None-Match", it) }
                    }.build()
                var redirects = 0
                while (true) {
                    val response = dns.forRequest(request.url) { chain.proceed(request) }
                    val next = try { redirect(request, response, redirects) } catch (error: Exception) {
                        response.close()
                        throw error
                    }
                    if (next == null) return@addInterceptor response
                    response.close()
                    request = next
                    redirects++
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable")
            }
            .addNetworkInterceptor { chain ->
                val uri = chain.request().url.toUri()
                try {
                    LocalNetworkPolicy.validate(uri)
                    if (uri.scheme == "http") {
                        val peer = chain.connection()?.socket()?.inetAddress ?: throw IOException("Missing HTTP peer address")
                        LocalNetworkPolicy.requirePrivate(arrayOf(peer))
                    }
                } catch (error: IllegalArgumentException) { throw IOException(error.message, error) }
                chain.proceed(chain.request())
            }.build()
    }

    internal fun redirect(request: Request, response: Response, number: Int): Request? {
        if (response.code !in setOf(301, 302, 303, 307, 308)) return null
        if (number >= 3) throw IOException("Too many update redirects")
        val location = response.headers.values("Location").singleOrNull() ?: throw IOException("Missing or ambiguous redirect")
        val next = request.url.resolve(location) ?: throw IOException("Invalid update redirect")
        try { LocalNetworkPolicy.validate(next.toUri()) } catch (error: IllegalArgumentException) {
            throw IOException(error.message, error)
        }
        if (request.url.isHttps && !next.isHttps) throw IOException("HTTPS downgrade rejected")
        return request.newBuilder().url(next).build()
    }
}
