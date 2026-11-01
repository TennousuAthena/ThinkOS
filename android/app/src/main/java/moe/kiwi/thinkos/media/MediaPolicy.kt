package moe.kiwi.thinkos.media

import moe.kiwi.thinkos.net.LocalNetworkPolicy
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Playback budgets and rules. Values come from the system settings schema (ADR-017); the engine reads
 * them whenever it builds a player or loads a queue, so a change applies from the next load.
 */
data class MediaPolicy(
    /** Longest time of media kept ahead of the playhead. */
    val bufferMillis: Int = 30_000,
    /** Hard cap on buffered bytes: the video default (125 MiB) would not fit this device's memory. */
    val bufferBytes: Int = 48 * 1024 * 1024,
    /** Cleartext HTTP/RTSP to public addresses. Off by default; HTTPS-to-HTTP downgrades stay refused either way. */
    val publicCleartext: Boolean = false,
    val maxVideoWidth: Int = 1920,
    val maxVideoHeight: Int = 1088,
) {
    init {
        require(bufferMillis in 5_000..120_000 && bufferBytes in 4 * 1024 * 1024..96 * 1024 * 1024)
    }
}

/**
 * RTSP opens its own sockets, so [moe.kiwi.thinkos.net.MediaHttpClient] cannot guard it. Every
 * connection resolves the host first, refuses non-private addresses unless the policy allows public
 * cleartext, and connects to the address it validated rather than resolving a second time.
 */
internal class PrivateSocketFactory(private val allowPublic: () -> Boolean) : SocketFactory() {
    private fun connect(addresses: Array<InetAddress>, port: Int, local: InetAddress? = null, localPort: Int = 0): Socket {
        if (!allowPublic()) {
            try { LocalNetworkPolicy.requirePrivate(addresses) } catch (error: IllegalArgumentException) { throw IOException(error.message, error) }
        }
        var failure: IOException? = null
        for (address in addresses) {
            val socket = Socket()
            try {
                if (local != null || localPort != 0) socket.bind(InetSocketAddress(local, localPort))
                socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MILLIS)
                return socket
            } catch (error: IOException) {
                runCatching { socket.close() }
                failure = error
            }
        }
        throw failure ?: IOException("No address for RTSP host")
    }

    override fun createSocket(): Socket = Socket()
    override fun createSocket(host: String, port: Int): Socket = connect(InetAddress.getAllByName(host), port)
    override fun createSocket(host: String, port: Int, localHost: InetAddress?, localPort: Int): Socket =
        connect(InetAddress.getAllByName(host), port, localHost, localPort)
    override fun createSocket(host: InetAddress, port: Int): Socket = connect(arrayOf(host), port)
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        connect(arrayOf(address), port, localAddress, localPort)

    private companion object { const val CONNECT_TIMEOUT_MILLIS = 5_000 }
}
