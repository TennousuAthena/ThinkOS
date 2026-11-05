package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import moe.kiwi.thinkos.core.Attention

/**
 * One icon: an Iconfont vector ([Glyphs]) or, for the few names still drawn in code, a bitmap mask ([IconArt]). The weather icons
 * move a little while somebody is looking: the sun sways, clouds drift. That is a transform of one cached hardware layer, so a tick
 * rasterises nothing and costs a property change.
 */
internal class IconView(context: Context) : ViewGroup(context) {
    var palette = Palette.DARK
        set(value) {
            val repaint = field.accent != value.accent
            field = value
            if (repaint) tint()
        }

    var name = "sun"
        set(value) {
            if (field == value) return
            field = value
            contentDescription = value
            rebuild()
        }

    var tileGlyph = false
        set(value) {
            if (field != value) {
                field = value
                rebuild()
            }
        }

    private var phase = 0.0
    private var previous = 0L
    var animating = false
        private set

    private val layerPaint = Paint()
    private var mask: Mask? = null
    private val viewport =
        object : ViewGroup(context) {
            override fun onMeasure(w: Int, h: Int) {
                val n = MeasureSpec.getSize(w)
                for (i in 0 until childCount) getChildAt(i).measure(w, w)
                setMeasuredDimension(n, n)
            }

            override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    child.layout(0, 0, child.measuredWidth, child.measuredHeight)
                }
            }
        }

    private inner class Mask(val key: String) : View(context) {
        private val brush =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        private val target = RectF(0f, 0f, 128f, 128f)
        /** Icons from Iconfont are vectors, drawn at the size they are shown at. The others are the bitmaps of [IconArt]. */
        private val glyph = Glyphs.drawable(context, key)?.also { it.setBounds(0, 0, 128, 128) }

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(c: Canvas) {
            c.save()
            c.scale(width / 128f, height / 128f)
            if (glyph != null) glyph.draw(c)
            else c.drawBitmap(IconArt.mask(if (tileGlyph) "metro:$key" else key), null, target, brush)
            c.restore()
        }
    }

    init {
        clipChildren = true
        addView(viewport)
        rebuild()
    }

    private fun rebuild() {
        removeAllViews()
        viewport.removeAllViews()
        addView(viewport)
        // A name nobody knows shows the question mark, never nothing: an unknown condition must not look like a clear sky.
        val key = when {
            name.isEmpty() -> null
            Glyphs.has(name) || name in drawnNames || name.startsWith("moon-phase-") -> name
            else -> "unknown"
        }
        mask = key?.let { Mask(it).also(viewport::addView) }
        tint()
        position()
        requestLayout()
    }

    private fun tint() {
        layerPaint.colorFilter = PorterDuffColorFilter(palette.accent, PorterDuff.Mode.SRC_IN)
        mask?.setLayerPaint(layerPaint)
    }

    private fun position() {
        val m = mask ?: return
        val n = min(width, height)
        when (name) {
            // The rays are a ring around a round sun, so swaying the whole icon moves only the rays.
            "clear-day" -> {
                m.pivotX = n / 2f
                m.pivotY = n / 2f
                m.rotation = (sin(phase * .35) * 9).toFloat()
            }
            in weatherNames -> m.translationX = (sin(phase * .7) * 2 * (n / 128f)).toFloat()
        }
    }

    fun tick(now: Long, attention: Attention) {
        val active =
            name in weatherNames &&
                name != "clear-night" &&
                attention in setOf(Attention.AWAKE, Attention.ENGAGED) &&
                ValueAnimator.areAnimatorsEnabled()
        if (active && animating && now - previous < 1000L / 30) return
        if (active && animating && previous != 0L)
            phase += (now - previous).coerceIn(0, 100) / 1000.0
        if (active) position()
        animating = active
        previous = now
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        val n = min(width, height)
        viewport.measure(
            MeasureSpec.makeMeasureSpec(n, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(n, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, height)
    }

    override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = min(width, height)
        val x = (width - n) / 2
        val y = (height - n) / 2
        viewport.layout(x, y, x + n, y + n)
        clipBounds = Rect(x, y, x + n, y + n)
        position()
    }

    companion object {
        /** The weather icons; all but the night sky move a little while somebody is looking. */
        val weatherNames =
            setOf(
                "clear-day",
                "clear-night",
                "partly-day",
                "partly-night",
                "cloud",
                "fog",
                "rain",
                "shower",
                "snow",
                "thunder",
            )
        /** The names still drawn in code ([IconArt]). Every other name comes from Iconfont ([Glyphs]). */
        val drawnNames =
            setOf(
                "calendar",
                "clock",
                "music",
                "photo",
                "bell",
                "home",
                "folder",
                "list",
                "star",
                "heart",
                "message",
                "play",
                "battery",
            )
        val names: Set<String> = drawnNames + Glyphs.names
    }
}

