package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.*
import moe.kiwi.thinkos.anim.*
import moe.kiwi.thinkos.core.*
import moe.kiwi.thinkos.plugin.PluginPage
import moe.kiwi.thinkos.plugin.PluginWidget
import org.json.JSONObject
import kotlin.math.*

/** Four independent layers. Dragging owns at most the current page and its neighbor. */
class ThinkStage(
    context: Context,
    private val store: Store,
    private val router: DeckRouter,
    private val onInteraction: () -> Unit,
    private val onMaintenance: () -> Unit,
    private val events: PageEvents,
    /** A long press on content owned by [pluginId]: open that plugin's settings (ADR-017). */
    private val onLongPress: (pluginId: String) -> Unit,
) : FrameLayout(context) {
    private val ambient = AmbientLayer(context)
    private val pageHost = FrameLayout(context)
    private val overlay = FrameLayout(context)
    private val system = SystemLayer(context)
    private val overlayText = TextView(context).apply { textSize = 16f; gravity = Gravity.CENTER; setPadding(20, 12, 20, 12) }
    private val boot = TextView(context).apply { text = "ThinkOS"; textSize = 36f; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val states = mutableMapOf<String, JSONObject>()
    private var pages = emptyList<PluginPage>()
    private var widgets = emptyList<PluginWidget>()
    private var current: PageView? = null
    private var neighbor: PageView? = null
    private var deck = DeckState()
    private var lastAttention = store.current.attention
    private var palette = Palette.forTheme(store.current.theme)
    private var fromPalette = palette
    private var targetPalette = palette
    private var theme = store.current.theme
    private var themeStart = 0L
    private var themeAnimating = false
    private var night = if (theme == Theme.NIGHT) 1f else 0f
    private var nightFrom = night
    private var nightTo = night
    private val nightPaint = Paint()
    private val governor = FrameGovernor()
    private val clock = MotionClock(governor, ::tick)
    private var scope: CoroutineScope? = null
    private var collection: Job? = null
    private var downX = 0f
    private var downY = 0f
    private var axis = 0 // 1 horizontal, 2 vertical
    private var dragDirection = 0
    private var offset = 0f
    private var stackDrag = false
    private var commitDrag = false
    private var velocity: VelocityTracker? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val springs = mutableListOf<ValueAnimator>()
    private val handler = Handler(Looper.getMainLooper())
    private var maintenanceArmed = false
    private val maintenance = Runnable { if (maintenanceArmed) { maintenanceArmed = false; onMaintenance() } }
    private var lastDrift = 0L
    private var driftX = 0f
    private var driftY = 0f
    private var wakeUntil = 0L
    private var longPressArmed = false
    private var longPressFired = false
    private val pressFeedback = Runnable {
        if (longPressArmed) pageHost.animate().scaleX(.985f).scaleY(.985f).setDuration(LONG_PRESS_MILLIS - FEEDBACK_AFTER_MILLIS).setInterpolator(Motion.EASE).start()
    }
    private val longPress = Runnable {
        if (!longPressArmed) return@Runnable
        longPressArmed = false
        longPressFired = true
        pageHost.animate().cancel()
        pageHost.scaleX = 1f
        pageHost.scaleY = 1f
        onLongPress(current?.ownerAt(downX, downY) ?: return@Runnable)
    }
    private fun armLongPress() {
        longPressArmed = true
        longPressFired = false
        handler.postDelayed(pressFeedback, FEEDBACK_AFTER_MILLIS)
        handler.postDelayed(longPress, LONG_PRESS_MILLIS)
    }
    private fun disarmLongPress() {
        if (longPressArmed) pageHost.animate().cancel().also { pageHost.animate().scaleX(1f).scaleY(1f).setDuration(160).start() }
        longPressArmed = false
        handler.removeCallbacks(pressFeedback)
        handler.removeCallbacks(longPress)
    }

    init {
        clipChildren = false
        clipToPadding = false
        addView(ambient, LayoutParams(-1, -1))
        addView(pageHost, LayoutParams(-1, -1))
        addView(overlay, LayoutParams(-1, -1))
        addView(system, LayoutParams(-1, -1))
        pageHost.addView(boot, LayoutParams(-1, -1))
        overlay.addView(overlayText, LayoutParams(-1, -2, Gravity.TOP))
        overlayText.visibility = GONE
        system.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        ambient.importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        runCatching { governor.reducedMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
        applyColors(palette)
        applyNight()
    }
    fun start() {
        if (collection != null) return
        val nextScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = nextScope
        collection = nextScope.launch {
            store.state.collect { value ->
                governor.attention = value.attention
                ambient.mode(value.attention, governor.reducedMotion)
                router.applyAttention(value.attention)
                router.applyTheme(value.theme)
                if (value.theme != theme) {
                    theme = value.theme
                    fromPalette = palette
                    targetPalette = Palette.forTheme(theme)
                    nightFrom = night
                    nightTo = if (theme == Theme.NIGHT) 1f else 0f
                    themeStart = SystemClock.uptimeMillis()
                    themeAnimating = true
                }
                renderDeck(store.current.deck)
                val message = if (value.safeMode) context.getString(moe.kiwi.thinkos.R.string.safe_mode_banner) else value.overlay.message
                overlayText.text = message.orEmpty()
                overlayText.visibility = if (message == null) GONE else VISIBLE
                if (lastAttention != value.attention) {
                    val waking = value.attention in setOf(Attention.AWAKE, Attention.ENGAGED) && lastAttention in setOf(Attention.ASLEEP, Attention.AMBIENT)
                    if (waking) {
                        system.wakeAt = SystemClock.uptimeMillis()
                        wakeUntil = system.wakeAt + 750
                    }
                    if (waking || value.attention in setOf(Attention.AMBIENT, Attention.ASLEEP)) current?.animateAttention(waking)
                    current?.tick(SystemClock.uptimeMillis(), value.attention)
                    lastAttention = value.attention
                }
                clock.wake()
            }
        }
        clock.start()
        current?.active(true)
    }
    fun stop() {
        collection?.cancel()
        collection = null
        scope?.cancel()
        scope = null
        clock.stop()
        current?.active(false)
        ambient.pause()
        current?.tick(SystemClock.uptimeMillis(), Attention.ASLEEP)
        neighbor?.tick(SystemClock.uptimeMillis(), Attention.ASLEEP)
        cancelGesture()
        handler.removeCallbacks(maintenance)
        maintenanceArmed = false
    }
    fun updateCatalog(nextPages: List<PluginPage>, nextWidgets: List<PluginWidget>) {
        pages = nextPages
        widgets = nextWidgets
        val visible = current
        val replacement = pages.firstOrNull { it.key == visible?.page?.key }
        if (visible != null && replacement != null) {
            // Same page: recompile changed declarations in place, without replaying navigation.
            visible.update(replacement, widgets)
        }
        renderDeck(store.current.deck)
        applyDrift()
    }
    /** Language, 12/24-hour setting or time zone changed in Android's settings. */
    fun localeChanged() {
        layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault())
        current?.localeChanged()
        neighbor?.localeChanged()
    }
    fun updatePluginState(pluginId: String, state: JSONObject) {
        val changed = Binding.changed(states.put(pluginId, state), state)
        if (changed.isEmpty()) return
        current?.bind(pluginId, changed)
        neighbor?.bind(pluginId, changed)
    }
    private fun newPage(page: PluginPage, variant: String): PageView = PageView(context, page, variant, widgets, events) { states[it] ?: JSONObject() }.also {
        it.colors(palette)
        it.night(night)
        it.tick(SystemClock.uptimeMillis(), store.current.attention)
    }
    private fun renderDeck(next: DeckState) {
        if (next.page == deck.page && next.variant == deck.variant && current != null) return
        val page = pages.firstOrNull { it.key == next.page } ?: return
        val old = current
        val preview = neighbor?.takeIf { it.page.key == next.page && it.variant == next.variant && commitDrag }
        val incoming = preview ?: newPage(page, next.variant).also { pageHost.addView(it, LayoutParams(-1, -1)) }
        if (preview == null) neighbor?.let(pageHost::removeView)
        neighbor = old
        current = incoming
        old?.active(false)
        incoming.active(true)
        pageHost.removeView(boot)
        val vertical = next.vertical && next.page == deck.page
        val direction = if (next.direction == 0) 1 else next.direction
        val extent = if (vertical) height.toFloat() else width.toFloat()
        if (old != null && old.page.key == next.page && !next.vertical) {
            // A removed hot-update variant is a local replacement, never a page slide.
            incoming.alpha = 0f
            incoming.scaleX = .99f
            incoming.scaleY = .99f
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
                pageHost.removeView(old)
                if (neighbor === old) neighbor = null
                applyDrift()
            }.start()
            old.animate().alpha(0f).setDuration(180).start()
        } else if (old != null && !governor.reducedMotion) {
            if (preview == null) position(incoming, direction * extent, vertical)
            val speed = if (commitDrag) velocity?.let { if (vertical) it.yVelocity else it.xVelocity } ?: 0f else 0f
            springs += Motion.spring(position(incoming, vertical), 0f, speed, { position(incoming, it, vertical) })
            springs += Motion.spring(position(old, vertical), -direction * extent, speed, { position(old, it, vertical) }) {
                pageHost.removeView(old)
                if (neighbor === old) neighbor = null
                applyDrift()
            }
        } else {
            old?.let(pageHost::removeView)
            neighbor = null
            position(incoming, 0f, vertical)
        }
        commitDrag = false
        deck = next
        val navigable = router.navigablePages
        system.pageCount = navigable.size
        system.pageIndex = navigable.indexOf(page)
        system.variantCount = page.variants.size
        system.variantIndex = page.variants.indexOf(next.variant)
        system.shownAt = SystemClock.uptimeMillis()
        applyDrift()
        clock.wake()
    }
    private fun position(view: View, vertical: Boolean): Float = if (vertical) view.translationY else view.translationX
    private fun position(view: View, value: Float, vertical: Boolean) {
        if (vertical) view.translationY = value else view.translationX = value
    }
    private fun syncAmbient() { ambient.covered(current?.opaque == true && neighbor == null && axis == 0) }
    private fun applyDrift() {
        val photoVisible = current?.photoStage == true || neighbor?.photoStage == true
        pageHost.translationX = if (photoVisible) 0f else driftX
        pageHost.translationY = if (photoVisible) 0f else driftY
        val x = if (photoVisible) driftX.roundToInt() else 0
        val y = if (photoVisible) driftY.roundToInt() else 0
        current?.burnInOffset(x, y)
        neighbor?.burnInOffset(x, y)
    }
    private fun prepareNeighbor(direction: Int, vertical: Boolean) {
        ambient.covered(false)
        if (dragDirection == direction && neighbor != null) return
        neighbor?.let(pageHost::removeView)
        neighbor = null
        dragDirection = direction
        val page = if (vertical) router.currentPage else router.previewPage(direction)
        val variant = if (vertical) router.previewVariant(direction) else page?.let(router::rememberedVariant)
        if (page != null && variant != null) {
            neighbor = newPage(page, variant).also { pageHost.addView(it, LayoutParams(-1, -1)) }
        }
        applyDrift()
    }
    private fun cancelGesture() {
        springs.forEach(ValueAnimator::cancel)
        springs.clear()
        neighbor?.let(pageHost::removeView)
        neighbor = null
        current?.apply { translationX = 0f; translationY = 0f }
        current?.releaseVertical(0f, 0f)
        axis = 0
        dragDirection = 0
        stackDrag = false
        commitDrag = false
        velocity?.recycle()
        velocity = null
        applyDrift()
    }
    fun refreshSettings() { current?.tick(SystemClock.uptimeMillis(), store.current.attention); clock.wake() }
    private fun tick(now: Long) {
        if (themeAnimating) {
            val t = ((now - themeStart).toFloat() / Motion.THEME).coerceIn(0f, 1f)
            palette = fromPalette.blend(targetPalette, t * t * (3 - 2 * t))
            applyColors(palette)
            val n = ((now - themeStart).toFloat() / Motion.NIGHT).coerceIn(0f, 1f)
            night = nightFrom + (nightTo - nightFrom) * n * n * (3 - 2 * n)
            applyNight()
            themeAnimating = t < 1
        }
        syncAmbient()
        ambient.tick(now)
        current?.tick(now, governor.attention)
        neighbor?.tick(now, governor.attention)
        if (now - lastDrift >= 1_000) {
            driftX = (round(sin(now / 180_000.0 * Math.PI) * 64) / 8).toFloat()
            driftY = (round(cos(now / 240_000.0 * Math.PI) * 48) / 8).toFloat()
            lastDrift = now
        }
        applyDrift()
        val systemActive = now < wakeUntil || now - system.shownAt < 2_600
        if (systemActive || system.wakeAt != 0L && now >= wakeUntil) {
            if (now >= wakeUntil) system.wakeAt = 0
            system.invalidate()
        }
        governor.activeAnimations = themeAnimating || systemActive || current?.activeAnimations == true || neighbor?.activeAnimations == true
        governor.ambientAnimations = current?.ambientAnimations == true || neighbor?.ambientAnimations == true
    }
    private fun applyColors(value: Palette) {
        ambient.colors(value)
        boot.setTextColor(value.primary)
        current?.colors(value)
        neighbor?.colors(value)
        overlayText.setTextColor(value.secondary)
        system.palette = value
        system.invalidate()
    }
    private fun applyNight() {
        current?.night(night)
        neighbor?.night(night)
        if (night <= 0f) { setLayerType(LAYER_TYPE_NONE, null); return }
        val gray = floatArrayOf(.2126f, .7152f, .0722f)
        val matrix = FloatArray(20)
        val channels = floatArrayOf(1f, 59 / 255f, 47 / 255f)
        for (row in 0..2) for (column in 0..2) matrix[row * 5 + column] = (if (row == column) 1f - night else 0f) + gray[column] * channels[row] * night
        matrix[18] = 1f
        nightPaint.colorFilter = ColorMatrixColorFilter(matrix)
        setLayerType(LAYER_TYPE_HARDWARE, nightPaint)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cancelGesture()
        store.update { it.copy(orientation = if (w > h) Orientation.LANDSCAPE else Orientation.PORTRAIT) }
    }
    private var nativeTouch = false
    private var gestureDownTime = -1L

    private fun beginTouch(event: MotionEvent) {
        gestureDownTime = event.downTime
        cancelGesture()
        downX = event.x; downY = event.y; offset = 0f
        velocity?.recycle()
        velocity = VelocityTracker.obtain().also { it.addMovement(event) }
        onInteraction()
        armLongPress()
    }

    /** Let controls tap and lists scroll vertically; claim horizontal swipes after intent is clear. */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        if (longPressFired && event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) return true
        if (router.settingsOpen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTouch(event)
                nativeTouch = current?.interactiveAt(event.x, event.y) == true
                return !nativeTouch
            }
            MotionEvent.ACTION_MOVE -> if (nativeTouch) {
                val dx = event.x - downX; val dy = event.y - downY
                velocity?.addMovement(event)
                if (max(abs(dx), abs(dy)) > slop) disarmLongPress()
                if (event.pointerCount == 1 && abs(dx) > slop && abs(dx) > abs(dy)) {
                    nativeTouch = false
                    axis = 1
                    return true
                }
                return false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (nativeTouch) disarmLongPress()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (nativeTouch) {
                disarmLongPress()
                nativeTouch = false
                velocity?.recycle(); velocity = null
                return false
            }
        }
        return !nativeTouch
    }

    private var maintenanceX = 0f
    private var maintenanceY = 0f

    /** The hidden recovery gesture (two fingers held top-left) must work on every page, form included. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> router.noteInteraction()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { maintenanceX = event.x; maintenanceY = event.y }
            MotionEvent.ACTION_POINTER_DOWN -> if (event.pointerCount == 2 && maintenanceX < width * .25f && maintenanceY < height * .25f) {
                maintenanceArmed = true
                handler.removeCallbacks(maintenance)
                handler.postDelayed(maintenance, 5_000)
            }
            MotionEvent.ACTION_MOVE -> if (maintenanceArmed && abs(event.x - maintenanceX) + abs(event.y - maintenanceY) > slop * 2) {
                handler.removeCallbacks(maintenance)
                maintenanceArmed = false
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(maintenance)
                maintenanceArmed = false
            }
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (gestureDownTime != event.downTime) beginTouch(event)
                // A native child may decline an empty hit region; the stage owns that gesture.
                nativeTouch = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> disarmLongPress()
            MotionEvent.ACTION_MOVE -> {
                velocity?.addMovement(event)
                val dx = event.x - downX
                val dy = event.y - downY
                if (abs(dx) + abs(dy) > slop * 2) {
                    handler.removeCallbacks(maintenance)
                    maintenanceArmed = false
                    disarmLongPress()
                }
                if (event.pointerCount == 1) dragTo(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                disarmLongPress()
                handler.removeCallbacks(maintenance)
                maintenanceArmed = false
                if (longPressFired) {
                    // The press already opened settings; lifting the finger must not also tap a card.
                    longPressFired = false
                    axis = 0
                    velocity?.recycle()
                    velocity = null
                    return true
                }
                // A brief fling may have no MOVE beyond slop. Use its final coordinates so it
                // cannot fall through as a tap, and so release uses the actual finger distance.
                if (event.actionMasked == MotionEvent.ACTION_UP && event.pointerCount == 1)
                    dragTo(event.x - downX, event.y - downY)
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                val vertical = axis == 2
                val speed = velocity?.let { if (vertical) it.yVelocity else it.xVelocity } ?: 0f
                if (stackDrag) current?.releaseVertical(if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0f else offset,
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0f else speed)
                else if (axis != 0) {
                    val extent = if (vertical) height else width
                    val commit = event.actionMasked != MotionEvent.ACTION_CANCEL && neighbor != null && (abs(offset) > extent * .22f || abs(speed) > 700 * resources.displayMetrics.density && offset * speed > 0)
                    if (commit) {
                        commitDrag = true
                        if (vertical) router.changeVariant(dragDirection) else router.navigate(dragDirection)
                    } else {
                        current?.let { view -> springs += Motion.spring(position(view, vertical), 0f, speed, { position(view, it, vertical) }) }
                        neighbor?.let { view ->
                            springs += Motion.spring(position(view, vertical), dragDirection * extent.toFloat(), speed, { position(view, it, vertical) }) {
                                pageHost.removeView(view)
                                if (neighbor === view) neighbor = null
                            }
                        }
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (current?.tapAt(event.x, event.y) != true) current?.openAt(event.x, event.y)?.let(router::open)
                    performClick()
                }
                axis = 0
                stackDrag = false
                // Velocity is retained until the StateFlow navigation commit consumes it.
            }
            MotionEvent.ACTION_POINTER_UP -> { handler.removeCallbacks(maintenance); maintenanceArmed = false }
        }
        return true
    }
    private fun dragTo(dx: Float, dy: Float) {
        if (axis == 0 && max(abs(dx), abs(dy)) > slop) {
            disarmLongPress()
            axis = if (abs(dx) > abs(dy)) 1 else 2
        }
        if (axis == 0) return
        val vertical = axis == 2
        offset = if (vertical) dy else dx
        stackDrag = vertical && current?.dragVertical(downX, downY, offset) == true
        if (!stackDrag) {
            prepareNeighbor(if (offset < 0) 1 else -1, vertical)
            current?.let { position(it, if (neighbor == null) offset * .16f else offset, vertical) }
            neighbor?.let { position(it, offset + dragDirection * if (vertical) height else width, vertical) }
        }
    }
    override fun performClick(): Boolean { super.performClick(); return true }

    companion object {
        const val LONG_PRESS_MILLIS = 600L
        const val FEEDBACK_AFTER_MILLIS = 300L
    }
}

/** Disk pixels are recorded once. Breathing changes only cached-layer properties. */
private class AmbientLayer(context: Context) : FrameLayout(context) {
    private val disk = object : View(context) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(canvas: Canvas) { canvas.drawCircle(width / 2f, height / 2f, min(width, height) / 2f, paint) }
    }.apply { setLayerType(LAYER_TYPE_HARDWARE, null) }
    private val breath = PropertyBreath(disk, .38f, 1f, 1.025f)
    private var attention = Attention.AMBIENT
    private var reduced = false
    private var covered = false
    fun covered(value: Boolean) {
        if (covered == value) return
        covered = value
        disk.visibility = if (value) INVISIBLE else VISIBLE
        mode(attention, reduced)
    }
    init { clipChildren = true; addView(disk) }
    fun colors(value: Palette) {
        setBackgroundColor(value.bg)
        disk.paint.color = Palette.mix(value.bg, value.accent, .04f)
        disk.invalidate()
    }
    fun mode(level: Attention, reducedMotion: Boolean) {
        attention = level
        reduced = reducedMotion
        val active = !covered && level in setOf(Attention.AWAKE, Attention.ENGAGED) && !reducedMotion && ValueAnimator.areAnimatorsEnabled()
        if (active && isAttachedToWindow) breath.start() else pause()
    }
    fun tick(now: Long) { if (!covered && !breath.running && !reduced) applyPhase(now.mod(Motion.BREATH).toFloat() / Motion.BREATH) }
    private fun applyPhase(phase: Float) {
        val breath = ((sin(phase * Math.PI * 2) + 1) / 2).toFloat()
        disk.scaleX = 1f + .025f * breath
        disk.scaleY = disk.scaleX
        disk.alpha = .38f + .62f * breath
    }
    fun pause() { breath.stop() }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); mode(attention, reduced) }
    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val size = (min(w, h) * .88f).toInt()
        disk.measure(MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY))
        setMeasuredDimension(w, h)
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val x = (width * .72f - disk.measuredWidth / 2).toInt()
        val y = (height * .62f - disk.measuredHeight / 2).toInt()
        disk.layout(x, y, x + disk.measuredWidth, y + disk.measuredHeight)
    }
}

