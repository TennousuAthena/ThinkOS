package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.image.LoadedImage
import moe.kiwi.thinkos.media.MediaEngine
import moe.kiwi.thinkos.media.MediaEntry
import moe.kiwi.thinkos.media.MediaRepeat
import moe.kiwi.thinkos.ui.RenderGeometry.Box
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** How the music player view asks for a cover picture: the same loader (and the same limits) every plugin image goes through. */
internal fun interface CoverLoader {
    fun load(pluginId: String, src: String, width: Int, height: Int, result: (Result<LoadedImage>) -> Unit): Future<*>
}

/**
 * `nowplaying` (ADR-036): the OS music player, drawn from the media session. A plugin declares the style and the idle
 * and notice texts; the cover, the colours taken from it, the controls and the queue are the OS's. Buttons, the bars and
 * the queue act on the player directly. The parts that are not controls (the cover and the text) belong to the stage, so
 * a tap on the cover pauses and a swipe anywhere outside the controls turns the page or changes the variant.
 */
internal class NowPlayingView(
    context: Context,
    private val pluginId: String,
    private val definition: JSONObject,
    private val engine: MediaEngine,
    private val allowed: () -> Boolean,
    private val levels: DeviceLevels,
    private val covers: CoverLoader,
) : FrameLayout(context), RenderComponent {
    private val dp = resources.displayMetrics.density
    private val style = definition.optString("style", "full")
    private val idleTemplate = Binding.compile(definition.optString("idle"))
    private val noticeTemplate = Binding.compile(definition.optString("notice"))
    private val handler = Handler(Looper.getMainLooper())

    private var palette = Palette.DARK
    private var media: JSONObject? = null
    private var variant = "cover"
    private var active = false
    private var hasNotice = false
    private var playing = false
    private var live = false
    private var count = 0
    private var shuffleOn = false
    private var repeat = "off"
    private var trackKey = ""
    private var hue = CoverTone.hue("")
    private var saturation = .5f
    private var toneFor: Pair<Palette, Pair<Float, Float>>? = null
    private var accent = palette.accent
    private var accentText = palette.accentText

    private val cover = CoverView(context, if (style == "card") 8f else if (style == "bar") 8f else null)
    private val title = label(TextScale.TITLE, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val artist = label(TextScale.BODY)
    private val format = label(TextScale.CAPTION)
    private val notice = label(TextScale.CAPTION)
    private val idle = label(TextScale.TITLE, Typeface.create("sans-serif-medium", Typeface.NORMAL))
    private val seek = MediaSeekBar(context, engine, allowed)
    private val line = ProgressLine(context, engine)
    private val volume = VolumeBar(context, levels, allowed)
    private val queue = QueueList(context)
    private val buttons = MediaButton.Kind.values().filter { it != MediaButton.Kind.BACK && it != MediaButton.Kind.FORWARD &&
        it != MediaButton.Kind.AUDIO && it != MediaButton.Kind.TEXT }.associateWith { kind -> MediaButton(context, kind) { press(kind) } }

    private var plan = NowPlayingLayout()
    private var planKey: List<Any> = emptyList()
    private val placed = ArrayList<Pair<View, Box>>()
    private var buttonKinds = emptyList<MediaButton.Kind>()

    private var artSource = ""
    private var artImage: LoadedImage? = null
    private var artRequest: Future<*>? = null
    private var queueEntries: List<MediaEntry>? = null
    private var queueCurrent = 0

    override val view: View get() = this
    override val handlesTap = true
    override val interactive: Boolean get() = true

    init {
        clipChildren = true
        for (child in listOf(cover, title, artist, format, notice, idle, seek, line, volume, queue)) addView(child)
        buttons.values.forEach { addView(it) }
        listOf(title, artist, format, notice, idle, seek, line, volume, queue, cover).forEach { it.visibility = GONE }
        buttons.values.forEach { it.visibility = GONE }
        cover.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        if (style == "bar") idle.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        queue.onPick = { index -> if (allowed()) engine.jump(index) }
        localeChanged()
    }

    private fun label(size: Float, face: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)) = TextView(context).apply {
        textSize = size; typeface = face; includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    // ---- state -----------------------------------------------------------------------------------------------------

    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
        val m = snapshot.optJSONObject("media")?.takeIf { allowed() }
        media = m
        val status = m?.optString("status", "stopped") ?: "stopped"
        count = m?.optInt("count", 0) ?: 0
        val nowActive = allowed() && count > 0 && status != "stopped"
        playing = MediaComponents.isPlaying(status)
        live = m?.optBoolean("live", false) == true
        shuffleOn = m?.optBoolean("shuffle", false) == true
        repeat = m?.optString("repeat", "off") ?: "off"
        val nextVariant = if (variant == "queue") "queue" else "cover"
        val noticeText = noticeTemplate.render(snapshot, variant, strings).trim()
        setText(idle, idleTemplate.render(snapshot, variant, strings).trim())
        visibility = if (style == "bar" && !nowActive && idle.text.isBlank()) GONE else VISIBLE
        setText(notice, noticeText)
        if (nowActive) {
            val newTitle = m?.optString("title").orEmpty()
            val key = "$newTitle|${m?.optString("artist").orEmpty()}|${m?.optString("album").orEmpty()}|${m?.optString("index")}"
            if (key != trackKey) {
                val first = trackKey.isEmpty()
                trackKey = key
                trackChanged(first)
            }
            setText(title, newTitle.ifBlank { "—" })
            setText(artist, m?.optString("artist").orEmpty())
            setText(format, if (m?.optString("kind") == "video") "" else AudioFormatLine.of(m?.optString("audioCodec").orEmpty(),
                m?.optInt("sampleRate") ?: 0, m?.optInt("bitDepth") ?: 0, m?.optInt("bitrate") ?: 0))
            val art = m?.optString("art").orEmpty()
            if (art != artSource) { artSource = art; releaseArt(); ensureArt() }
        } else if (trackKey.isNotEmpty()) {
            trackKey = ""; artSource = ""; releaseArt(); retone()
        }
        seek.bind(m)
        line.bind(m)
        buttons.getValue(MediaButton.Kind.PLAY).let { it.playing = playing }
        buttons.getValue(MediaButton.Kind.SHUFFLE).marked = shuffleOn
        buttons.getValue(MediaButton.Kind.REPEAT).let { it.marked = repeat != "off"; it.repeatOne = repeat == "one" }
        val several = count > 1
        for (kind in listOf(MediaButton.Kind.PREVIOUS, MediaButton.Kind.NEXT)) buttons.getValue(kind).let { it.isEnabled = nowActive && (several || live); it.alpha = if (it.isEnabled) 1f else .35f }
        if (nextVariant == "queue" && nowActive) updateQueue(m)
        val layoutChanged = nextVariant != this.variant || nowActive != active || noticeText.isNotEmpty() != hasNotice
        this.variant = nextVariant; active = nowActive; hasNotice = noticeText.isNotEmpty()
        applyColors()
        localeChanged()
        if (layoutChanged) requestLayout()
    }

    private fun setText(view: TextView, text: String) { if (view.text.toString() != text) view.text = text }

    /** A new track: its text slides in and its cover fades up, unless this is the first thing drawn. */
    private fun trackChanged(first: Boolean) {
        retone()
        if (first || !isLaidOut) return
        for (view in listOf(title, artist)) {
            view.translationY = 6 * dp
            view.animate().translationY(0f).setDuration(Motion.NUMBER).setInterpolator(Motion.EASE).start()
        }
        cover.alpha = 0f
        cover.animate().alpha(1f).setDuration(260).start()
    }

    /** The hue (and with a picture, the saturation) the controls take; a track without a picture gets a hue from its name. */
    private fun retone() {
        val image = artImage?.bitmap
        val fromPicture = image?.let(::averageTone)
        hue = fromPicture?.first ?: CoverTone.hue(trackKey)
        saturation = fromPicture?.second ?: .5f
        cover.set(CoverTone.cover(hue), firstGlyph(media?.optString("title").orEmpty().ifBlank { media?.optString("artist").orEmpty() }),
            if (active || trackKey.isNotEmpty()) media?.optString("artist").orEmpty() else "")
        toneFor = null
    }

    private fun averageTone(bitmap: Bitmap): Pair<Float, Float>? {
        if (bitmap.isRecycled || bitmap.width <= 0 || bitmap.height <= 0) return null
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var r = 0.0; var g = 0.0; var b = 0.0; var total = 0.0
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = small.getPixel(x, y)
            val spread = max(Color.red(c), max(Color.green(c), Color.blue(c))) - min(Color.red(c), min(Color.green(c), Color.blue(c)))
            val weight = 16.0 + spread               // vivid pixels decide the tone, greys barely
            r += Color.red(c) * weight; g += Color.green(c) * weight; b += Color.blue(c) * weight; total += weight
        }
        if (small !== bitmap) small.recycle()
        if (total <= 0) return null
        val (h, s) = CoverTone.hueSaturation(Color.rgb((r / total).toInt(), (g / total).toInt(), (b / total).toInt()))
        return h to s
    }

    private fun firstGlyph(text: String): String {
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            if (Character.isLetterOrDigit(cp)) return String(Character.toChars(cp)).uppercase(Locale.ROOT)
            i += Character.charCount(cp)
        }
        return "♪"
    }

    private fun ensureArt() {
        if (artSource.isBlank() || artImage != null || artRequest != null || !allowed()) return
        val box = plan.cover ?: return
        val source = artSource
        artRequest = covers.load(pluginId, source, box.width, box.height) { result ->
            artRequest = null
            val image = result.getOrNull()
            if (image == null || source != artSource || !isAttachedToWindow) { image?.close(); return@load }
            artImage?.close()
            artImage = image
            cover.setArt(image.bitmap)
            retone(); applyColors()
        }
    }

    private fun releaseArt() {
        artRequest?.cancel(true); artRequest = null
        artImage?.close(); artImage = null
        cover.setArt(null)
    }

    private fun updateQueue(m: JSONObject?) {
        val entries = engine.queueEntries()
        if (entries !== queueEntries) { queueEntries = entries; queue.set(entries) }
        val index = m?.optInt("index", 0) ?: 0
        queue.mark(index, playing)
        if (index != queueCurrent) { queueCurrent = index; queue.reveal(index) }
    }

    // ---- colours ---------------------------------------------------------------------------------------------------

    override fun colors(value: Palette) { palette = value; toneFor = null; applyColors() }

    private fun applyColors() {
        val key = palette to (hue to saturation)
        if (toneFor != key) {
            toneFor = key
            val backdrops = intArrayOf(palette.bg, palette.surface)
            val tinted = trackKey.isNotEmpty()
            accent = if (tinted) CoverTone.accent(hue, saturation, backdrops) else palette.accent
            accentText = if (tinted) CoverTone.accentText(hue, saturation, backdrops) else palette.accentText
        }
        // In the library's footer the idle text is a hint or a notice, not a heading.
        title.setTextColor(palette.primary); idle.setTextColor(if (style == "bar") palette.secondary else palette.primary)
        artist.setTextColor(palette.secondary); format.setTextColor(palette.secondary)
        notice.setTextColor(palette.warnText)
        seek.color = palette.primary; seek.accent = accent
        seek.dim = Color.argb(70, Color.red(palette.secondary), Color.green(palette.secondary), Color.blue(palette.secondary))
        line.set(accent, Color.argb(70, Color.red(palette.secondary), Color.green(palette.secondary), Color.blue(palette.secondary)))
        volume.set(palette.secondary, accent, palette.divider)
        queue.colors(palette, accent, accentText)
        for ((kind, button) in buttons) when (kind) {
            MediaButton.Kind.PLAY -> { button.disc = accent; button.color = CoverTone.on(accent) }
            MediaButton.Kind.SHUFFLE, MediaButton.Kind.REPEAT -> button.color = if (button.marked) accent else palette.secondary
            else -> button.color = palette.primary
        }
        cover.set(CoverTone.cover(hue), cover.glyph, cover.caption)
    }

    override fun localeChanged() {
        for ((kind, button) in buttons) button.contentDescription = when (kind) {
            MediaButton.Kind.REPEAT -> context.getString(when (repeat) { "all" -> R.string.media_repeat_all; "one" -> R.string.media_repeat_one; else -> R.string.media_repeat_off })
            else -> MediaComponents.describe(context, kind, playing, live)
        }
        seek.liveLabel = context.getString(R.string.media_live)
        seek.contentDescription = context.getString(R.string.media_seek)
        volume.contentDescription = context.getString(R.string.media_volume)
        queue.contentDescription = context.getString(R.string.media_queue)
    }

    private fun press(kind: MediaButton.Kind) {
        if (!allowed()) return
        when (kind) {
            MediaButton.Kind.SHUFFLE -> engine.setShuffle(!shuffleOn)
            MediaButton.Kind.REPEAT -> engine.setRepeat(when (repeat) { "off" -> MediaRepeat.ALL; "all" -> MediaRepeat.ONE; else -> MediaRepeat.OFF })
            else -> MediaComponents.act(engine, kind, live)
        }
    }

    // ---- layout ----------------------------------------------------------------------------------------------------

    private fun kindsOf(set: ButtonSet) = when (set) {
        ButtonSet.FULL -> listOf(MediaButton.Kind.SHUFFLE, MediaButton.Kind.PREVIOUS, MediaButton.Kind.PLAY, MediaButton.Kind.NEXT, MediaButton.Kind.REPEAT)
        ButtonSet.TRANSPORT -> listOf(MediaButton.Kind.PREVIOUS, MediaButton.Kind.PLAY, MediaButton.Kind.NEXT)
        ButtonSet.PLAY -> listOf(MediaButton.Kind.PLAY)
        ButtonSet.NONE -> emptyList()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        arrange(w, h)
        for ((child, box) in placed) child.measure(MeasureSpec.makeMeasureSpec(box.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(box.height, MeasureSpec.EXACTLY))
    }

    private fun arrange(w: Int, h: Int) {
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val key = listOf(style, variant, w, h, rtl, hasNotice, active)
        if (key == planKey) return
        planKey = key
        plan = NowPlayingGeometry.layout(style, variant, w, h, dp, rtl, hasNotice, active)
        placed.clear()
        fun put(child: View, box: Box?) {
            child.visibility = if (box == null) GONE else VISIBLE
            if (box != null) placed += child to box
        }
        put(cover, plan.cover); put(title, plan.title); put(artist, plan.artist); put(format, plan.format); put(notice, plan.notice)
        put(idle, plan.idle); put(seek, plan.seek); put(line, plan.line); put(volume, plan.volume); put(queue, plan.queue)
        buttonKinds = kindsOf(plan.buttons)
        val boxes = plan.controls?.let { NowPlayingGeometry.buttons(plan.buttons, it, dp) }.orEmpty()
        for ((kind, button) in buttons) {
            val at = buttonKinds.indexOf(kind)
            put(button, if (at in boxes.indices) boxes[at] else null)
        }
        for (text in listOf(title, idle)) {
            text.maxLines = plan.titleLines
            text.setAutoSizeTextTypeUniformWithPresetSizes(
                if (style == "full") intArrayOf(TextScale.BODY.toInt(), TextScale.TITLE.toInt()) else intArrayOf(TextScale.BODY.toInt()),
                TypedValue.COMPLEX_UNIT_SP)
        }
        artist.textSize = plan.artistSp
        val wideCard = style != "full"
        title.gravity = (if (wideCard) Gravity.START or Gravity.BOTTOM else Gravity.START or Gravity.CENTER_VERTICAL)
        ensureArt()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for ((child, box) in placed) child.layout(box.left, box.top, box.right, box.bottom)
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); ensureArt() }
    override fun onDetachedFromWindow() { releaseArt(); super.onDetachedFromWindow() }

    // ---- touch: the stage owns everything that is not a control -----------------------------------------------------

    private fun inside(box: Box?, x: Float, y: Float) = box != null && x >= box.left && x < box.right && y >= box.top && y < box.bottom

    override fun interactiveAt(x: Float, y: Float): Boolean =
        active && (inside(plan.controls, x, y) || inside(plan.seek, x, y) || inside(plan.volume, x, y) || inside(plan.queue, x, y))

    override fun capturesHorizontalAt(x: Float, y: Float): Boolean =
        active && (inside(plan.seek, x, y) && seek.seekable || inside(plan.volume, x, y))

    /** The cover of the full page pauses and resumes; elsewhere a tap falls through to whatever the card opens. */
    override fun tapAt(x: Float, y: Float): Boolean {
        if (style != "full" || !active || !inside(plan.cover, x, y) || !allowed()) return false
        engine.toggle()
        return true
    }
}

