package moe.kiwi.thinkos.media

import java.net.URI
import java.util.Locale
import moe.kiwi.thinkos.net.PublicNetworkPolicy

/** How the player should read an item. `auto` follows the URL (`.m3u8`, `.mpd`, `rtsp:`), then sniffs the container. */
enum class MediaSourceType(val id: String) {
    AUTO("auto"), PROGRESSIVE("progressive"), HLS("hls"), DASH("dash"), RTSP("rtsp");
    companion object {
        fun parse(value: String): MediaSourceType = entries.firstOrNull { it.id == value }
            ?: throw IllegalArgumentException("Media type must be auto, progressive, hls, dash or rtsp")
    }
}

/** A side-loaded text track. Embedded subtitles need no declaration. */
data class MediaSubtitle(
    val url: String,
    val mimeType: String = mimeForName(url) ?: SRT,
    val language: String? = null,
    val label: String? = null,
) {
    init {
        require(url.isNotBlank() && url.length <= 8192) { "Invalid subtitle URL" }
        require(mimeType in MIME_TYPES) { "Subtitle MIME type must be SubRip, WebVTT, TTML or SSA" }
        require(language == null || language.length in 2..35 && language.all { it.isLetterOrDigit() || it == '-' }) { "Invalid subtitle language" }
        require(label == null || label.length <= 128) { "Subtitle label too long" }
        val uri = URI(MediaEntry.normalize(url))
        require(uri.userInfo == null && uri.fragment == null) { "Subtitle URL cannot contain credentials or a fragment" }
        when (uri.scheme) {
            "http", "https" -> require(!uri.host.isNullOrBlank()) { "Invalid subtitle HTTP URL" }
            "file" -> require(uri.host.isNullOrEmpty() || uri.host == "localhost") { "Remote file URLs are not supported" }
            "content" -> require(!uri.authority.isNullOrBlank()) { "Invalid subtitle content URL" }
            else -> throw IllegalArgumentException("Subtitle URL must be HTTP(S), file, content or an absolute path")
        }
    }

    val normalizedUrl: String get() = MediaEntry.normalize(url)

    companion object {
        const val SRT = "application/x-subrip"
        const val VTT = "text/vtt"
        const val TTML = "application/ttml+xml"
        const val SSA = "text/x-ssa"
        val MIME_TYPES = setOf(SRT, VTT, TTML, SSA)

        fun mimeForName(name: String): String? = when (name.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
            "srt" -> SRT
            "vtt" -> VTT
            "ttml", "xml", "dfxp" -> TTML
            "ass", "ssa" -> SSA
            else -> null
        }

        fun fromMap(value: Map<*, *>): MediaSubtitle {
            fun string(key: String): String? = value[key]?.let { require(it is String) { "subtitle.$key must be a string" }; it }
            val url = string("url") ?: throw IllegalArgumentException("subtitle.url is required")
            return MediaSubtitle(url, string("mime") ?: mimeForName(url) ?: SRT, string("lang"), string("label"))
        }
    }
}

/**
 * One playback item. Disk and network IO are deliberately absent from validation.
 *
 * [headers] are request headers for the item's own HTTP(S) traffic. `Authorization` and `Cookie`
 * stay on the item's origin; they never appear in the published `media` state or in logs.
 * An `rtsp:` URL may carry `user:password@`; that is the only place credentials in a URL are accepted.
 */
