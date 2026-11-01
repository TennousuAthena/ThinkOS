package moe.kiwi.thinkos.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.TextureView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import moe.kiwi.thinkos.net.MediaHttpClient
import okhttp3.OkHttpClient
import java.net.URI

/**
 * Process-level audio/video session. Every public method and callback runs on the main thread.
 * ExoPlayer performs decoding, data loading and embedded metadata extraction on its own workers;
 * this class never opens a media file or resolves a hostname on the UI thread.
 *
 * Permissions and plugin lifecycle belong to the caller. Validate all capability/file grants
 * before [load], then relay [Listener.onState] into the reserved `media` snapshot and dispatch
 * [Listener.onEvent] only to its named owner. UI controls may call these methods directly.
 *
 * Streaming: each item is read as progressive media, HLS, DASH or RTSP (see [MediaSourceType]).
 * Transient network failures are retried with backoff while the status reads `reconnecting`;
 * everything else ends in a categorised `error` so the UI never shows a silent blank screen.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MediaEngine(
    context: Context,
    private val listener: Listener,
    private val policy: () -> MediaPolicy = { MediaPolicy() },
) {
    interface Listener {
        fun onState(state: Map<String, Any?>)
        fun onEvent(owner: String, name: String, payload: Map<String, Any?>)
    }

    private class TrackRef(val group: Tracks.Group, val index: Int)
    private data class SourceKey(val rtsp: Boolean, val progressive: Boolean, val origin: String?, val headers: Map<String, String>)

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val queue = MediaQueue()
    private val sources = Sources()
    private var player: ExoPlayer? = null
    private var builtWith: Triple<Int, Int, Boolean>? = null
    /**
     * Real-time sources (screen mirroring) trade smoothness for delay: no read-ahead, the first frame starts playback, and
     * the audio output keeps only a few tens of milliseconds. Applies from the next [load], which rebuilds the player.
     */
    var lowLatency = false
    /** A queue holding a [MirrorSource] always plays in the low-latency profile, whatever [lowLatency] says. */
    private var mirrorQueue = false
    private var client: OkHttpClient? = null
    private var owner: String? = null
    private var texture: TextureView? = null
    private var gain = 1f
    private var stopped = true
    private var endDelivered = false
    private var error: Map<String, Any?>? = null
    private var warning: String? = null
    private var released = false
    private var changingQueue = false
    private var droppedFrames = 0L
    private var videoDecoder = ""
    private var audioDecoder = ""
    private var stallPosition = -1L
    private var stallSince = 0L
    private var retries = 0
    private var liveRecoveries = 0
    private var retryPending = false
    private var checkedItem = -1
    private var audioRefs = emptyList<TrackRef>()
    private var textRefs = emptyList<TrackRef>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /** Receives the text of the cues to draw for the current position; the video component shows them. */
    var cueSink: ((List<CharSequence>) -> Unit)? = null

    private val pace = MirrorPace()
    private val pacer = object : Runnable {
        override fun run() {
            val active = player
            if (released || !mirrorQueue || stopped || active == null) { pace.reset(); return }
            if (active.playbackState == Player.STATE_READY && active.playWhenReady) {
                val before = pace.speed
                val speed = pace.update((active.bufferedPosition - active.currentPosition).coerceAtLeast(0))
                if (speed != active.playbackParameters.speed) active.playbackParameters = PlaybackParameters(speed)
                if ((speed > 1f) != (before > 1f)) android.util.Log.i("ThinkOS", "[media] mirror ${if (speed > 1f) "catching up, ${active.bufferedPosition - active.currentPosition} ms behind the live edge" else "is live again"}")
            }
            handler.postDelayed(this, PACE_MILLIS)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (released) return
            publish()
        }
    }

    /** Which decoders are in use and how many frames were lost: the numbers that tell hardware from software playback. */
    private val analytics = object : AnalyticsListener {
        override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) {
            this@MediaEngine.droppedFrames += droppedFrames
            android.util.Log.i("ThinkOS", "[media] dropped $droppedFrames frames in $elapsedMs ms at ${eventTime.currentPlaybackPositionMs} ms")
        }
        override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
            // Only a stall in the middle of playback is news; the first buffering after a load is not.
            if (state == Player.STATE_BUFFERING && eventTime.currentPlaybackPositionMs > 0) {
                android.util.Log.i("ThinkOS", "[media] buffering at ${eventTime.currentPlaybackPositionMs} ms, buffer ahead ${eventTime.totalBufferedDurationMs} ms")
            }
        }
        override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            videoDecoder = decoderName
        }
        override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
            audioDecoder = decoderName
        }
    }

    private val retryRun = Runnable {
        retryPending = false
        val active = player
        if (!released && active != null && queue.count > 0 && error == null && !stopped) {
            if (active.playbackState == Player.STATE_ENDED) active.seekToDefaultPosition()
            active.prepare()
            publish()
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (changingQueue || released) return
            if (queue.count > 0 && player.currentMediaItemIndex in 0 until queue.count) {
                queue.selectPlayerIndex(player.currentMediaItemIndex)
            }
            val state = player.playbackState
            if (state == Player.STATE_READY) { retries = 0; liveRecoveries = 0; retryPending = false }
            if ((state == Player.STATE_READY || state == Player.STATE_ENDED) && error == null &&
                checkedItem != player.currentMediaItemIndex) checkTracks(player)
            if (state == Player.STATE_ENDED && queue.count > 0 && !stopped && error == null) {
                // A stream the other side hung up is called again; a finished programme (or an RTSP recording) ends.
                if (liveClosed(player) && !retryPending && retries < MAX_RETRIES) reconnectLive()
                else if (!retryPending) deliverEnd()
            }
            publish()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            if (changingQueue || released || queue.count == 0) return
            val index = this@MediaEngine.player?.currentMediaItemIndex ?: return
            if (index !in 0 until queue.count) return
            queue.selectPlayerIndex(index)
            endDelivered = false
            error = null
            warning = null
            retries = 0
            checkedItem = -1
            event("media.track", mapOf("index" to queue.index.toLong(), "reason" to reason.toLong()))
        }

        override fun onTracksChanged(tracks: Tracks) {
            audioRefs = collect(tracks, C.TRACK_TYPE_AUDIO)
            textRefs = collect(tracks, C.TRACK_TYPE_TEXT)
        }

        override fun onCues(cueGroup: CueGroup) {
            cueSink?.invoke(cueGroup.cues.mapNotNull { it.text })
        }

        override fun onPlayerError(failure: PlaybackException) {
            if (changingQueue || released || queue.count == 0) return
            val active = player ?: return
            if (failure.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && liveRecoveries++ < MAX_LIVE_RECOVERIES) {
                // The playhead fell out of the live window: rejoin at the live edge.
                active.seekToDefaultPosition()
                active.prepare()
                publish()
                return
            }
            if (retries < MAX_RETRIES && transient(failure, queue.current)) {
                retries++
                android.util.Log.i("ThinkOS", "[media] retry $retries in ${backoff(retries)} ms after ${failure.errorCodeName}: " +
                    generateSequence<Throwable>(failure) { it.cause }.take(5).joinToString(" <- ") { redact("${it.javaClass.simpleName}: ${it.message}") })
                retryPending = true
                handler.removeCallbacks(retryRun)
                handler.postDelayed(retryRun, backoff(retries))
                publish()
                return
            }
            retries = 0
            fail(describe(failure))
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
        mirrorQueue = items.any { it.mirror != null }
        val activePlayer = getPlayer()
        val oldOwner = owner
        queue.load(items, options)
        owner = plugin
        stopped = false
        endDelivered = false
        error = null
        warning = null
        clearRetry()
        checkedItem = -1
        droppedFrames = 0
        videoDecoder = ""
        audioDecoder = ""
        sources.clear()
        if (oldOwner != null && oldOwner != plugin) {
            listener.onEvent(oldOwner, "media.session", mapOf("reason" to "replaced", "owner" to plugin))
        }
        options.volume?.let { gain = it }
        changingQueue = true
        try {
            val ordered = if (options.shuffle) queue.playlist.map(::mediaItem) else prepared
            val start = if (options.start > 0) options.start else C.TIME_UNSET
            activePlayer.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(if (queue.current?.kind == "video") C.AUDIO_CONTENT_TYPE_MOVIE else C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            activePlayer.setMediaItems(ordered, queue.playerIndex, start)
            activePlayer.repeatMode = repeatMode(queue.repeat)
            activePlayer.shuffleModeEnabled = false // OS queue, rather than ExoPlayer, owns its permutation.
            activePlayer.volume = gain
            activePlayer.setPlaybackParameters(PlaybackParameters.DEFAULT)
            pace.reset()
            activePlayer.prepare()
            activePlayer.playWhenReady = options.autoplay
        } finally { changingQueue = false }
        handler.removeCallbacks(pacer)
        if (mirrorQueue) handler.postDelayed(pacer, PACE_MILLIS)
        watchNetwork()
        publish()
    }

    fun play() {
        checkMain()
        val activePlayer = player ?: return
        if (queue.count == 0) return
        if (endDelivered || activePlayer.playbackState == Player.STATE_ENDED) {
            queue.selectPlayerIndex(0)
            activePlayer.seekToDefaultPosition(0)
        }
        endDelivered = false
        error = null
        warning = null
        stopped = false
        checkedItem = -1
        clearRetry()
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
        if (player?.playWhenReady == true && !endDelivered && error == null) pause() else play()
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

    /** Play queue item [index] (1 based, as Lua numbers them). */
    fun jump(index: Int) {
        checkMain()
        if (queue.count == 0) return
        queue.select(index)
        seekItem()
    }

    private fun seekItem() {
        val activePlayer = player ?: return
        endDelivered = false
        error = null
        warning = null
        stopped = false
        retries = 0
        checkedItem = -1
        clearRetry()
        activePlayer.seekToDefaultPosition(queue.playerIndex)
        if (activePlayer.playbackState == Player.STATE_IDLE) activePlayer.prepare()
        activePlayer.play()
        publish()
    }

    fun seek(positionMillis: Long) {
        checkMain()
        require(positionMillis >= 0) { "Media position cannot be negative" }
        val activePlayer = player ?: return
        if (queue.count == 0 || !activePlayer.isCurrentMediaItemSeekable) return
        val duration = activePlayer.duration
        val target = if (duration != C.TIME_UNSET && duration >= 0) minOf(positionMillis, duration) else positionMillis
        endDelivered = false
        activePlayer.seekTo(target)
        publish()
    }

    /** Rejoin the live edge of a live stream. */
    fun goLive() {
        checkMain()
        val activePlayer = player ?: return
        if (queue.count == 0 || !activePlayer.isCurrentMediaItemLive) return
        activePlayer.seekToDefaultPosition()
        activePlayer.play()
        publish()
    }

    fun volume(value: Float) {
        checkMain()
        require(value.isFinite() && value in 0f..1f) { "Media volume must be 0..1" }
        gain = value
        player?.volume = value
        publish()
    }

    /** Select the audio track [index] (1 based, as listed in the `audioTracks` state). */
    fun selectAudio(index: Int) {
        checkMain()
        val ref = audioRefs.getOrNull(index - 1) ?: return
        val active = player ?: return
        active.trackSelectionParameters = active.trackSelectionParameters.buildUpon()
            .setOverrideForType(TrackSelectionOverride(ref.group.mediaTrackGroup, ref.index)).build()
        publish()
    }

    /** Select the subtitle track [index] (1 based); 0 turns subtitles off. */
    fun selectText(index: Int) {
        checkMain()
        val active = player ?: return
        val builder = active.trackSelectionParameters.buildUpon()
        if (index <= 0) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            cueSink?.invoke(emptyList())
        } else {
            val ref = textRefs.getOrNull(index - 1) ?: return
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(ref.group.mediaTrackGroup, ref.index))
        }
        active.trackSelectionParameters = builder.build()
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
                sources.clear()
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
        warning = null
        clearRetry()
        audioRefs = emptyList()
        textRefs = emptyList()
        changingQueue = true
        try { player?.stop(); player?.clearMediaItems() } finally { changingQueue = false }
        cueSink?.invoke(emptyList())
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

    /** Current playhead, read per frame by the seek bar; the published `position` only moves once a second. */
    fun positionNow(): Long {
        checkMain()
        return (player?.currentPosition ?: 0L).coerceAtLeast(0)
    }

    fun bufferedNow(): Long {
        checkMain()
        return (player?.bufferedPosition ?: 0L).coerceAtLeast(0)
    }

    fun snapshot(): Map<String, Any?> {
        checkMain()
        val activePlayer = player
        val entry = queue.current
        val metadata = if (entry != null) activePlayer?.mediaMetadata else null
        val videoSize = if (entry != null) activePlayer?.videoSize else null
        val duration = if (entry != null) activePlayer?.duration?.takeIf { it != C.TIME_UNSET && it >= 0 } ?: 0L else 0L
        val position = if (entry == null) 0L else (activePlayer?.currentPosition ?: 0L).coerceAtLeast(0)
        val live = entry != null && activePlayer?.isCurrentMediaItemLive == true
        val status = when {
            stopped || entry == null -> "stopped"
            error != null -> "error"
            retryPending -> "reconnecting"
            endDelivered || activePlayer?.playbackState == Player.STATE_ENDED -> "ended"
            activePlayer?.playbackState == Player.STATE_BUFFERING -> "buffering"
            activePlayer?.isPlaying == true -> "playing"
            else -> "paused"
        }
        val video = if (entry != null) activePlayer?.videoFormat else null
        val audio = if (entry != null) activePlayer?.audioFormat else null
        return mapOf("status" to status, "owner" to owner.orEmpty(), "kind" to entry?.kind.orEmpty(),
            "index" to queue.index.toLong(), "count" to queue.count.toLong(),
            "title" to clip(metadata?.title?.toString()?.takeIf(String::isNotBlank) ?: entry?.title.orEmpty(), TEXT_LIMIT),
            "artist" to clip(metadata?.artist?.toString()?.takeIf(String::isNotBlank) ?: entry?.artist.orEmpty(), TEXT_LIMIT),
            "album" to clip(metadata?.albumTitle?.toString()?.takeIf(String::isNotBlank) ?: entry?.album.orEmpty(), TEXT_LIMIT),
            "art" to clip(metadata?.artworkUri?.toString()?.takeIf(String::isNotBlank) ?: entry?.art.orEmpty(), 2048),
            "position" to position, "duration" to duration,
            "progress" to if (duration > 0) (position.toDouble() / duration).coerceIn(0.0, 1.0) else 0.0,
            "volume" to gain.toDouble(), "shuffle" to queue.shuffle, "repeat" to queue.repeat.id,
            "error" to error, "warning" to warning.orEmpty(), "retry" to retries.toLong(),
            "videoWidth" to (videoSize?.width ?: 0).toLong(),
            "videoHeight" to (videoSize?.height ?: 0).toLong(),
            "pixelRatio" to (videoSize?.pixelWidthHeightRatio ?: 1f).toDouble(),
            "source" to (entry?.effectiveType?.id ?: if (entry == null) "" else "progressive"),
            "live" to live, "mirror" to (entry?.mirror != null), "seekable" to (entry != null && activePlayer?.isCurrentMediaItemSeekable == true),
            "buffered" to if (entry == null) 0L else (activePlayer?.bufferedPosition ?: 0L).coerceAtLeast(0),
            "liveOffset" to if (live) (activePlayer?.currentLiveOffset?.takeIf { it != C.TIME_UNSET } ?: 0L) else 0L,
            "videoCodec" to codecName(video?.sampleMimeType), "audioCodec" to codecName(audio?.sampleMimeType),
            "frameRate" to (video?.frameRate?.takeIf { it > 0f } ?: 0f).toDouble(),
            "bitrate" to ((video?.averageBitrate?.takeIf { it > 0 } ?: video?.bitrate?.takeIf { it > 0 } ?: 0) +
                (audio?.averageBitrate?.takeIf { it > 0 } ?: audio?.bitrate?.takeIf { it > 0 } ?: 0)).toLong(),
            "sampleRate" to (audio?.sampleRate?.takeIf { it > 0 } ?: 0).toLong(),
            "channels" to (audio?.channelCount?.takeIf { it > 0 } ?: 0).toLong(),
            "bitDepth" to bitDepth(audio?.pcmEncoding).toLong(),
            "videoDecoder" to videoDecoder, "audioDecoder" to audioDecoder, "droppedFrames" to droppedFrames,
            "audioTracks" to describeTracks(audioRefs), "textTracks" to describeTracks(textRefs))
    }

    fun release() {
        checkMain()
        stop()
        released = true
        handler.removeCallbacks(tick)
        handler.removeCallbacks(pacer)
        handler.removeCallbacks(retryRun)
        runCatching { networkCallback?.let { appContext.getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        networkCallback = null
        releasePlayer()
        texture = null
        client?.dispatcher?.cancelAll()
        client?.connectionPool?.evictAll()
        client?.dispatcher?.executorService?.shutdown()
        client = null
    }

    private fun releasePlayer() {
        player?.removeListener(playerListener)
        player?.release()
        player = null
        builtWith = null
    }

    private fun getPlayer(): ExoPlayer {
        val budget = policy()
        val live = lowLatency || mirrorQueue
        val key = Triple(budget.bufferMillis, budget.bufferBytes, live)
        player?.let { if (builtWith == key) return it else releasePlayer() }
        // Software decoders come second: the platform's hardware decoders win whenever they can play the stream.
        val renderers = (if (!live) DefaultRenderersFactory(appContext) else object : DefaultRenderersFactory(appContext) {
            // The audio clock paces the picture, so an audio buffer of a few hundred milliseconds is a few hundred milliseconds of delay.
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioTrackBufferSizeProvider(DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(20_000).setMaxPcmBufferDurationUs(100_000).setPcmBufferMultiplicationFactor(2).build())
                    .build()
        })
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val selector = DefaultTrackSelector(appContext)
        selector.setParameters(selector.buildUponParameters().setMaxVideoSize(budget.maxVideoWidth, budget.maxVideoHeight))
        val loadControl = (if (live) DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_000, 2_000, 0, 100)
                .setBackBuffer(0, false)
                .setPrioritizeTimeOverSizeThresholds(true)
            else DefaultLoadControl.Builder()
                .setBufferDurationsMs(minOf(15_000, budget.bufferMillis), budget.bufferMillis, 1_500, 3_000)
                .setPrioritizeTimeOverSizeThresholds(false))
            .setTargetBufferBytes(budget.bufferBytes)
            .build()
        val created = ExoPlayer.Builder(appContext, renderers)
            .setLooper(Looper.getMainLooper())
            .setMediaSourceFactory(sources)
            .setTrackSelector(selector)
            .setLoadControl(loadControl)
            .setHandleAudioBecomingNoisy(true)
            .build()
        created.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        created.addListener(playerListener)
        created.addAnalyticsListener(analytics)
        texture?.let(created::setVideoTextureView)
        player = created
        builtWith = key
        return created
    }

    private fun httpClient(): OkHttpClient = client ?: MediaHttpClient.create(allowPublicCleartext = { policy().publicCleartext }).also { client = it }

    /** Network data for one item. Credentials in [MediaEntry.headers] never leave the item's own origin. */
    private fun dataSource(entry: MediaEntry): DataSource.Factory {
        val userAgent = entry.headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value ?: USER_AGENT
        val extra = entry.headers.filterKeys { !it.equals("user-agent", true) }
        val http: DataSource.Factory = OkHttpDataSource.Factory(httpClient()).setUserAgent(userAgent)
        val origin = entry.origin
        val upstream = if (extra.isEmpty()) http else ResolvingDataSource.Factory(http) { spec ->
            val sameOrigin = spec.uri.scheme in setOf("http", "https") && origin != null &&
                runCatching { MediaEntry.origin(URI(spec.uri.toString())) == origin }.getOrDefault(false)
            val allowed = extra.filter { sameOrigin || it.key.lowercase() in MediaEntry.PORTABLE_HEADERS }
            if (allowed.isEmpty()) spec else spec.buildUpon().setHttpRequestHeaders(spec.httpRequestHeaders + allowed).build()
        }
        return AuthorizedMediaDataSourceFactory(appContext, upstream)
    }

    /** Builds the right [MediaSource] for each item, so one queue can mix local files, HLS, DASH and RTSP. */
    private inner class Sources : MediaSource.Factory {
        private val cache = HashMap<SourceKey, MediaSource.Factory>()
        private val extractors = DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)
        // A mirror is MPEG-TS from a phone or PC encoder: access units are found without relying on AUD NALs, and a stream that
        // recovers by gradual refresh instead of IDR frames still starts.
        private val mirrorExtractors = DefaultExtractorsFactory().setTsExtractorFlags(
            DefaultTsPayloadReaderFactory.FLAG_DETECT_ACCESS_UNITS or DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES)

        override fun setDrmSessionManagerProvider(drmSessionManagerProvider: DrmSessionManagerProvider) = this
        override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: LoadErrorHandlingPolicy) = this
        override fun getSupportedTypes() = intArrayOf(C.CONTENT_TYPE_DASH, C.CONTENT_TYPE_HLS, C.CONTENT_TYPE_OTHER, C.CONTENT_TYPE_RTSP)

        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val entry = mediaItem.localConfiguration?.tag as? MediaEntry ?: throw IllegalStateException("Media item has no entry")
            entry.mirror?.let { return ProgressiveMediaSource.Factory(MirrorDataSourceFactory(it), mirrorExtractors).createMediaSource(mediaItem) }
            val rtsp = entry.effectiveType == MediaSourceType.RTSP
            val progressive = entry.type == MediaSourceType.PROGRESSIVE
            val key = SourceKey(rtsp, progressive, entry.origin, entry.headers)
            val factory = cache.getOrPut(key) {
                when {
                    rtsp -> RtspMediaSource.Factory()
                        .setUserAgent(USER_AGENT)
                        // Interleaved TCP keeps every RTSP byte on the one socket the private-address policy checked.
                        .setForceUseRtpTcp(true)
                        .setTimeoutMs(RTSP_TIMEOUT_MILLIS)
                        .setSocketFactory(PrivateSocketFactory { policy().publicCleartext })
                    progressive -> ProgressiveMediaSource.Factory(dataSource(entry), extractors)
                    else -> DefaultMediaSourceFactory(dataSource(entry), extractors)
                }
            }
            return factory.createMediaSource(mediaItem)
        }

        fun clear() = cache.clear()
    }

    private fun mediaItem(entry: MediaEntry): MediaItem {
        val metadata = MediaMetadata.Builder().setTitle(entry.title).setArtist(entry.artist).setAlbumTitle(entry.album)
        if (entry.art.isNotBlank()) metadata.setArtworkUri(Uri.parse(entry.art))
        val builder = MediaItem.Builder().setUri(entry.normalizedUrl).setMediaMetadata(metadata.build()).setTag(entry)
        (entry.mimeType ?: if (entry.mirror != null) MimeTypes.VIDEO_MP2T else when (entry.effectiveType) {
            MediaSourceType.HLS -> MimeTypes.APPLICATION_M3U8
            MediaSourceType.DASH -> MimeTypes.APPLICATION_MPD
            MediaSourceType.RTSP -> MimeTypes.APPLICATION_RTSP
            else -> null
        })?.let(builder::setMimeType)
        if (entry.subtitles.isNotEmpty()) {
            builder.setSubtitleConfigurations(entry.subtitles.map {
                MediaItem.SubtitleConfiguration.Builder(Uri.parse(it.normalizedUrl)).setMimeType(it.mimeType)
                    .setLanguage(it.language).setLabel(it.label).setSelectionFlags(0).build()
            })
        }
        return builder.build()
    }

    // --- failures ---------------------------------------------------------------------------------------------

    private fun fail(details: Map<String, Any?>) {
        error = details + ("index" to queue.index.toLong())
        clearRetry()
        player?.pause()
        event("media.error", error!!)
        publish()
    }

    /**
     * The tracks the player actually selected decide whether there is something to watch or hear.
     * Without this an unsupported codec just plays on as a black screen or in silence.
     */
    private fun checkTracks(active: Player) {
        val tracks = active.currentTracks
        val video = tracks.groups.filter { it.type == C.TRACK_TYPE_VIDEO }
        val audio = tracks.groups.filter { it.type == C.TRACK_TYPE_AUDIO }
        if (video.isEmpty() && audio.isEmpty()) return
        checkedItem = active.currentMediaItemIndex
        val videoOk = video.isEmpty() || video.any { it.isSupported(false) }
        val audioOk = audio.isEmpty() || audio.any { it.isSupported(false) }
        fun mime(groups: List<Tracks.Group>) = groups.firstOrNull()?.getTrackFormat(0)?.sampleMimeType.orEmpty()
        when {
            !videoOk -> fail(failure(6001, "UNSUPPORTED_VIDEO", "format", "This device cannot decode ${codecName(mime(video))} video", mime(video)))
            !audioOk && video.isEmpty() -> fail(failure(6002, "UNSUPPORTED_AUDIO", "format", "This device cannot decode ${codecName(mime(audio))} audio", mime(audio)))
            !audioOk -> {
                warning = "audio-unsupported:${mime(audio)}"
                event("media.warning", mapOf("code" to 6002L, "name" to "UNSUPPORTED_AUDIO", "codec" to mime(audio)))
            }
        }
    }

    private fun failure(code: Int, name: String, category: String, message: String, codec: String = ""): Map<String, Any?> =
        mapOf("code" to code.toLong(), "name" to name, "category" to category, "message" to message, "codec" to codec)

    private fun describe(failure: PlaybackException): Map<String, Any?> {
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        val status = responseStatus(causes)
        val blocked = causes.any { policyBlocked(it) }
        val category = when {
            blocked -> "policy"
            failure.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "notfound"
            failure.errorCode == PlaybackException.ERROR_CODE_IO_NO_PERMISSION ||
                failure.errorCode == PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED -> "permission"
            status != null -> "http"
            failure.errorCode in 2000..2999 -> "network"
            failure.errorCode in 3000..3999 -> "format"
            failure.errorCode in 4000..4999 -> "decoder"
            failure.errorCode in 5000..5999 -> "audio"
            else -> "other"
        }
        val codec = (failure as? ExoPlaybackException)?.rendererFormat?.sampleMimeType.orEmpty()
        val details = linkedMapOf<String, Any?>("code" to failure.errorCode.toLong(), "name" to failure.errorCodeName,
            "category" to category, "message" to redact(failure.message ?: failure.errorCodeName), "codec" to codec)
        if (status != null) details["status"] = status.toLong()
        return details
    }

    private fun policyBlocked(error: Throwable) =
        error.message?.let { it.contains("restricted to local/private") || it.contains("HTTPS downgrade") || it.contains("Cleartext") } == true

    /** A certificate or handshake the device will not accept does not improve with a second try. */
    private fun tlsRefused(error: Throwable) = error is javax.net.ssl.SSLHandshakeException || error is javax.net.ssl.SSLPeerUnverifiedException ||
        error is java.security.cert.CertificateException

    /** A redirect loop or an unusable `Location` is the server's answer, not a flaky link: retrying cannot help. */
    private fun badRedirect(error: Throwable) =
        error.message?.let { it.contains("Too many redirects") || it.contains("Redirect missing Location") || it.contains("Invalid redirect URL") } == true

    private fun transient(failure: PlaybackException, entry: MediaEntry?): Boolean {
        if (entry == null || entry.scheme !in setOf("http", "https", "rtsp")) return false
        val causes = generateSequence<Throwable>(failure) { it.cause }.take(8).toList()
        if (causes.any { policyBlocked(it) || badRedirect(it) || tlsRefused(it) }) return false
        val status = responseStatus(causes)
        fun retryable(code: Int) = code in setOf(408, 429) || code >= 500
        return when (failure.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT -> true
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> status != null && retryable(status)
            // RTSP reports "DESCRIBE 401" as a plain IO error: a refusal is final, a server fault is worth another try.
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED ->
                if (status != null) retryable(status) else causes.any { it is java.io.IOException && it !is java.io.FileNotFoundException }
            else -> false
        }
    }

    /** The server's own answer: an HTTP status, or the status of a failed RTSP request ("DESCRIBE 401"). */
    private fun responseStatus(causes: List<Throwable>): Int? = causes.firstNotNullOfOrNull { error ->
        (error as? HttpDataSource.InvalidResponseCodeException)?.responseCode
            ?: if (error.javaClass.simpleName == "RtspPlaybackException") RTSP_STATUS.find(error.message.orEmpty())?.groupValues?.get(1)?.toIntOrNull() else null
    }

    /** A camera or radio that hangs up ends the stream; unlike a finished programme it should be called again. */
    private fun liveClosed(active: Player): Boolean {
        val entry = queue.current ?: return false
        return entry.scheme in setOf("http", "https", "rtsp") && active.isCurrentMediaItemLive
    }

    private fun reconnectLive() {
        retries++
        retryPending = true
        handler.removeCallbacks(retryRun)
        handler.postDelayed(retryRun, backoff(retries))
    }

    private fun backoff(attempt: Int): Long = minOf(1_000L shl (attempt - 1), 15_000L)

    private fun clearRetry() {
        stallPosition = -1
        retries = 0
        retryPending = false
        handler.removeCallbacks(retryRun)
    }

    /** Retry at once when the network comes back instead of waiting out the backoff. */
    private fun watchNetwork() {
        if (networkCallback != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (released || !retryPending) return
                handler.removeCallbacks(retryRun)
                handler.post(retryRun)
            }
        }
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback, handler)
            networkCallback = callback
        }
    }

    // --- helpers ----------------------------------------------------------------------------------------------

    private fun collect(tracks: Tracks, type: Int): List<TrackRef> = tracks.groups.filter { it.type == type }.flatMap { group ->
        (0 until group.length).filter { group.isTrackSupported(it, false) }.map { TrackRef(group, it) }
    }

    private fun clip(text: String, limit: Int): String = if (text.length <= limit) text else text.substring(0, limit)

    private fun describeTracks(refs: List<TrackRef>): List<Map<String, Any?>> = refs.take(MAX_TRACKS).mapIndexed { position, ref ->
        val format = ref.group.getTrackFormat(ref.index)
        mapOf("index" to (position + 1).toLong(), "language" to clip(format.language.orEmpty(), 35),
            "label" to clip((format.label ?: format.language).orEmpty(), 64), "codec" to codecName(format.sampleMimeType),
            "channels" to format.channelCount.coerceAtLeast(0).toLong(), "selected" to ref.group.isTrackSelected(ref.index))
    }

    private fun codecName(mime: String?): String = mime?.substringAfter('/')?.removePrefix("x-")?.removePrefix("vnd.")?.orEmpty().orEmpty()

    private fun bitDepth(encoding: Int?): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 8
        C.ENCODING_PCM_16BIT, C.ENCODING_PCM_16BIT_BIG_ENDIAN -> 16
        C.ENCODING_PCM_24BIT -> 24
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 32
        else -> 0
    }

    /** Messages may quote a URL; `user:password@` never leaves this class. */
    private fun redact(text: String) = MediaEntry.redact(text)

    private fun deliverEnd() {
        if (endDelivered || owner == null) return
        endDelivered = true
        event("queue.end", mapOf("index" to queue.index.toLong(), "count" to queue.count.toLong()))
    }

    private fun event(name: String, payload: Map<String, Any?>) {
        owner?.let { listener.onEvent(it, name, payload) }
    }

    /**
     * A source that goes quiet without an error (an RTSP camera that lost power) would leave the picture buffering
     * for ever. If the buffer has not grown for [STALL_MILLIS], reconnect; when that keeps failing, say so.
     */
    private fun watchStall(active: Player) {
        // A mirror that goes quiet is a still screen, not a dead connection: the sender ends the session itself, and starting the player again
        // would only throw away the picture and the stream's format until the sender's next keyframe.
        if (mirrorQueue || active.playbackState != Player.STATE_BUFFERING || retryPending || error != null || stopped) { stallPosition = -1; return }
        val now = SystemClock.elapsedRealtime()
        val buffered = active.bufferedPosition
        if (buffered != stallPosition) { stallPosition = buffered; stallSince = now; return }
        if (now - stallSince < STALL_MILLIS) return
        stallPosition = -1
        if (retries < MAX_RETRIES) {
            retries++
            retryPending = true
            android.util.Log.i("ThinkOS", "[media] stalled at ${active.currentPosition} ms; reconnecting, attempt $retries")
            handler.removeCallbacks(retryRun)
            active.stop()
            handler.postDelayed(retryRun, backoff(retries))
        } else {
            retries = 0
            fail(failure(PlaybackException.ERROR_CODE_TIMEOUT, "STALLED", "network", "The stream stopped delivering data"))
        }
    }

    private fun publish() {
        if (released) return
        handler.removeCallbacks(tick)
        player?.let(::watchStall)
        listener.onState(snapshot())
        if (queue.count > 0 && !stopped && error == null && !endDelivered &&
            (player?.isPlaying == true || player?.playbackState == Player.STATE_BUFFERING || retryPending)) {
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

    private companion object {
        const val USER_AGENT = "ThinkOS/1"
        const val PACE_MILLIS = 100L
        const val MAX_RETRIES = 6
        const val MAX_LIVE_RECOVERIES = 5
        const val RTSP_TIMEOUT_MILLIS = 8_000L
        const val STALL_MILLIS = 30_000L
        const val TEXT_LIMIT = 256
        const val MAX_TRACKS = 32
        val RTSP_STATUS = Regex("^[A-Z_]+ (\\d{3})$")
    }
}
