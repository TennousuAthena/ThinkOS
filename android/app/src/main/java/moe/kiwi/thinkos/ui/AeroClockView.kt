package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.TextView
import kotlin.math.cos
import kotlin.math.sin
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.tz.ZoneFormat
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONObject

/**
 * Aero's smooth analogue gadget. Dial and hands are recorded once; RenderThread rotates real
 * visible hands, without redrawing a window or polling the UI at 60fps.
 */
internal class AeroClockView(context: Context) : ViewGroup(context), RenderComponent {
    override val view: View
        get() = this

    private var palette = Palette.DARK
    private var active = false
    private var attention = Attention.AMBIENT
    private var lastSecond = -1L
    private val ease = LinearInterpolator()
    private val dial =
        object : View(context) {
                private val p =
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        strokeCap = Paint.Cap.ROUND
                    }

                override fun onDraw(c: Canvas) {
                    val cx = width / 2f
                    val cy = height / 2f
                    val r = minOf(width, height) * .44f
                    p.color = palette.secondary
                    p.alpha = 75
                    p.strokeWidth = resources.displayMetrics.density * 1.4f
                    c.drawCircle(cx, cy, r, p)
                    repeat(60) { i ->
                        val a = i * Math.PI / 30 - Math.PI / 2
                        val length = if (i % 5 == 0) r * .11 else r * .04
                        p.alpha = if (i % 5 == 0) 200 else 75
                        c.drawLine(
                            (cx + cos(a) * (r - length)).toFloat(),
                            (cy + sin(a) * (r - length)).toFloat(),
                            (cx + cos(a) * r * .96).toFloat(),
                            (cy + sin(a) * r * .96).toFloat(),
                            p,
                        )
                    }
                    p.style = Paint.Style.FILL
                    p.alpha = 255
                    p.color = palette.accent
                    c.drawCircle(cx, cy, r * .04f, p)
                    p.style = Paint.Style.STROKE
                }
            }
            .apply { setLayerType(LAYER_TYPE_HARDWARE, null) }

    private inner class Hand(val length: Float, val stroke: Float) : View(context) {
        private val p =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                strokeCap = Paint.Cap.ROUND
            }
        var radius = 0f

        init {
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }

        override fun onDraw(c: Canvas) {
            p.strokeWidth = radius * stroke
            c.drawLine(width / 2f, height - width / 2f, width / 2f, width / 2f, p)
        }
    }

    private val hands = listOf(Hand(.50f, .04f), Hand(.76f, .027f), Hand(.80f, .009f))
    private val waiting =
        TextView(context).apply {
            text = "--:--"
            textSize = 36f
            gravity = android.view.Gravity.CENTER
            visibility = GONE
        }

    init {
        addView(dial)
        hands.forEach(::addView)
        addView(waiting)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) = Unit

    override fun colors(value: Palette) {
        palette = value
        dial.invalidate()
        waiting.setTextColor(value.primary)
        hands.forEachIndexed { i, v ->
            v.setLayerPaint(
                Paint().apply {
                    colorFilter =
                        PorterDuffColorFilter(
                            if (i == 2) value.accent else value.primary,
                            PorterDuff.Mode.SRC_IN,
                        )
                }
            )
        }
    }

    override fun active(value: Boolean) {
        active = value
        if (!value) stop()
        lastSecond = -1
    }

    private fun stop() {
        hands.forEach { it.animate().cancel() }
    }

    override fun tick(monotonic: Long, attention: Attention) {
        if (this.attention != attention) {
            this.attention = attention
            lastSecond = -1
        }
        val wall = WallClock.now()
        if (wall / 1000 == lastSecond) return
        lastSecond = wall / 1000
        val synced = wall >= 1_577_836_800_000L
        waiting.visibility = if (synced) GONE else VISIBLE
        dial.visibility = if (synced) VISIBLE else GONE
        hands.forEach { it.visibility = if (synced) VISIBLE else GONE }
        if (!synced) {
            stop()
            return
        }
        val zone = Zones.home()
        val f = zone.fields(wall)
        val second = f.second + wall.mod(1000) / 1000f
        val angles =
            listOf(
                ((f.hour % 12) + f.minute / 60f + second / 3600) * 30,
                (f.minute + second / 60) * 6,
                second * 6,
            )
        val speed = listOf(1f / 120, .1f, 6f)
        val moving =
            active &&
                attention in setOf(Attention.AWAKE, Attention.ENGAGED) &&
                ValueAnimator.areAnimatorsEnabled()
        hands.forEachIndexed { i, v ->
            v.animate().cancel()
            v.rotation = angles[i]
            if (moving)
                v.animate()
                    .rotation(angles[i] + speed[i])
                    .setDuration(1000)
                    .setInterpolator(ease)
                    .setListener(null)
                    .setUpdateListener(null)
                    .withStartAction(null)
                    .withEndAction(null)
                    .start()
        }
        contentDescription = ZoneFormat.format("@timeSeconds", wall, zone)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onMeasure(w: Int, h: Int) {
        val width = MeasureSpec.getSize(w)
        val height = MeasureSpec.getSize(h)
        val n = minOf(width, height)
        val spec = MeasureSpec.makeMeasureSpec(n, MeasureSpec.EXACTLY)
        dial.measure(spec, spec)
        waiting.measure(spec, spec)
        for (hand in hands) {
            hand.radius = n * .44f
            val sw = kotlin.math.ceil(hand.radius * hand.stroke).toInt() + 2
            val sh = kotlin.math.ceil(hand.radius * hand.length).toInt() + sw
            hand.measure(
                MeasureSpec.makeMeasureSpec(sw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(sh, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(width, height)
    }

    override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val n = minOf(width, height)
        val x = (width - n) / 2
        val y = (height - n) / 2
        dial.layout(x, y, x + n, y + n)
        waiting.layout(x, y, x + n, y + n)
        for (hand in hands) {
            val sw = hand.measuredWidth
            val sh = hand.measuredHeight
            val left = (width - sw) / 2
            val top = (height / 2f - sh + sw / 2f).toInt()
            hand.layout(left, top, left + sw, top + sh)
            hand.pivotX = sw / 2f
            hand.pivotY = sh - sw / 2f
        }
    }
}
