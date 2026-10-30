package moe.kiwi.thinkos.media

import java.net.URI
import kotlin.random.Random

/** One playback item. Disk and network IO are deliberately absent from validation. */
data class MediaEntry(
    val url: String,
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val art: String = "",
    val kind: String = "audio",
    val mimeType: String? = null,
) {
    init {
        require(url.isNotBlank() && url.length <= 8192) { "Invalid media URL" }
        require(kind in setOf("audio", "video")) { "Media kind must be audio or video" }
        require(listOf(title, artist, album).all { it.length <= 2048 } && art.length <= 8192) { "Media metadata too large" }
        require(mimeType == null || mimeType.length <= 128 &&
            (mimeType.startsWith("audio/") || mimeType.startsWith("video/"))) { "Invalid media MIME type" }
        val uri = URI(normalizedUrl)
        require(uri.userInfo == null && uri.fragment == null) { "Media URL cannot contain credentials or a fragment" }
        when (uri.scheme) {
            "http", "https" -> require(!uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)) { "Invalid media HTTP URL" }
            "file" -> require(uri.host.isNullOrEmpty() || uri.host == "localhost") { "Remote file URLs are not supported" }
            "content" -> require(!uri.authority.isNullOrBlank()) { "Invalid content URL" }
            else -> throw IllegalArgumentException("Media URL must be HTTP(S), file, content or an absolute path")
        }
        if (uri.scheme in setOf("file", "content")) require(uri.path?.startsWith('/') == true) { "Invalid media file path" }
    }

    /** Convert an absolute local path without probing the filesystem on the UI thread. */
    val normalizedUrl: String get() = if (url.startsWith('/')) URI("file", null, url, null).toASCIIString() else url

    companion object {
        fun fromMap(value: Map<*, *>): MediaEntry {
            fun string(key: String, default: String = ""): String =
                value[key]?.let { require(it is String) { "media.$key must be a string" }; it } ?: default
            return MediaEntry(string("url"), string("title"), string("artist"), string("album"),
                string("art"), string("kind", "audio"), value["mime"]?.let {
                    require(it is String) { "media.mime must be a string" }; it
                })
        }
    }
}

enum class MediaRepeat(val id: String) {
    OFF("off"), ONE("one"), ALL("all");
    companion object {
        fun parse(value: String): MediaRepeat = entries.firstOrNull { it.id == value }
            ?: throw IllegalArgumentException("Media repeat must be off, one or all")
    }
}

data class MediaOptions(
    /** Lua-facing index, 1 based. */
    val index: Int = 1,
    val shuffle: Boolean = false,
    val repeat: MediaRepeat = MediaRepeat.OFF,
    val autoplay: Boolean = true,
    /** Null preserves the device's existing media gain. */
    val volume: Float? = null,
) {
    init { require(volume == null || volume.isFinite() && volume in 0f..1f) { "Media volume must be 0..1" } }
}

/**
 * OS-owned queue independent of Android and ExoPlayer. Public indices refer to the original queue;
 * the player receives [playlist] in playback order. This keeps shuffle deterministic for a session
 * and prevents repeated/skipped tracks when the current item is selected explicitly.
 */
class MediaQueue(private val random: Random = Random.Default) {
    var entries: List<MediaEntry> = emptyList()
        private set
    private var order: List<Int> = emptyList()
    var playerIndex: Int = 0
        private set
    var shuffle: Boolean = false
        private set
    var repeat: MediaRepeat = MediaRepeat.OFF
        private set
    val count: Int get() = entries.size
    val index: Int get() = order.getOrNull(playerIndex)?.plus(1) ?: 0
    val current: MediaEntry? get() = entries.getOrNull(index - 1)
    val playlist: List<MediaEntry> get() = order.map(entries::get)

    fun load(items: List<MediaEntry>, options: MediaOptions) {
        validate(items, options)
        // Copy only after every item and option has passed, preserving a live queue on failure.
        val copy = items.toList()
        val selected = options.index - 1
        val nextOrder = if (options.shuffle) listOf(selected) + copy.indices.filter { it != selected }.shuffled(random)
            else copy.indices.toList()
        entries = copy
        order = nextOrder
        playerIndex = if (options.shuffle) 0 else selected
        shuffle = options.shuffle
        repeat = options.repeat
    }

    fun clear() {
        entries = emptyList()
        order = emptyList()
        playerIndex = 0
        shuffle = false
        repeat = MediaRepeat.OFF
    }

    fun selectPlayerIndex(index: Int) {
        require(index in order.indices) { "Player index out of range" }
        playerIndex = index
    }

    /** Null means end of queue; explicit next bypasses repeat-one, automatic advance does not. */
    fun next(manual: Boolean = true): Int? {
        if (count == 0) return null
        if (!manual && repeat == MediaRepeat.ONE) return index
        val next = playerIndex + 1
        if (next >= count && repeat != MediaRepeat.ALL) return null
        playerIndex = next % count
        return index
    }

    fun previous(): Int? {
        if (count == 0) return null
        playerIndex = when {
            playerIndex > 0 -> playerIndex - 1
            repeat == MediaRepeat.ALL -> count - 1
            else -> 0
        }
        return index
    }

    /** Reorder around the current item; the host preserves its playback position when reloading. */
    fun setShuffle(enabled: Boolean) {
        if (shuffle == enabled) return
        val selected = index - 1
        order = if (enabled && count > 0) listOf(selected) + entries.indices.filter { it != selected }.shuffled(random)
            else entries.indices.toList()
        playerIndex = if (enabled || count == 0) 0 else selected
        shuffle = enabled
    }

    fun setRepeat(mode: MediaRepeat) { repeat = mode }

    companion object {
        /** Hard memory budget, not a user preference. */
        const val MAX_ITEMS = 512
        fun validate(items: List<MediaEntry>, options: MediaOptions) {
            require(items.isNotEmpty() && items.size <= MAX_ITEMS) { "Media queue must contain 1..$MAX_ITEMS items" }
            require(options.index in 1..items.size) { "Media index out of range" }
        }
    }
}
