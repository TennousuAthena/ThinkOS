package moe.kiwi.thinkos.media

import kotlin.random.Random

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
    /** Where the first item starts, in milliseconds; 0 means its default position (the live edge for live streams). */
    val start: Long = 0,
) {
    init {
        require(volume == null || volume.isFinite() && volume in 0f..1f) { "Media volume must be 0..1" }
        require(start >= 0) { "Media start position cannot be negative" }
    }
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

    /** Make the item at Lua position [index] (1 based, original order) current; the playback order is kept. */
    fun select(index: Int) {
        require(index in 1..count) { "Media index out of range" }
        playerIndex = order.indexOf(index - 1)
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
        /**
         * ExoPlayer prepares every item of a queue at once, and an HLS or DASH item fetches its manifest when prepared.
         * A list of hundreds of channels must be played one at a time, not queued.
         */
        const val MAX_ADAPTIVE = 16
        fun validate(items: List<MediaEntry>, options: MediaOptions) {
            require(items.isNotEmpty() && items.size <= MAX_ITEMS) { "Media queue must contain 1..$MAX_ITEMS items" }
            require(items.count { it.effectiveType == MediaSourceType.HLS || it.effectiveType == MediaSourceType.DASH } <= MAX_ADAPTIVE) {
                "A queue holds at most $MAX_ADAPTIVE HLS or DASH items; load a channel list one item at a time"
            }
            require(options.index in 1..items.size) { "Media index out of range" }
        }
    }
}
