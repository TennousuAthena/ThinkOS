package moe.kiwi.thinkos.update

import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import android.system.Os
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.ByteArrayOutputStream
import java.io.FileDescriptor
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One bounded exchange per connection to the optional root module. Never executes a shell,
 * accepts a path from Lua, supplies trust roots or silently retries a mutating request. */
internal class UpdaterClient(private val elapsed: () -> Long = SystemClock::elapsedRealtime) {
    /** A complete ERR reply is distinct from transport loss/timeout. */
    class Rejected(val code: String) : IOException(code)
    private val requests = Mutex()

    suspend fun hello(): Boolean = exchange("HELLO").getOrNull() == "OK thinkos-updater 1"
    suspend fun status(): Result<UpdaterStatusDocument> = exchange("STATUS").mapCatching(UpdaterProtocol::status)
    suspend fun begin(): Result<Unit> = action("BEGIN")
    suspend fun stage(manifest: FileDescriptor, signature: FileDescriptor, packageFile: FileDescriptor): Result<Unit> =
        action("STAGE", arrayOf(manifest, signature, packageFile))
    suspend fun installApk(apk: FileDescriptor): Result<Unit> = action("INSTALL_APK", arrayOf(apk))
    suspend fun healthy(): Result<Unit> = action("HEALTHY")
    suspend fun rollback(): Result<Unit> = action("ROLLBACK")
    suspend fun reboot(): Result<Unit> = action("REBOOT")
    suspend fun setTimeZone(id: String, tapElapsed: Long): Result<Unit> {
        if (!UpdaterProtocol.zoneId(id)) return Result.failure(IOException("zone-unknown"))
        return action("SET_TIMEZONE $id", tapElapsed = tapElapsed)
    }
    suspend fun grantLanguage(tapElapsed: Long): Result<Unit> = action("GRANT_LANGUAGE", tapElapsed = tapElapsed)

    private suspend fun action(line: String, descriptors: Array<FileDescriptor> = emptyArray(), tapElapsed: Long? = null): Result<Unit> =
        exchange(line, descriptors, tapElapsed).mapCatching {
            if (it != "OK") {
                val error = UpdaterProtocol.error(it)
                if (error != "invalid updater reply") throw Rejected(error)
                throw IOException(error)
            }
        }

    private suspend fun exchange(line: String, descriptors: Array<FileDescriptor> = emptyArray(), tapElapsed: Long? = null): Result<String> =
        requests.withLock {
            UpdateIo.run {
                exchangeNow(line, descriptors, tapElapsed)
            }
        }

    /** Fixed STATUS only, on recovery IO, using a private client instance. No mutation/retry. */
    internal fun statusForRepair(): Result<UpdaterStatusDocument> =
        exchangeNow("STATUS", emptyArray(), null).mapCatching(UpdaterProtocol::status)

    private fun exchangeNow(line: String, descriptors: Array<FileDescriptor>, tapElapsed: Long?): Result<String> {
        return try {
                    val request = UpdaterProtocol.request(line)
                    require(descriptors.size == when (line) { "STAGE" -> 3; "INSTALL_APK" -> 1; else -> 0 })
                    if (tapElapsed != null && !UpdaterProtocol.fresh(tapElapsed, elapsed())) throw IOException("tap")
                    LocalSocket().use { socket ->
                        val timeout = UpdaterProtocol.timeoutFor(line)
                        val deadline = elapsed() + timeout
                        // LocalSocket's historical two-argument connect is not implemented on
                        // every API27 build. Closing from the watchdog bounds connect as well as
                        // slow/partial responses; SO_TIMEOUT alone only bounds individual reads.
                        val watch = closer.schedule({ runCatching { socket.close() } }, timeout.toLong(), TimeUnit.MILLISECONDS)
                        try {
                            socket.connect(LocalSocketAddress("thinkos-updater", LocalSocketAddress.Namespace.ABSTRACT))
                            if (socket.peerCredentials.uid != 0) throw IOException("updater peer is not root")
                            if (tapElapsed != null && !UpdaterProtocol.fresh(tapElapsed, elapsed())) throw IOException("tap")
                            socket.soTimeout = remaining(deadline)
                            socket.setFileDescriptorsForSend(descriptors.takeIf { it.isNotEmpty() })
                            socket.outputStream.write(request)
                            socket.outputStream.flush()
                            socket.setFileDescriptorsForSend(null)
                            val response = ByteArrayOutputStream()
                            while (true) {
                                socket.soTimeout = remaining(deadline)
                                val byte = socket.inputStream.read()
                                val incoming = socket.ancillaryFileDescriptors.orEmpty()
                                if (incoming.isNotEmpty()) {
                                    incoming.forEach { runCatching { Os.close(it) } }
                                    throw IOException("unexpected updater descriptors")
                                }
                                if (byte < 0) throw IOException("truncated updater response")
                                if (byte == 10) break
                                if (byte !in 32..126 || response.size() >= UpdaterProtocol.RESPONSE_MAX) throw IOException("invalid updater response")
                                response.write(byte)
                            }
                            Result.success(response.toString(Charsets.US_ASCII.name()))
                        } finally { watch.cancel(false) }
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { Result.failure(error) }
    }

    private fun remaining(deadline: Long): Int = (deadline - elapsed()).takeIf { it > 0 }?.toInt()
        ?: throw SocketTimeoutException("updater deadline")

    companion object {
        private val closer by lazy {
            Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "ThinkOS-UpdaterDeadline").apply {
                isDaemon = true; priority = Thread.MIN_PRIORITY
            } }
        }
    }
}

/** Pure wire checks shared by transport tests. Every production caller has a fixed method. */
internal object UpdaterProtocol {
    const val TIMEOUT_MS = 2000
    // Fixed protocol execution envelope: root's distribution exec limit is
    // 180s. This is not an installation retry or a user-facing timer setting.
    const val MUTATION_TIMEOUT_MS = 190_000
    const val RESPONSE_MAX = 4096
    private val fixed = setOf("HELLO", "STATUS", "BEGIN", "STAGE", "INSTALL_APK", "REBOOT", "HEALTHY", "ROLLBACK", "GRANT_LANGUAGE")
    fun timeoutFor(line: String): Int = if (line in setOf("BEGIN", "STAGE", "INSTALL_APK", "ROLLBACK")) MUTATION_TIMEOUT_MS else TIMEOUT_MS
    fun zoneId(id: String): Boolean = id.length in 1..64 && id.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "_+/-"
    }
    fun fresh(tapElapsed: Long, now: Long): Boolean = tapElapsed >= 0 && now >= tapElapsed && now - tapElapsed <= 5000
    fun request(line: String): ByteArray {
        require(line in fixed || line.startsWith("SET_TIMEZONE ") && zoneId(line.removePrefix("SET_TIMEZONE ")))
        require(line.length + 1 <= 256 && line.all { it.code in 32..126 })
        return (line + "\n").toByteArray(Charsets.US_ASCII)
    }
    fun error(response: String): String = if (response.startsWith("ERR ") && response.length in 5..128)
        response.removePrefix("ERR ") else "invalid updater reply"
    fun status(response: String): UpdaterStatusDocument {
        require(response.startsWith("OK ") && response.length <= RESPONSE_MAX)
        val value = UpdateModels.readUpdaterStatusDocument(UpdateJson.read(response.removePrefix("OK ").toByteArray(Charsets.UTF_8), RESPONSE_MAX))
        require(value.modules.map { it.id }.distinct().size == value.modules.size) { "duplicate updater module" }
        require(!value.transaction || value.id.length == 32) { "missing updater transaction" }
        return value
    }
}
