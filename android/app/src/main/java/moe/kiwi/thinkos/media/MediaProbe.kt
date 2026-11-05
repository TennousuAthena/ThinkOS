package moe.kiwi.thinkos.media

import moe.kiwi.thinkos.net.MediaHttpClient
import moe.kiwi.thinkos.net.PublicNetworkPolicy
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.net.URI
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * Whether an address answers like a stream (`tos.media.probe`, ADR-038). It takes the network rules playback takes (private
 * cleartext, `media.public_http`, three redirects, no HTTPS downgrade), so a plain-HTTP channel the player may open can be tested
 * too, which `tos.http` could not do. It reads a few kilobytes and never keeps a session: the playing one is not touched.
 *
 * An HLS playlist is followed down to its first segment, because a dead channel often still serves a playlist. An HTML page
 * with status 200 (a portal's "this channel has moved") is an answer but not a stream.
 */
internal class MediaProbe(private val fetch: Fetch) {
    private var publicFetch: Fetch = fetch
    private constructor(fetch: Fetch, publicFetch: Fetch) : this(fetch) { this.publicFetch = publicFetch }
    fun interface Fetch {
        @Throws(IOException::class)
        fun get(url: String, headers: Map<String, String>, limit: Int): Reply
    }

    /** [head] is the first bytes of the body (at most the limit asked for); [url] is where the redirects ended. */
    class Reply(val status: Int, val contentType: String, val head: ByteArray, val url: String)

    private class Verdict(val ok: Boolean, val status: Int, val kind: String, val error: String? = null)

    /** `ok`, the HTTP `status` (0 when nothing answered), `kind` (`hls`, `stream`, `html`, `list`, `empty`), `millis` and, when not ok, `error`. */
    fun run(entry: MediaEntry): Map<String, Any?> {
        val started = System.nanoTime()
        val verdict = when (entry.scheme) {
            "http", "https" -> look(entry.normalizedUrl, entry.headers, 0, started, entry.publicOnly,
                if (entry.publicOnly) publicFetch else fetch, entry.origin)
            else -> Verdict(false, 0, "", "unsupported")
        }
        val result = linkedMapOf<String, Any?>("ok" to verdict.ok, "status" to verdict.status.toLong(), "kind" to verdict.kind,
            "millis" to (System.nanoTime() - started) / 1_000_000)
        verdict.error?.let { result["error"] = it }
        return result
    }

    private fun look(url: String, headers: Map<String, String>, depth: Int, started: Long,
                     publicOnly: Boolean, activeFetch: Fetch, origin: String?): Verdict {
        if (System.nanoTime() - started > DEADLINE_NANOS) return Verdict(false, 0, "", "timeout")
        val reply = try {
            if (publicOnly) PublicNetworkPolicy.validate(url)
            // Each HLS child is a new request, not necessarily an HTTP
            // redirect. Recheck its full scheme/host/effective port against
            // the entry's origin; resolving against the final reply URL must
            // never confer the origin's credentials on a CDN.
            val sameOrigin = origin != null && runCatching { MediaEntry.origin(URI(url)) == origin }.getOrDefault(false)
            val requestHeaders = if (sameOrigin) headers else headers.filterKeys { it.lowercase(Locale.ROOT) in MediaEntry.PORTABLE_HEADERS }
            activeFetch.get(url, requestHeaders, HEAD_BYTES).also { if (publicOnly) PublicNetworkPolicy.validate(it.url) }
        } catch (error: PublicNetworkPolicy.Blocked) { return Verdict(false, 0, "", "policy") }
          catch (error: IOException) { return Verdict(false, 0, "", categoryOf(error)) }
        if (reply.status !in 200..299) return Verdict(false, reply.status, "", "http")
        val kind = sniff(reply.contentType, reply.head)
        return when (kind) {
            "html", "list", "empty" -> Verdict(false, reply.status, kind, "format")
            "hls" -> {
                // Also reject cleartext/local URI attributes (keys, maps and
                // alternate text/renditions), not just the first bare segment.
                // DNS/peer checks occur in the actual client for every fetch.
                if (publicOnly && !publicPlaylist(reply)) Verdict(false, reply.status, kind, "policy")
                else if (depth >= MAX_DEPTH) Verdict(true, reply.status, kind)
                else follow(reply, headers, depth, started, publicOnly, activeFetch, origin)
                    ?.let { if (it.ok) Verdict(true, reply.status, "hls") else it } ?: Verdict(true, reply.status, kind)
            }
            else -> Verdict(true, reply.status, kind)
        }
    }

    /** The first variant or segment a playlist names, or null when it names none or the address cannot be resolved. */
    private fun follow(reply: Reply, headers: Map<String, String>, depth: Int, started: Long,
                       publicOnly: Boolean, activeFetch: Fetch, origin: String?): Verdict? {
        val first = String(reply.head, Charsets.UTF_8).lineSequence().map(String::trim).firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return null
        val next = try { URI(reply.url).resolve(first) } catch (error: IllegalArgumentException) { return null }
        if (next.scheme != "http" && next.scheme != "https") return null
        return look(next.toASCIIString(), headers, depth + 1, started, publicOnly, activeFetch, origin)
    }

    private fun publicPlaylist(reply: Reply): Boolean = try {
        val base = URI(reply.url)
        String(reply.head, Charsets.UTF_8).lineSequence().map(String::trim).forEach { line ->
            if (line.isNotEmpty() && !line.startsWith("#")) PublicNetworkPolicy.validate(base.resolve(line))
            else URI_ATTRIBUTE.findAll(line).forEach { PublicNetworkPolicy.validate(base.resolve(it.groupValues[1])) }
        }
        true
    } catch (error: Exception) { false }

    companion object {
        const val HEAD_BYTES = 16 * 1024
        /** What playback introduces itself as (MediaEngine); a channel list may name another one per channel. */
        private const val USER_AGENT = "ThinkOS/1"
        private const val MAX_DEPTH = 2          // playlist, variant playlist, segment
        private const val DEADLINE_NANOS = 12_000_000_000L
        private val URI_ATTRIBUTE = Regex("(?:^|[,:])\\s*URI\\s*=\\s*\"([^\"]*)\"")

        fun policyResult(): Map<String, Any?> = mapOf("ok" to false, "status" to 0L, "kind" to "", "millis" to 0L, "error" to "policy")

        fun sniff(contentType: String, head: ByteArray): String {
            if (head.isEmpty()) return "empty"
            val start = String(head, 0, minOf(head.size, 512), Charsets.ISO_8859_1).trimStart('ï', '»', '¿', ' ', '\t', '\r', '\n')
            val lower = start.lowercase(Locale.ROOT)
            if (lower.startsWith("#extm3u")) {
                return if (String(head, Charsets.ISO_8859_1).contains("#EXT-X-")) "hls" else "list"
            }
            if ("html" in contentType.lowercase(Locale.ROOT) || lower.startsWith("<!doctype html") || lower.startsWith("<html")) return "html"
            return "stream"
        }

        fun categoryOf(error: IOException): String = when {
            PublicNetworkPolicy.blocked(error) -> "policy"
            error is UnknownHostException -> "dns"
            error is InterruptedIOException -> "timeout"
            error is SSLException -> "tls"
            // The same words the engine looks for in a playback failure (MediaEngine.policyBlocked).
            error.message?.let { it.contains("restricted to local/private") || it.contains("HTTPS downgrade") || it.contains("Cleartext") } == true -> "policy"
            else -> "network"
        }

        /** The HTTP side: playback's client with shorter waits. [allowPublicCleartext] is read on every connection. */
        fun create(allowPublicCleartext: () -> Boolean): MediaProbe {
            fun fetch(publicOnly: Boolean) = OkHttpFetch(MediaHttpClient.create(publicOnly = publicOnly,
                allowPublicCleartext = allowPublicCleartext).newBuilder()
                .readTimeout(4, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS).build())
            // create() constructs two independent pools/dispatchers/DNS guards.
            return MediaProbe(fetch(false), fetch(true))
        }
    }

    internal class OkHttpFetch(private val client: OkHttpClient) : Fetch {
        override fun get(url: String, headers: Map<String, String>, limit: Int): Reply {
            val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).header("Range", "bytes=0-${limit - 1}").apply {
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            client.newCall(request).execute().use { response ->
                val buffer = ByteArray(limit)
                var count = 0
                val source = response.body?.source()
                if (source != null) try {
                    while (count < limit) {
                        val read = source.read(buffer, count, limit - count)
                        if (read < 0) break
                        count += read
                    }
                } catch (error: IOException) {
                    // A live stream that stalls after its first bytes is still a stream that answered.
                    if (count == 0) throw error
                }
                return Reply(response.code, response.header("Content-Type").orEmpty(), buffer.copyOf(count), response.request.url.toString())
            }
        }
    }
}