/** The artwork, or, when there is none, a cover drawn from the title: a colour from the name, the first letter large and faint, the artist on top. */
internal class CoverView(context: Context, private val cornerDp: Float? = null) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val rect = RectF()
    private val clip = Path()
    private val matrix = Matrix()
    private var bitmap: Bitmap? = null
    private var shader: BitmapShader? = null
    private var fill = 0xff3b4252.toInt()
    var glyph = ""
        private set
    var caption = ""
        private set

    fun set(fill: Int, glyph: String, caption: String) {
        if (this.fill == fill && this.glyph == glyph && this.caption == caption) return
        this.fill = fill; this.glyph = glyph; this.caption = caption
        invalidate()
    }

    fun setArt(art: Bitmap?) {
        bitmap = art
        shader = art?.let { BitmapShader(it, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = cornerDp?.let { it * dp } ?: (min(w, h) * .053f).coerceIn(8 * dp, 28 * dp)
        rect.set(0f, 0f, w, h)
        val art = bitmap
        val brush = shader
        if (art != null && brush != null && !art.isRecycled) {
            val scale = max(w / art.width, h / art.height)
            matrix.setScale(scale, scale)
            matrix.postTranslate((w - art.width * scale) / 2f, (h - art.height * scale) / 2f)
            brush.setLocalMatrix(matrix)
            paint.shader = brush
            canvas.drawRoundRect(rect, radius, radius, paint)
            paint.shader = null
            return
        }
        paint.color = fill
        canvas.drawRoundRect(rect, radius, radius, paint)
        clip.rewind(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        val side = min(w, h)
        if (glyph.isNotEmpty()) {
            text.color = Color.WHITE; text.alpha = 56; text.textSize = side * .9f; text.textAlign = Paint.Align.RIGHT
            canvas.drawText(glyph, w + side * .04f, h - side * .02f - text.descent(), text)
        }
        if (caption.isNotEmpty() && side >= 120 * dp) {
            text.color = Color.WHITE; text.alpha = 235; text.textSize = side * .066f; text.textAlign = Paint.Align.LEFT
            val room = w - side * .14f
            canvas.drawText(TextUtils.ellipsize(caption, text, room, TextUtils.TruncateAt.END).toString(), side * .07f, side * .07f - text.ascent(), text)
            paint.color = Color.WHITE; paint.alpha = 190
            canvas.drawRect(side * .07f, side * .07f + text.textSize * 1.45f, side * .07f + side * .09f, side * .07f + text.textSize * 1.45f + side * .012f, paint)
        }
        canvas.restore()
    }
}

/** A thin position line along the bottom of the mini player and the home card; it only reads the playhead and cannot be dragged. */
internal class ProgressLine(context: Context, private val engine: MediaEngine) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var duration = 0L
    private var running = false
    private var fillColor = Palette.DARK.accent
    private var trackColor = 0x44ffffff
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            if (running && isShown) postDelayed(this, 500)
        }
    }

    fun set(fill: Int, track: Int) { fillColor = fill; trackColor = track; invalidate() }

    fun bind(media: JSONObject?) {
        duration = media?.optLong("duration", 0) ?: 0
        running = MediaComponents.isPlaying(media?.optString("status")) && media?.optBoolean("live", false) != true
        removeCallbacks(refresh)
        if (running && isShown) post(refresh) else invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (running) post(refresh) }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val thickness = min(height.toFloat(), Stroke.TRACK * dp)
        val y = height / 2f
        paint.strokeWidth = thickness
        paint.color = trackColor
        canvas.drawLine(thickness / 2, y, width - thickness / 2, y, paint)
        if (duration <= 0) return
        val fraction = (engine.positionNow().toDouble() / duration).toFloat().coerceIn(0f, 1f)
        paint.color = fillColor
        canvas.drawLine(thickness / 2, y, thickness / 2 + (width - thickness) * fraction, y, paint)
    }
}

