package moe.kiwi.thinkos.cast.miracast

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException

/** One request line to the system module, one reply line back. */
internal fun interface WfdTransport {
    @Throws(IOException::class)
    fun exchange(line: String): String
}

internal sealed interface WfdReply {
    /** The system module is not installed, or its daemon is not running. */
    data object Unavailable : WfdReply
    data class Failed(val reason: String) : WfdReply
    data class Ok(val text: String) : WfdReply
    val ok: Boolean get() = this is Ok
}

internal data class WfdStatus(val sinkOn: Boolean, val port: Int, val wpaUp: Boolean, val element: String, val name: String)

/**
 * The application's side of the ThinkOS Cast system module (system/thinkos-cast, ADR-033). The module's daemon runs as root
 * and is the only thing that may set the Wi-Fi Display element in wpa_supplicant; this class is a client for its three
 * commands. Nothing here is required for the device to work (ADR-015): when the module is absent every call answers
 * [WfdReply.Unavailable] and Miracast simply does not appear.
 */
internal class WfdControl(private val transport: WfdTransport) {
    fun hello(): WfdReply = call("HELLO")

    /** Make this device discoverable as a wireless display whose RTSP control port is [port]. */
    fun sinkOn(port: Int, name: String): WfdReply {
        require(port in 1024..65535) { "Invalid WFD port" }
        return call("SINK_ON $port ${p2pName(name)}")
    }

    fun sinkOff(): WfdReply = call("SINK_OFF")

    fun status(): WfdStatus? {
        val reply = call("STATUS") as? WfdReply.Ok ?: return null
        val text = reply.text
        fun field(key: String) = Regex("(?:^| )$key=(\\S*)").find(text)?.groupValues?.get(1)
        return WfdStatus(
            sinkOn = field("sink") == "on", port = field("port")?.toIntOrNull() ?: 0, wpaUp = field("wpa") == "up",
            element = field("wfd").orEmpty(), name = text.substringAfter(" name=", ""),
        )
    }

    private fun call(line: String): WfdReply {
        val reply = try { transport.exchange(line) } catch (error: IOException) { return WfdReply.Unavailable } catch (error: SecurityException) { return WfdReply.Unavailable }
        return when {
            reply == "OK" || reply.startsWith("OK ") -> WfdReply.Ok(reply.removePrefix("OK").trim())
            reply.startsWith("ERR ") -> WfdReply.Failed(reply.removePrefix("ERR ").trim())
            else -> WfdReply.Failed("garbled")
        }
    }

    companion object {
        const val SOCKET = "thinkos-wfd"
        /** The port in the WFD element. Sources dial the Source-side port; this one is what the standard asks a Sink to announce. */
        const val CONTROL_PORT = 7236

        /** A name the daemon accepts: at most 32 bytes of UTF-8 without the characters it refuses, never empty. */
        fun p2pName(configured: String): String {
            val cleaned = configured.map { if (it.code < 0x20 || it.code == 0x7f || it in "\"\\`$") '-' else it }.joinToString("").trim().ifEmpty { "ThinkOS" }
            val out = StringBuilder()
            var bytes = 0
            var i = 0
            while (i < cleaned.length) {
                val cp = cleaned.codePointAt(i)
                val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
                if (bytes + size > 32) break
                out.appendCodePoint(cp); bytes += size; i += Character.charCount(cp)
            }
            return out.toString().trim().ifEmpty { "ThinkOS" }
        }
    }
}

/** The real thing: the daemon's abstract socket. Only an answer from root is believed, since any app could have bound that name first. */
internal class LocalSocketWfdTransport(private val name: String = WfdControl.SOCKET) : WfdTransport {
    override fun exchange(line: String): String {
        val socket = LocalSocket()
        try {
            socket.connect(LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT))
            socket.soTimeout = TIMEOUT_MILLIS
            if (socket.peerCredentials.uid != 0) throw IOException("The WFD socket is not held by root")
            socket.outputStream.write((line + "\n").toByteArray(Charsets.UTF_8))
            socket.outputStream.flush()
            val reply = java.io.ByteArrayOutputStream()
            val input = socket.inputStream
            while (reply.size() < MAX_REPLY) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) break
                reply.write(b)
            }
            return String(reply.toByteArray(), Charsets.UTF_8).trim()
        } finally { runCatching { socket.close() } }
    }

    private companion object { const val TIMEOUT_MILLIS = 4000; const val MAX_REPLY = 512 }
}
