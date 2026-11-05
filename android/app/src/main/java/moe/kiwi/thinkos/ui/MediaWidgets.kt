package moe.kiwi.thinkos.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import moe.kiwi.thinkos.media.MediaEngine
import org.json.JSONObject
import java.util.Locale
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

internal object MediaTime {
    fun format(millis: Long): String {
        val total = millis.coerceAtLeast(0) / 1000
        val hours = total / 3600
        val minutes = total % 3600 / 60
        val seconds = total % 60
        return if (hours > 0) String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        else String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}

/** One transport button, drawn with paths so it needs no icon assets and scales with its box. */
internal class MediaButton(context: Context, val kind: Kind, private val action: () -> Unit) : View(context) {
    enum class Kind { PREVIOUS, NEXT, PLAY, BACK, FORWARD, AUDIO, TEXT, SHUFFLE, REPEAT }

    var playing = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** AUDIO and TEXT: whether a non-default track is selected. SHUFFLE and REPEAT: whether the mode is on. */
    var marked = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** REPEAT: the mode is "one" rather than "all". */
    var repeatOne = false
        set(value) { if (field != value) { field = value; invalidate() } }
    /** A solid disc behind the glyph (the player's main play button); 0 draws none. */
    var disc = 0
        set(value) { if (field != value) { field = value; invalidate() } }
    var color = Color.WHITE
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val oval = RectF()
    private val dp = resources.displayMetrics.density

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { action() }
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        if (disc != 0) {
            paint.style = Paint.Style.FILL
            paint.color = disc
            paint.alpha = if (isPressed) 190 else 255
            canvas.drawCircle(cx, cy, min(width, height) / 2f, paint)
        }
        paint.color = color
        paint.alpha = if (isPressed) 150 else 255
        val size = min(width, height) * .20f
        when (kind) {
            Kind.PLAY -> {
                paint.style = Paint.Style.FILL
                if (playing) {
                    canvas.drawRect(cx - size, cy - size, cx - size * .3f, cy + size, paint)
                    canvas.drawRect(cx + size * .3f, cy - size, cx + size, cy + size, paint)
                } else triangle(canvas, cx, cy, size, 1)
            }
            Kind.PREVIOUS, Kind.NEXT -> {
                paint.style = Paint.Style.FILL
                val sign = if (kind == Kind.PREVIOUS) -1 else 1
                triangle(canvas, cx, cy, size, sign)
                val x = cx + sign * size * 1.05f
                canvas.drawRect(x - size * .12f, cy - size, x + size * .12f, cy + size, paint)
            }
            Kind.BACK, Kind.FORWARD -> skip(canvas, cx, cy, size * 1.25f, if (kind == Kind.BACK) -1 else 1)
            Kind.AUDIO -> speaker(canvas, cx, cy, size)
            Kind.TEXT -> captions(canvas, cx, cy, size)
            Kind.SHUFFLE -> shuffle(canvas, cx, cy, size * 1.1f)
            Kind.REPEAT -> repeat(canvas, cx, cy, size * 1.1f)
        }
        if (marked && (kind == Kind.SHUFFLE || kind == Kind.REPEAT)) {
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy + size * 2.15f, size * .2f, paint)
        }
    }

    private fun arrowHead(canvas: Canvas, x: Float, y: Float, size: Float, sign: Int) {
        paint.style = Paint.Style.FILL
        path.rewind()
        path.moveTo(x + sign * size, y)
        path.lineTo(x, y - size * .8f)
        path.lineTo(x, y + size * .8f)
        path.close()
        canvas.drawPath(path, paint)
    }

    /** Two lines that cross in the middle, each ending in an arrow. */
    private fun shuffle(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Stroke.ICON * dp
        path.rewind()
        path.moveTo(cx - s, cy - s * .55f); path.lineTo(cx - s * .25f, cy - s * .55f); path.lineTo(cx + s * .35f, cy + s * .55f); path.lineTo(cx + s * .6f, cy + s * .55f)
        path.moveTo(cx - s, cy + s * .55f); path.lineTo(cx - s * .25f, cy + s * .55f); path.lineTo(cx + s * .35f, cy - s * .55f); path.lineTo(cx + s * .6f, cy - s * .55f)
        canvas.drawPath(path, paint)
        arrowHead(canvas, cx + s * .6f, cy + s * .55f, s * .42f, 1)
        arrowHead(canvas, cx + s * .6f, cy - s * .55f, s * .42f, 1)
    }

    /** Two rounded lines chasing each other, an arrow at the end of each; "one" has a 1 in the middle. */
    private fun repeat(canvas: Canvas, cx: Float, cy: Float, s: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Stroke.ICON * dp
        paint.pathEffect = android.graphics.CornerPathEffect(s * .35f)
        path.rewind()
        path.moveTo(cx - s * .9f, cy + s * .15f); path.lineTo(cx - s * .9f, cy - s * .55f); path.lineTo(cx + s * .45f, cy - s * .55f)
        path.moveTo(cx + s * .9f, cy - s * .15f); path.lineTo(cx + s * .9f, cy + s * .55f); path.lineTo(cx - s * .45f, cy + s * .55f)
        canvas.drawPath(path, paint)
        paint.pathEffect = null
        arrowHead(canvas, cx + s * .45f, cy - s * .55f, s * .42f, 1)
        arrowHead(canvas, cx - s * .45f, cy + s * .55f, s * .42f, -1)
        if (repeatOne) {
            paint.style = Paint.Style.FILL
            paint.textSize = s * 1.0f
            canvas.drawText("1", cx, cy + paint.textSize * .34f, paint)
        }
    }

    private fun triangle(canvas: Canvas, cx: Float, cy: Float, size: Float, sign: Int) {
        path.rewind()
        path.moveTo(cx + sign * size, cy)
        path.lineTo(cx - sign * size * .65f, cy - size)
        path.lineTo(cx - sign * size * .65f, cy + size)
        path.close()
        canvas.drawPath(path, paint)
    }

    /** A 300° arrow around "10": clockwise for forward, mirrored for back. */
    private fun skip(canvas: Canvas, cx: Float, cy: Float, radius: Float, sign: Int) {
        canvas.save()
        if (sign < 0) canvas.scale(-1f, 1f, cx, cy)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Stroke.ICON * dp
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        // Clockwise from 3 o'clock to 60° before it: the gap sits at the top right, where the head points on.
        val start = 0f
        val sweep = 300f
        canvas.drawArc(oval, start, sweep, false, paint)
        val end = Math.toRadians((start + sweep).toDouble())
        val px = cx + radius * cos(end).toFloat()
        val py = cy + radius * sin(end).toFloat()
        // Tangent of a clockwise arc at angle t is (-sin t, cos t); the head leans slightly outwards.
        val tx = -sin(end).toFloat()
        val ty = cos(end).toFloat()
        val head = radius * .55f
        paint.style = Paint.Style.FILL
        path.rewind()
        path.moveTo(px + tx * head, py + ty * head)
        path.lineTo(px - ty * head * .8f, py + tx * head * .8f)
        path.lineTo(px + ty * head * .8f, py - tx * head * .8f)
        path.close()
        canvas.drawPath(path, paint)
        canvas.restore()
        paint.style = Paint.Style.FILL
        paint.textSize = radius * .95f
        canvas.drawText("10", cx, cy + paint.textSize * .34f, paint)
    }

    private fun speaker(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.FILL
        path.rewind()
        path.moveTo(cx - size * 1.1f, cy - size * .35f)
        path.lineTo(cx - size * .5f, cy - size * .35f)
        path.lineTo(cx + size * .15f, cy - size * .95f)
        path.lineTo(cx + size * .15f, cy + size * .95f)
        path.lineTo(cx - size * .5f, cy + size * .35f)
        path.lineTo(cx - size * 1.1f, cy + size * .35f)
        path.close()
        canvas.drawPath(path, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Stroke.ICON * dp
        oval.set(cx - size * .1f, cy - size * .6f, cx + size * .7f, cy + size * .6f)
        canvas.drawArc(oval, -50f, 100f, false, paint)
        oval.set(cx - size * .1f, cy - size * 1.0f, cx + size * 1.15f, cy + size * 1.0f)
        canvas.drawArc(oval, -50f, 100f, false, paint)
        marker(canvas, cx, cy, size)
    }

    private fun captions(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = Stroke.ICON * dp
        oval.set(cx - size * 1.3f, cy - size * .9f, cx + size * 1.3f, cy + size * .9f)
        canvas.drawRoundRect(oval, size * .3f, size * .3f, paint)
        paint.style = Paint.Style.FILL
        canvas.drawRect(cx - size * .85f, cy - size * .1f, cx - size * .1f, cy + size * .15f, paint)
        canvas.drawRect(cx + size * .1f, cy - size * .1f, cx + size * .85f, cy + size * .15f, paint)
        canvas.drawRect(cx - size * .85f, cy + size * .4f, cx + size * .35f, cy + size * .65f, paint)
        marker(canvas, cx, cy, size)
    }

    /** A small dot shows that something other than the default is on. */
    private fun marker(canvas: Canvas, cx: Float, cy: Float, size: Float) {
        if (!marked) return
        paint.style = Paint.Style.FILL
        canvas.drawCircle(cx + size * 1.3f, cy - size * .9f, size * .28f, paint)
    }
}