/**
 * The media volume the volume keys also change, as a slider with a speaker at its start. It writes the system level as the
 * finger moves, in steps, and picks up changes made elsewhere (the keys) once a second.
 */
internal class VolumeBar(context: Context, private val levels: DeviceLevels, private val allowed: () -> Boolean) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()
    private var iconColor = Palette.DARK.secondary
    private var fillColor = Palette.DARK.accent
    private var trackColor = Palette.DARK.divider
    private var shown = 0f
    private var applied = -1f
    private var dragging = false
    private val reread = object : Runnable {
        override fun run() {
            if (!dragging) { shown = levels.volume; invalidate() }
            if (isShown) postDelayed(this, 1000)
        }
    }

    init { layoutDirection = LAYOUT_DIRECTION_LTR; isClickable = true }

    fun set(icon: Int, fill: Int, track: Int) { iconColor = icon; fillColor = fill; trackColor = track; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); shown = levels.volume; post(reread) }
    override fun onDetachedFromWindow() { removeCallbacks(reread); super.onDetachedFromWindow() }

    private fun left() = 40 * dp
    private fun right() = width - 8 * dp

    override fun onDraw(canvas: Canvas) {
        val cy = height / 2f
        val s = 9 * dp
        paint.color = iconColor
        paint.style = Paint.Style.FILL
        path.rewind()
        path.moveTo(10 * dp, cy - s * .4f); path.lineTo(10 * dp + s * .55f, cy - s * .4f); path.lineTo(10 * dp + s * 1.25f, cy - s * .95f)
        path.lineTo(10 * dp + s * 1.25f, cy + s * .95f); path.lineTo(10 * dp + s * .55f, cy + s * .4f); path.lineTo(10 * dp, cy + s * .4f); path.close()
        canvas.drawPath(path, paint)
        if (shown > 0f) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = Stroke.ICON * dp
            canvas.drawArc(10 * dp + s * .9f, cy - s * .55f, 10 * dp + s * 1.9f, cy + s * .55f, -50f, 100f, false, paint)
        }
        paint.style = Paint.Style.STROKE; paint.strokeWidth = Stroke.TRACK * dp
        paint.color = trackColor
        canvas.drawLine(left(), cy, right(), cy, paint)
        val x = left() + (right() - left()) * shown.coerceIn(0f, 1f)
        paint.color = fillColor
        canvas.drawLine(left(), cy, x, cy, paint)
        paint.style = Paint.Style.FILL
        canvas.drawCircle(x, cy, if (dragging) 9 * dp else 6 * dp, paint)
    }

    private fun move(x: Float, final: Boolean) {
        shown = ((x - left()) / (right() - left())).coerceIn(0f, 1f)
        // One system call per step of the finger rather than per touch event.
        if (final || abs(shown - applied) >= .02f) { if (allowed()) levels.volume = shown; applied = shown }
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); dragging = true; move(event.x, false) }
            MotionEvent.ACTION_MOVE -> if (dragging) move(event.x, false)
            MotionEvent.ACTION_UP -> if (dragging) { move(event.x, true); dragging = false }
            MotionEvent.ACTION_CANCEL -> { dragging = false; shown = levels.volume; invalidate() }
        }
        return true
    }
}

