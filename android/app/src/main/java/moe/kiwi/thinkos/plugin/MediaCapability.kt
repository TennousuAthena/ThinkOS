package moe.kiwi.thinkos.plugin

import android.content.Context
import android.os.Handler
import moe.kiwi.thinkos.media.LibraryAccess
import moe.kiwi.thinkos.media.MediaEngine
import moe.kiwi.thinkos.media.MediaEntry
import moe.kiwi.thinkos.media.MediaLibrary
import moe.kiwi.thinkos.media.MediaOptions
import moe.kiwi.thinkos.media.MediaProbe
import moe.kiwi.thinkos.media.MediaQueue
import moe.kiwi.thinkos.media.MediaRepeat
import moe.kiwi.thinkos.media.MediaSubtitle
import moe.kiwi.thinkos.media.StoragePermission
import moe.kiwi.thinkos.net.PublicNetworkPolicy
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.FutureTask
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Typed capability adapter. All filesystem checks run on IO, all player/permission work on main. */
internal class MediaCapability(
    context: Context,
    private val engine: MediaEngine,
    private val storage: StoragePermission,
    private val io: ExecutorService,
    private val main: Handler,
) {
    private val app = context.applicationContext
    private val library = MediaLibrary(app)
    private val probe by lazy { MediaProbe.create(engine::allowsPublicCleartext) }
    /** A burst of probes from several plugins must not take every IO worker; one waits a moment for a slot, then gives up. */
    private val probeSlots = Semaphore(2)

    fun call(owner: String, permissions: Set<String>, method: String, args: Array<Any?>, job: CapabilityAwait,
             api: Int = PluginSchema.API) {
        try {
            if (method == "library.list") {
                require("library" in permissions) { "library permission required" }
                val options = args.getOrNull(0) as? Map<*, *> ?: error("Library options must be a table")
                onIO(job) {
                    val requiresRead = library.requiresStoragePermission(options)
                    withReadPermission(owner, requiresRead, job) {
                        // Large arrays cross JNI once as text; the budgeted C codec rebuilds the public table.
                        onIO(job) {
                            val text = JsonDocument.fromHost(library.list(options).toHost()).toString()
                            require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "Library response exceeds 1 MiB" }
                            job.finish(text)
                        }
                    }
                }
                return
            }
            require("media" in permissions) { "media permission required" }
            val playbackTicket = engine.playbackGeneration()
            if (method == "media.probe") {
                require("network" in permissions) { "network permission required to probe a stream" }
                val entry = probeEntry(args.getOrNull(0), args.getOrNull(1), api)
                onIO(job) {
                    if (!probeSlots.tryAcquire(2, TimeUnit.SECONDS)) job.finish(null, "Too many probes at once")
                    else try { job.finish(probe.run(entry)) } finally { probeSlots.release() }
                }
                return
            }
            if (method == "media.load") {
                val items = (args.getOrNull(0) as? List<*>)?.map { value ->
                    loadEntry(value as? Map<*, *> ?: error("Each media queue item must be a table"), api)
                } ?: error("Media queue must be an array")
                val options = options(args.getOrNull(1))
                MediaQueue.validate(items, options)
                // Subtitles are fetched or read like the media itself, so they need the same grants.
                val urls = items.flatMap { listOf(it.normalizedUrl) + it.subtitles.map(MediaSubtitle::normalizedUrl) }
                if (urls.any { URI(it).scheme in setOf("http", "https", "rtsp") }) {
                    require("network" in permissions) { "network permission required for remote media" }
                }
                val local = urls.filter { URI(it).scheme in setOf("file", "content") }
                if (local.isNotEmpty()) require("library" in permissions) { "library permission required for local media" }
                if (local.isEmpty()) onMain(job) { engine.requireAwake(playbackTicket); engine.load(owner, items, options); job.finish(publicState(engine.snapshot())) }
                else onIO(job) {
                    val requiresRead = LibraryAccess(app).requiresStoragePermissionForUrls(local)
                    withReadPermission(owner, requiresRead, job) {
                        onMain(job) { engine.requireAwake(playbackTicket); engine.load(owner, items, options); job.finish(publicState(engine.snapshot())) }
                    }
                }
                return
            }
            // Validate parameters before enqueuing UI work, including stopped/empty sessions.
            val value = args.getOrNull(0)
            val seek = if (method == "media.seek") finiteNumber(value, "Media position").also {
                require(it >= 0 && it <= Long.MAX_VALUE.toDouble()) { "Media position must be nonnegative milliseconds" }
            }.toLong() else null
            val volume = if (method == "media.volume") finiteNumber(value, "Media volume").also {
                require(it in 0.0..1.0) { "Media volume must be 0..1" }
            }.toFloat() else null
            val shuffle = if (method == "media.shuffle") value.also { require(it is Boolean) { "Media shuffle must be a boolean" } } as Boolean else null
            val repeat = if (method == "media.repeat") MediaRepeat.parse(value as? String ?: error("Media repeat must be text")) else null
            val position = if (method in setOf("media.jump", "media.audioTrack", "media.textTrack")) finiteNumber(value, "Media index").also {
                require(it % 1 == 0.0 && it >= 0 && it <= MediaQueue.MAX_ITEMS) { "Media index must be a whole number" }
            }.toInt() else null
            require(method in METHODS) { "Unavailable media API" }
            onMain(job) {
                if (method in PLAYBACK_METHODS) engine.requireAwake(playbackTicket)
                when (method) {
                    "media.play" -> engine.play()
                    "media.pause" -> engine.pause()
                    "media.toggle" -> engine.toggle()
                    "media.next" -> engine.next()
                    "media.prev" -> engine.prev()
                    "media.seek" -> engine.seek(seek!!)
                    "media.volume" -> engine.volume(volume!!)
                    "media.stop" -> engine.stop()
                    "media.shuffle" -> engine.setShuffle(shuffle!!)
                    "media.repeat" -> engine.setRepeat(repeat!!)
                    "media.jump" -> engine.jump(position!!)
                    "media.live" -> engine.goLive()
                    "media.audioTrack" -> engine.selectAudio(position!!)
                    "media.textTrack" -> engine.selectText(position!!)
                }
                job.finish(publicState(engine.snapshot()))
            }
        } catch (error: PublicNetworkPolicy.Blocked) {
            if (method == "media.probe") job.finish(MediaProbe.policyResult()) else job.finish(null, "policy")
        } catch (error: Exception) { job.finish(null, MediaEntry.redact(error.message ?: "Media capability failed")) }
    }

    private fun withReadPermission(owner: String, required: Boolean, job: CapabilityAwait, action: () -> Unit) {
        if (!required) { if (job.live()) action(); return }
        onMain(job) {
            val ticket = storage.request(owner) { granted, error ->
                if (job.live()) {
                    if (granted) action() else job.finish(null, error ?: "Media access denied")
                }
            }
            job.onCancel(ticket::close)
        }
    }

    private fun onMain(job: CapabilityAwait, action: () -> Unit) {
        val task = FutureTask<Unit> {
            if (job.live()) try { action() } catch (error: Exception) { job.finish(null, error.message ?: "Media operation failed") }
        }
        job.track(task, interruptible = false)
        if (!main.post(task)) job.finish(null, "Media UI thread is unavailable")
    }

    private fun onIO(job: CapabilityAwait, action: () -> Unit) {
        val task = FutureTask<Unit> {
            if (job.live()) try { action() } catch (error: Exception) { job.finish(null, error.message ?: "Media IO failed") }
        }
        job.track(task)
        try { io.execute(task) } catch (error: Exception) { job.finish(null, error.message ?: "Media IO is unavailable") }
    }

    companion object {
        private val PLAYBACK_METHODS = setOf("media.play", "media.toggle", "media.next", "media.prev", "media.jump", "media.live")
        val METHODS = setOf("media.play", "media.pause", "media.toggle", "media.next", "media.prev", "media.seek",
            "media.volume", "media.stop", "media.shuffle", "media.repeat", "media.jump", "media.live", "media.audioTrack",
            "media.textTrack")

        internal fun loadEntry(value: Map<*, *>, api: Int = PluginSchema.API): MediaEntry {
            if (value.containsKey("public_only")) require(api >= PluginSchema.mediaPublicOnlyApi(false)) { "media.public_only requires a newer plugin API" }
            return MediaEntry.fromMap(value)
        }

        /** Probe options share the queue item's strict headers/bool and public-network checks. */
        internal fun probeEntry(url: Any?, options: Any?, api: Int = PluginSchema.API): MediaEntry {
            require(url is String) { "Probe address must be text" }
            require(options == null || options is Map<*, *>) { "Probe options must be a table" }
            val fields = options as? Map<*, *> ?: emptyMap<Any, Any>()
            require(fields.keys.all { it == "headers" || it == "public_only" }) { "Unknown probe option" }
            if (fields.containsKey("public_only")) {
                require(api >= PluginSchema.mediaPublicOnlyApi(true)) { "media.probe.public_only requires a newer plugin API" }
                require(fields["public_only"] is Boolean) { "media.public_only must be a boolean" }
            }
            val entry = MediaEntry.fromMap(mapOf("url" to url, "headers" to fields["headers"],
                "public_only" to (fields["public_only"] ?: false)).filterValues { it != null })
            require(entry.scheme in setOf("http", "https", "rtsp")) { "Only http, https and rtsp addresses can be probed" }
            return entry
        }

        fun publicState(state: Map<String, Any?>): Map<String, Any?> =
            state + ("owner" to (state["owner"] as? String).orEmpty().substringBefore(':'))

        private fun finiteNumber(value: Any?, label: String): Double {
            require(value is Number && value.toDouble().isFinite()) { "$label must be a finite number" }
            return value.toDouble()
        }

        private fun options(value: Any?): MediaOptions {
            val fields = if (value == null) emptyMap<Any, Any>() else value as? Map<*, *> ?: error("Media options must be a table")
            require(fields.keys.all { it in setOf("index", "shuffle", "repeat", "autoplay", "volume", "start") }) { "Unknown media option" }
            val start = fields["start"]?.let { finiteNumber(it, "Media start").also { v ->
                require(v >= 0 && v <= Int.MAX_VALUE.toDouble() * 1000) { "Media start must be nonnegative milliseconds" }
            }.toLong() } ?: 0L
            val index = fields["index"]?.let { finiteNumber(it, "Media index").also { index ->
                require(index in 1.0..MediaQueue.MAX_ITEMS.toDouble() && index % 1 == 0.0) { "Media index must be a positive integer" }
            }.toInt() } ?: 1
            fun boolean(key: String, fallback: Boolean): Boolean = fields[key]?.let {
                require(it is Boolean) { "Media $key must be a boolean" }; it
            } ?: fallback
            return MediaOptions(index, boolean("shuffle", false), fields["repeat"]?.let {
                MediaRepeat.parse(it as? String ?: error("Media repeat must be text"))
            } ?: MediaRepeat.OFF, boolean("autoplay", true), fields["volume"]?.let {
                finiteNumber(it, "Media volume").also { v -> require(v in 0.0..1.0) { "Media volume must be 0..1" } }.toFloat()
            }, start)
        }
    }
}