/**
 * Position bar for the OS media session. It reads the playhead from the engine on every draw, so it moves
 * smoothly although the published `position` only changes once a second, and it keeps the page swipe away
 * from a drag that starts on the bar.
 */
internal class MediaSeekBar(context: Context, private val engine: MediaEngine, private val allowed: () -> Boolean) : View(context) {
    private val dp = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TextScale.CAPTION * resources.displayMetrics.scaledDensity; typeface = Typeface.create("sans-serif", Typeface.NORMAL); fontFeatureSettings = "tnum" }
    private val rect = RectF()
    private var duration = 0L
    private var live = false
    private var seekableNow = false
    private var running = false
    private var status = "stopped"
    private var liveOffset = 0L
    private var dragFraction = -1f
    private val sideWidth = 58f * dp
    private val refresh = object : Runnable {
        override fun run() {
            invalidate()
            if (running && isShown) postDelayed(this, REFRESH_MILLIS)
        }
    }

    var color = Color.WHITE
        set(value) { field = value; invalidate() }
    var accent = Palette.DARK.accent
        set(value) { field = value; invalidate() }
    var dim = 0x66ffffff
        set(value) { field = value; invalidate() }
    var liveLabel = "LIVE"

    /** True when a drag may start here: only then does the bar claim horizontal gestures from the page swipe. */
    val seekable: Boolean get() = seekableNow && duration > 0 && allowed()

    init { layoutDirection = LAYOUT_DIRECTION_LTR; isClickable = true }

    fun bind(media: JSONObject?) {
        duration = media?.optLong("duration", 0) ?: 0
        live = media?.optBoolean("live", false) == true
        seekableNow = media?.optBoolean("seekable", false) == true
        liveOffset = media?.optLong("liveOffset", 0) ?: 0
        status = media?.optString("status", "stopped") ?: "stopped"
        running = status == "playing" || status == "buffering"
        removeCallbacks(refresh)
        if (running && isShown) post(refresh) else invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (running) post(refresh) }
    override fun onDetachedFromWindow() { removeCallbacks(refresh); super.onDetachedFromWindow() }
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(refresh)
        if (visibility == VISIBLE && running) post(refresh)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (48 * dp).toInt())
    }

    private fun left() = sideWidth
    private fun right(): Float {
        val remaining = text.measureText("-" + MediaTime.format(maxOf(duration, liveOffset)))
        val labelWidth = maxOf(remaining, if (live) text.measureText(liveLabel) else 0f)
        return (width - maxOf(sideWidth, labelWidth + 12f * dp)).coerceAtLeast(sideWidth + 1)
    }

    override fun onDraw(canvas: Canvas) {
        val playhead = engine.positionNow()
        val fraction = when {
            dragFraction >= 0f -> dragFraction
            live && !seekableNow -> 1f
            duration > 0 -> (playhead.toDouble() / duration).toFloat().coerceIn(0f, 1f)
            else -> 0f
        }
        val buffered = if (duration > 0) (engine.bufferedNow().toDouble() / duration).toFloat().coerceIn(0f, 1f) else 0f
        val cy = height / 2f
        val half = 2f * dp
        val l = left()
        val r = right()
        track.style = Paint.Style.FILL
        track.color = dim
        rect.set(l, cy - half, r, cy + half)
        canvas.drawRoundRect(rect, half, half, track)
        track.color = Color.argb(150, Color.red(color), Color.green(color), Color.blue(color))
        rect.set(l, cy - half, l + (r - l) * maxOf(buffered, fraction), cy + half)
        canvas.drawRoundRect(rect, half, half, track)
        track.color = accent
        rect.set(l, cy - half, l + (r - l) * fraction, cy + half)
        canvas.drawRoundRect(rect, half, half, track)
        if (seekable || dragFraction >= 0f) {
            val x = l + (r - l) * fraction
            track.color = color
            canvas.drawCircle(x, cy, if (dragFraction >= 0f) 9f * dp else 6f * dp, track)
        }
        text.color = color
        val shownPosition = if (dragFraction >= 0f) (dragFraction * duration).toLong() else playhead
        text.textAlign = Paint.Align.LEFT
        canvas.drawText(if (duration > 0 || !live) MediaTime.format(shownPosition) else "", dp * 4f, cy + text.textSize * .35f, text)
        text.textAlign = Paint.Align.RIGHT
        val behind = live && liveOffset > LIVE_SLACK_MILLIS
        val label = when {
            live && !behind -> liveLabel
            live -> "-" + MediaTime.format(liveOffset)
            duration > 0 -> "-" + MediaTime.format((duration - shownPosition).coerceAtLeast(0))
            else -> ""
        }
        if (live) text.color = if (behind) color else accent
        canvas.drawText(label, width - dp * 4f, cy + text.textSize * .35f, text)
    }

    private var pointerId = -1

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // A live stream without a window, or media of unknown length, has nothing to seek: leave the touch to the page.
        if (!seekable && event.actionMasked == MotionEvent.ACTION_DOWN) return false
        // One finger owns the gesture; a second finger landing or lifting must not move the thumb.
        fun x(): Float? = event.findPointerIndex(pointerId).takeIf { it >= 0 }?.let(event::getX)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId = event.getPointerId(0)
                parent?.requestDisallowInterceptTouchEvent(true)
                dragFraction = fractionAt(event.x)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> x()?.let { dragFraction = fractionAt(it); invalidate() }
            MotionEvent.ACTION_POINTER_UP -> if (event.getPointerId(event.actionIndex) == pointerId) finish(event.getX(event.actionIndex))
            MotionEvent.ACTION_UP -> if (pointerId >= 0) finish(x() ?: event.x)
            MotionEvent.ACTION_CANCEL -> { dragFraction = -1f; pointerId = -1; invalidate() }
        }
        return true
    }

    private fun finish(x: Float) {
        val fraction = fractionAt(x)
        dragFraction = -1f
        pointerId = -1
        if (allowed()) engine.seek((fraction * duration).toLong())
        invalidate()
    }

    private fun fractionAt(x: Float): Float = ((x - left()) / (right() - left())).coerceIn(0f, 1f)

    private companion object {
        const val REFRESH_MILLIS = 200L
        const val LIVE_SLACK_MILLIS = 6_000L
    }
}

