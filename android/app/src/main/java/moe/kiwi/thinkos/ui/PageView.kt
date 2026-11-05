package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.plugin.LayoutVocabulary
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginWidget
import moe.kiwi.thinkos.ui.form.FormHost
import moe.kiwi.thinkos.ui.form.FormView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil

/** The corner radius of a card in a stack (`docs/04-ui-spec.md` §3), also the radius of the window the stack slides its cards in. */
private const val CARD_RADIUS_DP = 32f

/** One compiled component tree. Only bindings that depend on changed paths are evaluated. */
internal class ComponentGroup(
    context: Context,
    private var pluginId: String,
    declaration: JSONObject,
    private val state: (String) -> JSONObject,
    private val variant: () -> String,
    private val card: Boolean,
    private val compact: Boolean,
    private val events: PageEvents,
    private val desktop: Boolean = false,
    /** The children stand side by side in equal cells (the sheet's row of toggles) instead of one above the other. */
    private val row: Boolean = false,
) : ViewGroup(context) {
    private class Bound(val view: View, val definition: JSONObject, val template: Binding.Template,
        val renderer: RenderComponent?, val dependencies: List<Binding.Template>) {
        var formatTemplate: Binding.Template? = null
        var zoneTemplates: List<Pair<Binding.Template, Binding.Template?>> = emptyList()
        fun templates(): List<Binding.Template> = dependencies + listOfNotNull(template, formatTemplate) + zoneTemplates.flatMap { listOfNotNull(it.first, it.second) }
    }
    private val strings: (String) -> String? = { key -> events.text(pluginId, key) }
    private val bound = mutableListOf<Bound>()
    private val shape = GradientDrawable().apply { cornerRadius = CARD_RADIUS_DP * resources.displayMetrics.density }
    private var palette = Palette.DARK
    private var embeddedTile: TileView? = null
    private var photoNotice = false
    private var allocation = RenderGeometry.VerticalAllocation(emptyList(), 0, 0)
    private val formHost = object : FormHost {
        override fun emit(name: String, args: List<Any?>) = events.send(pluginId, name, args)
        override fun done() = events.closeSettings()
        override fun interacted() = events.settingsInteraction()
        override fun preview(scope: String, key: String, kind: String, value: Double?) = events.previewSetting(pluginId, scope, key, kind, value)
    }
    val owner: String get() = pluginId
    val floatingClock: Boolean get() = bound.singleOrNull()?.view.let { it is ClockView && it.clockStyle == "float" }
    val photo: Boolean get() = bound.singleOrNull()?.definition?.optString("type").let { it == "image" || it == "carousel" }
    val video: Boolean get() = bound.singleOrNull()?.definition?.optString("type").let { it == "video" || it == "tv" }
    var opens: String? = null
    val activeAnimations: Boolean get() = embeddedTile?.activeAnimations == true || bound.any { it.renderer?.animating == true }
    val bottomOverlayInset: Int get() = bound.maxOfOrNull { it.renderer?.bottomOverlayInset ?: 0 } ?: 0
    val ambientAnimations: Boolean get() = embeddedTile?.ambientAnimations == true || bound.any { (it.view as? IconView)?.animating == true }
    private var signature = ""
    private var active = false

    init {
        clipChildren = true
        clipToPadding = true
        if (card) { background = shape; clipToOutline = true }
        update(pluginId, declaration)
    }
    private fun children(definition: JSONObject): List<JSONObject> = definition.optJSONArray("children")?.let { array ->
        (0 until array.length()).map { array.getJSONObject(it) }
    } ?: listOf(definition)

    private fun dependencies(definition: JSONObject): List<Binding.Template> {
        val result = mutableListOf<Binding.Template>()
        fun visit(value: Any?, key: String? = null) {
            when (value) {
                is JSONObject -> value.keys().forEach { visit(value.opt(it), it) }
                is JSONArray -> for (i in 0 until value.length()) visit(value.opt(i))
                is String -> result += Binding.compile(if (key in setOf("path", "sun", "items", "values", "nav") && "{{" !in value) "{{$value}}" else value)
            }
        }
        visit(definition)
        // Functional media controls consume the OS-owned state without asking plugins to
        // invent redundant bindings just to refresh a play/pause icon or video visibility.
        if (definition.optString("type") in setOf("controls", "video", "seekbar", "nowplaying", "tv")) result += Binding.compile("{{media}}")
        return result
    }

    fun update(owner: String, declaration: JSONObject) {
        pluginId = owner
        if (declaration.optString("template") == "tile") {
            if (embeddedTile == null) {
                removeAllViews(); bound.clear(); background = null; clipToOutline = false
                embeddedTile = TileView(context, state, variant, events).also { addView(it) }
            }
            embeddedTile!!.update(owner, owner, declaration, JSONObject(), opens, "large")
            embeddedTile!!.colors(palette); embeddedTile!!.active(active)
            return
        }
        embeddedTile?.let { it.active(false); removeView(it); embeddedTile = null; signature = ""; if (card) { background = shape; clipToOutline = true } }
        val definitions = children(declaration)
        val nextSignature = owner + ":" + definitions.joinToString { it.toString() }
        if (nextSignature != signature) {
            removeAllViews()
            bound.clear()
            definitions.forEach { def ->
                val renderer = if(desktop && def.optString("type")=="clock" && def.optString("style")=="analog") AeroClockView(context) else BuiltinComponents.create(context, def, compact,
                    images = { image -> events.component(context, pluginId, image) }) { event, index ->
                    events.send(pluginId, "tap", if (index == null) listOf(event) else listOf(event, index))
                } ?: if (def.optString("type") !in setOf("clock", "form", "icon", "text")) events.component(context, pluginId, def) else null
                val view = renderer?.view ?: when (def.optString("type")) {
                    "clock" -> ClockView(context, def, compact)
                    "form" -> FormView(context, formHost)
                    "icon" -> IconView(context)
                    // A media view whose plugin has lost (or never had) the media permission shows nothing rather than crashing the page.
                    "controls", "video", "seekbar", "nowplaying", "tv", "slider" -> View(context)
                    "text" -> (if(desktop) TileTextView(context) else TextView(context)).apply {
                        gravity = Gravity.CENTER; includeFontPadding = false; maxLines = if(desktop && def.optString("style")=="caption") 4 else 2
                        ellipsize = TextUtils.TruncateAt.END
                        val style = def.optString("style", "body")
                        textSize = when (style) { "display" -> TextScale.DISPLAY; "title" -> TextScale.TITLE; "caption" -> TextScale.CAPTION; else -> TextScale.BODY }
                        typeface = Typeface.create(when (style) { "display" -> "sans-serif-light"; "title" -> "sans-serif-medium"; else -> "sans-serif" }, Typeface.NORMAL)
                        fontFeatureSettings = "tnum"
                    }
                    else -> error("OS renderer unavailable: ${def.optString("type")}")
                }
                if (card && def.optString("type") in setOf("image", "carousel")) {
                    view.outlineProvider = object : android.view.ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: android.graphics.Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, (CARD_RADIUS_DP - 24f) * resources.displayMetrics.density)
                        }
                    }
                    view.clipToOutline = true
                }
                val primary = when (view) {
                    is IconView -> def.optString("name")
                    is ClockView -> def.optString("style", "digital")
                    is FormView -> "{{${def.optString("path")}}}${def.optString("title")}${def.optString("done")}"
                    else -> def.optString("value")
                }
                val item = Bound(view, def, Binding.compile(primary), renderer, dependencies(def))
                if (view is ClockView) {
                    val format = def.optString("format", "@time")
                    item.formatTemplate = Binding.compile(if (format.startsWith('@')) "@$format" else format)
                    val zones = def.optJSONArray("zones")
                    item.zoneTemplates = (0 until (zones?.length() ?: 0)).mapNotNull { zones?.optJSONObject(it) }.map { zone ->
                        Binding.compile(zone.optString("timezone")) to zone.optString("label").takeIf { it.isNotEmpty() }?.let(Binding::compile)
                    }
                }
                if (renderer?.handlesTap != true && view !is FormView) def.optString("tap").takeIf { it.isNotBlank() }?.let { event ->
                    view.setOnClickListener { events.send(pluginId, "tap", listOf(event)) }
                }
                bound += item
                addView(view)
                renderer?.active(active)
            }
            signature = nextSignature
            requestLayout()
        }
        bind(pluginId, setOf(""), force = true)
        colors(palette)
    }
    fun bind(changedPlugin: String, paths: Set<String>, force: Boolean = false) {
        embeddedTile?.let { it.bind(changedPlugin, paths, force); return }
        if (changedPlugin != pluginId) return
        val snapshot = state(pluginId)
        for (item in bound) {
            if (!force && item.templates().none { it.affected(paths) }) continue
            val next = item.template.render(snapshot, variant(), strings)
            val renderer = item.renderer
            if (renderer != null) { renderer.bind(snapshot, variant(), strings); continue }
            when (val view = item.view) {
                is TextView -> {
                    view.visibility = if (next.isEmpty()) GONE else VISIBLE
                    if (view.text.toString() != next) {
                        view.text = next
                        if (view.isLaidOut && view.visibility == VISIBLE) {
                            view.translationY = 4f * resources.displayMetrics.density
                            view.animate().translationY(0f).setDuration(Motion.NUMBER).setInterpolator(Motion.EASE).start()
                        }
                    }
                }
                is IconView -> view.name = next
                is ClockView -> {
                    view.clockStyle = next
                    view.setSun(Binding.lookup(snapshot, item.definition.optString("sun")) as? JSONObject)
                    item.formatTemplate?.let { view.setFormat(it.render(snapshot, variant(), strings)) }
                    view.setZones(item.zoneTemplates.mapNotNull { (id, label) ->
                        val zone = id.render(snapshot, variant(), strings).trim()
                        if (zone.isEmpty()) null else ClockZone(zone, label?.render(snapshot, variant(), strings)?.takeIf(String::isNotBlank))
                    })
                }
                is FormView -> {
                    fun label(key: String) = Binding.compile(item.definition.optString(key)).render(snapshot, variant(), strings)
                    view.setSetup(item.definition.has("primary"), item.definition.optString("tap"), label("subtitle"), label("primary"), label("secondary"), label("back"))
                    view.setHeader(Binding.compile(item.definition.optString("title")).render(snapshot, variant(), strings), Binding.compile(item.definition.optString("done")).render(snapshot, variant(), strings))
                    view.setNavigation(Binding.lookup(snapshot, item.definition.optString("nav")) as? JSONObject)
                    view.setFields(Binding.lookup(snapshot, item.definition.optString("path")) as? JSONArray ?: JSONArray())
                }
            }
        }
    }
    fun colors(value: Palette) {
        palette = value
        embeddedTile?.let { it.colors(value); return }
        if (card) shape.setColor(value.surface)
        bound.forEach { item ->
            item.renderer?.colors(value)
            when (val view = item.view) {
                is TextView -> {
                    view.setTextColor(if (photoNotice) Color.WHITE else if (item.definition.optString("style") in setOf("body", "caption")) value.secondary else value.primary)
                    view.gravity = if (photoNotice) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
                    if (photoNotice) view.setShadowLayer(2 * resources.displayMetrics.density, 0f, 1f, 0x99000000.toInt())
                    else view.paint.clearShadowLayer()
                }
                is ClockView -> view.palette = value
                is FormView -> view.colors(value)
                is IconView -> view.palette = value
            }
        }
    }
    fun photoNotice(value: Boolean) {
        if (photoNotice == value) return
        photoNotice = value
        colors(palette)
    }
    fun night(amount: Float) { embeddedTile?.night(amount) }
    fun burnInOffset(x: Int, y: Int) { bound.forEach { it.renderer?.burnInOffset(x, y) } }
    fun tileViews(): List<TileView> = listOfNotNull(embeddedTile)
    /** Aero caches static labels, while live components keep their actual native parent chain. */
    var desktopTextCached = false
        set(value) { if (field != value) { field = value; invalidate() } }
    override fun dispatchDraw(canvas: Canvas) {
        if (!desktop || !desktopTextCached) { super.dispatchDraw(canvas); return }
        val save = canvas.save()
        canvas.clipRect(0, 0, width, height)
        embeddedTile?.let { drawChild(canvas, it, drawingTime) }
        bound.forEach { item ->
            if (item.view.visibility == VISIBLE && (item.renderer != null || item.view is ClockView || item.view is IconView))
                drawChild(canvas, item.view, drawingTime)
        }
        canvas.restoreToCount(save)
    }
    fun desktopText(): List<String> = bound.mapNotNull { (it.view as? TextView)?.text?.toString() }
    fun desktopTextViews(): List<TextView> = bound.mapNotNull { it.view as? TextView }
    fun tick(now: Long, attention: Attention) {
        embeddedTile?.tick(now, attention)
        bound.forEach { item ->
            item.renderer?.tick(now, attention)
            (item.view as? ClockView)?.tick(now, attention)
            (item.view as? IconView)?.tick(now, attention)
        }
    }
    fun active(value: Boolean) { active = value; embeddedTile?.active(value); bound.forEach { it.renderer?.active(value); (it.view as? FormView)?.active(value) } }
    fun tapAt(x: Float, y: Float): Boolean = bound.asReversed().any { item ->
        val v = item.view
        val hit = v.visibility == VISIBLE && x >= v.left && x < v.right && y >= v.top && y < v.bottom
        hit && (item.renderer?.tapAt(x - v.left, y - v.top) == true || item.definition.optString("tap").isNotBlank() && v.performClick())
    }
    /** A finger went down on the page: components that want to know where (the cinema view) are told before anything is decided. */
    fun touchDown(x: Float, y: Float) {
        for (item in bound) {
            val v = item.view
            if (v.visibility == VISIBLE && x >= v.left && x < v.right && y >= v.top && y < v.bottom) item.renderer?.touchDown(x - v.left, y - v.top)
        }
    }
    fun dragVerticalAt(x: Float, y: Float, offset: Float): RenderComponent? {
        for (item in bound.asReversed()) {
            val v = item.view
            if (v.visibility == VISIBLE && x >= v.left && x < v.right && y >= v.top && y < v.bottom &&
                item.renderer?.dragVerticalFrom(x - v.left, y - v.top, offset) == true) return item.renderer
        }
        return null
    }
    /** A component of this group closed something of its own on top (ADR-040). */
    val hasBack: Boolean get() = bound.any { it.renderer?.hasBack == true || (it.view as? FormView)?.hasBack == true }
    fun back(): Boolean = bound.any { it.renderer?.back() == true || (it.view as? FormView)?.back() == true }
    fun localeChanged() {
        embeddedTile?.localeChanged()
        bound.forEach { item -> item.renderer?.localeChanged(); (item.view as? ClockView)?.refreshLocale() }
        bind(pluginId, setOf(""), force = true)
    }
    fun interactiveAt(x: Float, y: Float): Boolean = bound.asReversed().any { item ->
        val view = item.view
        val inside = view.visibility == VISIBLE && x >= view.left + view.translationX && x < view.right + view.translationX &&
            y >= view.top + view.translationY && y < view.bottom + view.translationY
        inside && (item.renderer?.interactiveAt(x - view.left - view.translationX, y - view.top - view.translationY) == true ||
            item.definition.optString("tap").isNotBlank() || view is FormView)
    }
    fun capturesHorizontalAt(x: Float, y: Float): Boolean = bound.asReversed().any { item ->
        val view = item.view
        view.visibility == VISIBLE && x >= view.left + view.translationX && x < view.right + view.translationX &&
            y >= view.top + view.translationY && y < view.bottom + view.translationY &&
            item.renderer?.capturesHorizontalAt(x - view.left - view.translationX, y - view.top - view.translationY) == true
    }
    val components: Int get() = bound.size
    /** Native panel geometry may reserve a single caption's natural width. */
    fun singleTextWidth(): Int? = (bound.singleOrNull()?.view as? TextView)?.let {
        ceil(android.text.Layout.getDesiredWidth(it.text, it.paint).toDouble()).toInt() + it.paddingLeft + it.paddingRight
    }
    fun singleTextMetrics(): JSONObject? = (bound.singleOrNull()?.view as? TextView)?.let { view ->
        val layout = view.layout ?: return@let null
        JSONObject().put("text", view.text.toString()).put("lines", layout.lineCount).put("layoutHeight", layout.height)
            .put("availableHeight", view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
            .put("ellipsis", (0 until layout.lineCount).sumOf { layout.getEllipsisCount(it) })
    }
    fun singleTextLine() { (bound.singleOrNull()?.view as? TextView)?.let { if (it.maxLines != 1) it.maxLines = 1 } }
    fun noticeLines() { (bound.singleOrNull()?.view as? TextView)?.let { it.maxLines = 2; it.ellipsize = android.text.TextUtils.TruncateAt.END } }
    val hasContent: Boolean get() = embeddedTile != null || bound.any { it.view.visibility != GONE }
    /** Width of one cell in a row of [count] children inside [inner] pixels: equal cells, never wider than a toggle needs. */
    private fun cellWidth(inner: Int, count: Int) = minOf(inner / count.coerceAtLeast(1), (104 * resources.displayMetrics.density).toInt())

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        embeddedTile?.let { it.measure(widthMeasureSpec, heightMeasureSpec); setMeasuredDimension(w, h); return }
        if (row) {
            val cell = cellWidth(w, PanelGeometry.quickColumns(bound.size))
            val cellHeight = h / PanelGeometry.quickRows(bound.size)
            bound.forEach { it.view.measure(MeasureSpec.makeMeasureSpec(cell, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(cellHeight, MeasureSpec.EXACTLY)) }
            setMeasuredDimension(w, h)
            return
        }
        val padding = if (card) (24 * resources.displayMetrics.density).toInt() else 0
        val innerW = (w - 2 * padding).coerceAtLeast(0); val innerH = (h - 2 * padding).coerceAtLeast(0)
        val visible = bound.filter { it.view.visibility != GONE }
        val dense = innerH / resources.displayMetrics.density < 340 && visible.size >= 4
        val weights = visible.map { item -> (item.definition.opt("weight") as? Number)?.toDouble() ?: item.renderer?.defaultWeight
            ?: if (item.view is ClockView || item.view is FormView) 1.0 else 0.0 }
        visible.forEach { item ->
            (item.view as? TextView)?.let { text ->
                text.textSize = when (item.definition.optString("style", "body")) {
                    "display" -> if (dense) TextScale.TITLE else TextScale.DISPLAY
                    "title" -> if (dense) TextScale.BODY else TextScale.TITLE
                    "caption" -> TextScale.CAPTION
                    else -> TextScale.BODY
                }
            }
            val maxH = if (item.view is IconView) minOf(innerH, (52 * resources.displayMetrics.density).toInt()) else innerH
            item.view.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH,
                if (item.view is IconView) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        }
        allocation = RenderGeometry.verticalHeights(innerH, visible.map { it.view.measuredHeight }, weights, ((if (dense) 6 else 12) * resources.displayMetrics.density).toInt())
        visible.forEachIndexed { index, item -> item.view.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(allocation.heights[index], MeasureSpec.EXACTLY)) }
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        embeddedTile?.let { it.layout(0, 0, width, height); return }
        if (row) {
            val columns = PanelGeometry.quickColumns(bound.size)
            val cell = cellWidth(width, columns)
            val cellHeight = height / PanelGeometry.quickRows(bound.size)
            val start = (width - cell * columns) / 2
            val mirrored = layoutDirection == LAYOUT_DIRECTION_RTL
            bound.forEachIndexed { index, item ->
                val column = index % columns
                val x = start + (if (mirrored) columns - 1 - column else column) * cell
                val y = index / columns * cellHeight
                item.view.layout(x, y, x + cell, y + cellHeight)
            }
            return
        }
        val padding = if (card) (24 * resources.displayMetrics.density).toInt() else 0
        var y = padding + allocation.offset
        bound.filter { it.view.visibility != GONE }.forEach { item ->
            val view = item.view
            view.layout(padding, y, (width - padding).coerceAtLeast(padding), y + view.measuredHeight)
            y += view.measuredHeight + allocation.gap
        }
    }
}

