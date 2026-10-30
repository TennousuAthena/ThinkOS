package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import moe.kiwi.thinkos.core.Attention
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Shared alpha masks are rasterised once; themes tint them without allocating new artwork. */
internal class IconView(context: Context) : View(context) {
    var palette = Palette.DARK
        set(value) { val repaint = field.accent != value.accent; field = value; if (repaint) invalidate() }
    var name = "sun"
        set(value) { if (field != value) { field = value; contentDescription = value; invalidate() } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val target = RectF(0f, 0f, 128f, 128f)
    private var phase = 0.0
    private var previous = 0L
    var animating = false
        private set

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    fun tick(now: Long, attention: Attention) {
        val active = name in weatherNames && name != "clear-night" &&
            (attention == Attention.AWAKE || attention == Attention.ENGAGED) && ValueAnimator.areAnimatorsEnabled()
        if (active && animating && now - previous < 1000L / 30) return // Keep weather at 30fps when a tile transition raises the scene clock to 60.
        if (active) {
            if (animating && previous != 0L) phase += (now - previous).coerceIn(0, 100) / 1000.0
            invalidate()
        } else if (animating) invalidate()
        animating = active
        previous = now
    }

    override fun onDraw(canvas: Canvas) {
        val size = min(width, height).toFloat()
        if (size <= 0) return
        paint.color = palette.accent
        paint.alpha = 255
        canvas.save()
        canvas.translate((width - size) / 2, (height - size) / 2)
        canvas.scale(size / 128, size / 128)
        fun mask(key: String) { canvas.drawBitmap(IconArt.mask(key), null, target, paint) }
        fun sun(partly: Boolean = false) {
            canvas.save()
            if (partly) { canvas.clipRect(0f, 0f, 128f, 48f); canvas.translate(12f, 4f); canvas.scale(.65f, .65f) }
            mask("sun-disc")
            canvas.rotate((sin(phase * .35) * 9).toFloat(), 64f, 64f)
            mask("sun-rays")
            canvas.restore()
        }
        fun moon(partly: Boolean = false) {
            canvas.save()
            if (partly) { canvas.clipRect(0f, 0f, 128f, 49f); canvas.translate(10f, 2f); canvas.scale(.65f, .65f) }
            mask("moon")
            canvas.restore()
        }
        fun cloud(raised: Boolean = false) {
            canvas.save()
            canvas.translate((sin(phase * .7) * 2).toFloat(), if (raised) -14f else 0f)
            mask("cloud")
            canvas.restore()
        }
        fun precipitation(snow: Boolean, showers: Boolean = false) {
            canvas.save(); canvas.clipRect(18f, 76f, 112f, 121f)
            for (i in 0..2) {
                val step = (phase / (if (snow) 3.8 else 1.8) + i * .31) % 1
                canvas.save()
                val x = 43f + i * 21f + if (snow) (sin(phase + i) * 3).toFloat() else 0f
                canvas.translate(x - 64f, 78f + (step * 39).toFloat() - 64f)
                paint.alpha = (sin(step * Math.PI) * 255).toInt().coerceIn(0, 255)
                mask(if (snow) "flake" else if (showers) "shower-drop" else "drop")
                canvas.restore()
            }
            paint.alpha = 255; canvas.restore()
        }
        when (name) {
            "sun", "clear-day" -> sun()
            "moon", "clear-night" -> moon()
            "partly-day" -> { sun(true); cloud() }
            "partly-night" -> { moon(true); cloud() }
            "cloud" -> cloud()
            "fog" -> { cloud(true); mask("fog") }
            "rain", "shower" -> { cloud(true); precipitation(false, name == "shower") }
            "snow" -> { cloud(true); precipitation(true) }
            "thunder" -> { cloud(true); mask("bolt") }
            in staticNames -> mask(name)
            else -> mask("unknown")
        }
        canvas.restore()
    }
    companion object {
        val weatherNames = setOf("clear-day", "clear-night", "partly-day", "partly-night", "cloud", "fog", "rain", "shower", "snow", "thunder")
        val staticNames = setOf("calendar", "clock", "music", "photo", "bell", "settings", "home", "folder", "list", "star", "heart", "message", "play", "battery")
        val names: Set<String> = weatherNames + staticNames + setOf("sun", "moon", "unknown")
    }
}

/** A bounded, theme-independent set of 256px alpha masks (under 1 MiB for the vocabulary). */
private object IconArt {
    private val masks = mutableMapOf<String, Bitmap>()
    fun mask(name: String): Bitmap = masks.getOrPut(name) {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ALPHA_8)
        val canvas = Canvas(bitmap).apply { scale(2f, 2f) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; strokeWidth = 3.2f
        }
        val path = Path()
        when (name) {
            "sun-disc" -> canvas.drawCircle(64f, 64f, 22f, paint)
            "sun-rays" -> for (i in 0..7) {
                val angle = i * Math.PI / 4
                canvas.drawLine(64f + (cos(angle) * 33).toFloat(), 64f + (sin(angle) * 33).toFloat(),
                    64f + (cos(angle) * 44).toFloat(), 64f + (sin(angle) * 44).toFloat(), paint)
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
                path.close(); canvas.drawPath(path, paint)
            }
            "fog" -> for (i in 0..2) canvas.drawLine(29f + i % 2 * 8, 85f + i * 11, 100f - i % 2 * 8, 85f + i * 11, paint)
            "drop", "shower-drop" -> canvas.drawLine(66f, 59f, 62f, if (name == "drop") 68f else 72f, paint)
            "flake" -> for (i in 0..2) {
                val angle = i * Math.PI / 3
                val x = (cos(angle) * 5).toFloat(); val y = (sin(angle) * 5).toFloat()
                canvas.drawLine(64f - x, 64f - y, 64f + x, 64f + y, paint)
            }
            "bolt" -> {
                path.moveTo(70f, 78f); path.lineTo(55f, 99f); path.lineTo(69f, 99f)
                path.lineTo(59f, 119f); path.lineTo(83f, 93f); path.lineTo(69f, 93f)
                canvas.drawPath(path, paint)
            }
            "calendar" -> {
                canvas.drawRoundRect(28f, 30f, 100f, 102f, 7f, 7f, paint)
                canvas.drawLine(28f, 52f, 100f, 52f, paint)
                canvas.drawLine(46f, 22f, 46f, 38f, paint); canvas.drawLine(82f, 22f, 82f, 38f, paint)
            }
            "music" -> {
                canvas.drawLine(53f, 85f, 53f, 34f, paint); canvas.drawLine(53f, 34f, 94f, 24f, paint)
                canvas.drawLine(94f, 24f, 94f, 76f, paint); canvas.drawOval(30f, 80f, 53f, 96f, paint); canvas.drawOval(71f, 71f, 94f, 87f, paint)
            }
            "photo" -> {
                canvas.drawRoundRect(22f, 28f, 106f, 100f, 5f, 5f, paint); canvas.drawCircle(84f, 48f, 8f, paint)
                path.moveTo(24f, 92f); path.lineTo(51f, 58f); path.lineTo(73f, 82f); path.lineTo(86f, 68f); path.lineTo(105f, 92f); canvas.drawPath(path, paint)
            }
            "bell" -> {
                path.moveTo(30f, 88f); path.quadTo(42f, 75f, 40f, 56f); path.cubicTo(40f, 20f, 88f, 20f, 88f, 56f)
                path.quadTo(86f, 75f, 98f, 88f); path.close(); canvas.drawPath(path, paint); canvas.drawArc(54f, 88f, 74f, 108f, 0f, 180f, false, paint)
            }
            "settings" -> {
                canvas.drawCircle(64f, 64f, 28f, paint); canvas.drawCircle(64f, 64f, 11f, paint)
                for (i in 0..7) { canvas.save(); canvas.rotate(i*45f, 64f, 64f); canvas.drawLine(64f, 23f, 64f, 35f, paint); canvas.restore() }
            }
            "home" -> {
                path.moveTo(20f, 58f); path.lineTo(64f, 23f); path.lineTo(108f, 58f); path.moveTo(34f, 48f); path.lineTo(34f, 101f)
                path.lineTo(54f, 101f); path.lineTo(54f, 73f); path.lineTo(74f, 73f); path.lineTo(74f, 101f); path.lineTo(94f, 101f); path.lineTo(94f, 48f); canvas.drawPath(path, paint)
            }
            "folder" -> {
                path.moveTo(22f, 100f); path.lineTo(22f, 28f); path.lineTo(53f, 28f); path.lineTo(65f, 41f); path.lineTo(106f, 41f); path.lineTo(106f, 100f); path.close(); canvas.drawPath(path, paint)
            }
            "list" -> for (i in 0..2) { val y=36f+i*28f; canvas.drawCircle(29f,y,3f,paint); canvas.drawLine(46f,y,101f,y,paint) }
            "star" -> {
                for (i in 0..9) { val a = i*Math.PI/5-Math.PI/2; val radius = if (i%2==0) 43 else 19
                    val x=64+(cos(a)*radius).toFloat(); val y=64+(sin(a)*radius).toFloat(); if(i==0) path.moveTo(x,y) else path.lineTo(x,y) }
                path.close(); canvas.drawPath(path,paint)
            }
            "heart" -> {
                path.moveTo(64f,103f); path.cubicTo(2f,65f,24f,7f,64f,41f); path.cubicTo(104f,7f,126f,65f,64f,103f); canvas.drawPath(path,paint)
            }
            "message" -> {
                path.moveTo(24f,28f); path.lineTo(104f,28f); path.lineTo(104f,87f); path.lineTo(54f,87f); path.lineTo(32f,104f); path.lineTo(32f,87f); path.lineTo(24f,87f); path.close(); canvas.drawPath(path,paint)
                canvas.drawLine(41f,48f,87f,48f,paint); canvas.drawLine(41f,66f,74f,66f,paint)
            }
            "play" -> { path.moveTo(44f,25f); path.lineTo(103f,64f); path.lineTo(44f,103f); path.close(); canvas.drawPath(path,paint) }
            "battery" -> { canvas.drawRoundRect(22f,42f,99f,86f,4f,4f,paint); canvas.drawLine(106f,54f,106f,74f,paint); canvas.drawRect(31f,51f,72f,77f,paint) }
            "clock" -> {
                canvas.drawCircle(64f, 64f, 38f, paint)
                canvas.drawLine(64f, 64f, 64f, 39f, paint); canvas.drawLine(64f, 64f, 81f, 77f, paint)
            }
            else -> {
                canvas.drawCircle(64f, 64f, 34f, paint)
                canvas.drawLine(64f, 49f, 64f, 66f, paint); canvas.drawPoint(64f, 80f, paint)
            }
        }
        bitmap
    }
}