/** Subtitle text with an outline, laid out for the video box and centred above the bottom edge. */
internal class CueView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val fill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val outline = TextPaint(fill).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private var lines: CharSequence = ""
    private var layout: StaticLayout? = null
    private var outlineLayout: StaticLayout? = null

    fun setCues(cues: List<CharSequence>) {
        val joined = cues.joinToString("\n") { it.toString().trim() }.take(MAX_CHARACTERS)
        if (joined == lines.toString()) return
        lines = joined
        layout = null
        outlineLayout = null
        visibility = if (joined.isBlank()) INVISIBLE else VISIBLE
        invalidate()
    }

    private fun build() {
        if (width <= 0 || lines.isBlank()) return
        // About 1/22 of the shorter side keeps a two-line cue readable on the 8-inch panel without covering the picture.
        val size = (min(width, height) / 22f).coerceIn(14f * dp, 40f * dp)
        fill.textSize = size
        outline.textSize = size
        outline.strokeWidth = size * .16f
        val available = (width * .86f).toInt().coerceAtLeast(1)
        layout = StaticLayout.Builder.obtain(lines, 0, lines.length, fill, available).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
        outlineLayout = StaticLayout.Builder.obtain(lines, 0, lines.length, outline, available).setAlignment(Layout.Alignment.ALIGN_CENTER).build()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); layout = null; outlineLayout = null }

    override fun onDraw(canvas: Canvas) {
        if (layout == null) build()
        val text = layout ?: return
        val stroke = outlineLayout ?: return
        canvas.save()
        canvas.translate((width - text.width) / 2f, height - text.height - height * .06f)
        stroke.draw(canvas)
        text.draw(canvas)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = false

    private companion object { const val MAX_CHARACTERS = 400 }
}

