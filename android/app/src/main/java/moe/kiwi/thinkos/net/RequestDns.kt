package moe.kiwi.thinkos.net

import okhttp3.Dns
import okhttp3.HttpUrl
import java.io.IOException
import java.net.InetAddress

/** Scheme-aware, request-scoped DNS for synchronous OkHttp interceptors.
 * Redirects must create a new scope, and pooled sockets still need a peer-address check. */
internal class RequestDns(private val resolver: Dns, private val publicOnly: Boolean = false,
                          private val allowPublic: () -> Boolean = { false }) : Dns {
    private data class Scope(val host: String, val cleartext: Boolean, var addresses: List<InetAddress>? = null)
    private val current = ThreadLocal<Scope?>()

    fun <T> forRequest(url: HttpUrl, block: () -> T): T {
        policy { LocalNetworkPolicy.validate(url.toUri()) }
        if (publicOnly) policy { PublicNetworkPolicy.validate(url.toUri()) }
        val previous = current.get()
        current.set(Scope(url.host, !url.isHttps))
        return try {
            // Check even when OkHttp could reuse a pooled connection without resolving again.
            if (publicOnly || !url.isHttps) lookup(url.host)
            block()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val scope = current.get()?.takeIf { it.host.equals(hostname, ignoreCase = true) }
        scope?.addresses?.let { return it }
        val addresses = resolver.lookup(hostname).toList()
        if (publicOnly) policy { PublicNetworkPolicy.requirePublic(addresses) }
        if (scope?.cleartext == true && !allowPublic()) policy { LocalNetworkPolicy.requirePrivate(addresses.toTypedArray()) }
        if (scope != null) scope.addresses = addresses
        return addresses
    }

    private inline fun <T> policy(block: () -> T): T = try { block() } catch (error: IllegalArgumentException) {
        throw IOException(error.message, error)
    }
}
