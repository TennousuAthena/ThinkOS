package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.anim.ClockTicker
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.plugin.PluginWidget
import moe.kiwi.thinkos.tz.ZoneFormat
import org.json.JSONArray
import org.json.JSONObject

/** Fixed API-5 desktop. Glass is a recorded gradient, never a live blur or a web surface. */
internal class AeroHomeView(
    context: Context,
    private val events: PageEvents,
    private val state: (String) -> JSONObject,
    private val variant: () -> String,
) : ViewGroup(context), ClockTicker.Listener {
    private data class Window(
        val owner: String,
        val opens: String?,
        val view: AeroWindow,
        val body: ComponentGroup,
    )

    private val windows = mutableListOf<Window>()
    private val wallpaper = AeroWallpaper(context)
    private val heading = text(18f, Color.WHITE)
    private val brand =
        text(13f, Color.WHITE).apply {
            text = "ThinkOS"
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
    private val taskbar = View(context)
    private val start =
        text(24f, Color.WHITE).apply {
            text = "T"
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
    private val tray =
        text(12f, Color.WHITE).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 2
        }
    private val buttons = mutableListOf<TextView>()
    private val menu =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(12).toFloat()
            visibility = GONE
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
    private var dark = true
    private var active = false
    private var minute = -1L
    private var night = 0f
    private var owner = ""
    private var driftX = 0
    private var driftY = 0
    val activeAnimations: Boolean
        get() = windows.any { it.body.activeAnimations }

    val ambientAnimations: Boolean
        get() = windows.any { it.body.ambientAnimations }

    init {
        clipChildren = true
        listOf(wallpaper, heading, brand, taskbar, start, tray, menu).forEach(::addView)
        start.setOnClickListener { toggleMenu() }
        tray.setOnClickListener { events.openSettings("system") }
        localeChanged()
        colors(Palette.DARK)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    private fun exact(n: Int) = MeasureSpec.makeMeasureSpec(n.coerceAtLeast(0), MeasureSpec.EXACTLY)

    private fun text(sp: Float, color: Int) =
        AeroTextView(context).apply {
            textSize = sp
            setTextColor(color)
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            includeFontPadding = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
        }

    fun update(pageOwner: String, references: JSONArray, widgets: List<PluginWidget>) {
        owner = pageOwner
        windows.forEach {
            it.body.active(false)
            removeView(it.view)
        }
        windows.clear()
        buttons.forEach(::removeView)
        buttons.clear()
        menu.removeAllViews()
        for (i in 0 until references.length()) {
            val (plugin, declaration, opens) =
                resolveStackItem(pageOwner, references.opt(i), widgets, "glance")
            val body =
                ComponentGroup(
                    context,
                    plugin,
                    declaration,
                    state,
                    variant,
                    false,
                    true,
                    events,
                    desktop = true,
                )
            val title =
                events.text(plugin, "name")
                    ?: references.optJSONObject(i)?.optString("widget").orEmpty()
            val frame = AeroWindow(context, title, body)
            frame.contentDescription = title
            frame.setOnClickListener { opens?.let(events::openPage) }
            val window = Window(plugin, opens, frame, body)
            windows += window
            addView(frame, indexOfChild(taskbar))
            val button =
                text(13f, Color.WHITE).apply {
                    text = title
                    contentDescription = title
                    gravity = Gravity.CENTER
                    setPadding(dp(8), 0, dp(8), 0)
                    setOnClickListener { opens?.let(events::openPage) }
                }
            buttons += button
            addView(button, indexOfChild(start))
            menu.addView(
                menuItem(title) {
                    closeMenu()
                    opens?.let(events::openPage)
                }
            )
        }
        menu.addView(
            menuItem(context.getString(R.string.setting_system_name)) {
                closeMenu()
                events.openSettings("system")
            }
        )
        menu.bringToFront()
        start.bringToFront()
        colors(if (dark) Palette.DARK else Palette.LIGHT)
        active(active)
        requestLayout()
    }

    private fun menuItem(title: String, action: () -> Unit) =
        text(16f, 0xff12253b.toInt()).apply {
            text = title
            setPadding(dp(14), 0, dp(14), 0)
            minHeight = dp(48)
            background =
                GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0xfffcfeff.toInt(), 0xffe9f1fa.toInt()),
                    )
                    .apply {
                        setStroke(dp(1).coerceAtLeast(1), 0xffc2d3e5.toInt())
                        cornerRadius = dp(3).toFloat()
                    }
            setOnClickListener { action() }
        }

    private fun toggleMenu() {
        if (menu.visibility == VISIBLE) {
            closeMenu()
            return
        }
        menu.visibility = VISIBLE
        if (android.animation.ValueAnimator.areAnimatorsEnabled()) {
            menu.alpha = 0f
            menu.translationY = dp(12).toFloat()
            menu
                .animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(180)
                .setInterpolator(moe.kiwi.thinkos.anim.Motion.EASE)
                .start()
        }
    }

    private fun closeMenu() {
        menu.animate().cancel()
        menu.visibility = GONE
        menu.alpha = 1f
        menu.translationY = 0f
    }

    fun tapAt(x: Float, y: Float): Boolean {
        if (menu.visibility == VISIBLE && !hit(menu, x, y) && !hit(start, x, y)) {
            closeMenu()
            return true
        }
        return false
    }

    private fun hit(v: View, x: Float, y: Float) =
        v.visibility == VISIBLE && x >= v.left && x < v.right && y >= v.top && y < v.bottom

    fun interactiveAt(x: Float, y: Float) =
        hit(start, x, y) ||
            hit(tray, x, y) ||
            hit(menu, x, y) ||
            buttons.any { hit(it, x, y) } ||
            windows.any { hit(it.view, x, y) }

    fun ownerAt(x: Float, y: Float) = windows.firstOrNull { hit(it.view, x, y) }?.owner ?: owner

    /** Keep the wallpaper and full-width taskbar fixed while their foreground avoids static pixels. */
    fun burnInOffset(x: Int, y: Int) {
        if (x == driftX && y == driftY) return
        for (i in 0 until childCount) {
            val view = getChildAt(i)
            if (view !== wallpaper && view !== taskbar && view.isLaidOut) {
                view.offsetLeftAndRight(x - driftX)
                view.offsetTopAndBottom(y - driftY)
            }
        }
        driftX = x; driftY = y
    }

    fun bind(plugin: String, paths: Set<String>) {
        windows.forEach {
            val previous = it.body.desktopText()
            it.body.bind(plugin, paths)
            if (previous != it.body.desktopText()) it.view.staticChanged()
        }
    }

    fun active(value: Boolean) {
        active = value
        windows.forEach { it.body.active(value) }
        if (!value) closeMenu()
    }

    fun night(amount: Float) {
        night = amount
        windows.forEach {
            it.body.night(amount)
            it.view.night(amount)
        }
    }

    fun tick(now: Long, attention: Attention) {
        windows.forEach { it.body.tick(now, if (active) attention else Attention.AMBIENT) }
        showTime(WallClock.now())
    }

    /** The taskbar clock changes once a minute, exactly when the minute does (and uses the corrected time). */
    override val periodMillis: Long get() = 60_000L

    override fun onTick(wall: Long) = showTime(wall)

    private fun showTime(wall: Long) {
        if (wall / 60_000 != minute) {
            minute = wall / 60_000
            val zone = moe.kiwi.thinkos.tz.Zones.home()
            tray.text =
                ZoneFormat.format("@time", wall, zone) +
                    "\n" +
                    ZoneFormat.format("@date", wall, zone)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        minute = -1
        showTime(WallClock.now())
        ClockTicker.add(this)
    }

    override fun onDetachedFromWindow() {
        ClockTicker.remove(this)
        super.onDetachedFromWindow()
    }

    fun localeChanged() {
        heading.text = context.getString(R.string.aero_desktop)
        start.contentDescription = context.getString(R.string.aero_start)
        tray.contentDescription = context.getString(R.string.setting_system_name)
        windows.forEachIndexed { i, win ->
            win.body.localeChanged()
            win.view.staticChanged()
            val name = events.text(win.owner, "name") ?: win.owner
            win.view.title(name)
            buttons.getOrNull(i)?.text = name
            (menu.getChildAt(i) as? TextView)?.text = name
        }
        (menu.getChildAt(windows.size) as? TextView)?.text =
            context.getString(R.string.setting_system_name)
        minute = -1
    }

    fun colors(value: Palette) {
        dark = Color.red(value.bg) < 128
        wallpaper.dark = dark
        val body =
            if (dark)
                Palette(
                    0xff14283d.toInt(),
                    0xff14283d.toInt(),
                    0xfff4f8fd.toInt(),
                    0xffc7daee.toInt(),
                    0xff89cfff.toInt(),
                    0xff89cfff.toInt(),
                    0xff45617d.toInt(),
                )
            else
                Palette(
                    0xfff5f9fd.toInt(),
                    0xfff5f9fd.toInt(),
                    0xff152b43.toInt(),
                    0xff45617b.toInt(),
                    0xff1265a8.toInt(),
                    0xff1265a8.toInt(),
                    0xffbed0e1.toInt(),
                )
        windows.forEach {
            it.view.dark(dark)
            it.view.night(night)
            it.body.colors(body)
        }
        taskbar.background =
            GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xd977a2c4.toInt(), 0xe52d5578.toInt(), 0xef102f50.toInt()),
                )
                .apply { setStroke(dp(1).coerceAtLeast(1), 0x99d4edff.toInt()) }
        start.background =
            object : android.graphics.drawable.Drawable() {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)

                override fun draw(c: Canvas) {
                    val cx = bounds.exactCenterX()
                    val cy = bounds.exactCenterY()
                    val r = minOf(bounds.width(), bounds.height()) * .43f
                    paint.shader =
                        RadialGradient(
                            cx - r * .25f,
                            cy - r * .35f,
                            r * 1.5f,
                            intArrayOf(0xffc6f5ff.toInt(), 0xff1586c6.toInt(), 0xff06416f.toInt()),
                            null,
                            Shader.TileMode.CLAMP,
                        )
                    c.drawCircle(cx, cy, r, paint)
                    paint.shader = null
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = dp(1).coerceAtLeast(1).toFloat()
                    paint.color = 0xffd9f4ff.toInt()
                    c.drawCircle(cx, cy, r, paint)
                    paint.style = Paint.Style.FILL
                }

                override fun setAlpha(a: Int) {}

                override fun setColorFilter(f: android.graphics.ColorFilter?) {}

                @Suppress("DEPRECATION")
                override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
            }
        buttons.forEach {
            it.background =
                GradientDrawable(
                        GradientDrawable.Orientation.TOP_BOTTOM,
                        intArrayOf(0x669ed0f6, 0x22213e59),
                    )
                    .apply {
                        cornerRadius = dp(3).toFloat()
                        setStroke(dp(1).coerceAtLeast(1), 0x77e2f3ff)
                    }
        }
        menu.background =
            GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0xf0d6e9fb.toInt(), 0xf0477197.toInt()),
                )
                .apply {
                    cornerRadius = dp(6).toFloat()
                    setStroke(dp(1).coerceAtLeast(1), 0xffc4e7ff.toInt())
                }
    }

    private val boxes = linkedMapOf<View, android.graphics.Rect>()

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        val margin = dp(24)
        val gap = dp(14)
        val bar = dp(52)
        val top = dp(68)
        boxes.clear()
        fun box(v: View, x: Int, y: Int, cw: Int, ch: Int) {
            val l = if (layoutDirection == LAYOUT_DIRECTION_RTL) w - x - cw else x
            boxes[v] = android.graphics.Rect(l, y, l + cw, y + ch)
            v.measure(exact(cw), exact(ch))
        }
        box(wallpaper, 0, 0, w, h)
        box(heading, margin, dp(18), w / 2, dp(34))
        box(brand, w / 2, dp(18), w / 2 - margin, dp(34))
        if (w >= h) {
            val n = windows.size.coerceAtLeast(1)
            val cw = (w - 2 * margin - gap * (n - 1)) / n
            val ch = (h - bar - top - dp(40)).coerceAtLeast(0)
            windows.forEachIndexed { i, win ->
                box(win.view, margin + i * (cw + gap), top + i * dp(10), cw, ch)
            }
        } else {
            val cw = w - 2 * margin
            val primary = minOf(dp(212), (h - bar - top - gap) / 2)
            val secondTop = top + primary + gap
            windows.firstOrNull()?.let { box(it.view, margin, top, cw, primary) }
            val n = (windows.size - 1).coerceAtLeast(1)
            val sw = (cw - gap * (n - 1)) / n
            windows.drop(1).forEachIndexed { i, win ->
                box(
                    win.view,
                    margin + i * (sw + gap),
                    secondTop,
                    sw,
                    (h - bar - secondTop - dp(20)).coerceAtLeast(0),
                )
            }
        }
        box(taskbar, 0, h - bar, w, bar)
        box(start, dp(6), h - bar, dp(56), bar)
        val trayW = dp(110).coerceAtMost(w / 3)
        box(tray, w - trayW - dp(18), h - bar, trayW, bar)
        val room = (w - dp(82) - trayW - dp(30)).coerceAtLeast(0)
        val bw = minOf(dp(134), room / buttons.size.coerceAtLeast(1))
        buttons.forEachIndexed { i, b ->
            box(b, dp(74) + i * bw, h - bar + dp(5), bw - dp(6), bar - dp(10))
        }
        val mh = dp(48) * (windows.size + 1) + dp(14)
        box(menu, dp(8), h - bar - mh, minOf(dp(300), w - dp(16)), mh)
        setMeasuredDimension(w, h)
    }

    override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
        boxes.forEach { (v, box) ->
            val x = if (v === wallpaper || v === taskbar) 0 else driftX
            val y = if (v === wallpaper || v === taskbar) 0 else driftY
            v.layout(box.left + x, box.top + y, box.right + x, box.bottom + y)
        }
    }
}