/** A quarter-open ring turning in the RenderThread; there is no per-frame Kotlin work while it spins. */
internal class SpinnerView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Color.WHITE }
    private val oval = RectF()
    private var animator: ObjectAnimator? = null

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    override fun onDraw(canvas: Canvas) {
        paint.strokeWidth = Stroke.ICON * dp
        val inset = paint.strokeWidth
        oval.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(oval, 0f, 270f, false, paint)
    }

    fun spin(on: Boolean) {
        if (on && isShown) {
            if (animator == null) animator = ObjectAnimator.ofFloat(this, ROTATION, 0f, 360f).apply {
                duration = 900
                repeatCount = ObjectAnimator.INFINITE
                interpolator = LinearInterpolator()
            }
            if (animator?.isStarted != true) animator?.start()
        } else {
            animator?.cancel()
            rotation = 0f
        }
    }

    override fun onDetachedFromWindow() { animator?.cancel(); super.onDetachedFromWindow() }
}

/** A vertical meter at the side of the picture (brightness or volume) while a finger drags; it fades away by itself. */
internal class LevelCard(context: Context) : LinearLayout(context) {
    private val dp = resources.displayMetrics.density
    private val label = TextView(context).apply { setTextColor(Color.WHITE); textSize = TextScale.CAPTION; gravity = Gravity.CENTER }
    private val value = TextView(context).apply {
        setTextColor(Color.WHITE); textSize = TextScale.BODY; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val meter = object : View(context) {
        var level = 0f
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val track = RectF()
        override fun onDraw(canvas: Canvas) {
            val radius = width / 2f
            paint.color = 0x55ffffff
            track.set(0f, 0f, width.toFloat(), height.toFloat())
            canvas.drawRoundRect(track, radius, radius, paint)
            paint.color = Color.WHITE
            track.set(0f, height * (1f - level), width.toFloat(), height.toFloat())
            canvas.drawRoundRect(track, radius, radius, paint)
        }
    }
    private val hide = Runnable { animate().alpha(0f).setDuration(200).withEndAction { visibility = INVISIBLE }.start() }

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding((16 * dp).toInt(), (14 * dp).toInt(), (16 * dp).toInt(), (14 * dp).toInt())
        background = GradientDrawable().apply { setColor(0xb3000000.toInt()); cornerRadius = 16 * dp }
        addView(label, LayoutParams(-2, -2))
        addView(meter, LayoutParams((8 * dp).toInt(), (140 * dp).toInt()).apply { topMargin = (10 * dp).toInt(); bottomMargin = (10 * dp).toInt() })
        addView(value, LayoutParams(-2, -2))
        alpha = 0f
        visibility = INVISIBLE
    }

