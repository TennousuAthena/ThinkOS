package moe.kiwi.thinkos.cast.dlna

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Locale
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal class HttpRequest(
    val method: String,
    val target: String,
    /** Header names in lower case. */
    val headers: Map<String, String>,
    val body: ByteArray,
    val remote: InetAddress,
)

internal class HttpResponse(
    val status: Int,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: ByteArray = ByteArray(0),
    /** Runs after the response has been written, e.g. the first event of a new subscription. */
    val afterSent: (() -> Unit)? = null,
) {
    companion object {
        fun text(status: Int, contentType: String, text: String) =
            HttpResponse(status, listOf("Content-Type" to contentType), text.toByteArray(Charsets.UTF_8))
    }
}

/**
 * The little HTTP/1.1 server UPnP needs: device description, SCPD documents, SOAP control and event subscriptions. One request
 * per connection, bounded everything (header 16 KiB, body 256 KiB, 5 s per read, 8 connections at a time) and only for peers
 * [accept] lets in. It does not know UPnP; [handler] does.
 */
internal class HttpServer(
    private val accept: (InetAddress) -> Boolean,
    private val handler: (HttpRequest) -> HttpResponse,
    private val log: (String) -> Unit = {},
) {
    private var server: ServerSocket? = null
    private var pool: ThreadPoolExecutor? = null
    @Volatile private var running = false

    /** Binds the first free port from [preferred] upwards (a fixed port keeps controllers' cached addresses valid) and returns it. */
    fun start(preferred: Int, bind: InetAddress? = null): Int {
        check(!running) { "already running" }
        var failure: IOException? = null
        for (port in preferred..preferred + 20) {
            try {
                val socket = ServerSocket()
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(bind, port), 16)
                server = socket
                break
            } catch (error: IOException) { failure = error }
        }
        val socket = server ?: throw failure ?: IOException("no free port")
        pool = ThreadPoolExecutor(0, MAX_CONNECTIONS, 10, TimeUnit.SECONDS, SynchronousQueue()) { task ->
            Thread(task, "ThinkOS-Cast-Http").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
        }
        running = true
        Thread({ loop(socket) }, "ThinkOS-Cast-Accept").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
        return socket.localPort
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        pool?.shutdownNow()
        server = null; pool = null
    }

    private fun loop(socket: ServerSocket) {
        while (running) {
            val client = try { socket.accept() } catch (error: IOException) { if (running) log("accept failed: ${error.message}"); return }
            val peer = client.inetAddress
            if (!accept(peer)) { runCatching { client.close() }; continue }
            try { pool?.execute { serve(client) } } catch (error: RejectedExecutionException) { runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) {
        client.use { socket ->
            try {
                socket.soTimeout = READ_TIMEOUT_MILLIS
                socket.tcpNoDelay = true
                val request = read(socket)
                val response = try { handler(request) } catch (error: Exception) {
                    log("handler failed: ${error.javaClass.simpleName}")
                    HttpResponse(500)
                }
                write(socket, request, response)
                response.afterSent?.invoke()
            } catch (error: HttpError) {
                runCatching { write(socket, null, HttpResponse(error.status)); lingeringClose(socket) }
            } catch (error: SocketTimeoutException) {
                // A peer that stops talking is dropped without an answer.
            } catch (error: IOException) {
                // Reset by the peer.
            }
        }
    }

    private class HttpError(val status: Int) : Exception()

    /**
     * After an early error the peer may still be sending. Closing with unread data makes TCP reset the connection, and the
     * reset can overtake the error response; so finish the response, then drain what is left (a little) before closing.
     */
    private fun lingeringClose(socket: Socket) {
        socket.shutdownOutput()
        socket.soTimeout = 300
        val buffer = ByteArray(1024)
        var total = 0
        try { while (total < 16 * 1024) { val n = socket.getInputStream().read(buffer); if (n < 0) break; total += n } } catch (error: IOException) { }
    }

    private fun read(socket: Socket): HttpRequest {
        val input = socket.getInputStream()
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) throw IOException("closed")
            head.write(b)
            if (head.size() > MAX_HEADER_BYTES) throw HttpError(431)
            matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = head.toString(Charsets.ISO_8859_1.name()).split("\r\n").filter { it.isNotEmpty() }
        val first = lines.firstOrNull()?.split(' ') ?: throw HttpError(400)
        if (first.size != 3 || !first[2].startsWith("HTTP/1.")) throw HttpError(400)
        val headers = linkedMapOf<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpError(400)
            headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        if (headers["expect"]?.lowercase(Locale.ROOT) == "100-continue") {
            socket.getOutputStream().write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray()); socket.getOutputStream().flush()
        }
        val body = when {
            headers["transfer-encoding"]?.lowercase(Locale.ROOT)?.contains("chunked") == true -> chunked(input)
            else -> {
                val length = headers["content-length"]?.toLongOrNull() ?: 0L
                if (length < 0 || length > MAX_BODY_BYTES) throw HttpError(413)
                ByteArray(length.toInt()).also { readFully(input, it) }
            }
        }
        return HttpRequest(first[0].uppercase(Locale.ROOT), first[1], headers, body, socket.inetAddress)
    }

    private fun readFully(input: InputStream, into: ByteArray) {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) throw IOException("closed")
            read += n
        }
    }

    private fun chunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val line = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0) throw IOException("closed")
                if (b == '\n'.code) break
                if (b != '\r'.code) line.append(b.toChar())
                if (line.length > 16) throw HttpError(400)
            }
            val size = line.toString().substringBefore(';').trim().toIntOrNull(16) ?: throw HttpError(400)
            if (size == 0) break
            if (out.size() + size > MAX_BODY_BYTES) throw HttpError(413)
            val chunk = ByteArray(size)
            readFully(input, chunk)
            out.write(chunk)
            if (input.read() != '\r'.code || input.read() != '\n'.code) throw HttpError(400)
        }
        // Trailers are ignored up to the blank line.
        while (true) {
            var length = 0
            while (true) {
                val b = input.read()
                if (b < 0) return out.toByteArray()
                if (b == '\n'.code) break
                if (b != '\r'.code) length++
            }
            if (length == 0) return out.toByteArray()
        }
    }

    private fun write(socket: Socket, request: HttpRequest?, response: HttpResponse) {
        val reason = REASONS[response.status] ?: "OK"
        val out = ByteArrayOutputStream()
        out.write("HTTP/1.1 ${response.status} $reason\r\n".toByteArray())
        val names = response.headers.map { it.first.lowercase(Locale.ROOT) }.toSet()
        for ((name, value) in response.headers) out.write("$name: $value\r\n".toByteArray(Charsets.ISO_8859_1))
        if ("server" !in names) out.write("Server: Linux/3.18 UPnP/1.0 ThinkOS/1\r\n".toByteArray())
        if ("content-length" !in names) out.write("Content-Length: ${response.body.size}\r\n".toByteArray())
        out.write("Connection: close\r\n\r\n".toByteArray())
        if (request?.method != "HEAD") out.write(response.body)
        socket.getOutputStream().write(out.toByteArray())
        socket.getOutputStream().flush()
    }

    companion object {
        const val MAX_CONNECTIONS = 8
        private const val MAX_HEADER_BYTES = 16 * 1024
        private const val MAX_BODY_BYTES = 256 * 1024
        private const val READ_TIMEOUT_MILLIS = 5_000
        private val REASONS = mapOf(100 to "Continue", 200 to "OK", 400 to "Bad Request", 404 to "Not Found", 405 to "Method Not Allowed",
            412 to "Precondition Failed", 413 to "Payload Too Large", 431 to "Request Header Fields Too Large", 500 to "Internal Server Error",
            501 to "Not Implemented")
    }
}
