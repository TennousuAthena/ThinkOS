package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.core.Attention
import org.json.JSONObject

/** OS-owned density and typography. Bindings update this same tree, including its background. */
internal class TileView(
    context: Context,
    private val state: (String) -> JSONObject,
    private val variant: () -> String,
    private val events: PageEvents,
) : ViewGroup(context) {
    var owner = ""; private set
    var opens: String? = null; private set
    private var pageOwner = ""
    private var definition = JSONObject()
    private var signature = ""
    private var size = "large"
    private var faceIndex = 0
    private var faces = emptyList<Map<String, Binding.Template>>()
    private var rendered = emptyList<Map<String, String>>()
    private var cardColor: Binding.Template? = null
    private var pageColor: Binding.Template? = null
    private var badgeTemplate = Binding.compile("")
    private val warnings = mutableMapOf<String, String>()
    private var palette = Palette.DARK
    private var fill = palette.accent
    private var targetFill = fill
    private val shape = GradientDrawable()
    private var colorAnimation: ValueAnimator? = null
    private var active = false
    private var night = 0f
    private var content: ComponentGroup? = null
    private val icon = IconView(context)
    private fun text(sp: Float, medium: Boolean = false) = TextView(context).apply {
        textSize = sp; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = true; gravity = Gravity.START or Gravity.CENTER_VERTICAL
        typeface = Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }
    private val value = text(48f).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); setAutoSizeTextTypeUniformWithConfiguration(18, 48, 1, android.util.TypedValue.COMPLEX_UNIT_SP) }
    private val label = text(14f)
    private val detail = text(16f)
    private val badge = text(14f, true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val positions = mutableMapOf<View, RenderGeometry.Box>()
    private val face = object : ViewGroup(context) {
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
            arrange(w, h)
            for ((v, b) in positions) v.measure(exact(if (v.visibility == GONE) 0 else b.width), exact(if (v.visibility == GONE) 0 else b.height))
            setMeasuredDimension(w, h)
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            for ((v, box) in positions) v.layout(box.left, box.top, box.right, box.bottom)
        }
    }
    val ambientAnimations get() = active && icon.visibility == VISIBLE && icon.animating
    val activeAnimations get() = colorAnimation?.isRunning == true || flipping
    var flipping = false; private set
    val canFlip get() = eligible().size > 1
    init {
        background = shape; clipToOutline = true; clipChildren = true
        listOf(icon, value, label, detail).forEach { face.addView(it) }
        addView(face); addView(badge)
        applyFill(fill)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun exact(n: Int) = MeasureSpec.makeMeasureSpec(n.coerceAtLeast(0), MeasureSpec.EXACTLY)
    fun update(pageOwner: String, owner: String, declaration: JSONObject, reference: JSONObject, opens: String?, size: String) {
        val next = "$pageOwner:$owner:$declaration:$reference:$size"
        if (signature == next) return
        stopFlip()
        this.owner = owner; this.pageOwner = pageOwner; this.opens = opens; this.size = size
        signature = next; definition = declaration
        value.setAutoSizeTextTypeUniformWithConfiguration(18, if (size == "small") 28 else 48, 1, android.util.TypedValue.COMPLEX_UNIT_SP)
        val tile = declaration.optString("template") == "tile"
        if (!tile && declaration.has("template")) Log.w("ThinkOS", "glance in tiles: $owner; displaying empty tile")
        val array = if (tile) declaration.optJSONArray("faces") else null
        faces = List(array?.length() ?: 0) { i ->
            val f = array!!.getJSONObject(i)
            listOf("icon", "value", "label", "detail").associateWith { Binding.compile(f.optString(it)) }
        }
        cardColor = declaration.optString("color").takeIf { tile && it.isNotEmpty() }?.let(Binding::compile)
        pageColor = reference.optString("color").takeIf(String::isNotEmpty)?.let(Binding::compile)
        badgeTemplate = Binding.compile(if (tile) declaration.optString("badge") else "")
        content?.let { it.active(false); face.removeView(it) }; content = null
        if (tile && size in setOf("wide", "large")) declaration.optJSONObject("content")?.let {
            content = ComponentGroup(context, owner, it, state, variant, false, true, events).also { v -> face.addView(v); v.active(active) }
        }
        faceIndex = 0
        bind(owner, setOf(""), true)
    }
    fun bind(id: String, paths: Set<String>, force: Boolean = false) {
        if (!force && id != owner && id != pageOwner) return
        if (id == owner || force) {
            content?.bind(owner, paths, force)
            val snapshot = state(owner)
            val strings: (String) -> String? = { events.text(owner, it) }
            rendered = faces.map { f -> f.mapValues { it.value.render(snapshot, variant(), strings) } }
            if (faceIndex !in eligible()) faceIndex = eligible().firstOrNull() ?: 0
            showFace()
            val badgeText = badgeTemplate.render(snapshot, variant(), strings)
            if (badge.text.toString() != badgeText) badge.text = badgeText
            badge.visibility = if (badge.text.isEmpty()) GONE else VISIBLE
        }
        refreshColor(animate = !force)
    }
    private fun eligible(): List<Int> = rendered.indices.filter { i -> rendered[i]["value"].orEmpty().isNotBlank() || rendered[i]["detail"].orEmpty().isNotBlank() }
    private fun showFace() {
        val f = rendered.getOrNull(faceIndex).orEmpty()
        val labelOnly = size == "small" && rendered.isNotEmpty() && eligible().isEmpty()
        val hadValue = value.text.isNotEmpty()
        val hadIcon = icon.name.isNotBlank()
        icon.name = if (labelOnly) "" else f["icon"].orEmpty()
        if (hadIcon != icon.name.isNotBlank()) requestLayout()
        fun text(view: TextView, next: String) { if (view.text.toString() != next) view.text = next }
        text(value, if (labelOnly) f["label"].orEmpty() else f["value"].orEmpty())
        text(label, f["label"].orEmpty()); text(detail, f["detail"].orEmpty())
        if (hadValue != value.text.isNotEmpty()) requestLayout()
        val description = listOf(label.text, value.text, detail.text).filter { it.isNotBlank() }.distinct().joinToString(" · ")
        if (contentDescription?.toString() != description) contentDescription = description
    }
    private fun color(template: Binding.Template?, id: String, source: String): Int? {
        if (template == null) return null
        val raw = template.render(state(id), variant())
        val parsed = TileColor.parse(raw)
        if (parsed == null && warnings[source] != raw) {
            Log.w("ThinkOS", "tile color invalid ($id/$source): '$raw'; falling back")
            warnings[source] = raw
        } else if (parsed != null) warnings.remove(source)
        return parsed
    }
    private fun refreshColor(animate: Boolean) {
        val next = color(pageColor, pageOwner, "reference") ?: color(cardColor, owner, "card") ?: palette.accent
        if (next == targetFill) return
        targetFill = next
        colorAnimation?.cancel(); colorAnimation = null
        if (animate && active && isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            val from = fill
            colorAnimation = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = Motion.NUMBER; interpolator = Motion.EASE
                addUpdateListener { applyFill(Palette.mix(from, next, it.animatedValue as Float)) }
                start()
            }
        } else applyFill(next)
    }
    private fun applyFill(color: Int) {
        fill = color
        val safe = TileColor.nightSafe(color, night)
        shape.setColor(safe)
        val fg = TileColor.nightForeground(safe, night)
        val derived = Palette(safe, safe, fg, fg, fg, fg, fg, fg, fg)
        listOf(value, label, detail, badge).forEach { it.setTextColor(fg) }
        icon.palette = derived; content?.colors(derived)
    }
    fun colors(palette: Palette) { this.palette = palette; refreshColor(false); applyFill(fill) }
    fun night(amount: Float) { if (night != amount) { night = amount; applyFill(fill) } }
    fun localeChanged() { content?.localeChanged(); bind(owner, setOf(""), true) }
    fun active(value: Boolean) {
        active = value; content?.active(value)
        if (!value) { stopFlip(); colorAnimation?.cancel(); colorAnimation = null; applyFill(targetFill); icon.tick(0, Attention.AMBIENT) }
    }
    fun tick(now: Long, attention: Attention) {
        content?.tick(now, if (active) attention else Attention.AMBIENT)
        if (icon.visibility == VISIBLE) icon.tick(now, if (active) attention else Attention.AMBIENT)
        if (attention !in setOf(Attention.AWAKE, Attention.ENGAGED)) stopFlip()
    }
    fun flip() {
        val available = eligible()
        if (available.size < 2 || flipping) return
        val next = available[(available.indexOf(faceIndex) + 1).mod(available.size)]
        if (!ValueAnimator.areAnimatorsEnabled()) { faceIndex = next; showFace(); return }
        flipping = true
        face.setLayerType(LAYER_TYPE_HARDWARE, null)
        face.animate().translationY(-dp(16).toFloat()).alpha(0f).setDuration(Motion.TILE_EXIT).setInterpolator(Motion.EXIT).withEndAction {
            faceIndex = next; showFace(); face.translationY = dp(16).toFloat()
            face.animate().translationY(0f).alpha(1f).setDuration(Motion.TILE_ENTER).setInterpolator(Motion.EASE).withEndAction { stopFlip() }.start()
        }.start()
    }
    fun stopFlip() {
        face.animate().cancel(); face.animate().withEndAction(null)
        face.alpha = 1f; face.translationY = 0f; face.setLayerType(LAYER_TYPE_NONE, null); flipping = false
    }
    private fun arrange(w: Int, h: Int) {
        positions.clear()
        val p = minOf(dp(12), w / 2, h / 2); val iw = (w - 2*p).coerceAtLeast(0); val ih = (h - 2*p).coerceAtLeast(0)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun place(v: View, x: Int, y: Int, cw: Int, ch: Int, visible: Boolean = true) {
            v.visibility = if (visible && cw > 0 && ch > 0) VISIBLE else GONE
            val left = p + x.coerceIn(0, iw); val top = p + y.coerceIn(0, ih)
            val right = (left + cw.coerceAtLeast(0)).coerceAtMost(w - p); val bottom = (top + ch.coerceAtLeast(0)).coerceAtMost(h - p)
            positions[v] = if (rtl) RenderGeometry.Box(w-right, top, w-left, bottom) else RenderGeometry.Box(left, top, right, bottom)
        }
        val line = dp(30); val valH = dp(58); val iconSize = minOf(dp(if (size == "small") 44 else 40), iw, ih)
        if (size == "small") {
            place(icon, (iw-iconSize)/2, (ih-iconSize)/2, iconSize, iconSize, icon.name.isNotBlank())
            place(value, 0, 0, iw, ih, icon.name.isBlank()); value.gravity = Gravity.CENTER
            place(label, 0, 0, 0, 0, false); place(detail, 0, 0, 0, 0, false)
        } else {
            value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
            val hasContent = content != null
            val textW = if (size == "wide") (iw - dp(8)) / 2 else iw
            val textY = if (size == "large" && hasContent) (ih * .55f).toInt() else ((ih-line-iconSize-valH)/2).coerceAtLeast(0)
            place(icon, 0, textY, iconSize, iconSize, icon.name.isNotBlank())
            val y = textY + if (icon.name.isNotBlank()) iconSize else 0
            place(value, 0, y, textW, valH, value.text.isNotEmpty())
            place(label, 0, ih-line, textW, line)
            if (size == "wide") {
                place(detail, textW + dp(8), 0, textW, ih, !hasContent)
                content?.let { place(it, textW + dp(8), 0, textW, ih) }
            } else {
                place(detail, 0, (ih-line*2).coerceAtLeast(0), iw, line, size == "large")
                content?.let { place(it, 0, 0, iw, (ih*.5f).toInt()) }
            }
        }
    }
    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec); val h = MeasureSpec.getSize(hSpec)
        face.measure(exact(w), exact(h)); badge.measure(exact((w-dp(24)).coerceAtLeast(0)/2), exact(minOf(dp(28), h)))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        face.layout(0, 0, width, height)
        val p = minOf(dp(12), width/2)
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) p else width-p-badge.measuredWidth
        badge.layout(x, p, x+badge.measuredWidth, p+badge.measuredHeight)
    }
    override fun onDetachedFromWindow() { active(false); super.onDetachedFromWindow() }
}