    fun show(text: String, level: Float) {
        label.text = text
        value.text = "${(level * 100).roundToInt()}%"
        meter.level = level.coerceIn(0f, 1f)
        meter.invalidate()
        removeCallbacks(hide)
        animate().cancel()
        visibility = VISIBLE
        alpha = 1f
        postDelayed(hide, 1_200)
    }

    /** The finger lifted: let the meter go soon. */
    fun release() { removeCallbacks(hide); postDelayed(hide, 600) }
}

/** A short-lived note on the picture ("+20 s", a play or pause sign) that fades out on its own. */
internal class FlashView(context: Context, content: View) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private val hide = Runnable { animate().alpha(0f).setDuration(220).withEndAction { visibility = INVISIBLE }.start() }

    init {
        background = GradientDrawable().apply { setColor(0x99000000.toInt()); cornerRadius = 40 * dp }
        addView(content, LayoutParams(-2, -2, Gravity.CENTER))
        alpha = 0f
        visibility = INVISIBLE
    }

    fun flash(holdMillis: Long = 650) {
        removeCallbacks(hide)
        animate().cancel()
        visibility = VISIBLE
        alpha = 1f
        postDelayed(hide, holdMillis)
    }

    fun clear() { removeCallbacks(hide); animate().cancel(); alpha = 0f; visibility = INVISIBLE }
}
