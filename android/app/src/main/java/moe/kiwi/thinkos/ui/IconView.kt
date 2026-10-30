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

/** Each moving mask is a cached hardware layer; ticks change only composition properties. */
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
            val reusable =
                field in weatherNames &&
                    value in weatherNames &&
                    value != "clear-night" &&
                    sprite?.parent === this
            field = value
            contentDescription = value
            val ready =
                if (reusable)
                    WeatherAtlas.get(
                        value,
                        min(width, height),
                        (phase * 30).toLong() / 64,
                        tileGlyph,
                    )
                else null
            if (ready != null) {
                atlas = ready
                sprite!!.invalidate()
                position()
            } else rebuild()
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

    private val parts = mutableListOf<Mask>()
    private val layerPaint = Paint()
    private val drops = mutableListOf<Mask>()
    private var cloud: Mask? = null
    private var rays: Mask? = null
    private var sprite: View? = null
    private var atlas: WeatherAtlas.Frames? = null
    private val viewport =
        object : ViewGroup(context) {
            override fun onMeasure(w: Int, h: Int) {
                val n = MeasureSpec.getSize(w)
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    val spec =
                        if (child === sprite)
                            MeasureSpec.makeMeasureSpec(n * 8, MeasureSpec.EXACTLY)
                        else w
                    child.measure(spec, spec)
                }
                setMeasuredDimension(n, n)
            }

            override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
                for (i in 0 until childCount) {
                    val child = getChildAt(i)
                    child.layout(0, 0, child.measuredWidth, child.measuredHeight)
                }
            }
        }

    private inner class Mask(val key: String, private val partly: Boolean = false) : View(context) {
        private val brush =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        private val target = RectF(0f, 0f, 128f, 128f)

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        override fun onDraw(c: Canvas) {
            c.save()
            c.scale(width / 128f, height / 128f)
            if (partly) {
                c.clipRect(0f, 0f, 128f, if (key == "moon") 49f else 48f)
                c.translate(if (key == "moon") 10f else 12f, if (key == "moon") 2f else 4f)
                c.scale(.65f, .65f)
            }
            c.drawBitmap(IconArt.mask(if (tileGlyph) "metro:$key" else key), null, target, brush)
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
        parts.clear()
        drops.clear()
        cloud = null
        rays = null
        sprite = null
        atlas = null
        fun add(key: String, partly: Boolean = false, parent: ViewGroup = viewport): Mask =
            Mask(key, partly).also {
                parent.addView(it)
                parts += it
            }
        fun sun(partly: Boolean = false) {
            add("sun-disc", partly)
            rays = add("sun-rays", partly)
        }
        fun cloud(raised: Boolean = false) {
            cloud = add("cloud")
            cloud!!.tag = raised
        }
        when (name) {
            "sun",
            "clear-day" -> sun()
            "moon",
            "clear-night" -> add("moon")
            "partly-day" -> {
                sun(true)
                cloud()
            }
            "partly-night" -> {
                add("moon", true)
                cloud()
            }
            "cloud" -> cloud()
            "fog" -> {
                cloud(true)
                add("fog")
            }
            "rain",
            "shower",
            "snow" -> {
                cloud(true)
                val precipitation =
                    object : ViewGroup(context) {
                            override fun dispatchDraw(c: Canvas) {
                                c.save()
                                c.clipRect(
                                    width * 18 / 128f,
                                    height * 76 / 128f,
                                    width * 112 / 128f,
                                    height * 121 / 128f,
                                )
                                super.dispatchDraw(c)
                                c.restore()
                            }

                            override fun onMeasure(w: Int, h: Int) {
                                for (i in 0 until childCount) getChildAt(i).measure(w, h)
                                setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h))
                            }

                            override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
                                for (i in 0 until childCount) getChildAt(i)
                                    .layout(0, 0, width, height)
                            }
                        }
                        .apply { clipChildren = false }
                viewport.addView(precipitation)
                repeat(3) {
                    drops +=
                        add(
                            if (name == "snow") "flake"
                            else if (name == "shower") "shower-drop" else "drop",
                            parent = precipitation,
                        )
                }
            }
            "thunder" -> {
                cloud(true)
                add("bolt")
            }
            in staticNames -> add(name)
            "" -> Unit
            else -> add("unknown")
        }
        if (name in weatherNames && name != "clear-night") {
            sprite =
                object : View(context) {
                        private val brush = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

                        init {
                            setLayerType(LAYER_TYPE_HARDWARE, null)
                            visibility = INVISIBLE
                            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                        }

                        override fun onDraw(canvas: Canvas) {
                            atlas?.let { canvas.drawBitmap(it.bitmap, 0f, 0f, brush) }
                        }
                    }
                    .also { viewport.addView(it) }
        }
        tint()
        position()
        requestLayout()
    }

    private fun tint() {
        layerPaint.colorFilter = PorterDuffColorFilter(palette.accent, PorterDuff.Mode.SRC_IN)
        if (sprite?.parent !== this) parts.forEach { it.setLayerPaint(layerPaint) }
        sprite?.setLayerPaint(layerPaint)
    }

    private fun position() {
        val n = min(width, height)
        val scale = n / 128f
        if (sprite != null && n in 1..256) {
            val frame = (phase * 30).toLong().coerceAtLeast(0)
            val chunk = frame / 64
            val ready = WeatherAtlas.get(name, n, chunk, tileGlyph)
            if (frame % 64 >= 24) WeatherAtlas.get(name, n, chunk + 1, tileGlyph)
            if (ready != null) {
                if (atlas !== ready) {
                    atlas = ready
                    sprite!!.invalidate()
                }
                if (sprite!!.parent !== this) {
                    viewport.removeView(sprite)
                    removeView(viewport)
                    addView(sprite)
                    sprite!!.measure(
                        MeasureSpec.makeMeasureSpec(n * 8, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(n * 8, MeasureSpec.EXACTLY),
                    )
                    val x = (width - n) / 2
                    val y = (height - n) / 2
                    sprite!!.layout(x, y, x + n * 8, y + n * 8)
                    sprite!!.visibility = VISIBLE
                    parts.clear()
                    drops.clear()
                    cloud = null
                    rays = null
                }
                sprite!!.translationX = -(frame % 8 * n).toFloat()
                sprite!!.translationY = -(frame % 64 / 8 * n).toFloat()
                return
            }
        }
        cloud?.let {
            it.translationX = (sin(phase * .7) * 2 * scale).toFloat()
            it.translationY = if (it.tag == true) -14 * scale else 0f
        }
        rays?.let {
            it.pivotX = (if (name == "partly-day") 53.6f else 64f) * scale
            it.pivotY = (if (name == "partly-day") 45.6f else 64f) * scale
            it.rotation = (sin(phase * .35) * 9).toFloat()
        }
        drops.forEachIndexed { i, view ->
            val snow = name == "snow"
            val step = (phase / (if (snow) 3.8 else 1.8) + i * .31) % 1
            view.translationX =
                (43f + i * 21f + (if (snow) (sin(phase + i) * 3).toFloat() else 0f) - 64f) * scale
            view.translationY = (78f + (step * 39).toFloat() - 64f) * scale
            view.alpha = sin(step * Math.PI).toFloat().coerceIn(0f, 1f)
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
        if (sprite?.parent === this)
            sprite!!.measure(
                MeasureSpec.makeMeasureSpec(n * 8, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(n * 8, MeasureSpec.EXACTLY),
            )
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (min(w, h) > 256 && sprite?.parent === this) rebuild()
    }

    override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = min(width, height)
        val x = (width - n) / 2
        val y = (height - n) / 2
        viewport.layout(x, y, x + n, y + n)
        if (sprite?.parent === this) sprite!!.layout(x, y, x + n * 8, y + n * 8)
        clipBounds = Rect(x, y, x + n, y + n)
        position()
    }

    companion object {
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
        val staticNames =
            setOf(
                "calendar",
                "clock",
                "music",
                "photo",
                "bell",
                "settings",
                "home",
                "folder",
                "list",
                "star",
                "heart",
                "message",
                "play",
                "battery",
            )
        val names: Set<String> = weatherNames + staticNames + setOf("sun", "moon", "unknown")
    }
}

/**
 * Two-second full-frame sheets are shared by icon, pixel size and phase. Moving a cached sheet
 * behind a clipped viewport avoids both per-instance mask commands and layer refreshes. Raster work
 * stays off the main thread. Eviction drops references; never recycle an in-flight bitmap.
 */
private object WeatherAtlas {
    data class Frames(val bitmap: Bitmap)

    private data class Key(val name: String, val size: Int, val chunk: Long, val metro: Boolean)

    private val cache = object : LinkedHashMap<Key, Frames>(16, .75f, true) {}
    private val pending = mutableSetOf<Key>()
    private val worker =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(
                    {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND
                        )
                        r.run()
                    },
                    "weather-frames",
                )
                .apply { isDaemon = true }
        }

    @Synchronized
    fun get(name: String, size: Int, chunk: Long, metro: Boolean): Frames? {
        val key = Key(name, size, chunk, metro)
        cache[key]?.let {
            return it
        }
        if (pending.add(key))
            worker.execute {
                val bitmap = Bitmap.createBitmap(size * 8, size * 8, Bitmap.Config.ALPHA_8)
                bitmap.density = Bitmap.DENSITY_NONE
                val canvas = Canvas(bitmap)
                repeat(64) { frame ->
                    canvas.save()
                    canvas.translate((frame % 8 * size).toFloat(), (frame / 8 * size).toFloat())
                    canvas.clipRect(0, 0, size, size)
                    canvas.scale(size / 128f, size / 128f)
                    draw(canvas, name, (chunk * 64 + frame) / 30.0, metro)
                    canvas.restore()
                }
                bitmap.prepareToDraw()
                synchronized(this) {
                    pending.remove(key)
                    cache[key] = Frames(bitmap)
                    while (
                        cache.size > 10 ||
                            cache.values.sumOf { it.bitmap.allocationByteCount.toLong() } >
                                12L * 1024 * 1024
                    ) cache.remove(cache.keys.first())
                }
            }
        return null
    }

    private fun draw(c: Canvas, name: String, phase: Double, metro: Boolean) {
        val p =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { color = Color.WHITE }
        val target = RectF(0f, 0f, 128f, 128f)
        fun mask(key: String) {
            c.drawBitmap(IconArt.mask(if (metro) "metro:$key" else key), null, target, p)
        }
        fun cloud(raised: Boolean = false) {
            c.save()
            c.translate((sin(phase * .7) * 2).toFloat(), if (raised) -14f else 0f)
            mask("cloud")
            c.restore()
        }
        fun sun(partly: Boolean = false) {
            c.save()
            if (partly) {
                c.clipRect(0f, 0f, 128f, 48f)
                c.translate(12f, 4f)
                c.scale(.65f, .65f)
            }
            mask("sun-disc")
            c.rotate((sin(phase * .35) * 9).toFloat(), 64f, 64f)
            mask("sun-rays")
            c.restore()
        }
        when (name) {
            "clear-day" -> sun()
            "partly-day" -> {
                sun(true)
                cloud()
            }
            "partly-night" -> {
                c.save()
                c.clipRect(0f, 0f, 128f, 49f)
                c.translate(10f, 2f)
                c.scale(.65f, .65f)
                mask("moon")
                c.restore()
                cloud()
            }
            "cloud" -> cloud()
            "fog" -> {
                cloud(true)
                mask("fog")
            }
            "thunder" -> {
                cloud(true)
                mask("bolt")
            }
            "rain",
            "shower",
            "snow" -> {
                cloud(true)
                c.save()
                c.clipRect(18f, 76f, 112f, 121f)
                repeat(3) { i ->
                    val snow = name == "snow"
                    val step = (phase / (if (snow) 3.8 else 1.8) + i * .31) % 1
                    c.save()
                    c.translate(
                        43f + i * 21f + (if (snow) (sin(phase + i) * 3).toFloat() else 0f) - 64f,
                        78f + (step * 39).toFloat() - 64f,
                    )
                    p.alpha = (sin(step * Math.PI) * 255).toInt().coerceIn(0, 255)
                    mask(if (snow) "flake" else if (name == "shower") "shower-drop" else "drop")
                    c.restore()
                }
                c.restore()
            }
        }
    }
}

