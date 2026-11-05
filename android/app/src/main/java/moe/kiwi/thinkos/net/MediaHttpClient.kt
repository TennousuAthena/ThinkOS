package moe.kiwi.thinkos.net

import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/** Ordinary media follows [PluginHttp]'s LAN gate; publicOnly can only narrow it to public HTTPS. */
internal object MediaHttpClient {
    /** [allowPublicCleartext] is read on every connection and cannot widen [publicOnly].
     * Each call constructs its own connection pool and dispatcher. */
    fun create(resolver: Dns = Dns.SYSTEM, publicOnly: Boolean = false, allowPublicCleartext: () -> Boolean = { false }): OkHttpClient {
        val dns = RequestDns(resolver, publicOnly, allowPublicCleartext)
        return OkHttpClient.Builder()
            .dns(dns)
            // A configured device proxy must not bypass our actual-origin socket-address check.
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(Redirects(dns, publicOnly))
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                validateConnected(request.url.toUri(), chain.connection()?.socket()?.inetAddress, publicOnly, allowPublicCleartext)
                chain.proceed(request)
            }
            .build()
    }

    internal fun validateConnected(uri: URI, address: InetAddress?, publicOnly: Boolean = false, allowPublic: () -> Boolean = { false }) {
        ioPolicy {
            if (publicOnly) {
                PublicNetworkPolicy.validate(uri)
                PublicNetworkPolicy.requirePublic(listOfNotNull(address))
            }
            LocalNetworkPolicy.validate(uri)
            if (uri.scheme == "http") {
                require(address != null) { "Cleartext connection has no resolved address" }
                if (!allowPublic()) LocalNetworkPolicy.requirePrivate(arrayOf(address))
            }
        }
    }

    /** Framing and content negotiation headers, `Referer` and the like; anything else may carry a token. */
    private val PORTABLE_ON_REDIRECT = setOf("host", "user-agent", "accept", "accept-encoding", "accept-language", "range", "if-range",
        "referer", "origin", "connection", "content-length", "content-type")

    internal fun redirect(request: Request, response: Response, number: Int, publicOnly: Boolean = false): Request? {
        if (response.code !in setOf(301, 302, 303, 307, 308)) return null
        if (number >= 3) throw IOException("Too many redirects")
        val location = response.header("Location") ?: throw IOException("Redirect missing Location")
        val next = request.url.resolve(location) ?: throw IOException("Invalid redirect URL")
        ioPolicy {
            if (publicOnly) PublicNetworkPolicy.validate(next.toUri())
            LocalNetworkPolicy.validate(next.toUri())
            require(!(request.url.isHttps && !next.isHttps)) { "HTTPS downgrade rejected" }
        }
        val builder = request.newBuilder().url(next)
        if (request.url.scheme != next.scheme || request.url.host != next.host || request.url.port != next.port) {
            // Another origin gets the request line and the harmless headers, never anything that could be a credential.
            request.headers.names().filter { it.lowercase() !in PORTABLE_ON_REDIRECT }.forEach { builder.removeHeader(it) }
        }
        if (response.code == 303 || response.code in setOf(301, 302) && request.method == "POST") {
            builder.method("GET", null).removeHeader("Content-Length").removeHeader("Content-Type")
        }
        return builder.build()
    }

    private class Redirects(private val dns: RequestDns, private val publicOnly: Boolean) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            for (number in 0..3) {
                val response = dns.forRequest(request.url) { chain.proceed(request) }
                val next = try { redirect(request, response, number, publicOnly) } catch (error: Exception) {
                    response.close()
                    throw error
                }
                if (next == null) return response
                response.close()
                request = next
            }
            throw IOException("Too many redirects")
        }
    }

    private inline fun <T> ioPolicy(block: () -> T): T = try {
        block()
    } catch (error: IllegalArgumentException) {
        throw IOException(error.message, error)
    }
}
