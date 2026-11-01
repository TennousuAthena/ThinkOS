package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.media.MediaEngine
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.abs

/**
 * `controls`, `video` and `seekbar`: OS-drawn views bound to the media session. Their buttons call the player
 * directly; Lua provides the media and never handles these clicks (ADR-022, ADR-030).
 */
object MediaComponents {
    fun create(context: Context, pluginId: String, declaration: JSONObject, engine: MediaEngine,
               capabilityCheck: (String, String) -> Boolean, levels: DeviceLevels): RenderComponent? {
        val allowed = { capabilityCheck(pluginId, "media") }
        if (!allowed()) return null
        return when (declaration.optString("type")) {
            "controls" -> Controls(context, engine, declaration.optString("mode", "queue"), allowed)
            "video" -> Video(context, declaration.optString("fit", "contain"), engine, allowed, levels)
            "seekbar" -> Seekbar(context, engine, allowed)
            else -> null
        }
    }

    private const val SKIP_MILLIS = 10_000L

    private fun isPlaying(status: String?) = status == "playing" || status == "buffering" || status == "reconnecting"

    /** What a transport button does. Skipping on a live stream goes back to its live edge. */
    private fun act(engine: MediaEngine, kind: MediaButton.Kind, live: Boolean) {
        when (kind) {
            MediaButton.Kind.PREVIOUS -> engine.prev()
            MediaButton.Kind.NEXT -> engine.next()
            MediaButton.Kind.PLAY -> engine.toggle()
            MediaButton.Kind.BACK -> engine.seek((engine.positionNow() - SKIP_MILLIS).coerceAtLeast(0))
            MediaButton.Kind.FORWARD -> if (live) engine.goLive() else engine.seek(engine.positionNow() + SKIP_MILLIS)
            else -> Unit
        }
    }

    private fun describe(context: Context, kind: MediaButton.Kind, playing: Boolean, live: Boolean) = context.getString(when (kind) {
        MediaButton.Kind.PREVIOUS -> R.string.media_previous
        MediaButton.Kind.NEXT -> R.string.media_next
        MediaButton.Kind.PLAY -> if (playing) R.string.media_pause else R.string.media_play
        MediaButton.Kind.BACK -> R.string.media_rewind
        MediaButton.Kind.FORWARD -> if (live) R.string.media_go_live else R.string.media_forward
        MediaButton.Kind.AUDIO -> R.string.media_audio
        MediaButton.Kind.TEXT -> R.string.media_subtitles
    })

    private class Controls(private val context: Context, private val engine: MediaEngine, mode: String,
                           private val allowed: () -> Boolean) : RenderComponent {
        private val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = (72 * resources.displayMetrics.density).toInt()
        }
        private var live = false
        private var playing = false
        private val buttons: List<MediaButton> = when (mode) {
            "seek" -> listOf(MediaButton.Kind.BACK, MediaButton.Kind.PLAY, MediaButton.Kind.FORWARD)
            "play" -> listOf(MediaButton.Kind.PLAY)
            else -> listOf(MediaButton.Kind.PREVIOUS, MediaButton.Kind.PLAY, MediaButton.Kind.NEXT)
        }.map { kind -> MediaButton(context, kind) { if (allowed()) act(engine, kind, live) } }
        override val view: View get() = row
        override val defaultWeight = 0.0
        override val interactive = true
        override val handlesTap = true

        init {
            val size = (64 * context.resources.displayMetrics.density).toInt()
            buttons.forEach { row.addView(it, LinearLayout.LayoutParams(size, size).apply { leftMargin = size / 6; rightMargin = size / 6 }) }
            localeChanged()
        }