/** The blue ribbons are original vector artwork, cached in the view's display list. */
private class AeroWallpaper(context: Context) : View(context) {
    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    var dark = true
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        p.shader =
            LinearGradient(
                0f,
                0f,
                w,
                h,
                if (dark) intArrayOf(0xff061d3c.toInt(), 0xff0d5784.toInt(), 0xff087e9e.toInt())
                else intArrayOf(0xff174f96.toInt(), 0xff178fc1.toInt(), 0xff39b9bb.toInt()),
                null,
                Shader.TileMode.CLAMP,
            )
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
        val ribbon =
            Path().apply {
                moveTo(-w * .1f, h * .94f)
                cubicTo(w * .28f, h * .05f, w * .54f, h * 1.25f, w * 1.08f, h * .1f)
                lineTo(w * 1.08f, h * .44f)
                cubicTo(w * .52f, h * 1.45f, w * .21f, h * .36f, -w * .1f, h * 1.08f)
                close()
            }
        p.color = 0x1ce3ffff
        c.drawPath(ribbon, p)
        p.style = Paint.Style.STROKE
        p.strokeWidth = h * .0015f
        p.color = 0x44d1fdff
        c.drawPath(ribbon, p)
        p.style = Paint.Style.FILL
        p.shader =
            RadialGradient(
                w * .84f,
                h * .1f,
                h * .72f,
                intArrayOf(0x33e3ffff, Color.TRANSPARENT),
                null,
                Shader.TileMode.CLAMP,
            )
        c.drawRect(0f, 0f, w, h, p)
        p.shader = null
    }
}