/** A stack keeps the adjacent card alive while dragging; the other stack is untouched. */
private class StackSlot(context: Context, private val group: (Int) -> ComponentGroup) : FrameLayout(context) {
    private var active = false
    fun active(value: Boolean) { active = value; current?.active(value); preview?.active(false) }
    fun back(): Boolean = current?.back() == true
    val hasBack: Boolean get() = current?.hasBack == true
    var index = 0
    var count = 0
    var current: ComponentGroup? = null
    private var preview: ComponentGroup? = null
    private var direction = 0
    private val springs = mutableListOf<ValueAnimator>()
    val activeAnimations: Boolean get() = current?.activeAnimations == true || preview?.activeAnimations == true
    val ambientAnimations: Boolean get() = current?.ambientAnimations == true || preview?.ambientAnimations == true

    /**
     * True from the moment a card is pulled until the last one has settled. At rest every card sits exactly in its stack with its own
     * rounded corners and nothing needs masking. A card that is moving leaves the stack, and clipChildren (a child only stays inside
     * its own bounds) and clipToPadding (needs padding) never held it back: it was drawn over the other stack. Clipping to an outline
     * does not do it either: on this Android only the outline's bounding rectangle cuts the children, never its rounded corners.
     * So while it moves, the stack is drawn into its own layer and cut to a rounded window the size of a card.
     * A flag rather than a look at the cards' positions: the renderer moves them without the stack drawing again.
     */
    private var masking = false
    private var windowMask: Bitmap? = null
    private val windowPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }

    private fun masked(on: Boolean) {
        if (masking == on) return
        masking = on
        invalidate()
    }

    /** Opaque inside a rounded rectangle the size of the stack, with smooth edges, clear in the four corners. */
    private fun window(): Bitmap = windowMask?.takeIf { it.width == width && it.height == height }
        ?: Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8).also {
            val radius = CARD_RADIUS_DP * resources.displayMetrics.density
            it.density = Bitmap.DENSITY_NONE
            Canvas(it).drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), radius, radius, Paint(Paint.ANTI_ALIAS_FLAG))
            windowMask = it
        }

    override fun dispatchDraw(canvas: Canvas) {
        if (!masking || width == 0 || height == 0) { super.dispatchDraw(canvas); return }
        val layer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        super.dispatchDraw(canvas)
        canvas.drawBitmap(window(), 0f, 0f, windowPaint)
        canvas.restoreToCount(layer)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); windowMask = null }

    /** A spring finished: the stack is at rest once none is left. */
    private fun settled() { if (springs.none { it.isRunning }) masked(false) }
    fun refresh() {
        cancel()
        val replacement = group(index.coerceAtMost((count - 1).coerceAtLeast(0)))
        current?.let { removeView(it) }
        current = replacement
        addView(replacement, LayoutParams(-1, -1))
        replacement.active(active)
    }
    fun drag(offset: Float): Boolean {
        if (count < 2) return false
        if (springs.isNotEmpty()) cancel()
        val nextDirection = if (offset < 0) 1 else -1
        if (preview == null || direction != nextDirection) {
            preview?.let(::removeView)
            direction = nextDirection
            preview = group(Math.floorMod(index + direction, count)).also { addView(it, LayoutParams(-1, -1)) }
        }
        masked(true)
        current?.translationY = offset
        preview?.translationY = offset + direction * height
        return true
    }
    fun release(offset: Float, velocity: Float) {
        val incoming = preview ?: return
        val commit = abs(offset) > height * .2f || abs(velocity) > 700 * resources.displayMetrics.density && offset * velocity > 0
        val old = current ?: return
        if (commit) {
            index = Math.floorMod(index + direction, count)
            current = incoming
            old.active(false)
            incoming.active(active)
            preview = null
            springs += Motion.spring(old.translationY, -direction * height.toFloat(), velocity, { old.translationY = it }) { removeView(old); settled() }
            springs += Motion.spring(incoming.translationY, 0f, velocity, { incoming.translationY = it }) { settled() }
        } else {
            preview = null
            springs += Motion.spring(old.translationY, 0f, velocity, { old.translationY = it }) { settled() }
            springs += Motion.spring(incoming.translationY, direction * height.toFloat(), velocity, { incoming.translationY = it }) { removeView(incoming); settled() }
        }
    }
    fun cancel() {
        springs.forEach(ValueAnimator::cancel)
        springs.clear()
        preview?.let(::removeView)
        preview = null
        current?.translationY = 0f
        // A new drag/update can interrupt a spring; never retain a third hidden card.
        (0 until childCount).map(::getChildAt).filter { it !== current }.forEach(::removeView)
        masked(false)
    }
    fun night(amount: Float) { current?.night(amount); preview?.night(amount) }
    fun tileViews(): List<TileView> = current?.tileViews().orEmpty()
    fun bind(id: String, paths: Set<String>) { current?.bind(id, paths); preview?.bind(id, paths) }
    fun localeChanged() { current?.localeChanged(); preview?.localeChanged() }
    fun colors(value: Palette) { current?.colors(value); preview?.colors(value) }
    fun tick(now: Long, attention: Attention) { current?.tick(now, attention); preview?.tick(now, attention) }
    override fun onDetachedFromWindow() { cancel(); super.onDetachedFromWindow() }
}

