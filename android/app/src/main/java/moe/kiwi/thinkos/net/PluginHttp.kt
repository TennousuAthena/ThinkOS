package moe.kiwi.thinkos.net

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object PluginHttp {
    private val resolver = Executors.newFixedThreadPool(2) { task -> Thread(task, "ThinkOS-DNS").apply { isDaemon = true } }
    private const val MAX_BODY = 1024 * 1024

    fun timeout(options: Map<*, *>?): Long? = (options?.get("timeout_ms") as? Number)?.toLong()?.also {
        require(it in 500..10_000) { "Invalid HTTP timeout" }
    }

    private fun sameOrigin(a: URI, b: URI): Boolean {
        fun port(u: URI) = if (u.port != -1) u.port else if (u.scheme == "https") 443 else 80
        return a.scheme == b.scheme && a.host.equals(b.host, ignoreCase = true) && port(a) == port(b)
    }

    /** Called only on IO workers. HTTP is pinned to its validated private address. */
    fun request(method: String, target: String, body: String?, options: Map<*, *>?): Map<String, Any?> {
        val budget = timeout(options)
        val deadline = budget?.let { System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(it) }
        fun remaining(): Int {
            if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("HTTP cancelled")
            if (deadline == null) return 3000
            val ms = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (ms <= 0) throw java.net.SocketTimeoutException("HTTP deadline")
            return ms.coerceAtMost(3000).toInt()
        }
        val limit = (options?.get("max_bytes") as? Number)?.toInt()?.also { require(it in 1..MAX_BODY) } ?: MAX_BODY
        val initial = URI(target)
        var uri = initial
        var verb = method
        var payload = body
        repeat(4) { redirect ->
            LocalNetworkPolicy.validate(uri)
            val connectionUri = if (uri.scheme == "http") {
                val job = resolver.submit<Array<InetAddress>> { InetAddress.getAllByName(uri.host) }
                val addresses = try { job.get(remaining().toLong(), TimeUnit.MILLISECONDS) } finally { job.cancel(true) }
                LocalNetworkPolicy.requirePrivate(addresses)
                LocalNetworkPolicy.pin(uri, addresses.first())
            } else uri
            val connection = connectionUri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = remaining()
            connection.readTimeout = remaining()
            connection.instanceFollowRedirects = false
            connection.requestMethod = verb
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (uri.scheme == "http") connection.setRequestProperty("Host", uri.rawAuthority)
            if (sameOrigin(initial, uri)) (options?.get("headers") as? Map<*, *>)?.forEach { (name, value) ->
                require(name is String && value is String && name.lowercase() !in setOf("host", "connection", "content-length", "accept-encoding")) { "Invalid request header" }
                connection.setRequestProperty(name, value)
            }
            try {
                if (payload != null) {
                    val bytes = payload!!.toByteArray(Charsets.UTF_8)
                    require(bytes.size <= MAX_BODY) { "HTTP request body exceeds 1MB" }
                    connection.doOutput = true
                    connection.setFixedLengthStreamingMode(bytes.size)
                    connection.outputStream.use { it.write(bytes) }
                }
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    require(redirect < 3) { "Too many redirects" }
                    val next = uri.resolve(connection.getHeaderField("Location") ?: error("Redirect missing Location"))
                    require(!(uri.scheme == "https" && next.scheme == "http")) { "HTTPS downgrade rejected" }
                    require(options?.get("same_origin") != true || sameOrigin(initial, next)) { "Cross-origin redirect rejected" }
                    require(payload == null || sameOrigin(uri, next) || status in setOf(301, 302, 303)) { "Cross-origin request body rejected" }
                    uri = next
                    if (status == 303 || status in setOf(301, 302) && verb == "POST") { verb = "GET"; payload = null }
                } else {
                    val input = if (status >= 400) connection.errorStream else connection.inputStream
                    val output = java.io.ByteArrayOutputStream()
                    input?.use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            remaining()
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= limit) { "HTTP response exceeds 1MB" }
                            output.write(buffer, 0, count)
                        }
                    }
                    val headers = connection.headerFields.filterKeys { it != null }.mapValues { it.value.joinToString(", ") }
                    return mapOf("status" to status.toLong(), "headers" to headers, "body" to output.toString("UTF-8"))
                }
            } finally { connection.disconnect() }
        }
        error("Too many redirects")
    }
}
