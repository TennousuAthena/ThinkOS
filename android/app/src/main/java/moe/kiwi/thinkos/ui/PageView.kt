package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
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
    private var allocation = RenderGeometry.VerticalAllocation(emptyList(), 0, 0)
    private val formHost = object : FormHost {
        override fun emit(name: String, args: List<Any?>) = events.send(pluginId, name, args)
        override fun done() = events.closeSettings()
        override fun interacted() = events.settingsInteraction()
    }
    val owner: String get() = pluginId
    var opens: String? = null
    val activeAnimations: Boolean get() = bound.any { it.renderer?.animating == true }
    private var signature = ""

    init {
        clipChildren = true
        clipToPadding = true
        if (card) background = shape
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
        val definitions = children(declaration)
        val nextSignature = owner + ":" + definitions.joinToString { it.toString() }
        if (nextSignature != signature) {
            removeAllViews()
            bound.clear()
            definitions.forEach { def ->
                val renderer = BuiltinComponents.create(context, def, compact) { event, index ->
                    events.send(pluginId, "tap", if (index == null) listOf(event) else listOf(event, index))
                } ?: if (def.optString("type") !in setOf("clock", "form", "icon", "text")) events.component(context, pluginId, def) else null
                val view = renderer?.view ?: when (def.optString("type")) {
                    "clock" -> ClockView(context, def, compact)
                    "form" -> FormView(context, formHost)
                    "icon" -> IconView(context)
                    "text" -> TextView(context).apply {
                        gravity = Gravity.CENTER; includeFontPadding = false; maxLines = 2
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
            }
            signature = nextSignature
            requestLayout()
        }
        bind(pluginId, setOf(""), force = true)
        colors(palette)
    }
    fun bind(changedPlugin: String, paths: Set<String>, force: Boolean = false) {
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
        if (card) shape.setColor(value.surface)
        bound.forEach { item ->
            item.renderer?.colors(value)
            when (val view = item.view) {
                is TextView -> view.setTextColor(if (item.definition.optString("style") in setOf("body", "caption")) value.secondary else value.primary)
                is ClockView -> view.palette = value
                is FormView -> view.colors(value)
                is IconView -> view.palette = value
            }
        }
    }
    fun tick(now: Long, attention: Attention) {
        bound.forEach { item ->
            item.renderer?.tick(now, attention)
            (item.view as? ClockView)?.tick(now, attention)
        }
    }
    fun localeChanged() {
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
        val padding = if (card) (24 * resources.displayMetrics.density).toInt() else 0
        val innerW = (w - 2 * padding).coerceAtLeast(0); val innerH = (h - 2 * padding).coerceAtLeast(0)
        val weights = bound.map { item -> (item.definition.opt("weight") as? Number)?.toDouble() ?: item.renderer?.defaultWeight
            ?: if (item.view is ClockView || item.view is FormView) 1.0 else 0.0 }
        bound.forEach { item ->
            val maxH = if (item.view is IconView) minOf(innerH, (52 * resources.displayMetrics.density).toInt()) else innerH
            item.view.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(maxH,
                if (item.view is IconView) MeasureSpec.EXACTLY else MeasureSpec.AT_MOST))
        }
        allocation = RenderGeometry.verticalHeights(innerH, bound.map { it.view.measuredHeight }, weights, (12 * resources.displayMetrics.density).toInt())
        bound.forEachIndexed { index, item -> item.view.measure(MeasureSpec.makeMeasureSpec(innerW, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(allocation.heights[index], MeasureSpec.EXACTLY)) }
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
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
    var index = 0
    var count = 0
    var current: ComponentGroup? = null
    private var preview: ComponentGroup? = null
    private var direction = 0
    private val springs = mutableListOf<ValueAnimator>()
    val activeAnimations: Boolean get() = current?.activeAnimations == true || preview?.activeAnimations == true

    init { clipChildren = true; clipToPadding = true }
    fun refresh() {
        cancel()
        val replacement = group(index.coerceAtMost((count - 1).coerceAtLeast(0)))
        current?.let { removeView(it) }
        current = replacement
        addView(replacement, LayoutParams(-1, -1))
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
    private var palette = Palette.DARK
    private var definitions = mutableMapOf<String, String>()
    private val stackItems = mutableMapOf<String, JSONArray>()
    private var dragging: StackSlot? = null
    private var previousLayout = ""
    private val layout get() = page.view.optString("layout")
    private val slotNames get() = LayoutVocabulary.slots[layout].orEmpty()
    private val roles get() = LayoutVocabulary.roles[layout].orEmpty()
    val activeAnimations: Boolean get() = groups.values.any { it.activeAnimations } || stacks.values.any { it.activeAnimations }

    init { clipChildren = false; clipToPadding = false; update(page, widgets) }
    private fun resolve(name: String, index: Int): Triple<String, JSONObject, String?> {
        return resolveStackItem(page.pluginId, stackItems[name]?.opt(index), widgets)
    }
    fun update(next: PluginPage, nextWidgets: List<PluginWidget>) {
        page = next
        widgets = nextWidgets
        if (previousLayout.isNotEmpty() && previousLayout != layout) {
            removeAllViews()
            groups.clear()
            stacks.clear()
            definitions.clear()
        }
        previousLayout = layout
        contentDescription = page.title
        val slots = page.view.optJSONObject("slots") ?: JSONObject()
        for (name in slotNames) {
            if (roles[name] == "stack") {
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
    }
    fun bind(pluginId: String, paths: Set<String>) {
        groups.values.forEach { it.bind(pluginId, paths) }
        stacks.values.forEach { it.bind(pluginId, paths) }
    }
    fun colors(value: Palette) { palette = value; groups.values.forEach { it.colors(value) }; stacks.values.forEach { it.colors(value) } }
    fun localeChanged() { groups.values.forEach { it.localeChanged() }; stacks.values.forEach { it.localeChanged() } }
    fun tick(now: Long, attention: Attention) { groups.values.forEach { it.tick(now, attention) }; stacks.values.forEach { it.tick(now, attention) } }
    fun animateAttention(waking: Boolean) {
        (groups.values.toList() + stacks.values.toList()).forEachIndexed { i, view ->
            view.animate().cancel()
            if (waking) { view.alpha = .5f; view.translationY = 14 * resources.displayMetrics.density }
            view.animate().alpha(if (waking) 1f else .84f).translationY(if (waking) 0f else 6 * resources.displayMetrics.density)
                .setStartDelay((i * 50).coerceAtMost(200).toLong()).setDuration(if (waking) 340 else 260).setInterpolator(Motion.EASE).start()
        }
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
    fun ownerAt(x: Float, y: Float): String =
        stacks.values.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }?.current?.owner ?: page.pluginId
    fun openAt(x: Float, y: Float): String? = stacks.values.firstOrNull { x >= it.left && x <= it.right && y >= it.top && y <= it.bottom }?.current?.opens
    /** Native controls and lists own taps/vertical scrolling; the stage can still claim a horizontal page swipe. */
    fun interactiveAt(x: Float, y: Float): Boolean {
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
        slots().forEach { (name, view) ->
            val rect = rectangle(name, spec)
            view.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val spec = LayoutSpec(width, height, resources.displayMetrics.density)
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