internal class PageView(
    context: Context,
    var page: PluginPage,
    val variant: String,
    private var widgets: List<PluginWidget>,
    private val events: PageEvents,
    private val state: (String) -> JSONObject
) : ViewGroup(context) {
    private val groups = linkedMapOf<String, ComponentGroup>()
    private val stacks = linkedMapOf<String, StackSlot>()
    private val tiles = linkedMapOf<Int, TileView>()
    private var aero: AeroHomeView? = null
    private var tileItems = JSONArray()
    private var tileBoxes = emptyList<RenderGeometry.Box?>()
    private var droppedWarning = ""
    private var night = 0f
    private var palette = Palette.DARK
    private var definitions = mutableMapOf<String, String>()
    private val stackItems = mutableMapOf<String, JSONArray>()
    private var dragging: StackSlot? = null
    private var previousLayout = ""
    private var contentDriftX = 0
    private var contentDriftY = 0
    private val cadence = TileCadence()
    private var title: TextView? = null
    private var titleBox: RenderGeometry.Box? = null
    private var backdrop: TileBackdrop? = null
    private fun liveTiles(): List<TileView> = tiles.values.filter { it.visibility == VISIBLE } + stacks.values.flatMap { it.tileViews() } + groups.values.flatMap { it.tileViews() }
    private var active = false
    fun active(value: Boolean) { if (!value) cadence.reset(); active = value; aero?.active(value); groups.values.forEach { it.active(value) }; stacks.values.forEach { it.active(value) }; tiles.values.forEach { it.active(value && it.visibility == VISIBLE) } }
    private val layout get() = page.view.optString("layout")
    val opaque: Boolean get() = layout in setOf("tiles", "aero") || photoStage || videoStage
    /** Content lists and forms need a quiet backdrop; this is computed only when the declaration changes. */
    private var quietBackdrop = false
    val coversAmbient: Boolean get() = opaque || quietBackdrop
    private val slotNames get() = LayoutVocabulary.slots[layout].orEmpty()
    private val roles get() = LayoutVocabulary.roles[layout].orEmpty()
    val photoStage: Boolean get() = layout == "stage" && groups.any { (name, group) -> roles[name] == "stage" && group.photo }
    /** A film fills the screen edge to edge like a photo does: if it drifted with the burn-in nudge, the backdrop would show along two edges. */
    val videoStage: Boolean get() = layout == "stage" && groups.any { (name, group) -> roles[name] == "stage" && group.video }
    val activeAnimations: Boolean get() = aero?.activeAnimations == true || groups.values.any { it.activeAnimations } || stacks.values.any { it.activeAnimations } || tiles.values.any { it.activeAnimations }
    val ambientAnimations: Boolean get() = aero?.ambientAnimations == true || groups.values.any { it.ambientAnimations } || stacks.values.any { it.ambientAnimations } || tiles.values.any { it.ambientAnimations }

    init { clipChildren = false; clipToPadding = false; update(page, widgets) }
    private fun resolve(name: String, index: Int): Triple<String, JSONObject, String?> {
        return resolveStackItem(page.pluginId, stackItems[name]?.opt(index), widgets, "glance")
    }
    fun update(next: PluginPage, nextWidgets: List<PluginWidget>) {
        page = next
        widgets = nextWidgets
        quietBackdrop = PageBackdrop.quiet(next.view)
        if (previousLayout.isNotEmpty() && previousLayout != layout) {
            groups.values.forEach { it.active(false) }; stacks.values.forEach { it.active(false) }; tiles.values.forEach { it.active(false) }
            removeAllViews()
            aero?.active(false); aero = null
            tiles.clear(); tileItems = JSONArray(); tileBoxes = emptyList()
            title = null; titleBox = null; backdrop = null; background = null
            groups.clear()
            stacks.clear()
            definitions.clear()
            contentDriftX = 0; contentDriftY = 0
        }
        previousLayout = layout
        contentDescription = localizedTitle()
        val slots = page.view.optJSONObject("slots") ?: JSONObject()
        if (layout == "aero") {
            val desktop = aero ?: AeroHomeView(context, events, state) { variant }.also { aero = it; addView(it) }
            desktop.update(page.pluginId, slots.optJSONArray("windows") ?: JSONArray(), widgets)
            desktop.colors(palette); desktop.night(night); desktop.active(active); requestLayout(); return
        }
        for (name in slotNames) {
            if (roles[name] == "tiles") {
                tileItems = slots.optJSONArray(name) ?: JSONArray()
                ensureTitle()
                tiles.keys.filter { it >= tileItems.length() }.toList().forEach { i -> tiles.remove(i)?.let { it.active(false); removeView(it) } }
                for (i in 0 until tileItems.length()) {
                    val reference = tileItems.getJSONObject(i)
                    val (owner, definition, opens) = resolveStackItem(page.pluginId, reference, widgets, "tile")
                    val tile = tiles.getOrPut(i) { TileView(context, state, { variant }, events).also { addView(it) } }
                    tile.update(page.pluginId, owner, definition, reference, opens, reference.optString("size"))
                    tile.colors(Palette.DARK)
                }
            } else if (roles[name] == "stack") {
                val items = slots.optJSONArray(name) ?: JSONArray()
                stackItems[name] = items
                val slot = stacks.getOrPut(name) {
                    StackSlot(context) { index ->
                        val (owner, definition, opens) = resolve(name, index)
                        ComponentGroup(context, owner, definition, state, { variant }, true, true, events).apply { this.opens = opens; colors(palette) }
                    }.also { addView(it) }
                }
                slot.count = items.length()
                val (owner, definition, opens) = resolve(name, slot.index.coerceAtMost((slot.count - 1).coerceAtLeast(0)))
                val signature = "$owner:${definition}:$opens:${items}"
                if (definitions[name] != signature) {
                    if (slot.current == null) slot.refresh()
                    else slot.current?.apply { this.opens = opens; update(owner, definition) }
                    definitions[name] = signature
                }
            } else {
                val declared = slots.optJSONObject(name)
                if (declared == null) { groups.remove(name)?.let(::removeView); continue }
                val (owner, definition, opens) = resolveStackItem(page.pluginId, declared, widgets, "glance")
                val signature = "$owner:$definition:$opens"
                if (definitions[name] != signature) {
                    val group = groups.getOrPut(name) {
                        ComponentGroup(context, owner, definition, state, { variant }, false, false, events, row = roles[name] == "row").also { addView(it) }
                    }
                    group.opens = opens
                    group.update(owner, definition)
                    group.colors(palette)
                    definitions[name] = signature
                }
            }
        }
        requestLayout()
        active(active)
        night(night)
    }
    fun night(amount: Float) { night = amount; aero?.night(amount); tiles.values.forEach { it.night(amount) }; groups.values.forEach { it.night(amount) }; stacks.values.forEach { it.night(amount) } }
    fun bind(pluginId: String, paths: Set<String>) {
        aero?.bind(pluginId,paths)
        groups.values.forEach { it.bind(pluginId, paths) }
        stacks.values.forEach { it.bind(pluginId, paths) }
        tiles.values.forEach { it.bind(pluginId, paths) }
        if (layout == "panel" && pluginId == page.pluginId && hasMedia() != mediaLoaded) {
            mediaLoaded = !mediaLoaded
            requestLayout()
        }
    }
    // The tile page is always the dark Start screen, whatever the theme (ADR-028).
    fun colors(value: Palette) { palette = value; applyBackdrop(); aero?.colors(value); groups.values.forEach { it.colors(value) }; stacks.values.forEach { it.colors(value) }; tiles.values.forEach { it.colors(Palette.DARK) } }
    private fun applyBackdrop() {
        if (layout != "tiles") { background = null; backdrop = null; return }
        val wanted = TileBackdrop.Variant.of(events.tilesBackground)
        if (backdrop?.variant != wanted) { backdrop = TileBackdrop(wanted); background = backdrop }
    }
    /** `tiles.background` changed in the settings page. */
    fun renderSettingsChanged() { if (layout == "tiles") applyBackdrop() }
    private fun ensureTitle() {
        val view = title ?: TextView(context).apply {
            setTextColor(0xf2ffffff.toInt())
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            includeFontPadding = false
            maxLines = 1
            gravity = Gravity.START or Gravity.TOP
            textSize = TextScale.TITLE
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }.also { title = it; addView(it) }
        val text = localizedTitle()
        if (view.text.toString() != text) view.text = text
    }
    private fun localizedTitle(): String = page.titleKey?.let { events.text(page.pluginId, it) } ?: page.title
    fun localeChanged() { contentDescription = localizedTitle(); title?.let { ensureTitle() }; aero?.localeChanged(); groups.values.forEach { it.localeChanged() }; stacks.values.forEach { it.localeChanged() }; tiles.values.forEach { it.localeChanged() } }
    /** The stage pins opaque backdrops; their foreground (and adjacent page previews) still drifts. */
    fun burnInOffset(x: Int, y: Int) {
        if (x == contentDriftX && y == contentDriftY) return
        aero?.burnInOffset(x, y)
        fun move(view: View) {
            if (view.isLaidOut) {
                view.offsetLeftAndRight(x - contentDriftX)
                view.offsetTopAndBottom(y - contentDriftY)
            }
        }
        groups.forEach { (name, group) ->
            if ((photoStage || videoStage) && roles[name] == "stage") group.burnInOffset(x, y)
            else move(group)
        }
        stacks.values.forEach(::move)
        tiles.values.forEach(::move)
        title?.let(::move)
        contentDriftX = x; contentDriftY = y
    }
    private var pressed: TileView? = null
    /** A finger went down on the Start page: the tile under it tilts toward the touch. */
    fun pressAt(x: Float, y: Float) {
        releasePress()
        groups.values.forEach { it.touchDown(x - it.left - it.translationX, y - it.top - it.translationY) }
        if (layout != "tiles") return
        val tile = tileAt(x, y) ?: return
        pressed = tile
        tile.press(x - tile.left, y - tile.top)
    }
    fun releasePress() { pressed?.release(); pressed = null }
    /** Back closed something on this page instead of leaving it: the Aero start menu, a component's own menu (ADR-040). */
    val hasBack: Boolean get() = aero?.hasBack == true || groups.values.any { it.hasBack } || stacks.values.any { it.hasBack }
    fun back(): Boolean = aero?.back() == true || groups.values.any { it.back() } || stacks.values.any { it.back() }
    /**
     * What turns when an app is opened or closed (ADR-040), in the order it turns: on the Start page the title and the tiles,
     * the one that was touched at [origin] last; on any other page the page as a whole.
     */
    fun flipTargets(origin: PointF?): List<View> {
        if (layout != "tiles") return listOf(this)
        val shown = tiles.entries.sortedBy { it.key }.map { it.value }.filter { it.visibility == VISIBLE && it.width > 0 }
        val hit = origin?.let { o -> shown.firstOrNull { o.x >= it.left && o.x < it.right && o.y >= it.top && o.y < it.bottom } }
        val rest = shown.filter { it !== hit }.let { list ->
            if (origin == null) list else list.sortedBy { Math.hypot((it.left + it.right) / 2.0 - origin.x, (it.top + it.bottom) / 2.0 - origin.y) }
        }
        return listOfNotNull(title?.takeIf { it.visibility == VISIBLE }) + rest + listOfNotNull(hit)
    }
    /** The page was opened rather than dragged in: its tiles rise in, one after another. */
    fun enterAnimation() {
        if (layout != "tiles") return
        tiles.entries.sortedBy { it.key }.map { it.value }.filter { it.visibility == VISIBLE }
            .forEachIndexed { index, tile -> tile.enter(minOf(index * 38L, 380L)) }
    }
    /** A plugin asked for a named tile animation (`tos.tile.animate`); only its own visible tiles play it. */
    fun playTileAnimation(owner: String, name: String) {
        if (!active || !events.tilesLive) return
        liveTiles().filter { it.owner == owner }.forEach { it.play(name) }
    }
    fun tick(now: Long, attention: Attention) {
        aero?.tick(now,attention)
        groups.values.forEach { it.tick(now, attention) }; stacks.values.forEach { it.tick(now, attention) }
        tiles.values.filter { it.visibility == VISIBLE }.forEach { it.tick(now, attention) }
        val visible = liveTiles()
        val allowed = active && events.tilesLive && attention in setOf(Attention.AWAKE, Attention.ENGAGED)
        if (!allowed) visible.forEach { it.stopFlip() }
        cadence.select(now, allowed, events.tilesIntervalMillis, visible.map { it.canFlip }, visible.any { it.flipping })?.let { visible[it].flip() }
    }
    fun animateAttention(waking: Boolean) {
        (groups.values.toList() + stacks.values.toList() + tiles.values.toList()).forEachIndexed { i, view ->
            view.animate().cancel()
            if (photoStage && groups.any { (name, group) -> roles[name] == "stage" && group === view }) {
                view.alpha = 1f; view.translationY = 0f
                return@forEachIndexed
            }
            val containsTile = view is TileView || view is ComponentGroup && view.tileViews().isNotEmpty() || view is StackSlot && view.tileViews().isNotEmpty()
            if (waking) { view.alpha = if (containsTile) 1f else .5f; view.translationY = 14 * resources.displayMetrics.density }
            view.animate().alpha(if (waking || containsTile) 1f else .84f).translationY(if (waking) 0f else 6 * resources.displayMetrics.density)
                .setStartDelay((i * 50).coerceAtMost(200).toLong()).setDuration(if (waking) 340 else 260).setInterpolator(Motion.EASE).start()
        }
    }
    private var draggingComponent: RenderComponent? = null
    fun dragVertical(x: Float, y: Float, offset: Float): Boolean {
        draggingComponent?.let { return it.dragVertical(offset) }
        for (group in groups.values.toList().asReversed()) {
            val renderer = group.dragVerticalAt(x - group.left - group.translationX, y - group.top - group.translationY, offset)
            if (renderer != null) { draggingComponent = renderer; return true }
        }
        return dragStack(x, y, offset)
    }
    fun releaseVertical(offset: Float, velocity: Float): Boolean {
        draggingComponent?.let {
            draggingComponent = null
            it.releaseVertical(offset, velocity)
            return true
        }
        return releaseStack(offset, velocity)
    }
    fun dragStack(x: Float, y: Float, offset: Float): Boolean {
        val slot = dragging ?: stacks.values.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }?.also { dragging = it }
        return slot?.drag(offset) == true
    }
    fun releaseStack(offset: Float, velocity: Float): Boolean {
        val slot = dragging ?: return false
        dragging = null
        if (slot.count < 2) return false
        slot.release(offset, velocity)
        return true
    }
    /** The plugin under a point: the card's own plugin on a stacked page, otherwise the page's plugin. */
    private fun tileAt(x: Float, y: Float): TileView? = tiles.values.firstOrNull { it.visibility == VISIBLE && x >= it.left && x < it.right && y >= it.top && y < it.bottom }
    /** The topmost slot under a point: an overlay is what was touched, not a referenced widget beneath it. */
    private fun slotAt(x: Float, y: Float): ComponentGroup? = groups.values.toList().asReversed().firstOrNull {
        x >= it.left && x < it.right && y >= it.top && y < it.bottom
    }
    fun ownerAt(x: Float, y: Float): String = aero?.ownerAt(x,y) ?: tileAt(x, y)?.owner ?:
        stacks.values.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }?.current?.owner ?:
        slotAt(x, y)?.owner ?: page.pluginId
    fun openAt(x: Float, y: Float): String? = tileAt(x, y)?.opens ?: stacks.values.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }?.current?.opens ?:
        slotAt(x, y)?.opens
    fun tapAt(x: Float, y: Float): Boolean {
        if (aero?.tapAt(x,y) == true || groups.values.toList().asReversed().any {
            it.tapAt(x - it.left - it.translationX, y - it.top - it.translationY)
        }) return true
        // A setup greeting is a whole-screen action. Explicit header/footer
        // controls above retain priority over the focus page's main action.
        if (layout == "focus" && "onboarding" in page.roles) {
            val action = page.view.optJSONObject("slots")?.optJSONObject("hero")?.optString("tap").orEmpty()
            if (action.isNotBlank()) {
                events.send(page.pluginId, "tap", listOf(action))
                return true
            }
        }
        return false
    }
    // Tile content is presentation only: stage-owned taps and long press reach opens/ownerAt.
    /** Native controls and lists own taps/vertical scrolling; the stage can still claim a horizontal page swipe. */
    fun interactiveAt(x: Float, y: Float): Boolean {
        if (aero?.interactiveAt(x,y) == true) return true
        for (group in groups.values.toList().asReversed()) {
            if (group.interactiveAt(x - group.left - group.translationX, y - group.top - group.translationY)) return true
        }
        return stacks.values.any { slot -> slot.current?.let { group ->
            group.interactiveAt(x - slot.left - group.left - group.translationX, y - slot.top - group.top - group.translationY)
        } == true }
    }
    /** True when the touch starts on a component that owns horizontal drags (the seek bar). */
    fun capturesHorizontalAt(x: Float, y: Float): Boolean {
        for (group in groups.values.toList().asReversed()) {
            if (group.capturesHorizontalAt(x - group.left - group.translationX, y - group.top - group.translationY)) return true
        }
        return stacks.values.any { slot -> slot.current?.let { group ->
            group.capturesHorizontalAt(x - slot.left - group.left - group.translationX, y - slot.top - group.top - group.translationY)
        } == true }
    }
    private fun rectangle(name: String, spec: LayoutSpec): Rect {
        val role = roles[name]
        if (layout == "form" && groups["hero"]?.hasContent == true) {
            val edge = minOf(spec.dp(20f), spec.width / 2, spec.height / 2)
            val heroHeight = minOf(spec.dp(104f), ((spec.height - edge * 2) / 3).coerceAtLeast(0))
            return if (name == "hero") Rect(edge, edge, spec.width - edge, edge + heroHeight)
            else Rect(edge, edge + heroHeight, spec.width - edge, spec.height - edge)
        }
        if (role == "stack") return spec.stack(slotNames.indexOf(name))
        if (layout == "stage" && role in setOf("header", "over") && groups[name]?.floatingClock == true) {
            val box = RenderGeometry.photoClock(spec.width, spec.height, spec.density)
            return Rect(box.left, box.top, box.right, box.bottom)
        }
        if (layout == "stage" && role == "under") {
            val inset = groups.filterKeys { roles[it] == "stage" }.values.maxOfOrNull { it.bottomOverlayInset } ?: 0
            groups[name]?.photoNotice(inset > 0)
            if (inset > 0) {
                val edge = minOf((24 * spec.density).toInt(), spec.width / 2, spec.height / 2)
                val bottom = (spec.height - inset - (8 * spec.density).toInt()).coerceAtLeast(edge)
                val top = (bottom - (32 * spec.density).toInt()).coerceAtLeast(edge)
                return Rect(edge, top, spec.width - edge, bottom)
            }
        }
        val header = groups.any { (name, group) -> roles[name] == "header" && group.hasContent }
        val footer = groups.any { (name, group) -> roles[name] == "footer" && group.hasContent }
        val box = RenderGeometry.slot(layout, role ?: "content", spec.width, spec.height, spec.density, header, footer)
        return Rect(box.left, box.top, box.right, box.bottom)
    }
    private fun slots(): Map<String, View> = groups + stacks

    /** The screen the control sheet (layout `panel`) is drawn on; its own size follows from this and what the plugin declared. */
    private var screenWidth = 0
    private var screenHeight = 0
    private var mediaLoaded = false
    fun panelScreen(width: Int, height: Int) {
        if (width == screenWidth && height == screenHeight) return
        screenWidth = width; screenHeight = height
        requestLayout()
    }
    /** The OS collapses the media slot while no session holds anything (ADR-035). */
    private fun hasMedia(): Boolean = groups.containsKey("media") && (state(page.pluginId).optJSONObject("media")?.optInt("count", 0) ?: 0) > 0
    private fun panelPlan(): PanelGeometry.Plan = PanelGeometry.plan(screenWidth, screenHeight, resources.displayMetrics.density,
        groups.containsKey("header"), groups.containsKey("quick"), groups["levels"]?.components ?: 0, hasMedia(), groups.containsKey("footer"),
        groups["footer"]?.singleTextWidth()?.plus((16 * resources.displayMetrics.density).toInt()),
        update = groups["update"]?.hasContent == true, quickCount = groups["quick"]?.components ?: 0)
    fun panelTextMetrics(): JSONObject = JSONObject().apply {
        for (name in listOf("header", "footer", "update")) groups[name]?.singleTextMetrics()?.let { put(name, it) }
    }
    private fun panelBox(name: String, plan: PanelGeometry.Plan): PanelGeometry.Box? = when (name) {
        "header" -> plan.header; "quick" -> plan.quick; "levels" -> plan.levels; "media" -> plan.media; "footer" -> plan.footer
        "update" -> plan.update
        else -> null
    }
    /** Size the sheet needs for what is declared and shown now; the stage asks before it places the page. */
    fun panelSize(): Pair<Int, Int> = panelPlan().let { it.width to it.height }

    private fun measurePanel(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        groups["header"]?.singleTextLine()
        groups["update"]?.noticeLines()
        val plan = panelPlan()
        groups.forEach { (name, group) ->
            val box = panelBox(name, plan)
            group.visibility = if (box == null) GONE else VISIBLE
            if (box != null) group.measure(MeasureSpec.makeMeasureSpec(box.width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(box.height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
    }
    private fun layoutPanel() {
        val plan = panelPlan()
        groups.forEach { (name, group) -> panelBox(name, plan)?.let { group.layout(it.left, it.top, it.right, it.bottom) } }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (layout == "panel") { measurePanel(widthMeasureSpec, heightMeasureSpec); return }
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val spec = LayoutSpec(w, h, resources.displayMetrics.density)
        aero?.measure(widthMeasureSpec,heightMeasureSpec)
        slots().forEach { (name, view) ->
            val rect = rectangle(name, spec)
            view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
        }
        tileBoxes = RenderGeometry.tiles(List(tileItems.length()) { tileItems.getJSONObject(it).optString("size") }, w, h, spec.density, layoutDirection == LAYOUT_DIRECTION_RTL)
        titleBox = if (layout == "tiles") RenderGeometry.tileTitle(w, h, spec.density, layoutDirection == LAYOUT_DIRECTION_RTL) else null
        title?.let { view ->
            val box = titleBox
            view.visibility = if (box == null) GONE else VISIBLE
            view.measure(MeasureSpec.makeMeasureSpec(box?.width ?: 0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(box?.height ?: 0, MeasureSpec.EXACTLY))
        }
        tiles.forEach { (i, view) ->
            val box = tileBoxes.getOrNull(i)
            view.visibility = if (box == null) GONE else VISIBLE
            view.active(active && box != null)
            view.measure(MeasureSpec.makeMeasureSpec(box?.width ?: 0, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(box?.height ?: 0, MeasureSpec.EXACTLY))
        }
        val dropped = tileBoxes.indices.filter { tileBoxes[it] == null }
        val warning = "$w:$h:$tileItems:$dropped"
        if (dropped.isNotEmpty() && droppedWarning != warning) { android.util.Log.w("ThinkOS", "tiles dropped (no space): $dropped on ${page.key}"); droppedWarning = warning }
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (layout == "panel") { layoutPanel(); return }
        aero?.layout(0,0,width,height)
        aero?.burnInOffset(contentDriftX, contentDriftY)
        val spec = LayoutSpec(width, height, resources.displayMetrics.density)
        tiles.forEach { (i, v) -> tileBoxes.getOrNull(i)?.let {
            v.layout(it.left + contentDriftX, it.top + contentDriftY, it.right + contentDriftX, it.bottom + contentDriftY)
        } }
        title?.let { view -> titleBox?.let { view.layout(it.left + contentDriftX, it.top + contentDriftY, it.right + contentDriftX, it.bottom + contentDriftY) } }
        // A photo or a film is pinned to the screen edges; only the rest of the page wanders with the burn-in nudge.
        val pinned = photoStage || videoStage
        slots().forEach { (name, view) ->
            val x = view.left
            val y = view.top
            val wasLaid = view.isLaidOut
            val rect = rectangle(name, spec)
            if (!pinned || roles[name] != "stage") rect.offset(contentDriftX, contentDriftY)
            view.layout(rect.left, rect.top, rect.right, rect.bottom)
            if (pinned && roles[name] == "stage") {
                // Neither attention nor resize transitions may reveal the backdrop at an edge.
                view.animate().cancel()
                view.alpha = 1f; view.translationX = 0f; view.translationY = 0f
                (view as? ComponentGroup)?.burnInOffset(contentDriftX, contentDriftY)
            } else if (changed && wasLaid && (x != rect.left || y != rect.top)) {
                view.translationX = (x - rect.left).toFloat()
                view.translationY = (y - rect.top).toFloat()
                view.animate().translationX(0f).translationY(0f).setDuration(Motion.PAGE).setInterpolator(Motion.EASE).start()
            }
        }
    }
}