/** The icons still drawn in code, in two families (outline and metro solid), as a bounded set of 256px alpha masks. */
private object IconArt {
    private val masks = mutableMapOf<String, Bitmap>()
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** Metro tile glyphs are solid shapes with cut-outs, not outlines. */
    private fun drawSolid(name: String, canvas: Canvas, paint: Paint): Boolean {
        val fill = Paint(paint).apply { style = Paint.Style.FILL }
        cut.style = Paint.Style.STROKE
        cut.strokeWidth = 7f
        when (name) {
            "clock" -> {
                canvas.drawCircle(64f, 64f, 42f, fill)
                canvas.drawLine(64f, 64f, 64f, 36f, cut)
                canvas.drawLine(64f, 64f, 84f, 78f, cut)
            }
            "calendar" -> {
                canvas.drawRoundRect(24f, 30f, 104f, 106f, 7f, 7f, fill)
                canvas.drawRoundRect(40f, 18f, 50f, 40f, 4f, 4f, fill)
                canvas.drawRoundRect(78f, 18f, 88f, 40f, 4f, 4f, fill)
                cut.strokeWidth = 4f
                canvas.drawLine(24f, 56f, 104f, 56f, cut)
                cut.style = Paint.Style.FILL
                for (row in 0..1) for (column in 0..2) {
                    val x = 38f + column * 20f; val y = 68f + row * 18f
                    canvas.drawRect(x, y, x + 12f, y + 11f, cut)
                }
            }
            "photo" -> {
                canvas.drawRoundRect(20f, 26f, 108f, 102f, 7f, 7f, fill)
                cut.style = Paint.Style.FILL
                canvas.drawCircle(84f, 50f, 9f, cut)
                cut.style = Paint.Style.STROKE
                cut.strokeWidth = 6f
                val ridge = Path().apply {
                    moveTo(24f, 94f); lineTo(51f, 62f); lineTo(72f, 84f); lineTo(86f, 70f); lineTo(104f, 90f)
                }
                canvas.drawPath(ridge, cut)
            }
            "music" -> {
                canvas.drawOval(26f, 76f, 54f, 98f, fill)
                canvas.drawOval(70f, 68f, 98f, 90f, fill)
                val stems = Paint(fill).apply { style = Paint.Style.STROKE; strokeWidth = 7f }
                canvas.drawLine(51f, 86f, 51f, 34f, stems)
                canvas.drawLine(95f, 78f, 95f, 26f, stems)
                canvas.drawRect(48f, 24f, 98f, 40f, fill)
            }
            else -> return false
        }
        return true
    }

    /** `moon-phase-<w|n>-<0..100>`: lit fraction in tens of percent; w is the northern waxing (lit right) side. */
    private fun drawMoonPhase(name: String, canvas: Canvas, paint: Paint) {
        val parts = name.split('-')
        val waxing = parts.getOrNull(2) != "n"
        val lit = ((parts.getOrNull(3)?.toIntOrNull() ?: 50).coerceIn(0, 100)) / 100f
        val cx = 64f; val cy = 64f; val r = 40f
        val shape = Paint(paint).apply { style = Paint.Style.FILL }
        // The dark side stays faintly visible so a new moon is still a disc.
        canvas.drawCircle(cx, cy, r, Paint(shape).apply { alpha = 70 })
        if (lit <= .02f) return
        canvas.save()
        if (!waxing) canvas.scale(-1f, 1f, cx, cy)
        if (lit >= .98f) canvas.drawCircle(cx, cy, r, shape)
        else {
            val rx = r * kotlin.math.abs(1f - 2f * lit)
            val path = Path()
            path.arcTo(RectF(cx - r, cy - r, cx + r, cy + r), -90f, 180f)
            path.arcTo(RectF(cx - rx, cy - r, cx + rx, cy + r), 90f, if (lit < .5f) -180f else 180f)
            path.close()
            canvas.drawPath(path, shape)
        }
        canvas.restore()
    }

