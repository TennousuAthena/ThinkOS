package moe.kiwi.thinkos.update

import java.io.Closeable
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real loopback sockets, including deliberately truncated/chunked bodies. No external network. */
internal class UpdateTestOrigin(socket: ServerSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")),
                                private val scheme: String = "http", handler: (Exchange) -> Unit) : Closeable {
    data class Hit(val path: String, val headers: Map<String, List<String>>)
    val hits = CopyOnWriteArrayList<Hit>()
    private val failures = CopyOnWriteArrayList<Throwable>()
    private val workers = Executors.newCachedThreadPool { work -> Thread(work, "update-fixture").apply { isDaemon = true } }
    private val server = socket
    private val acceptor = Thread({
        while (!server.isClosed) {
            val socket = try { server.accept() } catch (error: IOException) { if (!server.isClosed) failures.add(error); break }
            workers.execute {
                socket.use {
                    try {
                        val exchange = Exchange(socket)
                        hits.add(Hit(exchange.requestURI.path, exchange.requestHeaders.values.mapValues { it.value.toList() }))
                        handler(exchange)
                    } catch (_: IOException) {
                        // An intentional disconnect or cancellation may close either end of the socket.
                    } catch (error: Throwable) { failures.add(error) }
                }
            }
        }
    }, "update-fixture-accept").apply { isDaemon = true; start() }
    fun url(path: String = "/") = "$scheme://127.0.0.1:${server.localPort}$path"
    override fun close() {
        server.close()
        acceptor.join(1000)
        workers.shutdown()
        if (!workers.awaitTermination(1, TimeUnit.SECONDS)) workers.shutdownNow()
        failures.firstOrNull()?.let { throw AssertionError("Fixture failed", it) }
    }

    class Headers {
        val values = linkedMapOf<String, MutableList<String>>()
        fun add(name: String, value: String) { values.getOrPut(name.lowercase()) { mutableListOf() }.add(value) }
        fun getFirst(name: String) = values[name.lowercase()]?.firstOrNull()
    }

    class Exchange(private val socket: Socket) {
        val requestURI: URI
        val requestHeaders = Headers()
        val responseHeaders = Headers()
        private val wire = socket.getOutputStream().buffered()
        private var chunked = false
        val responseBody = object : OutputStream() {
            override fun write(value: Int) { write(byteArrayOf(value.toByte()), 0, 1) }
            override fun write(bytes: ByteArray, offset: Int, length: Int) {
                if (length == 0) return
                if (chunked) wire.write("${length.toString(16)}\r\n".toByteArray())
                wire.write(bytes, offset, length)
                if (chunked) wire.write("\r\n".toByteArray())
            }
            override fun flush() = wire.flush()
            override fun close() {
                if (chunked) wire.write("0\r\n\r\n".toByteArray())
                wire.flush()
            }
        }
        init {
            socket.soTimeout = 3000
            val input = socket.getInputStream()
            fun line(): String {
                val result = StringBuilder()
                while (result.length < 8192) {
                    val value = input.read()
                    if (value < 0) throw IOException("Truncated request")
                    if (value == 10) return result.toString().removeSuffix("\r")
                    result.append(value.toChar())
                }
                throw IOException("Oversized request line")
            }
            requestURI = URI(line().split(' ')[1])
            var headers = 0
            while (true) {
                val header = line()
                if (header.isEmpty()) break
                if (++headers > 64) throw IOException("Too many fixture request headers")
                val colon = header.indexOf(':')
                if (colon < 1) throw IOException("Bad fixture header")
                requestHeaders.add(header.substring(0, colon), header.substring(colon + 1).trim())
            }
        }
        fun sendResponseHeaders(code: Int, length: Long) {
            chunked = length == 0L
            val head = buildString {
                append("HTTP/1.1 $code Fixture\r\nConnection: close\r\n")
                if (chunked) append("Transfer-Encoding: chunked\r\n") else append("Content-Length: ${length.coerceAtLeast(0)}\r\n")
                responseHeaders.values.forEach { (key, values) -> values.forEach { append("$key: $it\r\n") } }
                append("\r\n")
            }
            wire.write(head.toByteArray()); wire.flush()
        }
    }

    companion object {
        fun reply(exchange: Exchange, bytes: ByteArray, code: Int = 200, headers: List<Pair<String, String>> = emptyList(),
                  chunked: Boolean = false, cutAt: Int? = null) {
            headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            exchange.sendResponseHeaders(code, if (code == 304 || bytes.isEmpty()) -1 else if (chunked) 0 else bytes.size.toLong())
            if (code != 304 && bytes.isNotEmpty()) {
                exchange.responseBody.write(bytes, 0, cutAt ?: bytes.size)
                exchange.responseBody.flush()
            }
            exchange.responseBody.close()
        }

        fun artifact(exchange: Exchange, bytes: ByteArray) {
            val range = exchange.requestHeaders.getFirst("Range")
            if (range == null) { reply(exchange, bytes); return }
            val values = Regex("bytes=(\\d+)-(\\d+)").matchEntire(range)!!.groupValues
            val start = values[1].toInt(); val end = minOf(values[2].toInt(), bytes.lastIndex)
            reply(exchange, bytes.copyOfRange(start, end + 1), 206, listOf("Content-Range" to "bytes $start-$end/${bytes.size}"))
        }
    }
}
