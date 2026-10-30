package moe.kiwi.thinkos.net

import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal object PluginHttp {
    private val resolver = Executors.newFixedThreadPool(2) { task -> Thread(task, "ThinkOS-DNS").apply { isDaemon = true } }
    private const val MAX_BODY = 1024 * 1024

    /** Called only on IO workers. HTTP is pinned to its validated private address. */
    fun request(method: String, target: String, body: String?, options: Map<*, *>?): Map<String, Any?> {
        var uri = URI(target)
        var verb = method
        var payload = body
        repeat(4) { redirect ->
            LocalNetworkPolicy.validate(uri)
            val connectionUri = if (uri.scheme == "http") {
                val job = resolver.submit<Array<InetAddress>> { InetAddress.getAllByName(uri.host) }
                val addresses = try { job.get(3, TimeUnit.SECONDS) } finally { job.cancel(true) }
                LocalNetworkPolicy.requirePrivate(addresses)
                LocalNetworkPolicy.pin(uri, addresses.first())
            } else uri
            val connection = connectionUri.toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            connection.instanceFollowRedirects = false
            connection.requestMethod = verb
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (uri.scheme == "http") connection.setRequestProperty("Host", uri.rawAuthority)
            (options?.get("headers") as? Map<*, *>)?.forEach { (name, value) ->
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
                    uri = next
                    if (status == 303 || status in setOf(301, 302) && verb == "POST") { verb = "GET"; payload = null }
                } else {
                    val input = if (status >= 400) connection.errorStream else connection.inputStream
                    val output = java.io.ByteArrayOutputStream()
                    input?.use { stream ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            require(output.size() + count <= MAX_BODY) { "HTTP response exceeds 1MB" }
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
