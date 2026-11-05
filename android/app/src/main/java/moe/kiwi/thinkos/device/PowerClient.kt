package moe.kiwi.thinkos.device

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One bounded exchange per connection to the optional power guard module (`thinkos-power`, docs/40). It sends one of three fixed
 * lines, takes no path or argument from Lua, never runs a shell and never retries a power off.
 */
internal class PowerClient(private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    /** The module is installed and running: it answers HELLO to this app. */
    suspend fun available(): Boolean = withContext(Dispatchers.IO) { PowerProtocol.hello(exchange("HELLO").getOrNull()) }

    /** Asks for a clean power off: the guard puts the lights out, flushes and goes read-only, then powers off. */
    suspend fun powerOff(): Boolean = withContext(Dispatchers.IO) { exchange("POWEROFF").getOrNull() == "OK" }

    private fun exchange(line: String): Result<String> = try {
        LocalSocket().use { socket ->
            val deadline = elapsed() + PowerProtocol.TIMEOUT_MS
            // Closing from a watchdog bounds connect as well as a slow reply; SO_TIMEOUT alone only bounds single reads.
            val watch = closer.schedule({ runCatching { socket.close() } }, PowerProtocol.TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            try {
                socket.connect(LocalSocketAddress(PowerProtocol.ADDRESS, LocalSocketAddress.Namespace.ABSTRACT))
                if (socket.peerCredentials.uid != 0) throw IOException("power guard peer is not root")
                socket.soTimeout = remaining(deadline)
                socket.outputStream.write(PowerProtocol.request(line))
                socket.outputStream.flush()
                val response = ByteArrayOutputStream()
                while (true) {
                    socket.soTimeout = remaining(deadline)
                    val byte = socket.inputStream.read()
                    if (byte < 0) throw IOException("truncated power guard reply")
                    if (byte == 10) break
                    if (byte !in 32..126 || response.size() >= PowerProtocol.REPLY_MAX) throw IOException("invalid power guard reply")
                    response.write(byte)
                }
                Result.success(response.toString(Charsets.US_ASCII.name()))
            } finally { watch.cancel(false) }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (error: Exception) { Result.failure(error) }

    private fun remaining(deadline: Long): Int = (deadline - elapsed()).takeIf { it > 0 }?.toInt()
        ?: throw SocketTimeoutException("power guard deadline")

    private companion object {
        val closer by lazy {
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ThinkOS-PowerDeadline").apply {
                isDaemon = true; priority = Thread.MIN_PRIORITY
            } }
        }
    }
}

/** The wire rules, apart from the socket so they can be tested. */
internal object PowerProtocol {
    const val ADDRESS = "thinkos-power"
    const val TIMEOUT_MS = 2000
    const val REPLY_MAX = 256
    private val fixed = setOf("HELLO", "STATUS", "POWEROFF")

    fun request(line: String): ByteArray {
        require(line in fixed) { "unknown power guard request" }
        return (line + "\n").toByteArray(Charsets.US_ASCII)
    }

    fun hello(reply: String?): Boolean = reply != null && reply.startsWith("OK thinkos-power ") && reply.length <= REPLY_MAX
}
