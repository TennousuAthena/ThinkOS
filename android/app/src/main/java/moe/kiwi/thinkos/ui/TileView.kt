package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
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
    var owner = ""
        private set

    var opens: String? = null
        private set

    private var pageOwner = ""
    private var definition = JSONObject()
    private var signature = ""
    private var size = "large"
    private var faceIndex = 0
    private var faces = emptyList<Map<String, Binding.Template>>()
    private var rendered = emptyList<Map<String, String>>()
    private var eligibleFaces = emptyList<Int>()
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
    /** Distinct content components of this tile; a face may bring its own (API 6) or share the tile's. */
    private var contentGroups = emptyList<ComponentGroup>()
    private var faceContents = emptyList<ComponentGroup?>()
    private var content: ComponentGroup? = null
    private var picture: RenderComponent? = null
    private var pictureSource = Binding.compile("")
    private var showingPicture = false
    private val scrim = View(context).apply {
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0, 0xC0000000.toInt()))
        visibility = GONE
    }
    private val icon = IconView(context)

    private fun text(sp: Float, medium: Boolean = false) =
        TileTextView(context).apply {
            textSize = sp
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = true
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            typeface =
                Typeface.create(if (medium) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
        }

    private val value = text(TextScale.DISPLAY)
    private val label = text(TextScale.CAPTION)
    private val detail = text(TextScale.CAPTION).apply { typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val badge = text(TextScale.CAPTION, true).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
    private val positions = mutableMapOf<View, RenderGeometry.Box>()
    private val face =
        object : ViewGroup(context) {
            override fun onMeasure(wSpec: Int, hSpec: Int) {
                val w = MeasureSpec.getSize(wSpec)
                val h = MeasureSpec.getSize(hSpec)
                arrange(w, h)
                for ((v, b) in positions) v.measure(
                    exact(if (v.visibility == GONE) 0 else b.width),
                    exact(if (v.visibility == GONE) 0 else b.height),
                )
                setMeasuredDimension(w, h)
            }

            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                for ((v, box) in positions) if (v !== label)
                    v.layout(box.left, box.top, box.right, box.bottom)
            }
        }
    val ambientAnimations
        get() = active && icon.visibility == VISIBLE && icon.animating

    val activeAnimations
        get() = colorAnimation?.isRunning == true || flipping || picture?.animating == true

    var flipping = false
        private set

    val canFlip
        get() = eligibleFaces.size > 1

    init {
        icon.tileGlyph = true
        background = shape
        clipToOutline = true
        clipChildren = true
        listOf(icon, value, detail).forEach { face.addView(it) }
        addView(scrim)
        addView(face)
        addView(label)
        addView(badge)
        applyFill(fill)
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()

    // ---- Metro micro-animations (ADR-028). Poses are OS vocabulary: plugins can only ask for a name.

    /** Tilt toward the finger like a physical button; [x] and [y] are in this tile's own coordinates. */
    fun press(x: Float, y: Float) {
        if (!ValueAnimator.areAnimatorsEnabled() || width == 0 || height == 0 || visibility != VISIBLE) return
        cameraDistance = resources.displayMetrics.density * 1400f
        val nx = (x / width * 2f - 1f).coerceIn(-1f, 1f)
        val ny = (y / height * 2f - 1f).coerceIn(-1f, 1f)
        animate().cancel()
        animate()
            .rotationY(nx * TILT_DEGREES)
            .rotationX(-ny * TILT_DEGREES)
            .scaleX(PRESSED_SCALE)
            .scaleY(PRESSED_SCALE)
            .alpha(1f)
            .setStartDelay(0)
            .setDuration(90)
            .setInterpolator(Motion.EASE)
            .start()
    }

    /** Spring back, with the slight overshoot that makes the press feel physical. */
    fun release() {
        if (rotationX == 0f && rotationY == 0f && scaleX == 1f && scaleY == 1f) return
        animate().cancel()
        animate()
            .rotationX(0f)
            .rotationY(0f)
            .scaleX(1f)
            .scaleY(1f)
            .setStartDelay(0)
            .setDuration(300)
            .setInterpolator(android.view.animation.OvershootInterpolator(2.6f))
            .start()
    }

    /** Page entrance: each tile rises into place after the one before it. */
    fun enter(delay: Long) {
        if (!ValueAnimator.areAnimatorsEnabled()) return
        animate().cancel()
        alpha = 0f
        scaleX = .9f
        scaleY = .9f
        translationY = dp(26).toFloat()
        animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setStartDelay(delay)
            .setDuration(380)
            .setInterpolator(Motion.EASE)
            .start()
    }

    /** A plugin asked for [name]. Returns false when nothing was played. */
    fun play(name: String): Boolean {
        if (!ValueAnimator.areAnimatorsEnabled() || width == 0) return false
        when (name) {
            "pulse" -> {
                animate().cancel()
                animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .setStartDelay(0)
                    .setDuration(110)
                    .setInterpolator(Motion.EASE)
                    .withEndAction {
                        animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(340)
                            .setInterpolator(android.view.animation.OvershootInterpolator(3f))
                            .start()
                    }
                    .start()
            }
            "nudge" -> {
                val d = dp(10).toFloat()
                animate().cancel()
                translationX = 0f
                android.animation.ObjectAnimator.ofFloat(this, TRANSLATION_X, 0f, -d, d, -d * .6f, d * .6f, -d * .25f, 0f)
                    .setDuration(360)
                    .start()
            }
            "flip" -> {
                if (!canFlip || flipping) return false
                flip()
            }
            else -> return false
        }
        return true
    }

    private fun resetMotion() {
        animate().cancel()
        alpha = 1f
        scaleX = 1f
        scaleY = 1f
        translationY = 0f
        rotationX = 0f
        rotationY = 0f
    }

    private fun exact(n: Int) = MeasureSpec.makeMeasureSpec(n.coerceAtLeast(0), MeasureSpec.EXACTLY)

    /** Height of the fixed name rail: the label never slides with the face. */
    private fun rail() = if (size == "small") dp(8 + 16) else dp(12 + 20 + 2)

    fun update(
        pageOwner: String,
        owner: String,
        declaration: JSONObject,
        reference: JSONObject,
        opens: String?,
        size: String,
    ) {
        val next = "$pageOwner:$owner:$declaration:$reference:$size"
        if (signature == next) return
        stopFlip()
        this.owner = owner
        this.pageOwner = pageOwner
        this.opens = opens
        this.size = size
        signature = next
        definition = declaration
        val tile = declaration.optString("template") == "tile"
        if (!tile && declaration.has("template"))
            Log.w("ThinkOS", "glance in tiles: $owner; displaying empty tile")
        val array = if (tile) declaration.optJSONArray("faces") else null
        faces =
            List(array?.length() ?: 0) { i ->
                val f = array!!.getJSONObject(i)
                listOf("icon", "value", "label", "detail").associateWith {
                    Binding.compile(f.optString(it))
                }
            }
        cardColor =
            declaration.optString("color").takeIf { tile && it.isNotEmpty() }?.let(Binding::compile)
        pageColor = reference.optString("color").takeIf(String::isNotEmpty)?.let(Binding::compile)
        badgeTemplate = Binding.compile(if (tile) declaration.optString("badge") else "")
        contentGroups.forEach {
            it.active(false)
            face.removeView(it)
        }
        contentGroups = emptyList()
        faceContents = emptyList()
        content = null
        picture?.let {
            it.active(false)
            removeView(it.view)
        }
        picture = null
        showingPicture = false
        val photo = declaration.optJSONObject("image").takeIf { tile }
        pictureSource = Binding.compile(photo?.optString("src").orEmpty())
        picture = photo?.let { events.component(context, owner, it) }?.also {
            addView(it.view, 0)
            it.colors(palette)
            it.active(active)
        }
        // Content shows only where there is room for it. A face's own content wins over the tile's.
        if (tile && size in setOf("wide", "large")) {
            val groups = linkedMapOf<String, ComponentGroup>()
            faceContents =
                List(array?.length() ?: 0) { i ->
                    val declared =
                        array!!.getJSONObject(i).optJSONObject("content")
                            ?: declaration.optJSONObject("content")
                    declared?.let {
                        groups.getOrPut(it.toString()) {
                            ComponentGroup(context, owner, it, state, variant, false, true, events)
                                .also { v ->
                                    v.visibility = GONE
                                    face.addView(v)
                                }
                        }
                    }
                }
            contentGroups = groups.values.toList()
        }
        faceIndex = 0
        styleTexts()
        bind(owner, setOf(""), true)
    }

    /** Value is a big light number, except over a photo where it is a two-line title. */
    private fun styleTexts() {
        if (showingPicture) {
            value.setAutoSizeTextTypeWithDefaults(TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            value.textSize = TextScale.BODY
            value.maxLines = 2
            value.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            detail.textSize = TextScale.CAPTION
            detail.maxLines = 2
        } else {
            value.maxLines = 1
            value.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            value.setAutoSizeTextTypeUniformWithPresetSizes(
                TextScale.presets(if (size == "small") TextScale.TITLE else TextScale.DISPLAY),
                android.util.TypedValue.COMPLEX_UNIT_SP,
            )
            detail.textSize = TextScale.CAPTION
            detail.maxLines = 1
        }
        label.textSize = TextScale.CAPTION
    }

    fun bind(id: String, paths: Set<String>, force: Boolean = false) {
        if (!force && id != owner && id != pageOwner) return
        if (id == owner || force) {
            contentGroups.forEach { it.bind(owner, paths, force) }
            val snapshot = state(owner)
            val strings: (String) -> String? = { events.text(owner, it) }
            picture?.let { photo ->
                photo.bind(snapshot, variant(), strings)
                val next = pictureSource.render(snapshot, variant(), strings).isNotBlank()
                if (next != showingPicture) {
                    showingPicture = next
                    photo.view.visibility = if (next) VISIBLE else GONE
                    scrim.visibility = photo.view.visibility
                    styleTexts()
                    face.requestLayout()
                    applyFill(fill)
                }
            }
            if (force || faces.any { f -> f.values.any { it.affected(paths) } }) {
                rendered = faces.map { f ->
                    f.mapValues { it.value.render(snapshot, variant(), strings) }
                }
                eligibleFaces =
                    rendered.indices.filter { i ->
                        rendered[i]["value"].orEmpty().isNotBlank() ||
                            rendered[i]["detail"].orEmpty().isNotBlank() ||
                            faceContents.getOrNull(i) != null
                    }
                if (faceIndex !in eligibleFaces) faceIndex = eligibleFaces.firstOrNull() ?: 0
                showFace()
            }
            val badgeText = badgeTemplate.render(snapshot, variant(), strings)
            if (badge.text.toString() != badgeText) badge.text = badgeText
            badge.visibility = if (badge.text.isEmpty()) GONE else VISIBLE
        }
        refreshColor(animate = !force)
    }

    private fun showFace() {
        val f = rendered.getOrNull(faceIndex).orEmpty()
        val labelOnly =
            size == "small" &&
                rendered.isNotEmpty() &&
                eligibleFaces.isEmpty() &&
                f["icon"].isNullOrBlank()
        val hadValue = value.text.isNotEmpty()
        val hadIcon = icon.name.isNotBlank()
        icon.name = if (labelOnly) "" else f["icon"].orEmpty()
        if (hadIcon != icon.name.isNotBlank()) requestLayout()
        fun text(view: TextView, next: String) {
            if (view.text.toString() != next) view.text = next
        }
        text(value, if (labelOnly) f["label"].orEmpty() else f["value"].orEmpty())
        text(label, f["label"].orEmpty())
        text(detail, f["detail"].orEmpty())
        if (hadValue != value.text.isNotEmpty()) requestLayout()
        val nextContent = faceContents.getOrNull(faceIndex)
        if (nextContent !== content) {
            content?.let {
                it.active(false)
                it.visibility = GONE
            }
            content = nextContent
            content?.let {
                it.visibility = VISIBLE
                it.active(active)
            }
            requestLayout()
        }
        val description =
            listOf(label.text, value.text, detail.text)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString("  ")
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
        val next =
            color(pageColor, pageOwner, "reference")
                ?: color(cardColor, owner, "card")
                ?: palette.accent
        if (next == targetFill) return
        targetFill = next
        colorAnimation?.cancel()
        colorAnimation = null
        if (animate && active && isLaidOut && ValueAnimator.areAnimatorsEnabled()) {
            val from = fill
            colorAnimation =
                ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = Motion.NUMBER
                    interpolator = Motion.EASE
                    addUpdateListener {
                        applyFill(Palette.mix(from, next, it.animatedValue as Float))
                    }
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
        // A photo sits under its own scrim, whatever the fallback colour would have chosen.
        listOf(value, label, detail, badge).forEach { it.setTextColor(if (showingPicture) Color.WHITE else fg) }
        icon.palette = derived
        contentGroups.forEach { it.colors(derived) }
    }

    fun colors(palette: Palette) {
        this.palette = palette
        picture?.colors(palette)
        refreshColor(false)
        applyFill(fill)
    }

    fun night(amount: Float) {
        if (night != amount) {
            night = amount
            applyFill(fill)
        }
    }

    fun localeChanged() {
        contentGroups.forEach { it.localeChanged() }
        picture?.localeChanged()
        bind(owner, setOf(""), true)
    }

    fun active(value: Boolean) {
        val was = active
        active = value
        content?.active(value)
        picture?.active(value)
        if (!value) {
            if (was) resetMotion()
            stopFlip()
            colorAnimation?.cancel()
            colorAnimation = null
            applyFill(targetFill)
            icon.tick(0, Attention.AMBIENT)
        }
    }

    fun tick(now: Long, attention: Attention) {
        content?.tick(now, if (active) attention else Attention.AMBIENT)
        picture?.tick(now, if (active) attention else Attention.AMBIENT)
        if (icon.visibility == VISIBLE) icon.tick(now, if (active) attention else Attention.AMBIENT)
        if (attention !in setOf(Attention.AWAKE, Attention.ENGAGED)) stopFlip()
    }

    fun flip() {
        val available = eligibleFaces
        if (available.size < 2 || flipping) return
        val next = available[(available.indexOf(faceIndex) + 1).mod(available.size)]
        if (!ValueAnimator.areAnimatorsEnabled()) {
            faceIndex = next
            showFace()
            return
        }
        flipping = true
        face.setLayerType(LAYER_TYPE_HARDWARE, null)
        val distance = (height - rail()).toFloat()
        face
            .animate()
            .translationY(-distance)
            .setDuration(Motion.TILE_EXIT)
            .setInterpolator(Motion.EXIT)
            .withEndAction {
                faceIndex = next
                showFace()
                face.translationY = distance
                face
                    .animate()
                    .translationY(0f)
                    .alpha(1f)
                    .setDuration(Motion.TILE_ENTER)
                    .setInterpolator(Motion.EASE)
                    .withEndAction { stopFlip() }
                    .start()
            }
            .start()
    }

    fun stopFlip() {
        face.animate().cancel()
        face.animate().withEndAction(null)
        face.alpha = 1f
        face.translationY = 0f
        face.setLayerType(LAYER_TYPE_NONE, null)
        flipping = false
    }

    /**
     * Metro layout: icon top-left, a large light number, a name on a fixed bottom rail. Wide tiles add a
     * detail line and, when the face brings one, a content component filling the middle. All geometry is OS
     * owned; the face only supplies text, an icon and optionally one of the fixed content components.
     */
    private fun arrange(w: Int, h: Int) {
        positions.clear()
        val small = size == "small"
        val p = minOf(dp(if (small) 8 else 12), w / 2, h / 2)
        val iw = (w - 2 * p).coerceAtLeast(0)
        val ih = (h - 2 * p).coerceAtLeast(0)
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        fun place(v: View, x: Int, y: Int, cw: Int, ch: Int, visible: Boolean = true) {
            v.visibility = if (visible && cw > 0 && ch > 0) VISIBLE else GONE
            val left = p + x.coerceIn(0, iw)
            val top = p + y.coerceIn(0, ih)
            val right = (left + cw.coerceAtLeast(0)).coerceAtMost(w - p)
            val bottom = (top + ch.coerceAtLeast(0)).coerceAtMost(h - p)
            positions[v] =
                if (rtl) RenderGeometry.Box(w - right, top, w - left, bottom)
                else RenderGeometry.Box(left, top, right, bottom)
            val box = positions.getValue(v)
            if (v.layoutParams.width != box.width || v.layoutParams.height != box.height) {
                v.layoutParams.width = box.width
                v.layoutParams.height = box.height
            }
        }
        val labelH = dp(if (small) 16 else 20)
        val hasLabel = label.text.isNotEmpty()
        val body = (ih - if (hasLabel) labelH + dp(4) else 0).coerceAtLeast(0)
        val hasIcon = icon.name.isNotBlank()
        val hasValue = value.text.isNotEmpty()
        val hasDetail = detail.text.isNotEmpty()
        val badgeReserve = if (badge.visibility == VISIBLE) dp(30) else 0
        place(label, 0, ih - labelH, iw - badgeReserve, labelH, hasLabel)
        content?.let { place(it, 0, 0, 0, 0, false) }

        if (showingPicture) {
            // A full-bleed photo: a title and a credit stack above the name, over the scrim.
            val credit = if (hasDetail && !small) dp(30) else 0
            val title = if (hasValue && !small) dp(if (size == "large") 46 else 40) else 0
            value.gravity = Gravity.START or Gravity.BOTTOM
            detail.gravity = Gravity.START or Gravity.TOP
            place(icon, 0, 0, 0, 0, false)
            place(detail, 0, body - credit, iw, credit, credit > 0)
            place(value, 0, body - credit - title, iw, title, title > 0)
            return
        }
        value.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        detail.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        if (small) {
            val glyph = minOf(dp(36), iw, body)
            place(icon, (iw - glyph) / 2, (body - glyph) / 2, glyph, glyph, hasIcon && !hasValue)
            place(value, 0, 0, iw, body, hasValue)
            place(detail, 0, 0, 0, 0, false)
            return
        }
        if (size == "medium") {
            val glyph = if (hasValue) minOf(dp(36), body, iw) else minOf(dp(76), iw, body)
            place(icon, if (hasValue) 0 else (iw - glyph) / 2, if (hasValue) 0 else (body - glyph) / 2, glyph, glyph, hasIcon)
            val top = if (hasIcon && hasValue) glyph + dp(2) else 0
            place(value, 0, top, iw, body - top, hasValue)
            place(detail, 0, 0, 0, 0, false)
            return
        }
        // wide and large
        val detailH = if (hasDetail) dp(22) else 0
        val box = content
        if (box != null) {
            var y = 0
            if (size == "large") {
                val shown = (body * .5f).toInt()
                place(box, 0, 0, iw, shown)
                y = shown + dp(4)
            }
            val rowH = if (size == "large") dp(56) else dp(32)
            if (hasValue) {
                place(value, 0, y, iw, rowH)
                y += rowH
            } else place(value, 0, 0, 0, 0, false)
            place(detail, 0, y, iw, detailH, hasDetail)
            y += detailH
            if (size == "wide") place(box, 0, y + dp(4), iw, (body - y - dp(4)).coerceAtLeast(0))
            place(icon, 0, 0, 0, 0, false)
            return
        }
        val glyph = if (size == "large") dp(40) else dp(30)
        place(icon, 0, 0, glyph, glyph, hasIcon)
        val top = if (hasIcon) glyph + dp(2) else 0
        value.gravity = Gravity.START or Gravity.BOTTOM
        place(detail, 0, body - detailH, iw, detailH, hasDetail)
        place(value, 0, top, iw, (body - detailH - top).coerceAtLeast(0), hasValue)
    }

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        val w = MeasureSpec.getSize(wSpec)
        val h = MeasureSpec.getSize(hSpec)
        picture?.view?.measure(exact(w), exact(h))
        scrim.measure(exact(w), exact((h * .62f).toInt()))
        face.measure(exact(w), exact(h))
        badge.measure(
            exact(minOf(dp(52), (w - dp(24)).coerceAtLeast(0) / 2)),
            exact(minOf(dp(28), h)),
        )
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        picture?.view?.layout(0, 0, width, height)
        scrim.layout(0, height - scrim.measuredHeight, width, height)
        face.layout(0, 0, width, height)
        positions[label]?.let { label.layout(it.left, it.top, it.right, it.bottom) }
        val p = minOf(dp(12), width / 2)
        val x = if (layoutDirection == LAYOUT_DIRECTION_RTL) p else width - p - badge.measuredWidth
        val y = (height - p - badge.measuredHeight).coerceAtLeast(0)
        badge.layout(x, y, x + badge.measuredWidth, y + badge.measuredHeight)
    }

    override fun dispatchDraw(canvas: android.graphics.Canvas) {
        val time = drawingTime
        picture?.view?.takeIf { it.visibility == VISIBLE }?.let { drawChild(canvas, it, time) }
        if (scrim.visibility == VISIBLE) drawChild(canvas, scrim, time)
        canvas.save()
        canvas.clipRect(0, 0, width, if (size == "small") height else (height - rail()).coerceAtLeast(0))
        drawChild(canvas, face, time)
        canvas.restore()
        if (label.visibility == VISIBLE) drawChild(canvas, label, time)
        if (badge.visibility == VISIBLE) drawChild(canvas, badge, time)
    }

    override fun onDetachedFromWindow() {
        active(false)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val TILT_DEGREES = 7f
        const val PRESSED_SCALE = .965f
    }
}
