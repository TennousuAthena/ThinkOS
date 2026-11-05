package moe.kiwi.thinkos.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.OverlappingFileLockException
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Bounded OTA/registry transport. Only authenticated metadata may supply artifact size/hash.
 * Downloading never installs anything, and a .part is never exposed as a verified artifact.
 * [health] only reorders candidate URLs (unreachable hosts last); it never drops one or skips verification. */
internal class Fetcher(version: String, timeouts: UpdateHttpClient.Timeouts, resolver: Dns = Dns.SYSTEM,
                       internal val health: UpdateSourceHealth? = null) : Closeable {
    // TLS trust-store initialization also belongs to the IO worker, never the caller's UI thread.
    private val transport = lazy { UpdateHttpClient.create(version, timeouts, resolver) }
    private val closed = AtomicBoolean(false)
    private val operations = ConcurrentHashMap.newKeySet<Operation>()

    enum class DocumentKind(val maxBytes: Int) { CATALOG(1024 * 1024), SIGNATURE(1024), MANIFEST(64 * 1024) }
    enum class ArtifactKind(val maxBytes: Long, val extension: String) {
        APK(64L * 1024 * 1024, "apk"), MODULE(16L * 1024 * 1024, "zip")
    }
    data class Artifact(val kind: ArtifactKind, val bytes: Long, val sha256: String) {
        init { require(bytes in 1..kind.maxBytes && sha256.matches(Regex("[a-f0-9]{64}"))) }
    }
    sealed class Document {
        data class Body(val bytes: ByteArray, val etag: String?) : Document()
        data object NotModified : Document()
    }

    suspend fun readDocument(candidates: List<String>, kind: DocumentKind, declaredBytes: Int? = null, etag: String? = null): Document {
        require(declaredBytes == null || declaredBytes in 1..kind.maxBytes)
        require(etag == null || etag.length in 1..1024 && etag.all { it.code in 0x20..0x7e })
        val sources = candidates.toList().also { require(it.isNotEmpty()) }
        return io { operation ->
            across(sources, operation) { url ->
                val request = request(url).apply { etag?.let { header("If-None-Match", it) } }.build()
                operation.request(request) { response ->
                    if (response.code == 304) {
                        if (etag == null) throw IOException("Unsolicited not-modified response")
                        Document.NotModified
                    } else {
                        if (response.code != 200) throw IOException("Update document HTTP ${response.code}")
                        identityEncoding(response)
                        val limit = declaredBytes ?: kind.maxBytes
                        val length = response.body?.contentLength() ?: throw IOException("Missing update body")
                        if (length > limit || declaredBytes != null && length >= 0 && length != declaredBytes.toLong()) {
                            throw IOException("Update document length rejected")
                        }
                        val bytes = ByteArrayOutputStream(minOf(limit, BUFFER)).use { out ->
                            val input = response.body!!.byteStream()
                            val buffer = ByteArray(BUFFER)
                            while (true) {
                                operation.check()
                                val count = input.read(buffer, 0, minOf(buffer.size, limit - out.size() + 1))
                                if (count < 0) break
                                if (count > limit - out.size()) throw IOException("Update document exceeds size bound")
                                out.write(buffer, 0, count)
                            }
                            out.toByteArray()
                        }
                        if (bytes.isEmpty() || declaredBytes != null && bytes.size != declaredBytes) throw IOException("Incomplete update document")
                        Document.Body(bytes, response.header("ETag")?.takeIf { it.length in 1..1024 && it.all { char -> char.code in 0x20..0x7e } })
                    }
                }
            }
        }
    }

    /** A content-addressed staging directory in the app's private storage. No manifest-controlled paths. */
    suspend fun download(candidates: List<String>, artifact: Artifact, directory: File, onProgress: (Long) -> Unit = {}): File {
        val sources = candidates.toList().also { require(it.isNotEmpty()) }
        return io { operation ->
            val root = storage {
                if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create update staging directory")
                directory.canonicalFile
            }
            val name = "${artifact.sha256}.${artifact.kind.extension}"
            val target = safeFile(root, name)
            val partial = safeFile(root, "$name.part")
            val lockFile = safeFile(root, "$name.lock")
            storage { RandomAccessFile(lockFile, "rw") }.use { lockHandle ->
                val lock = try { lockHandle.channel.tryLock() } catch (error: OverlappingFileLockException) {
                    throw IOException("Update artifact is already being downloaded", error)
                } ?: throw IOException("Update artifact is already being downloaded")
                lock.use {
                    if (target.exists()) {
                        if (valid(target, artifact, operation)) { onProgress(artifact.bytes); return@io target }
                        storage { if (!target.delete()) throw IOException("Cannot remove invalid cached update") }
                    }
                    storage { RandomAccessFile(partial, "rw") }.use { output ->
                        syncDirectory(root)
                        if (output.length() > artifact.bytes) storage { output.setLength(0); output.fd.sync() }
                        if (output.length() == artifact.bytes && !valid(partial, artifact, operation)) {
                            storage { output.setLength(0); output.fd.sync() }
                        }
                        onProgress(output.length())
                        if (output.length() < artifact.bytes) across(sources, operation) { url ->
                            while (output.length() < artifact.bytes) {
                                operation.check()
                                val offset = output.length()
                                val end = minOf(offset + CHUNK - 1, artifact.bytes - 1)
                                val request = request(url).header("Range", "bytes=$offset-$end").build()
                                operation.request(request) { response ->
                                    identityEncoding(response)
                                    val count = when (response.code) {
                                        206 -> {
                                            val range = response.headers.values("Content-Range").singleOrNull()
                                            val values = range?.let { CONTENT_RANGE.matchEntire(it) }?.groupValues
                                                ?: throw IOException("Invalid update Content-Range")
                                            if (values[1].toLongOrNull() != offset || values[2].toLongOrNull() != end ||
                                                values[3].toLongOrNull() != artifact.bytes) throw IOException("Mismatched update Content-Range")
                                            end - offset + 1
                                        }
                                        200 -> {
                                            // An origin ignoring Range can serve a fresh download, never append a full body.
                                            if (offset != 0L || response.header("Content-Range") != null) throw IOException("Origin does not support update resume")
                                            artifact.bytes
                                        }
                                        else -> throw IOException("Update artifact HTTP ${response.code}")
                                    }
                                    val body = response.body ?: throw IOException("Missing update artifact body")
                                    if (body.contentLength() >= 0 && body.contentLength() != count) throw IOException("Update artifact length rejected")
                                    storage { output.seek(offset) }
                                    val buffer = ByteArray(BUFFER)
                                    var received = 0L
                                    try {
                                        val input = body.byteStream()
                                        while (received < count) {
                                            operation.check()
                                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), count - received).toInt())
                                            if (read < 0) throw EOFException("Incomplete update artifact")
                                            storage { output.write(buffer, 0, read) }
                                            received += read
                                            if (received % CHUNK == 0L) {
                                                storage { output.fd.sync() }
                                                onProgress(offset + received)
                                            }
                                        }
                                        operation.check()
                                        if (input.read() != -1) {
                                            // Retain previous chunks, never a response that exceeded its range/declared size.
                                            storage { output.setLength(offset) }
                                            throw IOException("Update artifact exceeds size bound")
                                        }
                                    } finally { storage { output.fd.sync() } }
                                    onProgress(output.length())
                                }
                            }
                            if (!valid(partial, artifact, operation)) {
                                storage { output.setLength(0); output.fd.sync() }
                                onProgress(0)
                                throw IOException("Update artifact SHA-256 mismatch")
                            }
                        }
                    }
                    operation.check()
                    storage { if (!partial.renameTo(target)) throw IOException("Cannot publish verified update") }
                    syncDirectory(root)
                    target
                }
            }
        }
    }

    override fun close() {
        closed.set(true)
        operations.forEach { it.cancel() }
        if (transport.isInitialized()) transport.value.connectionPool.evictAll()
    }

    private fun request(url: String): Request.Builder = try { Request.Builder().url(url.toHttpUrl()) } catch (error: IllegalArgumentException) {
        throw IOException("Invalid update source URL", error)
    }

    private fun identityEncoding(response: Response) {
        val encodings = response.headers.values("Content-Encoding")
        if (encodings.isNotEmpty() && (encodings.size != 1 || !encodings.single().equals("identity", true))) {
            throw IOException("Encoded update body rejected")
        }
    }

    private fun valid(file: File, artifact: Artifact, operation: Operation): Boolean = storage {
        if (file.length() != artifact.bytes) return@storage false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                operation.check()
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) } == artifact.sha256
    }

    private fun safeFile(root: File, name: String): File = storage {
        File(root, name).also { file ->
            if (file.canonicalFile != file.absoluteFile || file.exists() && !file.isFile) throw IOException("Invalid update cache path")
        }
    }

    private fun syncDirectory(directory: File) = storage {
        FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    internal class StorageFailure(cause: IOException) : IOException("Update storage failed", cause)
    private inline fun <T> storage(block: () -> T): T = try { block() } catch (error: IOException) {
        if (error is StorageFailure) throw error
        throw StorageFailure(error)
    }

    private inline fun <T> across(sources: List<String>, operation: Operation, block: (String) -> T): T {
        val failure = IOException("All update sources failed")
        for (url in health?.order(sources) ?: sources) {
            operation.check()
            try { return block(url).also { health?.success(url) } } catch (error: IOException) {
                operation.check()
                if (error is StorageFailure) throw error
                health?.failure(url)
                failure.addSuppressed(error)
            }
        }
        throw failure
    }

    private suspend fun <T> io(block: (Operation) -> T): T = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { continuation ->
            val operation = Operation()
            operations.add(operation)
            continuation.invokeOnCancellation { operation.cancel() }
            val thread = Thread.currentThread()
            val priority = thread.priority
            val result = try {
                thread.priority = Thread.MIN_PRIORITY
                operation.check()
                Result.success(block(operation))
            } catch (error: Throwable) { Result.failure(error) } finally {
                operations.remove(operation)
                thread.priority = priority
            }
            continuation.resumeWith(result)
        }
    }

    private inner class Operation {
        private val cancelled = AtomicBoolean(false)
        private val active = AtomicReference<Call?>()
        fun cancel() { cancelled.set(true); active.get()?.cancel() }
        fun check() { if (closed.get() || cancelled.get() || Thread.currentThread().isInterrupted) throw CancellationException("Update fetch cancelled") }
        fun <T> request(request: Request, block: (Response) -> T): T {
            check()
            val call = transport.value.newCall(request)
            active.set(call)
            if (cancelled.get()) call.cancel()
            try {
                check()
                return call.execute().use(block)
            } finally { active.compareAndSet(call, null) }
        }
    }

    private companion object {
        const val CHUNK = 1024L * 1024
        const val BUFFER = 8192
        val CONTENT_RANGE = Regex("bytes ([0-9]+)-([0-9]+)/([0-9]+)")
    }
}
