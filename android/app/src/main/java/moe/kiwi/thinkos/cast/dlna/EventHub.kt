package moe.kiwi.thinkos.cast.dlna

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.util.UUID
import java.util.concurrent.Executors

/** GENA: what a controller asked to be told about, and the NOTIFY messages that tell it. */
internal class EventHub(
    private val now: () -> Long = System::currentTimeMillis,
    /** Delivers one NOTIFY; false when the controller could not be reached. Replaceable in tests. */
    private val deliver: (URI, List<Pair<String, String>>, String) -> Boolean = EventHub::notifyOverSocket,
    private val executor: java.util.concurrent.Executor = Executors.newSingleThreadExecutor { Thread(it, "ThinkOS-Cast-Events").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } },
) {
    private class Subscription(val sid: String, val service: ServiceDef, val callback: URI, var expiresAt: Long) {
        var seq = 0L
        var failures = 0
    }

    private val subscriptions = linkedMapOf<String, Subscription>()

    class Granted(val sid: String, val timeoutSeconds: Int)

    /**
     * [callbackHeader] is `<http://host:port/path>`, possibly several. Only an address on the controller's own host is accepted,
     * so a subscription can never make this device send requests to a third machine.
     */
    @Synchronized
    fun subscribe(service: ServiceDef, callbackHeader: String, timeoutHeader: String?, remote: InetAddress): Granted? {
        prune()
        val callback = Regex("<([^>]+)>").findAll(callbackHeader).map { it.groupValues[1] }.mapNotNull { runCatching { URI(it) }.getOrNull() }
            .firstOrNull { it.scheme == "http" && it.host == remote.hostAddress && it.userInfo == null && (it.port == -1 || it.port in 1..65535) }
            ?: return null
        if (subscriptions.size >= MAX_SUBSCRIPTIONS) return null
        val seconds = timeoutSeconds(timeoutHeader)
        val sub = Subscription("uuid:" + UUID.randomUUID(), service, callback, now() + seconds * 1000L)
        subscriptions[sub.sid] = sub
        return Granted(sub.sid, seconds)
    }

    @Synchronized
    fun renew(sid: String, timeoutHeader: String?): Granted? {
        prune()
        val sub = subscriptions[sid] ?: return null
        val seconds = timeoutSeconds(timeoutHeader)
        sub.expiresAt = now() + seconds * 1000L
        return Granted(sid, seconds)
    }

    @Synchronized
    fun unsubscribe(sid: String): Boolean = subscriptions.remove(sid) != null

    @Synchronized
    fun count(): Int = subscriptions.size

    /** The first event of a subscription carries the whole state. */
    @Synchronized
    fun sendInitial(sid: String, properties: List<Pair<String, String>>) {
        subscriptions[sid]?.let { send(it, properties) }
    }

    /** A service's `LastChange` document, to everyone subscribed to that service. */
    @Synchronized
    fun publish(service: ServiceDef, lastChange: String) {
        prune()
        for (sub in subscriptions.values.filter { it.service == service }) send(sub, listOf("LastChange" to lastChange))
    }

    private fun send(sub: Subscription, properties: List<Pair<String, String>>) {
        val seq = sub.seq++
        val body = buildString {
            append("<?xml version=\"1.0\" encoding=\"utf-8\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">")
            for ((name, value) in properties) append("<e:property><").append(name).append('>').append(Soap.escape(value)).append("</").append(name).append("></e:property>")
            append("</e:propertyset>")
        }
        val headers = listOf("NT" to "upnp:event", "NTS" to "upnp:propchange", "SID" to sub.sid, "SEQ" to seq.toString())
        executor.execute {
            val ok = deliver(sub.callback, headers, body)
            synchronized(this) {
                if (ok) sub.failures = 0 else if (++sub.failures >= MAX_FAILURES) subscriptions.remove(sub.sid)
            }
        }
    }

    private fun prune() {
        val limit = now()
        subscriptions.values.removeAll { it.expiresAt <= limit }
    }

    private fun timeoutSeconds(header: String?): Int =
        header?.trim()?.removePrefix("Second-")?.toIntOrNull()?.coerceIn(60, MAX_TIMEOUT_SECONDS) ?: DEFAULT_TIMEOUT_SECONDS

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 1800
        private const val MAX_TIMEOUT_SECONDS = 3600
        private const val MAX_SUBSCRIPTIONS = 12
        private const val MAX_FAILURES = 3

        /** `HttpURLConnection` has no NOTIFY method, so this speaks the few lines of HTTP itself. */
        fun notifyOverSocket(callback: URI, headers: List<Pair<String, String>>, body: String): Boolean = try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(callback.host, if (callback.port == -1) 80 else callback.port), 2000)
                socket.soTimeout = 2000
                val path = (callback.rawPath.takeUnless { it.isNullOrEmpty() } ?: "/") + (callback.rawQuery?.let { "?$it" } ?: "")
                val payload = body.toByteArray(Charsets.UTF_8)
                val head = buildString {
                    append("NOTIFY ").append(path).append(" HTTP/1.1\r\nHost: ").append(callback.host)
                    if (callback.port != -1) append(':').append(callback.port)
                    append("\r\nContent-Type: text/xml; charset=\"utf-8\"\r\nContent-Length: ").append(payload.size).append("\r\n")
                    for ((name, value) in headers) append(name).append(": ").append(value).append("\r\n")
                    append("Connection: close\r\n\r\n")
                }
                socket.getOutputStream().write(head.toByteArray(Charsets.ISO_8859_1) + payload)
                socket.getOutputStream().flush()
                val status = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1).readLine().orEmpty()
                status.startsWith("HTTP/1.") && status.split(' ').getOrNull(1)?.startsWith("2") == true
            }
        } catch (error: Exception) { false }
    }
}