    @Synchronized
    fun mask(key: String): Bitmap =
        masks.getOrPut(key) {
            val metro = key.startsWith("metro:")
            val name = key.removePrefix("metro:")
            val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ALPHA_8)
            val canvas = Canvas(bitmap).apply { scale(2f, 2f) }
            val paint =
                Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = android.graphics.Color.WHITE
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = if (metro) 6f else 3.2f
                }
            val path = Path()
            if (metro && drawSolid(name, canvas, paint)) return@getOrPut bitmap
            if (name.startsWith("moon-phase-")) {
                drawMoonPhase(name, canvas, paint)
                return@getOrPut bitmap
            }
            if (
                metro &&
                    name in
                        setOf(
                            "folder",
                            "star",
                            "heart",
                            "play",
                            "bell",
                            "message",
                            "home",
                        )
            )
                paint.style = Paint.Style.FILL
            when (name) {
                "calendar" -> {
                    canvas.drawRoundRect(28f, 30f, 100f, 102f, 7f, 7f, paint)
                    canvas.drawLine(28f, 52f, 100f, 52f, paint)
                    canvas.drawLine(46f, 22f, 46f, 38f, paint)
                    canvas.drawLine(82f, 22f, 82f, 38f, paint)
                }
                "music" -> {
                    canvas.drawLine(53f, 85f, 53f, 34f, paint)
                    canvas.drawLine(53f, 34f, 94f, 24f, paint)
                    canvas.drawLine(94f, 24f, 94f, 76f, paint)
                    canvas.drawOval(30f, 80f, 53f, 96f, paint)
                    canvas.drawOval(71f, 71f, 94f, 87f, paint)
                }
                "photo" -> {
                    canvas.drawRoundRect(22f, 28f, 106f, 100f, 5f, 5f, paint)
                    canvas.drawCircle(84f, 48f, 8f, paint)
                    path.moveTo(24f, 92f)
                    path.lineTo(51f, 58f)
                    path.lineTo(73f, 82f)
                    path.lineTo(86f, 68f)
                    path.lineTo(105f, 92f)
                    canvas.drawPath(path, paint)
                }
                "bell" -> {
                    path.moveTo(30f, 88f)
                    path.quadTo(42f, 75f, 40f, 56f)
                    path.cubicTo(40f, 20f, 88f, 20f, 88f, 56f)
                    path.quadTo(86f, 75f, 98f, 88f)
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawArc(54f, 88f, 74f, 108f, 0f, 180f, false, paint)
                }
                "home" -> {
                    path.moveTo(20f, 58f)
                    path.lineTo(64f, 23f)
                    path.lineTo(108f, 58f)
                    path.moveTo(34f, 48f)
                    path.lineTo(34f, 101f)
                    path.lineTo(54f, 101f)
                    path.lineTo(54f, 73f)
                    path.lineTo(74f, 73f)
                    path.lineTo(74f, 101f)
                    path.lineTo(94f, 101f)
                    path.lineTo(94f, 48f)
                    canvas.drawPath(path, paint)
                }
                "folder" -> {
                    path.moveTo(22f, 100f)
                    path.lineTo(22f, 28f)
                    path.lineTo(53f, 28f)
                    path.lineTo(65f, 41f)
                    path.lineTo(106f, 41f)
                    path.lineTo(106f, 100f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "list" ->
                    for (i in 0..2) {
                        val y = 36f + i * 28f
                        canvas.drawCircle(29f, y, 3f, paint)
                        canvas.drawLine(46f, y, 101f, y, paint)
                    }
                "star" -> {
                    for (i in 0..9) {
                        val a = i * Math.PI / 5 - Math.PI / 2
                        val radius = if (i % 2 == 0) 43 else 19
                        val x = 64 + (cos(a) * radius).toFloat()
                        val y = 64 + (sin(a) * radius).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "heart" -> {
                    path.moveTo(64f, 103f)
                    path.cubicTo(2f, 65f, 24f, 7f, 64f, 41f)
                    path.cubicTo(104f, 7f, 126f, 65f, 64f, 103f)
                    canvas.drawPath(path, paint)
                }
                "message" -> {
                    path.moveTo(24f, 28f)
                    path.lineTo(104f, 28f)
                    path.lineTo(104f, 87f)
                    path.lineTo(54f, 87f)
                    path.lineTo(32f, 104f)
                    path.lineTo(32f, 87f)
                    path.lineTo(24f, 87f)
                    path.close()
                    canvas.drawPath(path, paint)
                    canvas.drawLine(41f, 48f, 87f, 48f, paint)
                    canvas.drawLine(41f, 66f, 74f, 66f, paint)
                }
                "play" -> {
                    path.moveTo(44f, 25f)
                    path.lineTo(103f, 64f)
                    path.lineTo(44f, 103f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "battery" -> {
                    canvas.drawRoundRect(22f, 42f, 99f, 86f, 4f, 4f, paint)
                    canvas.drawLine(106f, 54f, 106f, 74f, paint)
                    canvas.drawRect(31f, 51f, 72f, 77f, paint)
                }
                "clock" -> {
                    canvas.drawCircle(64f, 64f, 38f, paint)
                    canvas.drawLine(64f, 64f, 64f, 39f, paint)
                    canvas.drawLine(64f, 64f, 81f, 77f, paint)
                }
            }
            bitmap
        }
}
