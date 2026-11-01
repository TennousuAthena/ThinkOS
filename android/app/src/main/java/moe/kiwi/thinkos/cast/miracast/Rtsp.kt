package moe.kiwi.thinkos.cast.miracast

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

/** One RTSP request or response. Header names are lower case. */
internal class RtspMessage(val startLine: String, val headers: Map<String, String>, val body: String) {
    val isResponse: Boolean get() = startLine.startsWith("RTSP/")
    val method: String get() = if (isResponse) "" else startLine.substringBefore(' ')
    val uri: String get() = if (isResponse) "" else startLine.split(' ').getOrElse(1) { "" }
    val status: Int get() = if (isResponse) startLine.split(' ').getOrNull(1)?.toIntOrNull() ?: 0 else 0
    val cseq: Int? get() = headers["cseq"]?.trim()?.toIntOrNull()

    /** `name: value` pairs of a `text/parameters` body; a bare `name` (as in GET_PARAMETER) maps to the empty string. */
    fun parameters(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (line in body.split("\r\n", "\n")) {
            if (line.isBlank()) continue
            val colon = line.indexOf(':')
            if (colon < 0) out[line.trim()] = "" else out[line.substring(0, colon).trim()] = line.substring(colon + 1).trim()
        }
        return out
    }
}

internal class RtspError(message: String) : IOException(message)

/**
 * Reads RTSP messages from a stream the sender controls, so everything is bounded: a header block of at most
 * [maxHeaderBytes], a body of at most [maxBodyBytes], at most [maxHeaders] header lines.
 */
internal class RtspReader(
    private val input: InputStream,
    private val maxHeaderBytes: Int = 16 * 1024,
    private val maxBodyBytes: Int = 64 * 1024,
    private val maxHeaders: Int = 64,
) {
    /** Returns null when the peer closed the connection cleanly between messages. */
    fun read(): RtspMessage? {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0) { if (head.size() == 0) return null else throw RtspError("Connection closed inside a message") }
            // Stray line breaks between messages are tolerated.
            if (head.size() == 0 && (b == '\r'.code || b == '\n'.code)) continue
            head.write(b)
            if (head.size() > maxHeaderBytes) throw RtspError("RTSP header too large")
            matched = if (b == "\r\n\r\n"[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        val lines = String(head.toByteArray(), Charsets.ISO_8859_1).split("\r\n").filter { it.isNotEmpty() }
        val start = lines.firstOrNull() ?: throw RtspError("Empty RTSP message")
        if (!start.contains("RTSP/1.")) throw RtspError("Not an RTSP message")
        if (lines.size - 1 > maxHeaders) throw RtspError("Too many RTSP headers")
        val headers = LinkedHashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon <= 0) throw RtspError("Malformed RTSP header")
            headers[line.substring(0, colon).trim().lowercase(Locale.ROOT)] = line.substring(colon + 1).trim()
        }
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        if (length < 0 || length > maxBodyBytes) throw RtspError("RTSP body too large")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw RtspError("Connection closed inside a body")
            read += n
        }
        return RtspMessage(start, headers, String(body, Charsets.UTF_8))
    }
}

internal object Rtsp {
    fun request(method: String, uri: String, cseq: Int, headers: List<Pair<String, String>> = emptyList(), body: String = ""): ByteArray =
        build("$method $uri RTSP/1.0", cseq, headers, body)

    fun response(status: Int, cseq: Int, headers: List<Pair<String, String>> = emptyList(), body: String = ""): ByteArray =
        build("RTSP/1.0 $status ${reason(status)}", cseq, headers, body)

    private fun build(start: String, cseq: Int, headers: List<Pair<String, String>>, body: String): ByteArray {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val out = StringBuilder(start).append("\r\n").append("CSeq: ").append(cseq).append("\r\n")
        for ((name, value) in headers) {
            require(name.none { it == '\r' || it == '\n' || it == ':' } && value.none { it == '\r' || it == '\n' }) { "Invalid RTSP header" }
            out.append(name).append(": ").append(value).append("\r\n")
        }
        if (bytes.isNotEmpty()) out.append("Content-Type: text/parameters\r\nContent-Length: ").append(bytes.size).append("\r\n")
        out.append("\r\n")
        return out.toString().toByteArray(Charsets.ISO_8859_1) + bytes
    }

    private fun reason(status: Int) = when (status) {
        200 -> "OK"; 400 -> "Bad Request"; 405 -> "Method Not Allowed"; 454 -> "Session Not Found"; 501 -> "Not Implemented"; else -> "Error"
    }
}