        override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
            val media = snapshot.optJSONObject("media")
            val has = allowed() && (media?.optInt("count", 0) ?: 0) > 0
            live = media?.optBoolean("live", false) == true
            playing = isPlaying(media?.optString("status"))
            val seekable = media?.optBoolean("seekable", false) == true
            buttons.forEach { button ->
                val usable = has && (seekable || button.kind != MediaButton.Kind.BACK && button.kind != MediaButton.Kind.FORWARD)
                button.isEnabled = usable
                button.alpha = if (usable) 1f else .35f
                if (button.kind == MediaButton.Kind.PLAY) button.playing = playing
            }
            localeChanged()
        }

        override fun colors(value: Palette) { buttons.forEach { it.color = value.primary } }
        override fun localeChanged() { buttons.forEach { it.contentDescription = describe(context, it.kind, playing, live) } }
    }

    private class Seekbar(private val context: Context, engine: MediaEngine, allowed: () -> Boolean) : RenderComponent {
        private val bar = MediaSeekBar(context, engine, allowed)
        override val view: View get() = bar
        override val defaultWeight = 0.0
        override val interactive = true
        override val handlesTap = true

        init { localeChanged() }

        override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) = bar.bind(snapshot.optJSONObject("media"))
        override fun colors(value: Palette) {
            bar.color = value.primary
            bar.accent = value.accent
            bar.dim = Color.argb(70, Color.red(value.secondary), Color.green(value.secondary), Color.blue(value.secondary))
        }
        override fun capturesHorizontalAt(x: Float, y: Float) = bar.seekable
        override fun localeChanged() {
            bar.liveLabel = context.getString(R.string.media_live)
            bar.contentDescription = context.getString(R.string.media_seek)
        }
    }

    /**
     * The cinema view: the picture, subtitles, buffering and error cards, and a control overlay that appears while
     * someone is touching the screen and fades away on its own. The plugin supplies the media and nothing else.
     */
    private class Video(private val context: Context, private val fit: String, private val engine: MediaEngine,
                        private val allowed: () -> Boolean, private val levels: DeviceLevels) : RenderComponent {
        private val dp = context.resources.displayMetrics.density
        private val handler = Handler(Looper.getMainLooper())
        private val visible = Rect()
        private val transform = Matrix()
        private var attached = false
        private var videoWidth = 0.0
        private var videoHeight = 0.0
        private var attention = Attention.AWAKE
        private var media: JSONObject? = null
        private var overlayShown = false
        private var live = false
        private var seekable = false
        /** A screen shared from a phone or PC: the picture only, nothing to pause, skip or seek. */
        private var mirror = false
        private var playing = false
        /** A tap on the picture put the controls away while the finger's attention is still up. */
        private var hidden = false
        private var shownAtDown = false
        /** How the controls stood just before a touch raised attention to ENGAGED; null when no touch has done that since. */
        private var shownBeforeRise: Boolean? = null
        private var pendingTap: Runnable? = null
        private val doubleTapMillis = ViewConfiguration.getDoubleTapTimeout().toLong()
        private val taps = DoubleTap(doubleTapMillis, ViewConfiguration.get(context).scaledDoubleTapSlop.toFloat())
        private val skips = SkipTally(900)
        private var drag: Drag? = null
        private class Drag(val brightness: Boolean, val base: Float)
        private var lastWarning = ""
        private var toast: String? = null
        private val root = FrameLayout(context)
        private val texture = object : TextureView(context) {
            override fun onDetachedFromWindow() { disconnect(); super.onDetachedFromWindow() }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                updateTransform()
            }
        }.apply { isOpaque = false }
        private val cues = CueView(context).apply { visibility = View.INVISIBLE }
        private val sink: (List<CharSequence>) -> Unit = cues::setCues
        private val title = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 18f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding((24 * dp).toInt(), (20 * dp).toInt(), (24 * dp).toInt(), (28 * dp).toInt())
            background = fade(GradientDrawable.Orientation.TOP_BOTTOM)
        }
        private val seekbar = MediaSeekBar(context, engine, allowed).apply { color = Color.WHITE; dim = 0x55ffffff }
        private val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        private val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * dp).toInt(), (28 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())
            background = fade(GradientDrawable.Orientation.BOTTOM_TOP)
        }
        private val overlay = FrameLayout(context).apply { alpha = 0f; visibility = View.INVISIBLE }
        private val spinner = SpinnerView(context)
        private val message = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 17f; gravity = Gravity.CENTER
            setPadding((18 * dp).toInt(), (10 * dp).toInt(), (18 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xb3000000.toInt()); cornerRadius = 12 * dp }
            visibility = View.GONE
        }
        private val status = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; visibility = View.INVISIBLE }
        private val hideToast = Runnable { toast = null; showStatus() }
        private val buttons = mutableMapOf<MediaButton.Kind, MediaButton>()
        // Feedback for the touch gestures: a meter at each side, and notes for a double tap.
        private val brightnessCard = LevelCard(context)
        private val volumeCard = LevelCard(context)
        private val playIcon = MediaButton(context, MediaButton.Kind.PLAY) { }.apply { color = Color.WHITE; isClickable = false; isFocusable = false }
        private val playFlash = FlashView(context, playIcon)
        private val rewindNote = noteView()
        private val forwardNote = noteView()
        private val rewindFlash = FlashView(context, rewindNote)
        private val forwardFlash = FlashView(context, forwardNote)

        private fun noteView() = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 20f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding((22 * dp).toInt(), (14 * dp).toInt(), (22 * dp).toInt(), (14 * dp).toInt())
        }

        override val view: View get() = root
        override val interactive = true
        override val handlesTap = true

        init {
            root.setBackgroundColor(Color.BLACK)
            root.addView(texture, ViewGroup.LayoutParams(-1, -1))
            root.addView(cues, ViewGroup.LayoutParams(-1, -1))
            overlay.addView(title, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
            val size = (60 * dp).toInt()
            for (kind in listOf(MediaButton.Kind.AUDIO, MediaButton.Kind.PREVIOUS, MediaButton.Kind.BACK, MediaButton.Kind.PLAY,
                MediaButton.Kind.FORWARD, MediaButton.Kind.NEXT, MediaButton.Kind.TEXT)) {
                val button = MediaButton(context, kind) { press(kind) }.apply { color = Color.WHITE }
                buttons[kind] = button
                row.addView(button, LinearLayout.LayoutParams(size, size))
            }
            panel.addView(seekbar, LinearLayout.LayoutParams(-1, -2))
            panel.addView(row, LinearLayout.LayoutParams(-1, -2))
            overlay.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
            root.addView(overlay, ViewGroup.LayoutParams(-1, -1))
            status.addView(spinner, LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { bottomMargin = (12 * dp).toInt() })
            status.addView(message, LinearLayout.LayoutParams(-2, -2))
            root.addView(status, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER))
            // Double-tap notes sit in the middle of the zone they belong to: left quarter, middle half, right quarter.
            val zones = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for ((weight, flash) in listOf(1f to rewindFlash, 2f to playFlash, 1f to forwardFlash)) {
                val zone = FrameLayout(context)
                zone.addView(flash, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
                zones.addView(zone, LinearLayout.LayoutParams(0, -1, weight))
            }
            playIcon.layoutParams = FrameLayout.LayoutParams((72 * dp).toInt(), (72 * dp).toInt())
            root.addView(zones, ViewGroup.LayoutParams(-1, -1))
            val margin = (40 * dp).toInt()
            root.addView(brightnessCard, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.CENTER_VERTICAL).apply { marginStart = margin })
            root.addView(volumeCard, FrameLayout.LayoutParams(-2, -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = margin })
            localeChanged()
        }

        private fun fade(orientation: GradientDrawable.Orientation) =
            GradientDrawable(orientation, intArrayOf(0xb3000000.toInt(), 0x00000000))

        private fun press(kind: MediaButton.Kind) {
            if (!allowed()) return
            when (kind) {
                MediaButton.Kind.AUDIO -> cycleAudio()
                MediaButton.Kind.TEXT -> cycleText()
                else -> act(engine, kind, live)
            }
        }

        private fun tracks(name: String): JSONArray = media?.optJSONArray(name) ?: JSONArray()
        private fun selected(list: JSONArray): Int = (0 until list.length()).firstOrNull { list.getJSONObject(it).optBoolean("selected") }?.plus(1) ?: 0

        private fun trackName(track: JSONObject): String {
            val label = track.optString("label")
            if (label.isNotBlank()) return label
            val language = track.optString("language")
            val display = if (language.isBlank()) "" else Locale.forLanguageTag(language).getDisplayLanguage(Locale.getDefault())
            return display.ifBlank { context.getString(R.string.media_track_n, track.optInt("index", 1).toString()) }
        }

        private fun cycleAudio() {
            val list = tracks("audioTracks")
            if (list.length() < 2) return
            val next = selected(list) % list.length() + 1
            engine.selectAudio(next)
            say(context.getString(R.string.media_audio_track, trackName(list.getJSONObject(next - 1))))
        }

        private fun cycleText() {
            val list = tracks("textTracks")
            if (list.length() == 0) return
            val next = (selected(list) + 1) % (list.length() + 1)
            engine.selectText(next)
            say(context.getString(R.string.media_subtitles_now,
                if (next == 0) context.getString(R.string.media_subtitles_off) else trackName(list.getJSONObject(next - 1))))
        }

        /** A one-line note ("Audio: English") that goes away by itself. */
        private fun say(text: String) {
            toast = text
            handler.removeCallbacks(hideToast)
            handler.postDelayed(hideToast, 2_000)
            showStatus()
        }

        override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
            val next = snapshot.optJSONObject("media")
            media = next
            val ratio = next?.optDouble("pixelRatio", 1.0) ?: 1.0
            videoWidth = (next?.optDouble("videoWidth", 0.0) ?: 0.0) * ratio
            videoHeight = next?.optDouble("videoHeight", 0.0) ?: 0.0
            val isVideo = allowed() && next?.optString("kind") == "video"
            texture.visibility = if (isVideo) View.VISIBLE else View.INVISIBLE
            live = next?.optBoolean("live", false) == true
            mirror = next?.optBoolean("mirror", false) == true
            seekable = next?.optBoolean("seekable", false) == true
            playing = isPlaying(next?.optString("status"))
            seekbar.bind(next)
            title.text = next?.optString("title").orEmpty()
            val seekable = next?.optBoolean("seekable", false) == true
            val count = next?.optInt("count", 0) ?: 0
            buttons[MediaButton.Kind.PLAY]?.playing = playing
            buttons[MediaButton.Kind.PREVIOUS]?.visibility = if (count > 1) View.VISIBLE else View.GONE
            buttons[MediaButton.Kind.NEXT]?.visibility = if (count > 1) View.VISIBLE else View.GONE
            buttons[MediaButton.Kind.BACK]?.visibility = if (seekable) View.VISIBLE else View.GONE
            buttons[MediaButton.Kind.FORWARD]?.visibility = if (seekable) View.VISIBLE else View.GONE
            val audio = tracks("audioTracks")
            val text = tracks("textTracks")
            buttons[MediaButton.Kind.AUDIO]?.visibility = if (audio.length() > 1) View.VISIBLE else View.GONE
            buttons[MediaButton.Kind.AUDIO]?.marked = selected(audio) > 1
            buttons[MediaButton.Kind.TEXT]?.visibility = if (text.length() > 0) View.VISIBLE else View.GONE
            buttons[MediaButton.Kind.TEXT]?.marked = selected(text) > 0
            val warning = next?.optString("warning").orEmpty()
            if (warning != lastWarning) {
                lastWarning = warning
                if (warning.startsWith("audio-unsupported:")) say(context.getString(R.string.media_warning_audio, codecLabel(warning.substringAfter(':'))))
            }
            if (!isVideo) cues.setCues(emptyList())
            localeChanged()
            showStatus()
            applyOverlay()
            updateTransform()
            connectIfVisible()
        }

        private fun codecLabel(mime: String) = mime.substringAfter('/').removePrefix("x-").removePrefix("vnd.").uppercase(Locale.ROOT)

        private fun errorText(error: JSONObject): String {
            val codec = codecLabel(error.optString("codec"))
            return when (error.optString("category")) {
                "network" -> context.getString(R.string.media_error_network)
                "http" -> context.getString(R.string.media_error_http, error.optInt("status").toString())
                "notfound" -> context.getString(R.string.media_error_notfound)
                "permission" -> context.getString(R.string.media_error_permission)
                "policy" -> context.getString(R.string.media_error_policy)
                "format", "decoder" -> if (codec.isNotBlank()) context.getString(R.string.media_error_codec, codec)
                    else context.getString(if (error.optString("category") == "format") R.string.media_error_format else R.string.media_error_decoder)
                "audio" -> context.getString(R.string.media_error_audio)
                else -> context.getString(R.string.media_error_other)
            }
        }

        /** One centre card at a time: an error beats reconnecting, which beats buffering, which beats a passing note. */
        private fun showStatus() {
            val current = media
            val shown = allowed() && current?.optString("kind") == "video"
            val statusName = current?.optString("status").orEmpty()
            val error = current?.optJSONObject("error")
            val text: String? = when {
                !shown -> null
                statusName == "error" && error != null -> errorText(error)
                statusName == "reconnecting" -> context.getString(R.string.media_reconnecting, (current?.optInt("retry", 1) ?: 1).toString())
                toast != null -> toast
                else -> null
            }
            // A mirror with nothing new to show is not loading: no turning wheel over a still screen.
            val busy = shown && !mirror && (statusName == "buffering" || statusName == "reconnecting")
            status.visibility = if (text != null || busy) View.VISIBLE else View.INVISIBLE
            message.text = text.orEmpty()
            message.visibility = if (text != null) View.VISIBLE else View.GONE
            spinner.visibility = if (busy) View.VISIBLE else View.GONE
            spinner.spin(busy)
        }

        private fun applyOverlay() {
            val current = media
            val statusName = current?.optString("status").orEmpty()
            val isVideo = allowed() && current?.optString("kind") == "video"
            val show = isVideo && !mirror && VideoGestures.controlsShown(attention, hidden, statusName)
            if (show == overlayShown) return
            overlayShown = show
            overlay.animate().cancel()
            if (show) {
                overlay.visibility = View.VISIBLE
                overlay.animate().alpha(1f).setDuration(150).start()
            } else {
                overlay.animate().alpha(0f).setDuration(250).withEndAction { if (!overlayShown) overlay.visibility = View.INVISIBLE }.start()
            }
            // Subtitles make room for the controls instead of hiding behind them.
            cues.animate().translationY(if (show) -(panel.height.coerceAtLeast((130 * dp).toInt())).toFloat() else 0f).setDuration(200).start()
        }

        override fun colors(value: Palette) { seekbar.accent = value.accent }
        override fun active(value: Boolean) {
            if (!value) {
                disconnect()
                pendingTap?.let(handler::removeCallbacks); pendingTap = null
                taps.reset(); drag = null; hidden = false; shownBeforeRise = null
                for (flash in listOf(playFlash, rewindFlash, forwardFlash)) flash.clear()
            } else connectIfVisible()
        }
        override fun tick(monotonic: Long, attention: Attention) {
            if (attention == Attention.ENGAGED && this.attention != Attention.ENGAGED) {
                // A touch raises attention before the stage says where the finger is, so this is the last moment the controls
                // are still as the viewer saw them. A touch on the bare picture does not show them until it is a single tap.
                shownBeforeRise = overlayShown
                hidden = !overlayShown
            } else if (attention != Attention.ENGAGED) { hidden = false; shownBeforeRise = null }
            this.attention = attention
            applyOverlay()
            connectIfVisible()
        }

        // ---- touch gestures on the picture ----------------------------------------------------------------------
        // The stage tells the picture about every touch that is not a control's: where a finger went down, a tap, a vertical
        // drag. Horizontal swipes (leave the page), a long press (settings) and the seek bar stay with the stage.

        private fun gesturesOn() = allowed() && media?.optString("kind") == "video"

        override fun touchDown(x: Float, y: Float) {
            if (!gesturesOn()) return
            // Whether the controls were up before this touch (the touch itself may have shown them already: see [tick]).
            // A touch that started on the bare picture keeps them as they were until it is known to be a single tap.
            shownAtDown = shownBeforeRise ?: overlayShown
            shownBeforeRise = null
            hidden = !shownAtDown
        }

        override fun tapAt(x: Float, y: Float): Boolean {
            if (!gesturesOn() || mirror) return false
            val wasShown = shownAtDown
            if (taps.tap(SystemClock.uptimeMillis(), x, y)) {
                pendingTap?.let(handler::removeCallbacks); pendingTap = null
                doubleTap(x)
            } else {
                pendingTap?.let(handler::removeCallbacks)
                // A single tap is only certain once no second one follows: then it shows the controls, or puts them away.
                val confirm = Runnable { pendingTap = null; hidden = wasShown; applyOverlay() }
                pendingTap = confirm
                handler.postDelayed(confirm, doubleTapMillis)
            }
            return true
        }

        private fun doubleTap(x: Float) {
            when (VideoGestures.zone(x, root.width.toFloat())) {
                VideoGestures.Zone.TOGGLE -> {
                    val resuming = !playing
                    engine.toggle()
                    playIcon.playing = !resuming          // the note shows what the film is doing now: a pause sign when it has just paused
                    playFlash.flash()
                }
                VideoGestures.Zone.REWIND -> skip(-1)
                VideoGestures.Zone.FORWARD -> skip(1)
            }
        }

        private fun skip(direction: Int) {
            if (live && direction > 0) {
                act(engine, MediaButton.Kind.FORWARD, true)                  // a live stream cannot go ahead of itself: back to its edge
                forwardNote.text = context.getString(R.string.media_live)
                forwardFlash.flash()
                return
            }
            if (!seekable) return
            act(engine, if (direction < 0) MediaButton.Kind.BACK else MediaButton.Kind.FORWARD, live)
            val total = skips.add(SystemClock.uptimeMillis(), direction * (SKIP_MILLIS / 1000).toInt())
            val text = (if (total < 0) "−" else "+") + abs(total) + " s"
            if (direction < 0) { rewindNote.text = text; rewindFlash.flash(800) } else { forwardNote.text = text; forwardFlash.flash(800) }
        }

        override fun dragVerticalFrom(x: Float, y: Float, offset: Float): Boolean {
            if (!gesturesOn()) return false
            if (drag == null) {
                val brightness = VideoGestures.isBrightness(x, root.width.toFloat())
                drag = Drag(brightness, if (brightness) levels.brightness else levels.volume)
                pendingTap?.let(handler::removeCallbacks); pendingTap = null
                taps.reset()
            }
            return dragVertical(offset)
        }

        override fun dragVertical(offset: Float): Boolean {
            val current = drag ?: return false
            val travel = root.height * .7f                  // moving the finger 70% of the way up the screen covers the whole scale
            if (current.brightness) {
                levels.brightness = VideoGestures.level(current.base, offset, travel, levels.minimumBrightness)
                brightnessCard.show(context.getString(R.string.media_brightness), levels.brightness)
            } else {
                levels.volume = VideoGestures.level(current.base, offset, travel, 0f)
                volumeCard.show(context.getString(R.string.media_volume), levels.volume)
            }
            return true
        }

        override fun releaseVertical(offset: Float, velocity: Float) {
            drag = null
            brightnessCard.release()
            volumeCard.release()
        }

        override fun capturesHorizontalAt(x: Float, y: Float): Boolean {
            if (!overlayShown || !seekbar.seekable) return false
            val left = overlay.left + panel.left + seekbar.left
            val top = overlay.top + panel.top + seekbar.top
            return x >= left && x < left + seekbar.width && y >= top && y < top + seekbar.height
        }

        override fun localeChanged() {
            texture.contentDescription = context.getString(R.string.media_video)
            seekbar.liveLabel = context.getString(R.string.media_live)
            seekbar.contentDescription = context.getString(R.string.media_seek)
            buttons.forEach { (kind, button) -> button.contentDescription = describe(context, kind, playing, live) }
        }

        private fun updateTransform() {
            if (texture.width <= 0 || texture.height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
            val ratio = videoWidth / videoHeight / (texture.width.toDouble() / texture.height)
            val sx = if (fit == "cover") maxOf(1.0, ratio) else minOf(1.0, ratio)
            val sy = if (fit == "cover") maxOf(1.0, 1 / ratio) else minOf(1.0, 1 / ratio)
            transform.setScale(sx.toFloat(), sy.toFloat(), texture.width / 2f, texture.height / 2f)
            texture.setTransform(transform)
        }

        private fun connectIfVisible() {
            // A preloaded neighbouring page must not take the current page's video surface.
            val shown = allowed() && texture.isAttachedToWindow && texture.isShown && texture.getGlobalVisibleRect(visible) &&
                visible.width().toLong() * visible.height() > texture.width.toLong() * texture.height / 2
            if (shown && !attached) {
                engine.attachVideo(texture)
                engine.cueSink = sink
                attached = true
            } else if (!shown) disconnect()
        }

        private fun disconnect() {
            if (!attached) return
            engine.detachVideo(texture)
            if (engine.cueSink === sink) engine.cueSink = null
            attached = false
        }

    }
}
