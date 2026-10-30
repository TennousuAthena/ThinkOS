package moe.kiwi.thinkos.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import moe.kiwi.thinkos.net.MediaHttpClient
import okhttp3.OkHttpClient

/**
 * Process-level audio/video session. Every public method and callback runs on the main thread.
 * ExoPlayer performs decoding, data loading and embedded metadata extraction on its own workers;
 * this class never opens a media file or resolves a hostname on the UI thread.
 *
 * Permissions and plugin lifecycle belong to the caller. Validate all capability/file grants
 * before [load], then relay [Listener.onState] into the reserved `media` snapshot and dispatch
 * [Listener.onEvent] only to its named owner. UI controls may call these methods directly.
 */
class MediaEngine(context: Context, private val listener: Listener) {
    interface Listener {
        fun onState(state: Map<String, Any?>)
        fun onEvent(owner: String, name: String, payload: Map<String, Any?>)
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val queue = MediaQueue()
    private var player: ExoPlayer? = null
    private var client: OkHttpClient? = null
    private var owner: String? = null
    private var texture: TextureView? = null
    private var gain = 1f
    private var stopped = true
    private var endDelivered = false
    private var error: Map<String, Any?>? = null
    private var released = false
    private var changingQueue = false

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            publish()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (changingQueue || released) return
            if (queue.count > 0 && player.currentMediaItemIndex in 0 until queue.count) {
                queue.selectPlayerIndex(player.currentMediaItemIndex)
            }
            if (player.playbackState == Player.STATE_ENDED && queue.count > 0 && !stopped) deliverEnd()
            publish()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (changingQueue || released || queue.count == 0) return
            val index = this@MediaEngine.player?.currentMediaItemIndex ?: return
            if (index !in 0 until queue.count) return
            queue.selectPlayerIndex(index)
            endDelivered = false
            error = null
            event("media.track", mapOf("index" to queue.index.toLong(), "reason" to reason.toLong()))
        }

        override fun onPlayerError(failure: PlaybackException) {
            if (changingQueue || released || queue.count == 0) return
            error = mapOf("code" to failure.errorCode.toLong(), "name" to failure.errorCodeName,
                "message" to (failure.message ?: failure.errorCodeName), "index" to queue.index.toLong())
            event("media.error", error!!)
            publish()
        }

