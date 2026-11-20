package moe.kiwi.thinkos.net

import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Assert.*
import org.junit.Test

class PluginHttpTest {
    /** Simple HTTP/1.1 loopback origin, no Android or additional JDK modules. */
    private class Origin : AutoCloseable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val base = "http://127.0.0.1:" + server.localPort

        fun serve(count: Int, response: (String, Map<String, String>) -> Pair<String, String>): FutureTask<Unit> {
            val task = FutureTask {
                repeat(count) {
                    server.accept().use { socket ->
                        socket.soTimeout = 2000
                        val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                        val target = input.readLine().split(' ')[1]
                        val fields = mutableMapOf<String, String>()
                        while (true) {
                            val line = input.readLine()?.takeUnless { it.isEmpty() } ?: break
                            fields[line.substringBefore(':').lowercase()] = line.substringAfter(':').trim()
                        }
                        val (statusAndHeaders, body) = response(target, fields)
                        val bytes = body.toByteArray(Charsets.UTF_8)
                        val output = socket.getOutputStream()
                        output.write(("HTTP/1.1 $statusAndHeaders\r\nConnection: close\r\nContent-Length: "+bytes.size+"\r\n\r\n").toByteArray(Charsets.US_ASCII))
                        output.write(bytes)
                    }
                }
            }
            thread(isDaemon = true) { task.run() }
            return task
        }

        override fun close() = server.close()
    }

    @Test fun preservesTheEncodedQueryAndFollowsSameOriginRedirect() {
        Origin().use { origin ->
            val serving = origin.serve(4) { path, _ ->
                val query = path.substringAfter('?')
                if (path.startsWith("/search")) "302 Found\r\nLocation: /result?$query" to ""
                else "200 OK" to query
            }
            val reply = PluginHttp.request("GET", origin.base+"/search?q=A%26B", null,
                mapOf("same_origin" to true, "timeout_ms" to 5000, "max_bytes" to 32))
            assertEquals(200L, reply["status"])
            assertEquals("q=A%26B", reply["body"])
            val error = runCatching { PluginHttp.request("GET", origin.base+"/search?q=A%26B", null, mapOf("max_bytes" to 4)) }
            assertTrue(error.exceptionOrNull() is IllegalArgumentException)
            serving.get(2, TimeUnit.SECONDS)
        }
    }

    @Test fun deniesCrossOriginWhenRequestedAndDropsCustomHeadersOtherwise() {
        Origin().use { source ->
            Origin().use { other ->
                val first = source.serve(2) { _, _ -> "302 Found\r\nLocation: "+other.base+"/check" to "" }
                val second = other.serve(1) { _, headers -> "200 OK" to (headers["x-test-marker"] ?: "none") }
                val headers = mapOf("headers" to mapOf("X-Test-Marker" to "origin-only"))
                assertEquals("none", PluginHttp.request("GET", source.base+"/search", null, headers)["body"])
                val rejected = runCatching { PluginHttp.request("GET", source.base+"/search", null, headers + ("same_origin" to true)) }
                assertEquals("Cross-origin redirect rejected", rejected.exceptionOrNull()?.message)
                first.get(2, TimeUnit.SECONDS)
                second.get(2, TimeUnit.SECONDS)
            }
        }
    }
}
