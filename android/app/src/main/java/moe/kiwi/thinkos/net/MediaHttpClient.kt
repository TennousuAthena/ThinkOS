package moe.kiwi.thinkos.net

import okhttp3.Dns
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.URI
import java.util.concurrent.TimeUnit

/** The media HTTP stack obeys the same cleartext/private-address gate as [PluginHttp]. */
internal object MediaHttpClient {
    fun create(resolver: Dns = Dns.SYSTEM): OkHttpClient {
        val dns = RequestDns(resolver)
        return OkHttpClient.Builder()
            .dns(dns)
            // A configured device proxy must not bypass our actual-origin socket-address check.
            .proxy(Proxy.NO_PROXY)
            .followRedirects(false)
            .followSslRedirects(false)
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor(Redirects(dns))
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                validateConnected(request.url.toUri(), chain.connection()?.socket()?.inetAddress)
                chain.proceed(request)
            }
            .build()
    }

    internal fun validateConnected(uri: URI, address: InetAddress?) {
        ioPolicy {
            LocalNetworkPolicy.validate(uri)
            if (uri.scheme == "http") {
                require(address != null) { "Cleartext connection has no resolved address" }
                LocalNetworkPolicy.requirePrivate(arrayOf(address))
            }
        }
    }

    internal fun redirect(request: Request, response: Response, number: Int): Request? {
        if (response.code !in setOf(301, 302, 303, 307, 308)) return null
        if (number >= 3) throw IOException("Too many redirects")
        val location = response.header("Location") ?: throw IOException("Redirect missing Location")
        val next = request.url.resolve(location) ?: throw IOException("Invalid redirect URL")
        ioPolicy {
            LocalNetworkPolicy.validate(next.toUri())
            require(!(request.url.isHttps && !next.isHttps)) { "HTTPS downgrade rejected" }
        }
        val builder = request.newBuilder().url(next)
        if (request.url.scheme != next.scheme || request.url.host != next.host || request.url.port != next.port) {
            builder.removeHeader("Authorization").removeHeader("Cookie")
        }
        if (response.code == 303 || response.code in setOf(301, 302) && request.method == "POST") {
            builder.method("GET", null).removeHeader("Content-Length").removeHeader("Content-Type")
        }
        return builder.build()
    }

    /**
     * A DNS answer is scoped to a single request and pinned for its connection attempt. Automatic
     * redirects are disabled: each hop gets a new scheme-aware scope and address validation.
     * Connection reuse still goes through the network interceptor's actual peer-address gate.
     */
    internal class RequestDns(private val resolver: Dns) : Dns {
        private data class Scope(val host: String, val cleartext: Boolean, var addresses: List<InetAddress>? = null)
        private val current = ThreadLocal<Scope?>()

        fun <T> forRequest(url: HttpUrl, block: () -> T): T {
            ioPolicy { LocalNetworkPolicy.validate(url.toUri()) }
            val previous = current.get()
            current.set(Scope(url.host, !url.isHttps))
            return try {
                // Validate every address before OkHttp can open a cleartext socket, even if a
                // pooled connection means OkHttp itself would skip DNS this time.
                if (!url.isHttps) lookup(url.host)
                block()
            } finally {
                if (previous == null) current.remove() else current.set(previous)
            }
        }

        override fun lookup(hostname: String): List<InetAddress> {
            val scope = current.get()?.takeIf { it.host.equals(hostname, ignoreCase = true) }
            scope?.addresses?.let { return it }
            val addresses = resolver.lookup(hostname)
            if (scope?.cleartext == true) ioPolicy { LocalNetworkPolicy.requirePrivate(addresses.toTypedArray()) }
            if (scope != null) scope.addresses = addresses.toList()
            return addresses
        }
    }

    private class Redirects(private val dns: RequestDns) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            for (number in 0..3) {
                val response = dns.forRequest(request.url) { chain.proceed(request) }
                val next = try { redirect(request, response, number) } catch (error: Exception) {
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