/** Both OS glyph families have a bounded set of 256px alpha masks (under 4 MiB). */
private object IconArt {
    private val masks = mutableMapOf<String, Bitmap>()

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
            if (
                metro &&
                    name in
                        setOf(
                            "sun-disc",
                            "moon",
                            "cloud",
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
                "sun-disc" -> canvas.drawCircle(64f, 64f, 22f, paint)
                "sun-rays" ->
                    for (i in 0..7) {
                        val angle = i * Math.PI / 4
                        canvas.drawLine(
                            64f + (cos(angle) * 33).toFloat(),
                            64f + (sin(angle) * 33).toFloat(),
                            64f + (cos(angle) * 44).toFloat(),
                            64f + (sin(angle) * 44).toFloat(),
                            paint,
                        )
                    }
                "moon" -> {
                    path.moveTo(79f, 24f)
                    path.cubicTo(48f, 15f, 25f, 42f, 34f, 72f)
                    path.cubicTo(43f, 100f, 79f, 107f, 97f, 79f)
                    path.cubicTo(72f, 88f, 50f, 67f, 56f, 45f)
                    path.cubicTo(59f, 34f, 68f, 27f, 79f, 24f)
                    canvas.drawPath(path, paint)
                }
                "cloud" -> {
                    path.moveTo(33f, 85f)
                    path.cubicTo(12f, 85f, 11f, 57f, 32f, 54f)
                    path.cubicTo(32f, 28f, 65f, 24f, 76f, 46f)
                    path.cubicTo(100f, 38f, 116f, 61f, 104f, 77f)
                    path.cubicTo(100f, 84f, 93f, 85f, 86f, 85f)
                    path.close()
                    canvas.drawPath(path, paint)
                }
                "fog" ->
                    for (i in 0..2) canvas.drawLine(
                        29f + i % 2 * 8,
                        85f + i * 11,
                        100f - i % 2 * 8,
                        85f + i * 11,
                        paint,
                    )
                "drop",
                "shower-drop" ->
                    canvas.drawLine(66f, 59f, 62f, if (name == "drop") 68f else 72f, paint)
                "flake" ->
                    for (i in 0..2) {
                        val angle = i * Math.PI / 3
                        val x = (cos(angle) * 5).toFloat()
                        val y = (sin(angle) * 5).toFloat()
                        canvas.drawLine(64f - x, 64f - y, 64f + x, 64f + y, paint)
                    }
                "bolt" -> {
                    path.moveTo(70f, 78f)
                    path.lineTo(55f, 99f)
                    path.lineTo(69f, 99f)
                    path.lineTo(59f, 119f)
                    path.lineTo(83f, 93f)
                    path.lineTo(69f, 93f)
                    canvas.drawPath(path, paint)
                }
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
                "settings" -> {
                    canvas.drawCircle(64f, 64f, 28f, paint)
                    canvas.drawCircle(64f, 64f, 11f, paint)
                    for (i in 0..7) {
                        canvas.save()
                        canvas.rotate(i * 45f, 64f, 64f)
                        canvas.drawLine(64f, 23f, 64f, 35f, paint)
                        canvas.restore()
                    }
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
                else -> {
                    canvas.drawCircle(64f, 64f, 34f, paint)
                    canvas.drawLine(64f, 49f, 64f, 66f, paint)
                    canvas.drawPoint(64f, 80f, paint)
                }
            }
            bitmap
        }
}
