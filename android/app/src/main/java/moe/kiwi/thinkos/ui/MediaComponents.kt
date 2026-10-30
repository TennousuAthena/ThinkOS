package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.widget.LinearLayout
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.media.MediaEngine
import org.json.JSONObject

/** OS media controls call the player directly. Lua provides data and never handles these clicks. */
object MediaComponents {
    fun create(context: Context, pluginId: String, declaration: JSONObject, engine: MediaEngine,
               capabilityCheck: (String, String) -> Boolean): RenderComponent? {
        val allowed = { capabilityCheck(pluginId, "media") }
        if (!allowed()) return null
        return when (declaration.optString("type")) {
            "controls" -> Controls(context, engine, allowed)
            "video" -> Video(context, declaration.optString("fit", "contain"), engine, allowed)
            else -> null
        }
    }

    private class Controls(context: Context, engine: MediaEngine, private val allowed: () -> Boolean) : RenderComponent {
        private val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = (72 * resources.displayMetrics.density).toInt()
        }
        private val previous = Button(context, -1) { if (allowed()) engine.prev() }
        private val toggle = Button(context, 0) { if (allowed()) engine.toggle() }
        private val next = Button(context, 1) { if (allowed()) engine.next() }
        override val view: View get() = row
        override val defaultWeight = 0.0
        override val interactive = true
        override val handlesTap = true
        init {
            val size = (64 * context.resources.displayMetrics.density).toInt()
            listOf(previous, toggle, next).forEach {
                row.addView(it, LinearLayout.LayoutParams(size, size).apply {
                    leftMargin = size / 6; rightMargin = size / 6
                })
            }
            localeChanged()
        }
        override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
            val media = snapshot.optJSONObject("media")
            val enabled = allowed() && (media?.optInt("count", 0) ?: 0) > 0
            listOf(previous, toggle, next).forEach { it.isEnabled = enabled; it.alpha = if (enabled) 1f else .35f }
            toggle.playing = media?.optString("status") in setOf("playing", "buffering")
            localeChanged()
        }
        override fun colors(value: Palette) { listOf(previous, toggle, next).forEach { it.color = value.primary } }
        override fun localeChanged() {
            previous.contentDescription = row.context.getString(R.string.media_previous)
            toggle.contentDescription = row.context.getString(if (toggle.playing) R.string.media_pause else R.string.media_play)
            next.contentDescription = row.context.getString(R.string.media_next)
        }
    }

    private class Button(context: Context, private val direction: Int, action: () -> Unit) : View(context) {
        var playing = false
            set(value) { if (field != value) { field = value; invalidate() } }
        var color = Palette.DARK.primary
            set(value) { field = value; invalidate() }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val path = Path()
        init { isClickable = true; isFocusable = true; setOnClickListener { action() } }
        override fun onDraw(canvas: Canvas) {
            paint.color = color
            paint.alpha = if (isPressed) 150 else 255
            val cx = width / 2f
            val cy = height / 2f
            val size = minOf(width, height) * .20f
            if (direction == 0 && playing) {
                canvas.drawRect(cx - size, cy - size, cx - size * .3f, cy + size, paint)
                canvas.drawRect(cx + size * .3f, cy - size, cx + size, cy + size, paint)
            } else {
                val sign = if (direction < 0) -1 else 1
                path.rewind()
                path.moveTo(cx + sign * size, cy)
                path.lineTo(cx - sign * size * .65f, cy - size)
                path.lineTo(cx - sign * size * .65f, cy + size)
                path.close()
                canvas.drawPath(path, paint)
                if (direction != 0) {
                    val x = cx + sign * size * 1.05f
                    canvas.drawRect(x - size * .12f, cy - size, x + size * .12f, cy + size, paint)
                }
            }
        }
    }

    private class Video(context: Context, private val fit: String, private val engine: MediaEngine,
                        private val allowed: () -> Boolean) : RenderComponent {
        private var attached = false
        private val visible = Rect()
        private val transform = Matrix()
        private var videoWidth = 0.0
        private var videoHeight = 0.0
        private val texture = object : TextureView(context) {
            override fun onDetachedFromWindow() { disconnect(); super.onDetachedFromWindow() }
            override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
                super.onSizeChanged(w, h, oldw, oldh)
                updateTransform()
            }
        }.apply { contentDescription = context.getString(R.string.media_video); isOpaque = false }
        override val view: View get() = texture
        override fun bind(snapshot: JSONObject, variant: String, strings: (String) -> String?) {
            val media = snapshot.optJSONObject("media")
            videoWidth = (media?.optDouble("videoWidth", 0.0) ?: 0.0) * (media?.optDouble("pixelRatio", 1.0) ?: 1.0)
            videoHeight = media?.optDouble("videoHeight", 0.0) ?: 0.0
            texture.visibility = if (allowed() && media?.optString("kind") == "video") View.VISIBLE else View.INVISIBLE
            updateTransform()
            connectIfVisible()
        }
        override fun colors(value: Palette) { /* The stage supplies the letterbox background. */ }
        override fun tick(monotonic: Long, attention: Attention) { connectIfVisible() }
        override fun localeChanged() { texture.contentDescription = texture.context.getString(R.string.media_video) }
        private fun updateTransform() {
            if (texture.width <= 0 || texture.height <= 0 || videoWidth <= 0 || videoHeight <= 0) return
            val ratio = videoWidth / videoHeight / (texture.width.toDouble() / texture.height)
            val sx = if (fit == "cover") maxOf(1.0, ratio) else minOf(1.0, ratio)
            val sy = if (fit == "cover") maxOf(1.0, 1 / ratio) else minOf(1.0, 1 / ratio)
            transform.setScale(sx.toFloat(), sy.toFloat(), texture.width / 2f, texture.height / 2f)
            texture.setTransform(transform)
        }
        private fun connectIfVisible() {
            // A preloaded neighboring page must not take the current page's video surface.
            val shown = allowed() && texture.isAttachedToWindow && texture.isShown && texture.getGlobalVisibleRect(visible) &&
                visible.width().toLong() * visible.height() > texture.width.toLong() * texture.height / 2
            if (shown && !attached) { engine.attachVideo(texture); attached = true }
            else if (!shown) disconnect()
        }
        private fun disconnect() { if (attached) engine.detachVideo(texture); attached = false }
    }
}
