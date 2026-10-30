package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
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
) : ViewGroup(context) {
    private class Bound(val view: View, val definition: JSONObject, val template: Binding.Template,
        val renderer: RenderComponent?, val dependencies: List<Binding.Template>) {
        var formatTemplate: Binding.Template? = null
        var zoneTemplates: List<Pair<Binding.Template, Binding.Template?>> = emptyList()
        fun templates(): List<Binding.Template> = dependencies + listOfNotNull(template, formatTemplate) + zoneTemplates.flatMap { listOfNotNull(it.first, it.second) }
    }
    private val strings: (String) -> String? = { key -> events.text(pluginId, key) }
    private val bound = mutableListOf<Bound>()
    private val shape = GradientDrawable().apply { cornerRadius = 32f * resources.displayMetrics.density }
    private var palette = Palette.DARK
    private var embeddedTile: TileView? = null
    private var photoNotice = false
    private var allocation = RenderGeometry.VerticalAllocation(emptyList(), 0, 0)
    private val formHost = object : FormHost {
        override fun emit(name: String, args: List<Any?>) = events.send(pluginId, name, args)
        override fun done() = events.closeSettings()
        override fun interacted() = events.settingsInteraction()
    }
    val owner: String get() = pluginId
    val floatingClock: Boolean get() = bound.singleOrNull()?.view.let { it is ClockView && it.clockStyle == "float" }
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
                is String -> result += Binding.compile(if (key in setOf("path", "sun", "items", "values") && "{{" !in value) "{{$value}}" else value)
            }
        }
        visit(definition)
        // Functional media controls consume the OS-owned state without asking plugins to
        // invent redundant bindings just to refresh a play/pause icon or video visibility.
        if (definition.optString("type") in setOf("controls", "video")) result += Binding.compile("{{media}}")
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
                val renderer = if(desktop && def.optString("type")=="clock" && def.optString("style")=="analog") AeroClockView(context) else BuiltinComponents.create(context, def, compact) { event, index ->
                    events.send(pluginId, "tap", if (index == null) listOf(event) else listOf(event, index))
                } ?: if (def.optString("type") !in setOf("clock", "form", "icon", "text")) events.component(context, pluginId, def) else null
                val view = renderer?.view ?: when (def.optString("type")) {
                    "clock" -> ClockView(context, def, compact)
                    "form" -> FormView(context, formHost)
                    "icon" -> IconView(context)
                    "text" -> (if(desktop) TileTextView(context) else TextView(context)).apply {
                        gravity = Gravity.CENTER; includeFontPadding = false; maxLines = if(desktop && def.optString("style")=="caption") 4 else 2
                        ellipsize = TextUtils.TruncateAt.END
                        val style = def.optString("style", "body")
                        textSize = when (style) { "display" -> 72f; "title" -> 32f; "caption" -> 14f; else -> 22f }
                        typeface = Typeface.create(when (style) { "display" -> "sans-serif-light"; "title" -> "sans-serif-medium"; else -> "sans-serif" }, Typeface.NORMAL)
                        fontFeatureSettings = "tnum"
                    }
                    else -> error("OS renderer unavailable: ${def.optString("type")}")
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
                if (renderer?.handlesTap != true) def.optString("tap").takeIf { it.isNotBlank() }?.let { event ->
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
                is TextView -> if (view.text.toString() != next) {
                    view.text = next
                    if (view.isLaidOut) {
                        view.translationY = 4f * resources.displayMetrics.density
                        view.animate().translationY(0f).setDuration(Motion.NUMBER).setInterpolator(Motion.EASE).start()
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
                    view.setHeader(Binding.compile(item.definition.optString("title")).render(snapshot, variant(), strings), Binding.compile(item.definition.optString("done")).render(snapshot, variant(), strings))
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
    fun tileViews(): List<TileView> = listOfNotNull(embeddedTile)
    /** Native visuals stay live above Aero's cached static text/chrome. */
    fun desktopVisuals(): List<View> = embeddedTile?.let { listOf(it) } ?: bound.filter {
        it.renderer != null || it.view is ClockView || it.view is IconView
    }.map { it.view }
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
    fun active(value: Boolean) { active = value; embeddedTile?.active(value); bound.forEach { it.renderer?.active(value) } }
    fun tapAt(x: Float, y: Float): Boolean = bound.asReversed().any { item ->
        val v = item.view
        val hit = v.visibility == VISIBLE && x >= v.left && x < v.right && y >= v.top && y < v.bottom
        hit && (item.renderer?.tap() == true || item.definition.optString("tap").isNotBlank() && v.performClick())
    }
    fun dragVerticalAt(x: Float, y: Float, offset: Float): RenderComponent? {
        for (item in bound.asReversed()) {
            val v = item.view
            if (v.visibility == VISIBLE && x >= v.left && x < v.right && y >= v.top && y < v.bottom &&
                item.renderer?.dragVertical(offset) == true) return item.renderer
        }
        return null
    }
    fun localeChanged() {
        embeddedTile?.localeChanged()
        bound.forEach { item -> item.renderer?.localeChanged(); (item.view as? ClockView)?.refreshLocale() }
        bind(pluginId, setOf(""), force = true)
    }
    fun interactiveAt(x: Float, y: Float): Boolean = bound.asReversed().any { item ->
        val view = item.view
        val interactive = item.renderer?.interactive == true || item.definition.optString("tap").isNotBlank() || view is FormView
        interactive && view.visibility == VISIBLE && x >= view.left + view.translationX && x < view.right + view.translationX &&
            y >= view.top + view.translationY && y < view.bottom + view.translationY
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec); val h = MeasureSpec.getSize(heightMeasureSpec)
        embeddedTile?.let { it.measure(widthMeasureSpec, heightMeasureSpec); setMeasuredDimension(w, h); return }
        val padding = if (card) (24 * resources.displayMetrics.density).toInt() else 0
        val innerW = (w - 2 * padding).coerceAtLeast(0); val innerH = (h - 2 * padding).coerceAtLeast(0)
        val dense = innerH / resources.displayMetrics.density < 340 && bound.size >= 4
        val weights = bound.map { item -> (item.definition.opt("weight") as? Number)?.toDouble() ?: item.renderer?.defaultWeight
            ?: if (item.view is ClockView || item.view is FormView) 1.0 else 0.0 }
        bound.forEach { item ->
            (item.view as? TextView)?.let { text ->
                text.textSize = when (item.definition.optString("style", "body")) {
                    "display" -> if (dense) 56f else 72f
                    "title" -> if (dense) 24f else 32f
                    "caption" -> 14f
                    else -> if (dense) 18f else 22f
                }
            }
            val maxH = if (item.view is IconView) minOf(innerH, (52 * resources.displayMetrics.density).toInt()) else innerH
            item.view.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH,
                if (item.view is IconView) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        }
        allocation = RenderGeometry.verticalHeights(innerH, bound.map { it.view.measuredHeight }, weights, ((if (dense) 6 else 12) * resources.displayMetrics.density).toInt())
        bound.forEachIndexed { index, item -> item.view.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(allocation.heights[index], MeasureSpec.EXACTLY)) }
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        embeddedTile?.let { it.layout(0, 0, width, height); return }
        val padding = if (card) (24 * resources.displayMetrics.density).toInt() else 0
        var y = padding + allocation.offset
        bound.forEach { item ->
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
    var index = 0
    var count = 0
    var current: ComponentGroup? = null
    private var preview: ComponentGroup? = null
    private var direction = 0
    private val springs = mutableListOf<ValueAnimator>()
    val activeAnimations: Boolean get() = current?.activeAnimations == true || preview?.activeAnimations == true
    val ambientAnimations: Boolean get() = current?.ambientAnimations == true || preview?.ambientAnimations == true

    init { clipChildren = true; clipToPadding = true }
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
            springs += Motion.spring(old.translationY, -direction * height.toFloat(), velocity, { old.translationY = it }) { removeView(old) }
            springs += Motion.spring(incoming.translationY, 0f, velocity, { incoming.translationY = it })
        } else {
            preview = null
            springs += Motion.spring(old.translationY, 0f, velocity, { old.translationY = it })
            springs += Motion.spring(incoming.translationY, direction * height.toFloat(), velocity, { incoming.translationY = it }) { removeView(incoming) }
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
    private val cadence = TileCadence()
    private fun liveTiles(): List<TileView> = tiles.values.filter { it.visibility == VISIBLE } + stacks.values.flatMap { it.tileViews() } + groups.values.flatMap { it.tileViews() }
    private var active = false
    fun active(value: Boolean) { if (!value) cadence.reset(); active = value; aero?.active(value); groups.values.forEach { it.active(value) }; stacks.values.forEach { it.active(value) }; tiles.values.forEach { it.active(value && it.visibility == VISIBLE) } }
    private val layout get() = page.view.optString("layout")
    val opaque: Boolean get() = layout in setOf("tiles", "aero")
    private val slotNames get() = LayoutVocabulary.slots[layout].orEmpty()
    private val roles get() = LayoutVocabulary.roles[layout].orEmpty()
    val activeAnimations: Boolean get() = aero?.activeAnimations == true || groups.values.any { it.activeAnimations } || stacks.values.any { it.activeAnimations } || tiles.values.any { it.activeAnimations }
    val ambientAnimations: Boolean get() = aero?.ambientAnimations == true || groups.values.any { it.ambientAnimations } || stacks.values.any { it.ambientAnimations } || tiles.values.any { it.ambientAnimations }

    init { clipChildren = false; clipToPadding = false; update(page, widgets) }
    private fun resolve(name: String, index: Int): Triple<String, JSONObject, String?> {
        return resolveStackItem(page.pluginId, stackItems[name]?.opt(index), widgets)
    }
    fun update(next: PluginPage, nextWidgets: List<PluginWidget>) {
        page = next
        widgets = nextWidgets
        if (previousLayout.isNotEmpty() && previousLayout != layout) {
            groups.values.forEach { it.active(false) }; stacks.values.forEach { it.active(false) }; tiles.values.forEach { it.active(false) }
            removeAllViews()
            aero?.active(false); aero = null
            tiles.clear(); tileItems = JSONArray(); tileBoxes = emptyList()
            groups.clear()
            stacks.clear()
            definitions.clear()
        }
        previousLayout = layout
        contentDescription = page.title
        val slots = page.view.optJSONObject("slots") ?: JSONObject()
        if (layout == "aero") {
            val desktop = aero ?: AeroHomeView(context, events, state) { variant }.also { aero = it; addView(it) }
            desktop.update(page.pluginId, slots.optJSONArray("windows") ?: JSONArray(), widgets)
            desktop.colors(palette); desktop.night(night); desktop.active(active); requestLayout(); return
        }
        for (name in slotNames) {
            if (roles[name] == "tiles") {
                tileItems = slots.optJSONArray(name) ?: JSONArray()
                tiles.keys.filter { it >= tileItems.length() }.toList().forEach { i -> tiles.remove(i)?.let { it.active(false); removeView(it) } }
                for (i in 0 until tileItems.length()) {
                    val reference = tileItems.getJSONObject(i)
                    val (owner, definition, opens) = resolveStackItem(page.pluginId, reference, widgets)
                    val tile = tiles.getOrPut(i) { TileView(context, state, { variant }, events).also { addView(it) } }
                    tile.update(page.pluginId, owner, definition, reference, opens, reference.optString("size"))
                    tile.colors(palette)
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
                val definition = slots.optJSONObject(name)
                if (definition == null) { groups.remove(name)?.let(::removeView); continue }
                val signature = definition.toString()
                if (definitions[name] != signature) {
                    val group = groups.getOrPut(name) {
                        ComponentGroup(context, page.pluginId, definition, state, { variant }, false, false, events).also { addView(it) }
                    }
                    group.update(page.pluginId, definition)
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
    }
    fun colors(value: Palette) { palette = value; if (layout == "tiles") setBackgroundColor(value.bg) else background = null; aero?.colors(value); groups.values.forEach { it.colors(value) }; stacks.values.forEach { it.colors(value) }; tiles.values.forEach { it.colors(value) } }
    fun localeChanged() { aero?.localeChanged(); groups.values.forEach { it.localeChanged() }; stacks.values.forEach { it.localeChanged() }; tiles.values.forEach { it.localeChanged() } }
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
    fun ownerAt(x: Float, y: Float): String = aero?.ownerAt(x,y) ?: tileAt(x, y)?.owner ?:
        stacks.values.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }?.current?.owner ?: page.pluginId
    fun openAt(x: Float, y: Float): String? = tileAt(x, y)?.opens ?: stacks.values.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }?.current?.opens
    fun tapAt(x: Float, y: Float): Boolean = aero?.tapAt(x,y) == true || groups.values.toList().asReversed().any {
        it.tapAt(x - it.left - it.translationX, y - it.top - it.translationY)
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
    private fun rectangle(name: String, spec: LayoutSpec): Rect {
        val role = roles[name]
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
        val header = groups.keys.any { roles[it] == "header" }
        val footer = groups.keys.any { roles[it] == "footer" }
        val box = RenderGeometry.slot(layout, role ?: "content", spec.width, spec.height, spec.density, header, footer)
        return Rect(box.left, box.top, box.right, box.bottom)
    }
    private fun slots(): Map<String, View> = groups + stacks
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val spec = LayoutSpec(w, h, resources.displayMetrics.density)
        aero?.measure(widthMeasureSpec,heightMeasureSpec)
        slots().forEach { (name, view) ->
            val rect = rectangle(name, spec)
            view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
        }
        tileBoxes = RenderGeometry.tiles(List(tileItems.length()) { tileItems.getJSONObject(it).optString("size") }, w, h, spec.density, layoutDirection == LAYOUT_DIRECTION_RTL)
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
        aero?.layout(0,0,width,height)
        val spec = LayoutSpec(width, height, resources.displayMetrics.density)
        tiles.forEach { (i, v) -> tileBoxes.getOrNull(i)?.let { v.layout(it.left, it.top, it.right, it.bottom) } }
        slots().forEach { (name, view) ->
            val x = view.left
            val y = view.top
            val wasLaid = view.isLaidOut
            val rect = rectangle(name, spec)
            view.layout(rect.left, rect.top, rect.right, rect.bottom)
            if (changed && wasLaid && (x != rect.left || y != rect.top)) {
                view.translationX = (x - rect.left).toFloat()
                view.translationY = (y - rect.top).toFloat()
                view.animate().translationX(0f).translationY(0f).setDuration(Motion.PAGE).setInterpolator(Motion.EASE).start()
            }
        }
    }
}