/** The queue of the session in the order it was loaded; the track in use is marked and a tap plays another. */
internal class QueueList(context: Context) : ListView(context) {
    private val dp = resources.displayMetrics.density
    private var entries: List<MediaEntry> = emptyList()
    private var current = 0
    private var playing = false
    private var palette = Palette.DARK
    private var accent = palette.accent
    private var accentText = palette.accentText
    var onPick: (Int) -> Unit = {}

    private val rows = object : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int): Any = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
            ((convertView as? Row) ?: Row()).also { it.bind(position, entries[position]) }
    }

    init {
        divider = null; overScrollMode = OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false; isFocusable = false
        adapter = rows
        setOnItemClickListener { _, _, position, _ -> onPick(position + 1) }
    }

    fun set(next: List<MediaEntry>) { entries = next; rows.notifyDataSetChanged() }

    fun mark(index: Int, isPlaying: Boolean) {
        if (index == current && isPlaying == playing) return
        current = index; playing = isPlaying
        rows.notifyDataSetChanged()
    }

    /** Bring the track in use into view, a third of the way down. */
    fun reveal(index: Int) {
        if (index !in 1..entries.size) return
        post { if (!isPressed) smoothScrollToPositionFromTop(index - 1, height / 3, 250) }
    }

    fun colors(value: Palette, accentColor: Int, text: Int) {
        if (palette == value && accent == accentColor && accentText == text) return
        palette = value; accent = accentColor; accentText = text
        rows.notifyDataSetChanged()
    }

    private inner class Row : LinearLayout(context) {
        private val marker = object : View(context) {
            var bars = false
            val bar = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun onDraw(canvas: Canvas) {
                val w = width.toFloat(); val h = height.toFloat()
                if (!bars) return
                bar.color = accentText
                val bw = 4 * dp
                val heights = if (playing) floatArrayOf(.45f, .9f, .65f) else floatArrayOf(.55f, .55f, .55f)
                for (i in 0 until 3) canvas.drawRoundRect(w / 2 - bw * 2.2f + i * bw * 1.6f, h / 2 + h * .22f - h * .44f * heights[i], w / 2 - bw * 2.2f + i * bw * 1.6f + bw, h / 2 + h * .22f, bw / 2, bw / 2, bar)
            }
        }
        private val number = TextView(context).apply { textSize = TextScale.CAPTION; gravity = Gravity.CENTER; includeFontPadding = false; fontFeatureSettings = "tnum" }
        private val title = TextView(context).apply { textSize = TextScale.BODY; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        private val artist = TextView(context).apply { textSize = TextScale.CAPTION; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false }
        private val highlight = GradientDrawable().apply { cornerRadius = 14 * dp }
        private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
        private val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (64 * dp).toInt()
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            val slot = FrameLayout(context).apply { addView(number, FrameLayout.LayoutParams(-1, -1)); addView(marker, FrameLayout.LayoutParams(-1, -1)) }
            addView(slot, LayoutParams((32 * dp).toInt(), (32 * dp).toInt()).apply { marginEnd = (12 * dp).toInt() })
            addView(LinearLayout(context).apply { orientation = VERTICAL; addView(title); addView(artist) }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        fun bind(position: Int, entry: MediaEntry) {
            val here = position + 1 == current
            number.text = (position + 1).toString()
            number.visibility = if (here) INVISIBLE else VISIBLE
            marker.bars = here; marker.invalidate()
            title.text = entry.title.ifBlank { "—" }
            artist.text = entry.artist
            artist.visibility = if (entry.artist.isBlank()) GONE else VISIBLE
            title.typeface = if (here) medium else regular
            title.setTextColor(if (here) accentText else palette.primary)
            artist.setTextColor(palette.secondary); number.setTextColor(palette.secondary)
            highlight.setColor(palette.surface)
            background = if (here) highlight else null
        }
    }
}