        override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
            if (!released && !changingQueue) publish()
        }
    }

    fun load(plugin: String, items: List<MediaEntry>, options: MediaOptions = MediaOptions()) {
        checkMain()
        require(plugin.isNotBlank()) { "Media owner is required" }
        MediaQueue.validate(items, options)
        // No mutation/ownership notification before all caller-provided values and MediaItems pass.
        val prepared = items.map(::mediaItem)
        val activePlayer = getPlayer()
        val oldOwner = owner
        queue.load(items, options)
        owner = plugin
        stopped = false
        endDelivered = false
        error = null
        if (oldOwner != null && oldOwner != plugin) {
            listener.onEvent(oldOwner, "media.session", mapOf("reason" to "replaced", "owner" to plugin))
        }
        options.volume?.let { gain = it }
        changingQueue = true
        try {
            val ordered = if (options.shuffle) queue.playlist.map(::mediaItem) else prepared
            activePlayer.setMediaItems(ordered, queue.playerIndex, 0)
            activePlayer.repeatMode = repeatMode(queue.repeat)
            activePlayer.shuffleModeEnabled = false // OS queue, rather than ExoPlayer, owns its permutation.
            activePlayer.volume = gain
            activePlayer.prepare()
            activePlayer.playWhenReady = options.autoplay
        } finally { changingQueue = false }
        publish()
    }

    fun play() {
        checkMain()
        val activePlayer = player ?: return
        if (queue.count == 0) return
        if (endDelivered || activePlayer.playbackState == Player.STATE_ENDED) {
            queue.selectPlayerIndex(0)
            activePlayer.seekTo(0, 0)
        }
        endDelivered = false
        error = null
        stopped = false
        if (activePlayer.playbackState == Player.STATE_IDLE) activePlayer.prepare()
        activePlayer.play()
        publish()
    }

    fun pause() {
        checkMain()
        player?.pause()
        publish()
    }

    fun toggle() {
        checkMain()
        if (player?.playWhenReady == true && !endDelivered) pause() else play()
    }

    fun next() {
        checkMain()
        if (queue.count == 0) return
        if (queue.next() == null) {
            player?.pause()
            deliverEnd()
            publish()
            return
        }
        seekItem()
    }

    fun prev() {
        checkMain()
        if (queue.previous() == null) return
        seekItem()
    }

    private fun seekItem() {
        val activePlayer = player ?: return
        endDelivered = false
        error = null
        stopped = false
        activePlayer.seekTo(queue.playerIndex, 0)
        if (activePlayer.playbackState == Player.STATE_IDLE) activePlayer.prepare()
        publish()
    }

    fun seek(positionMillis: Long) {
        checkMain()
        require(positionMillis >= 0) { "Media position cannot be negative" }
        val activePlayer = player ?: return
        if (queue.count == 0) return
        val duration = activePlayer.duration
        val target = if (duration != C.TIME_UNSET && duration >= 0) minOf(positionMillis, duration) else positionMillis
        endDelivered = false
        activePlayer.seekTo(target)
        publish()
    }

    fun volume(value: Float) {
        checkMain()
        require(value.isFinite() && value in 0f..1f) { "Media volume must be 0..1" }
        gain = value
        player?.volume = value
        publish()
    }

    fun setShuffle(enabled: Boolean) {
        checkMain()
        if (queue.shuffle == enabled) return
        val activePlayer = player
        val position = activePlayer?.currentPosition?.coerceAtLeast(0) ?: 0
        val play = activePlayer?.playWhenReady ?: false
        queue.setShuffle(enabled)
        if (activePlayer != null && queue.count > 0) {
            changingQueue = true
            try {
                activePlayer.setMediaItems(queue.playlist.map(::mediaItem), queue.playerIndex, position)
                activePlayer.prepare()
                activePlayer.playWhenReady = play
            } finally { changingQueue = false }
        }
        publish()
    }

    fun setRepeat(mode: MediaRepeat) {
        checkMain()
        queue.setRepeat(mode)
        player?.repeatMode = repeatMode(mode)
        publish()
    }

    /** Explicit stop clears the queue and ownership; a later unrelated plugin unload cannot stop a new owner. */
    fun stop() {
        checkMain()
        val previous = owner
        owner = null
        queue.clear()
        stopped = true
        endDelivered = false
        error = null
        changingQueue = true
        try { player?.stop(); player?.clearMediaItems() } finally { changingQueue = false }
        previous?.let { listener.onEvent(it, "media.session", mapOf("reason" to "stopped", "owner" to "")) }
        publish()
    }

    fun releaseOwner(plugin: String) {
        checkMain()
        if (owner == plugin) stop()
    }

    fun attachVideo(view: TextureView) {
        checkMain()
        texture = view
        player?.setVideoTextureView(view)
    }

    /** Detaching a stale page must not clear the surface attached by a newly displayed page. */
    fun detachVideo(view: TextureView) {
        if (released) return
        checkMain()
        if (texture !== view) return
        player?.clearVideoTextureView(view)
        texture = null
    }

    fun snapshot(): Map<String, Any?> {
        checkMain()
        val activePlayer = player
        val entry = queue.current
        val metadata = if (entry != null) activePlayer?.mediaMetadata else null
        val videoSize = if (entry != null) activePlayer?.videoSize else null
        val duration = if (entry != null) activePlayer?.duration?.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0L else 0L
        val position = if (entry == null) 0L else (activePlayer?.currentPosition ?: 0L).coerceAtLeast(0)
        val status = when {
            stopped || entry == null -> "stopped"
            error != null -> "error"
            endDelivered || activePlayer?.playbackState == Player.STATE_ENDED -> "ended"
            activePlayer?.playbackState == Player.STATE_BUFFERING -> "buffering"
            activePlayer?.isPlaying == true -> "playing"
            else -> "paused"
        }
        return mapOf("status" to status, "owner" to owner.orEmpty(), "kind" to entry?.kind.orEmpty(),
            "index" to queue.index.toLong(), "count" to queue.count.toLong(),
            "title" to (metadata?.title?.toString()?.takeIf(String::isNotBlank) ?: entry?.title.orEmpty()),
            "artist" to (metadata?.artist?.toString()?.takeIf(String::isNotBlank) ?: entry?.artist.orEmpty()),
            "album" to (metadata?.albumTitle?.toString()?.takeIf(String::isNotBlank) ?: entry?.album.orEmpty()),
            "art" to (metadata?.artworkUri?.toString()?.takeIf(String::isNotBlank) ?: entry?.art.orEmpty()),
            "position" to position, "duration" to duration,
            "progress" to if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0,
            "volume" to gain.toDouble(), "shuffle" to queue.shuffle, "repeat" to queue.repeat.id,
            "error" to error, "videoWidth" to (videoSize?.width ?: 0).toLong(),
            "videoHeight" to (videoSize?.height ?: 0).toLong(),
            "pixelRatio" to (videoSize?.pixelWidthHeightRatio ?: 1f).toDouble())
    }

    fun release() {
        checkMain()
        stop()
        released = true
        handler.removeCallbacks(tick)
        player?.removeListener(playerListener)
        player?.release()
        player = null
        texture = null
        client?.dispatcher?.cancelAll()
        client?.connectionPool?.evictAll()
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun getPlayer(): ExoPlayer {
        player?.let { return it }
        val http = MediaHttpClient.create().also { client = it }
        val data = DefaultDataSource.Factory(appContext, OkHttpDataSource.Factory(http).setUserAgent("ThinkOS/1"))
        val created = ExoPlayer.Builder(appContext)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(DefaultMediaSourceFactory(data))
            .build()
        created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        created.setHandleAudioBecomingNoisy(true)
        created.addListener(playerListener)
        texture?.let(created::setVideoTextureView)
        player = created
        return created
    }

    private fun mediaItem(entry: MediaEntry): MediaItem {
        val metadata = MediaMetadata.Builder().setTitle(entry.title).setArtist(entry.artist).setAlbumTitle(entry.album)
        if (entry.art.isNotBlank()) metadata.setArtworkUri(Uri.parse(entry.art))
        return MediaItem.Builder().setUri(entry.normalizedUrl).setMediaMetadata(metadata.build())
            .apply { entry.mimeType?.let(::setMimeType) }.build()
    }

    private fun deliverEnd() {
        if (endDelivered || owner == null) return
        endDelivered = true
        event("queue.end", mapOf("index" to queue.index.toLong(), "count" to queue.count.toLong()))
    }

    private fun event(name: String, payload: Map<String, Any?>) {
        owner?.let { listener.onEvent(it, name, payload) }
    }

    private fun publish() {
        if (released) return
        handler.removeCallbacks(tick)
        listener.onState(snapshot())
        if (queue.count > 0 && !stopped && error == null && !endDelivered &&
            (player?.isPlaying == true || player?.playbackState == Player.STATE_BUFFERING)) {
            handler.postDelayed(tick, 1000)
        }
    }

    private fun repeatMode(mode: MediaRepeat): Int = when (mode) {
        MediaRepeat.OFF -> Player.REPEAT_MODE_OFF
        MediaRepeat.ONE -> Player.REPEAT_MODE_ONE
        MediaRepeat.ALL -> Player.REPEAT_MODE_ALL
    }

    private fun checkMain() {
        check(Looper.myLooper() == Looper.getMainLooper()) { "MediaEngine must be used on the main thread" }
        check(!released) { "MediaEngine is released" }
    }
}