data class MediaEntry(
    val url: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val art: String = "",
    val kind: String = "audio",
    val mimeType: String? = null,
    val type: MediaSourceType = MediaSourceType.AUTO,
    val headers: Map<String, String> = emptyMap(),
    val subtitles: List<MediaSubtitle> = emptyList(),
    /** A live stream the OS produces itself (screen mirroring). Lua has no way to set it, and [MirrorSource.SCHEME] is refused in every plugin-supplied URL. */
    val mirror: MirrorSource? = null,
    /** Narrow all media and subtitle fetches to public HTTPS, including HLS/DASH children. */
    val publicOnly: Boolean = false,
) {
    init {
        require(url.isNotBlank() && url.length <= 8192) { "Invalid media URL" }
        require(kind in setOf("audio", "video")) { "Media kind must be audio or video" }
        require(listOf(title, artist, album).all { it.length <= 2048 } && art.length <= 8192) { "Media metadata too large" }
        require(mimeType == null || mimeType.length <= 128 && mimeType.none { it.code < 32 || it.code == 127 } &&
            (mimeType.startsWith("audio/") || mimeType.startsWith("video/") || mimeType in MANIFEST_MIME_TYPES)) { "Invalid media MIME type" }
        val uri = URI(normalizedUrl)
        if (publicOnly) {
            PublicNetworkPolicy.validate(uri)
            subtitles.forEach { PublicNetworkPolicy.validate(URI(it.normalizedUrl)) }
        }
        require(uri.fragment == null) { "Media URL cannot contain a fragment" }
        if (mirror != null) {
            require(uri.scheme == MirrorSource.SCHEME && kind == "video" && type == MediaSourceType.PROGRESSIVE && headers.isEmpty() && subtitles.isEmpty()) {
                "A mirror is a progressive video without headers or subtitles"
            }
        } else {
            when (uri.scheme) {
                "http", "https" -> {
                    require(uri.userInfo == null) { "Media URL cannot contain credentials" }
                    require(!uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)) { "Invalid media HTTP URL" }
                    require(type != MediaSourceType.RTSP) { "An RTSP item needs an rtsp: URL" }
                }
                "file" -> {
                    require(uri.userInfo == null) { "Media URL cannot contain credentials" }
                    require(uri.host.isNullOrEmpty() || uri.host == "localhost") { "Remote file URLs are not supported" }
                    require(type != MediaSourceType.RTSP) { "An RTSP item needs an rtsp: URL" }
                }
                "content" -> {
                    require(uri.userInfo == null) { "Media URL cannot contain credentials" }
                    require(!uri.authority.isNullOrBlank()) { "Invalid content URL" }
                    require(type != MediaSourceType.RTSP) { "An RTSP item needs an rtsp: URL" }
                }
                "rtsp" -> {
                    require(!uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)) { "Invalid RTSP URL" }
                    require(type == MediaSourceType.AUTO || type == MediaSourceType.RTSP) { "An rtsp: URL cannot use type ${type.id}" }
                }
                else -> throw IllegalArgumentException("Media URL must be HTTP(S), RTSP, file, content or an absolute path")
            }
            if (uri.scheme in setOf("file", "content")) require(uri.path?.startsWith('/') == true) { "Invalid media file path" }
            require(headers.isEmpty() || uri.scheme in setOf("http", "https")) { "Request headers apply to HTTP(S) items only" }
            require(subtitles.size <= 16) { "At most 16 side-loaded subtitle tracks" }
            require(type != MediaSourceType.PROGRESSIVE || subtitles.isEmpty()) { "Side-loaded subtitles need type auto, hls or dash" }
            validateHeaders(headers)
        }
    }

    /** Convert an absolute local path without probing the filesystem on the UI thread. */
    val normalizedUrl: String get() = normalize(url)

    val scheme: String get() = URI(normalizedUrl).scheme

    /** What this item resolves to once `auto` has looked at its URL. Null means "let the player sniff the container". */
    val effectiveType: MediaSourceType? get() = when {
        type != MediaSourceType.AUTO -> type
        scheme == "rtsp" -> MediaSourceType.RTSP
        mimeType == M3U8 || mimeType == M3U8_APPLE -> MediaSourceType.HLS
        mimeType == MPD -> MediaSourceType.DASH
        else -> URI(normalizedUrl).path.orEmpty().lowercase(Locale.ROOT).let {
            when {
                it.endsWith(".m3u8") -> MediaSourceType.HLS
                it.endsWith(".mpd") -> MediaSourceType.DASH
                else -> null
            }
        }
    }

    /** `scheme://host:port` of an HTTP(S) URL, the unit that credentials are scoped to. */
    val origin: String? get() = URI(normalizedUrl).takeIf { it.scheme == "http" || it.scheme == "https" }?.let { origin(it) }

    /** The URL with any `user:password@` removed, safe for state and logs. */
    val publicUrl: String get() = URI(normalizedUrl).let { uri ->
        if (uri.userInfo == null) normalizedUrl
        else URI(uri.scheme, null, uri.host, uri.port, uri.rawPath, uri.rawQuery, null).toASCIIString()
    }

    companion object {
        const val M3U8 = "application/x-mpegURL"
        const val M3U8_APPLE = "application/vnd.apple.mpegurl"
        const val MPD = "application/dash+xml"
        val MANIFEST_MIME_TYPES = setOf(M3U8, M3U8_APPLE, MPD)

        /** Headers a plugin may never set: the HTTP stack owns framing, caching and connection state. */
        private val FORBIDDEN = setOf("host", "content-length", "range", "connection", "transfer-encoding", "upgrade", "te", "trailer",
            "keep-alive", "expect", "accept-encoding", "proxy-authorization", "proxy-connection", "if-range", "if-match",
            "if-none-match", "if-modified-since", "if-unmodified-since", "content-type", "content-encoding")
        /**
         * The only headers that may go to a host other than the item's own (a CDN, a key server, a redirect target).
         * Everything else a plugin sets, including `Authorization`, `Cookie` and any `X-Api-Key` style token, is a
         * credential as far as the player can tell, and stays with the origin of the item's URL.
         */
        val PORTABLE_HEADERS = setOf("user-agent", "referer", "accept", "accept-language", "origin")
        private val CREDENTIALS = Regex("(?<=://)[^/@\\s]+@")

        /** Error text and logs quote URLs; `user:password@` never leaves the engine. */
        fun redact(text: String): String = text.replace(CREDENTIALS, "")
        private val TOKEN = Regex("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,64}")

        fun validateHeaders(headers: Map<String, String>) {
            require(headers.size <= 8) { "At most 8 request headers" }
            val seen = HashSet<String>()
            headers.forEach { (name, value) ->
                require(TOKEN.matches(name)) { "Invalid request header name" }
                require(name.lowercase(Locale.ROOT) !in FORBIDDEN) { "Request header ${name} is not allowed" }
                require(seen.add(name.lowercase(Locale.ROOT))) { "Duplicate request header" }
                require(value.length <= 2048 && value.none { it.code < 32 && it != '\t' || it.code == 127 }) { "Invalid request header value" }
            }
        }

        fun origin(uri: URI): String {
            val port = if (uri.port == -1) (if (uri.scheme == "https") 443 else 80) else uri.port
            return "${uri.scheme}://${uri.host.lowercase(Locale.ROOT)}:$port"
        }

        fun normalize(url: String): String = if (url.startsWith('/')) URI("file", null, url, null).toASCIIString() else url

        fun fromMap(value: Map<*, *>): MediaEntry {
            fun string(key: String, default: String = ""): String =
                value[key]?.let { require(it is String) { "media.$key must be a string" }; it } ?: default
            val headers = value["headers"]?.let { raw ->
                require(raw is Map<*, *>) { "media.headers must be a table" }
                raw.entries.associate { (k, v) ->
                    require(k is String && v is String) { "media.headers entries must be text" }
                    k to v
                }
            }.orEmpty()
            val subtitles = value["subtitles"]?.let { raw ->
                require(raw is List<*>) { "media.subtitles must be an array" }
                raw.map { MediaSubtitle.fromMap(it as? Map<*, *> ?: throw IllegalArgumentException("Each subtitle must be a table")) }
            }.orEmpty()
            return MediaEntry(string("url"), string("title"), string("artist"), string("album"),
                string("art"), string("kind", "audio"), value["mime"]?.let {
                    require(it is String) { "media.mime must be a string" }; it
                }, MediaSourceType.parse(string("type", "auto")), headers, subtitles,
                publicOnly = if (value.containsKey("public_only")) value["public_only"].let {
                    require(it is Boolean) { "media.public_only must be a boolean" }; it
                } else false)
        }
    }
}