private class SystemLayer(context: Context) : View(context) {
    var palette = Palette.DARK
    var pageCount = 0
    var pageIndex = 0
    var variantCount = 0
    var variantIndex = 0
    var shownAt = 0L
    var wakeAt = 0L
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val density = resources.displayMetrics.density
        val age = now - shownAt
        val visibility = if (age < 2_000) 1f else (1 - (age - 2_000) / 500f).coerceIn(0f, 1f)
        if (visibility > 0) {
            paint.style = Paint.Style.FILL
            for (i in 0 until pageCount) {
                paint.color = if (i == pageIndex) palette.secondary else palette.divider
                paint.alpha = (255 * visibility).toInt()
                canvas.drawCircle(width / 2f + (i - (pageCount - 1) / 2f) * 12 * density, height - 9 * density, 2.2f * density, paint)
            }
            if (variantCount > 1) for (i in 0 until variantCount) {
                paint.color = if (i == variantIndex) palette.secondary else palette.divider
                paint.alpha = (255 * visibility).toInt()
                canvas.drawCircle(width - 8 * density, height / 2f + (i - (variantCount - 1) / 2f) * 11 * density, 2 * density, paint)
            }
        }
        val progress = (now - wakeAt) / 750f
        if (wakeAt != 0L && progress in 0f..1f) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f * density
            paint.color = palette.accent
            paint.alpha = ((1 - progress) * 80).toInt()
            canvas.drawCircle(width / 2f, height / 2f, min(width, height) * (.12f + progress * .47f), paint)
        }
    }
}