private class AeroWindow(context: Context, title: String, private val body: ComponentGroup) :
    ViewGroup(context) {
    private var dark = true
    private var night = 0f
    private var titleFill = 0xffbcd6e9.toInt()
    private var staticBitmap: Bitmap? = null
    private var staticDirty = true
    private var revision = 0L
    private var rasterPending = false
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val caption =
        TextView(context).apply {
            text = title
            textSize = 14f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
        }

    init {
        setWillNotDraw(false)
        clipChildren = true
        addView(body)
        addView(caption)
        elevation = dp(7).toFloat()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    fun title(value: String) {
        caption.text = value
        contentDescription = value
        staticChanged()
    }

    fun staticChanged() {
        staticDirty = true
        revision++
        invalidate()
    }

    fun dark(value: Boolean) {
        dark = value
        staticChanged()
    }

    fun night(value: Float) {
        night = value
        titleFill =
            TileColor.nightSafe(Palette.mix(0xffbcd6e9.toInt(), 0xff11171f.toInt(), value), value)
        caption.setTextColor(TileColor.nightForeground(titleFill, value))
        staticChanged()
    }

    private data class Chrome(
        val width: Int,
        val height: Int,
        val density: Float,
        val dark: Boolean,
        val night: Float,
        val fill: Int,
    )

    private fun drawChrome(c: Canvas, style: Chrome) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun dp(n: Int) = (n * style.density).toInt()
        val w = style.width.toFloat()
        val h = style.height.toFloat()
        val one = dp(1).coerceAtLeast(1).toFloat()
        val radius = dp(5).toFloat()
        p.shader =
            LinearGradient(
                0f,
                0f,
                w,
                h,
                intArrayOf(0xcce0f5ff.toInt(), 0xa073a7cc.toInt(), 0xcc426c91.toInt()),
                floatArrayOf(0f, .45f, 1f),
                Shader.TileMode.CLAMP,
            )
        c.drawRoundRect(one / 2, one / 2, w - one / 2, h - one / 2, radius, radius, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = one
        p.color = 0xdd071b2c.toInt()
        c.drawRoundRect(one / 2, one / 2, w - one / 2, h - one / 2, radius, radius, p)
        p.color = 0xbbe5f6ff.toInt()
        c.drawRoundRect(
            one * 1.5f,
            one * 1.5f,
            w - one * 1.5f,
            h - one * 1.5f,
            radius - one,
            radius - one,
            p,
        )
        p.style = Paint.Style.FILL
        p.color = style.fill
        c.drawRect(dp(2).toFloat(), dp(2).toFloat(), w - dp(2), dp(34).toFloat(), p)
        p.color = if (style.dark) 0xff14283d.toInt() else 0xfff5f9fd.toInt()
        c.drawRect(dp(6).toFloat(), dp(34).toFloat(), w - dp(6), h - dp(6), p)
        p.color = if (style.night > 0) 0x08ffffff else 0x33ffffff
        c.drawRect(dp(2).toFloat(), dp(2).toFloat(), w - dp(2), dp(16).toFloat(), p)
    }

    private data class TextRaster(
        val value: String,
        val paint: android.text.TextPaint,
        val width: Int,
        val align: android.text.Layout.Alignment,
        val includePad: Boolean,
        val lines: Int,
        val ellipsize: TextUtils.TruncateAt?,
        val rtl: Boolean,
        val spacing: Float,
        val multiplier: Float,
        val breaks: Int,
        val hyphenation: Int,
        val x: Int,
        val y: Int,
        val bounds: android.graphics.Rect,
    )

    private fun texts(): List<TextRaster> =
        (body.desktopTextViews() + caption).mapNotNull { v ->
            val layout = v.layout ?: return@mapNotNull null
            if (v.visibility != VISIBLE || v.width <= 0 || v.height <= 0) return@mapNotNull null
            val rect = android.graphics.Rect(0, 0, v.width, v.height)
            offsetDescendantRectToMyCoords(v, rect)
            TextRaster(
                v.text.toString(),
                android.text.TextPaint(v.paint).apply { color = v.currentTextColor },
                layout.width,
                layout.alignment,
                v.includeFontPadding,
                v.maxLines,
                v.ellipsize,
                v.layoutDirection == LAYOUT_DIRECTION_RTL,
                v.lineSpacingExtra,
                v.lineSpacingMultiplier,
                v.breakStrategy,
                v.hyphenationFrequency,
                rect.left + v.totalPaddingLeft,
                rect.top + v.totalPaddingTop,
                rect,
            )
        }

    private fun requestRaster(style: Chrome) {
        if (rasterPending) return
        rasterPending = true
        val version = revision
        val labels = texts()
        AeroRaster.worker.execute {
            val bitmap =
                Bitmap.createBitmap(style.width, style.height, Bitmap.Config.ARGB_8888).apply {
                    density = Bitmap.DENSITY_NONE
                }
            val target = Canvas(bitmap)
            drawChrome(target, style)
            for (t in labels) {
                val builder =
                    android.text.StaticLayout.Builder.obtain(
                            t.value,
                            0,
                            t.value.length,
                            t.paint,
                            t.width.coerceAtLeast(1),
                        )
                        .setAlignment(t.align)
                        .setIncludePad(t.includePad)
                        .setMaxLines(t.lines)
                        .setTextDirection(
                            if (t.rtl) android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL
                            else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR
                        )
                        .setLineSpacing(t.spacing, t.multiplier)
                        .setBreakStrategy(t.breaks)
                        .setHyphenationFrequency(t.hyphenation)
                t.ellipsize?.let {
                    builder.setEllipsize(it).setEllipsizedWidth(t.width.coerceAtLeast(1))
                }
                target.save()
                target.clipRect(t.bounds)
                target.translate(t.x.toFloat(), t.y.toFloat())
                builder.build().draw(target)
                target.restore()
            }
            bitmap.prepareToDraw()
            AeroRaster.main.post {
                rasterPending = false
                if (
                    isAttachedToWindow &&
                        version == revision &&
                        width == style.width &&
                        height == style.height
                ) {
                    staticBitmap = bitmap
                    staticDirty = false
                }
                if (isAttachedToWindow) invalidate()
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        staticBitmap = null
        staticChanged()
    }

    override fun dispatchDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0) return
        val style = Chrome(width, height, resources.displayMetrics.density, dark, night, titleFill)
        if (staticDirty || staticBitmap == null) requestRaster(style)
        val bitmap = staticBitmap
        if (bitmap == null) {
            body.desktopTextCached = false
            drawChrome(canvas, style)
            super.dispatchDraw(canvas)
            return
        }
        canvas.drawBitmap(bitmap, 0f, 0f, bitmapPaint)
        body.desktopTextCached = true
        drawChild(canvas, body, drawingTime)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        caption.measure(
            MeasureSpec.makeMeasureSpec((w - dp(12)).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(dp(32), MeasureSpec.EXACTLY),
        )
        body.measure(
            MeasureSpec.makeMeasureSpec((w - dp(32)).coerceAtLeast(0), MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec((h - dp(58)).coerceAtLeast(0), MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(c: Boolean, l: Int, t: Int, r: Int, b: Int) {
        caption.layout(dp(6), dp(1), width - dp(6), dp(33))
        body.layout(dp(16), dp(44), width - dp(16), height - dp(14))
    }
}

/** At most one pending raster per window; no views or mutable UI paints are read by this worker. */
private object AeroRaster {
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val worker =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(
                    {
                        android.os.Process.setThreadPriority(
                            android.os.Process.THREAD_PRIORITY_BACKGROUND
                        )
                        r.run()
                    },
                    "aero-raster",
                )
                .apply { isDaemon = true }
        }
}

/**
 * Chrome remains a real TextView for layout, clicking and accessibility. Minute changes shape in
 * the background; the previous glyph bitmap stays visible until the next is ready.
 */
private class AeroTextView(context: Context) : TextView(context) {
    private data class Key(
        val text: String,
        val width: Int,
        val height: Int,
        val size: Float,
        val face: Typeface?,
        val gravity: Int,
        val direction: Int,
        val locale: String,
    )

    private var displayed: Key? = null
    private var bitmap: Bitmap? = null
    private var pending = false
    private val brush = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun key() =
        Key(
            text.toString(),
            width,
            height,
            textSize,
            typeface,
            gravity,
            layoutDirection,
            resources.configuration.locales.toLanguageTags(),
        )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w,h,oldw,oldh)
        bitmap = null
        displayed = null
    }

    override fun onDraw(canvas: Canvas) {
        if (width <= 0 || height <= 0 || text.isEmpty()) return
        val next = key()
        val source = layout
        if (next != displayed && !pending && source != null) {
            pending = true
            val paint = android.text.TextPaint(this.paint).apply { color = Color.WHITE }
            val textWidth = source.width.coerceAtLeast(1)
            val alignment = source.alignment
            val pad = includeFontPadding
            val lines = maxLines
            val truncate = ellipsize
            val x = totalPaddingLeft
            val y = totalPaddingTop
            val spacing = lineSpacingExtra
            val multiplier = lineSpacingMultiplier
            val breaks = breakStrategy
            val hyphenation = hyphenationFrequency
            AeroRaster.worker.execute {
                val image =
                    Bitmap.createBitmap(next.width, next.height, Bitmap.Config.ALPHA_8).apply {
                        density = Bitmap.DENSITY_NONE
                    }
                val target = Canvas(image)
                target.translate(x.toFloat(), y.toFloat())
                val builder =
                    android.text.StaticLayout.Builder.obtain(
                            next.text,
                            0,
                            next.text.length,
                            paint,
                            textWidth,
                        )
                        .setAlignment(alignment)
                        .setIncludePad(pad)
                        .setMaxLines(lines)
                        .setLineSpacing(spacing, multiplier)
                        .setBreakStrategy(breaks)
                        .setHyphenationFrequency(hyphenation)
                        .setTextDirection(
                            if (next.direction == LAYOUT_DIRECTION_RTL)
                                android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL
                            else android.text.TextDirectionHeuristics.FIRSTSTRONG_LTR
                        )
                truncate?.let { builder.setEllipsize(it).setEllipsizedWidth(textWidth) }
                builder.build().draw(target)
                image.prepareToDraw()
                AeroRaster.main.post {
                    pending = false
                    if (isAttachedToWindow && key() == next) {
                        bitmap = image
                        displayed = next
                    }
                    if (isAttachedToWindow) invalidate()
                }
            }
        }
        val cached = bitmap
        if (cached == null) super.onDraw(canvas)
        else {
            brush.color = currentTextColor
            canvas.drawBitmap(cached, 0f, 0f, brush)
        }
    }
}
