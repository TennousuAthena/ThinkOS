package moe.kiwi.thinkos.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.os.MessageQueue
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
    /** The control sheet over everything else (ADR-035). It is an overlay, not a page, so the page below hears nothing. */
    private val panel = PanelHost(context, events, { states[it] ?: JSONObject() },
        dimBackground = { current?.photoStage == true || current?.videoStage == true }) { onInteraction() }
    private val overlayText = TextView(context).apply { textSize = TextScale.BODY; gravity = Gravity.CENTER; setPadding(20, 12, 20, 12) }
    private val practiceHint = PracticeHint(context).apply {
        onUnavailable = { finishPracticeFeedback(cancelled = true) }
    }
    private var practiceName: String? = null
    private var practiceText = ""
    private var practiceDone = false
    private var practiceFeedbackPage: String? = null
    private var pendingPractice: Pair<String?, String>? = null
    private var practiceFeedbackReady = false
    private var practiceFeedbackGeneration = 0L
    private var practiceFeedbackEnd: Runnable? = null
    /** OS recognition only. The caller has already checked the built-in onboarding authority. */
    var onPractice: ((String) -> Unit)? = null
    fun setPractice(name: String?, text: String) {
        val next = name?.takeIf { it in setOf("swipe", "variant", "panel", "back", "home", "long-press") }
        if (practiceFeedbackPage != null) {
            if (router.onboardingOpen && router.currentPage?.key == practiceFeedbackPage &&
                router.currentPage?.roles?.contains("onboarding") == true) {
                pendingPractice = next to text
                return
            }
            cancelPracticeFeedback()
        }
        if (next != practiceName || text != practiceText) {
            practiceName = next; practiceText = text; practiceDone = false
            practiceHint.bind(next, text, false)
        }
        practiceHint.visibility = if (practiceActive()) VISIBLE else GONE
    }
    private fun practiceActive(): Boolean = practiceName != null && router.onboardingOpen &&
        router.currentPage?.roles?.contains("onboarding") == true
    private fun cancelPracticeFeedback(): Pair<String?, String>? {
        val pending = pendingPractice
        practiceFeedbackGeneration++
        practiceFeedbackEnd?.let(handler::removeCallbacks); practiceFeedbackEnd = null
        practiceHint.onCompleteDraw = null
        practiceFeedbackPage = null; pendingPractice = null; practiceFeedbackReady = false
        pageHost.visibility = VISIBLE
        return pending
    }
    private fun finishPracticeFeedback(cancelled: Boolean = false) {
        if (practiceFeedbackPage == null || !cancelled && (!practiceFeedbackReady || touching)) return
        val samePage = router.onboardingOpen && router.currentPage?.key == practiceFeedbackPage &&
            router.currentPage?.roles?.contains("onboarding") == true
        val pending = cancelPracticeFeedback()
        if (cancelled && touching) {
            // Interrupted presentation ends this pointer, never arms the next lesson from its tail.
            disarmLongPress(); cancelGesture(); endPanelTouch(); panelTouch = PanelTouch.SWALLOW
        }
        if (samePage && pending != null) setPractice(pending.first, pending.second)
        else setPractice(null, "")
    }
    private fun reportPractice(name: String): Boolean {
        if (!practiceActive()) return false
        if (!practiceDone && practiceName == name) {
            practiceDone = true
            if (name != "home") {
                cancelPracticeFeedback()
                practiceFeedbackPage = router.currentPage?.key
                // Lua advances immediately; keep the next page behind this lesson's checkmark.
                pageHost.visibility = INVISIBLE
                pendingPractice = null to ""
                val ticket = practiceFeedbackGeneration
                practiceHint.onCompleteDraw = {
                    if (ticket == practiceFeedbackGeneration && practiceFeedbackPage != null) {
                        val finish = Runnable {
                            if (ticket == practiceFeedbackGeneration) {
                                practiceFeedbackEnd = null; practiceFeedbackReady = true
                                finishPracticeFeedback()
                            }
                        }
                        practiceFeedbackEnd = finish
                        handler.postDelayed(finish, Motion.FEEDBACK_EXIT)
                    }
                }
            }
            practiceHint.bind(practiceName, practiceText, true)
            onPractice?.invoke(name)
        }
        // Every recognised action is suppressed while practising, including an incorrect one.
        return true
    }
    private val boot = TextView(context).apply { text = "ThinkOS"; textSize = TextScale.TITLE; gravity = Gravity.CENTER; typeface = Typeface.create("sans-serif-light", Typeface.NORMAL) }
    private val states = mutableMapOf<String, JSONObject>()
    private var pages = emptyList<PluginPage>()
    private var widgets = emptyList<PluginWidget>()
    private var current: PageView? = null
    private var neighbor: PageView? = null
    private var prepared: PageView? = null
    private var previewQueued = false
    private var previewChecked = false
    private var touching = false
    private var debugDownX = -1f
    private var debugDownY = -1f
    private var debugDownCount = 0L
    /** Read-only debug receiver checkpoint for real edge input and lazy page construction. */
    fun debugState(): JSONObject = JSONObject().put("downX", debugDownX).put("downY", debugDownY)
        .put("downCount", debugDownCount).put("width", width).put("height", height)
        .put("children", pageHost.childCount).put("touching", touching).put("page", deck.page)
        .put("visibleChildren", (0 until pageHost.childCount).count { pageHost.getChildAt(it).visibility == VISIBLE })
        .put("edge", panelTouch.name).put("upProgress", system.upProgress).put("offset", offset)
        .put("translationY", current?.translationY ?: 0f).put("canGoBack", router.canGoBack)
        .put("pageBack", current?.hasBack == true).put("animatorsEnabled", ValueAnimator.areAnimatorsEnabled())
        .put("layoutDirection", layoutDirection).put("fontScale", resources.configuration.fontScale)
        .put("panelOpen", panel.showing)
        .put("practice", practiceName ?: JSONObject.NULL).put("practiceText", practiceText).put("practiceDone", practiceDone)
        .put("practiceVisible", practiceHint.visibility == VISIBLE)
    private val previewIdle = MessageQueue.IdleHandler {
        previewQueued = false
        if (canPreparePreview()) {
            previewChecked = true
            val page = listOf(-1, 1).firstNotNullOfOrNull { direction ->
                router.previewPage(direction)?.takeIf(::photoPage)
            }
            if (page != null) {
                prepared = newPage(page, router.rememberedVariant(page)).also {
                    it.visibility = INVISIBLE
                    pageHost.addView(it, LayoutParams(-1, -1))
                    it.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                    it.layout(0, 0, width, height)
                }
            }
        }
        false
    }
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
    /** The flip that opens and closes apps (ADR-040), and where the finger was when it started one. */
    private val appTransition = AppTransition(resources.displayMetrics.density, handler)
    private var launchOrigin: PointF? = null
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
        current?.releasePress()
        if (!reportPractice("long-press")) onLongPress(current?.ownerAt(downX, downY) ?: return@Runnable)
    }
    private fun armLongPress() {
        longPressArmed = true
        longPressFired = false
        val age = (SystemClock.uptimeMillis() - gestureDownTime).coerceAtLeast(0)
        handler.postDelayed(pressFeedback, (FEEDBACK_AFTER_MILLIS - age).coerceAtLeast(0))
        handler.postDelayed(longPress, (LONG_PRESS_MILLIS - age).coerceAtLeast(0))
        current?.pressAt(downX, downY)
    }
    private fun disarmLongPress() {
        current?.releasePress()
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
        addView(panel, LayoutParams(-1, -1))
        pageHost.addView(boot, LayoutParams(-1, -1))
        overlay.addView(overlayText, LayoutParams(-1, -2, Gravity.TOP))
        overlay.addView(practiceHint, LayoutParams(-1, -1))
        practiceHint.visibility = GONE
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
                    clearPrepared()
                    theme = value.theme
                    fromPalette = palette
                    targetPalette = Palette.forTheme(theme)
                    nightFrom = night
                    nightTo = if (theme == Theme.NIGHT) 1f else 0f
                    themeStart = SystemClock.uptimeMillis()
                    themeAnimating = true
                }
                renderDeck(store.current.deck)
                if (practiceFeedbackPage != null && value.deck.page != practiceFeedbackPage) {
                    cancelPracticeFeedback(); practiceName = null
                }
                if (!practiceActive()) { cancelPracticeFeedback(); practiceName = null; practiceHint.visibility = GONE }
                val message = if (value.safeMode) context.getString(moe.kiwi.thinkos.R.string.safe_mode_banner) else value.overlay.message
                overlayText.text = message.orEmpty()
                overlayText.visibility = if (message == null) GONE else VISIBLE
                if (lastAttention != value.attention) {
                    prepared?.tick(SystemClock.uptimeMillis(), value.attention)
                    if (value.attention == Attention.ASLEEP) clearPrepared()
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
        queuePreview()
    }
    fun stop() {
        cancelPracticeFeedback()
        setPractice(null, "")
        collection?.cancel()
        collection = null
        scope?.cancel()
        scope = null
        clock.stop()
        touching = false
        clearPrepared()
        current?.active(false)
        ambient.pause()
        current?.tick(SystemClock.uptimeMillis(), Attention.ASLEEP)
        neighbor?.tick(SystemClock.uptimeMillis(), Attention.ASLEEP)
        cancelGesture()
        panel.close(false)
        endPanelTouch()
        handler.removeCallbacks(maintenance)
        maintenanceArmed = false
    }
    /** The control sheet is up: Home and the plugin's own "close" bring it back down. */
    val panelOpen: Boolean get() = panel.showing
    fun closePanel(animated: Boolean = true) = panel.close(animated)
    fun prepareSystemHome() {
        cancelPracticeFeedback(); setPractice(null, "")
        panel.close(false); clearPrepared(); cancelGesture(); endPanelTouch()
        if (touching) panelTouch = PanelTouch.SWALLOW
    }
    fun updateCatalog(nextPages: List<PluginPage>, nextWidgets: List<PluginWidget>) {
        clearPrepared()
        pages = nextPages
        widgets = nextWidgets
        val visible = current
        val replacement = pages.firstOrNull { it.key == visible?.page?.key }
        if (visible != null && replacement != null) {
            // Same page: recompile changed declarations in place, without replaying navigation.
            visible.update(replacement, widgets)
        }
        renderDeck(store.current.deck)
        if (!practiceActive()) { cancelPracticeFeedback(); practiceName = null; practiceHint.visibility = GONE }
        applyDrift()
        queuePreview()
        panel.refresh(router.panelPage, widgets)
    }
    /** Language, 12/24-hour setting or time zone changed in Android's settings. */
    fun localeChanged() {
        layoutDirection = android.text.TextUtils.getLayoutDirectionFromLocale(java.util.Locale.getDefault())
        current?.localeChanged()
        neighbor?.localeChanged()
        prepared?.localeChanged()
        panel.localeChanged()
    }
    fun updatePluginState(pluginId: String, state: JSONObject) {
        val changed = Binding.changed(states.put(pluginId, state), state)
        if (changed.isEmpty()) return
        panel.bind(pluginId, changed)
        current?.bind(pluginId, changed)
        neighbor?.bind(pluginId, changed)
        prepared?.bind(pluginId, changed)
    }
    private fun photoPage(page: PluginPage): Boolean {
        if (page.view.optString("layout") != "stage") return false
        val slot = page.view.optJSONObject("slots")?.optJSONObject("stage") ?: return false
        val children = slot.optJSONArray("children")
        val component = if (children == null) slot else children.takeIf { it.length() == 1 }?.optJSONObject(0)
        return component?.optString("type").let { it == "image" || it == "carousel" }
    }
    private fun canPreparePreview() = collection != null && !touching && axis == 0 && neighbor == null &&
        prepared == null && current?.isLaidOut == true && width > 0 && height > 0 &&
        store.current.attention != Attention.ASLEEP &&
        abs(current?.translationX ?: 0f) < .5f && abs(current?.translationY ?: 0f) < .5f
    private fun queuePreview() {
        if (previewQueued || previewChecked || !canPreparePreview()) return
        previewQueued = true
        Looper.getMainLooper().queue.addIdleHandler(previewIdle)
    }
    private fun clearPrepared() {
        Looper.getMainLooper().queue.removeIdleHandler(previewIdle)
        previewQueued = false; previewChecked = false
        prepared?.let(pageHost::removeView)
        prepared = null
    }
    private fun takePrepared(page: PluginPage, variant: String): PageView? {
        val view = prepared?.takeIf { it.page.key == page.key && it.variant == variant }
        if (view != null) {
            prepared = null
            view.visibility = VISIBLE
            view.tick(SystemClock.uptimeMillis(), store.current.attention)
        }
        clearPrepared()
        return view
    }
    private fun retire(view: PageView) {
        val adjacent = listOf(-1, 1).any { direction ->
            router.previewPage(direction)?.let { it.key == view.page.key && router.rememberedVariant(it) == view.variant } == true
        }
        if (view.parent === pageHost && prepared == null && collection != null &&
            store.current.attention != Attention.ASLEEP && adjacent &&
            (view.photoStage || current?.photoStage == true)) {
            // Keep one adjacent page warm for an immediate reversal; it owns no running animation.
            view.visibility = INVISIBLE
            view.translationX = 0f; view.translationY = 0f
            view.tick(SystemClock.uptimeMillis(), Attention.AMBIENT)
            prepared = view
        } else pageHost.removeView(view)
        if (neighbor === view) neighbor = null
        applyDrift()
        queuePreview()
    }
    private fun newPage(page: PluginPage, variant: String): PageView = PageView(context, page, variant, widgets, events) { states[it] ?: JSONObject() }.also {
        it.colors(palette)
        it.night(night)
        it.tick(SystemClock.uptimeMillis(), store.current.attention)
    }
    private fun renderDeck(next: DeckState) {
        if (next.page == deck.page && next.variant == deck.variant && current != null) return
        val page = pages.firstOrNull { it.key == next.page } ?: return
        appTransition.snap()
        val old = current
        val preview = neighbor?.takeIf { it.page.key == next.page && it.variant == next.variant && commitDrag }
        val incoming = preview ?: takePrepared(page, next.variant) ?: newPage(page, next.variant).also { pageHost.addView(it, LayoutParams(-1, -1)) }
        if (preview == null) neighbor?.let(pageHost::removeView)
        neighbor = old
        current = incoming
        old?.active(false)
        incoming.active(true)
        pageHost.removeView(boot)
        val vertical = next.transition == Transition.RISE || next.vertical && next.page == deck.page
        val direction = if (next.direction == 0) 1 else next.direction
        val extent = if (vertical) height.toFloat() else width.toFloat()
        if (old != null && old.page.key == next.page && !next.vertical) {
            // A removed hot-update variant is a local replacement, never a page slide.
            incoming.alpha = 0f
            incoming.scaleX = .99f
            incoming.scaleY = .99f
            incoming.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).withEndAction {
                retire(old)
            }.start()
            old.animate().alpha(0f).setDuration(180).start()
        } else if (old != null && next.transition in setOf(Transition.LAUNCH, Transition.RETURN) && old.page.key != next.page && preview == null &&
            !governor.reducedMotion && ValueAnimator.areAnimatorsEnabled()) {
            // An app opens or closes: the Start page turns away and the app turns in, or the other way round (ADR-040).
            // The page that comes in is placed now: the turn needs its size (the hinge, which tiles there are), not the next frame's.
            if (!incoming.isLaidOut && width > 0 && height > 0) {
                incoming.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
                incoming.layout(0, 0, width, height)
            }
            if (next.transition == Transition.LAUNCH) appTransition.launch(old, incoming, launchOrigin) { retire(old) }
            else appTransition.back(old, incoming, launchOrigin) { retire(old) }
        } else if (old != null && !governor.reducedMotion && ValueAnimator.areAnimatorsEnabled()) {
            if (preview == null) position(incoming, direction * extent, vertical)
            val speed = if (commitDrag) velocity?.let { if (vertical) it.yVelocity else it.xVelocity } ?: 0f else 0f
            springs += Motion.spring(position(incoming, vertical), 0f, speed, { position(incoming, it, vertical) })
            springs += Motion.spring(position(old, vertical), -direction * extent, speed, { position(old, it, vertical) }) {
                retire(old)
            }
        } else {
            old?.let(::retire)
            neighbor = null
            position(incoming, 0f, vertical)
        }
        // A page opened by tap or command rises in; one the finger already dragged into view must not blink.
        if (preview == null && old?.page?.key != next.page && !governor.reducedMotion && next.transition == Transition.SLIDE) incoming.enterAnimation()
        commitDrag = false
        deck = next
        val navigable = router.navigablePages
        // An app, a takeover or the settings page is not one of the sequence's pages: no dots for it.
        system.pageCount = if (router.ownsSideways) 0 else navigable.size
        system.pageIndex = navigable.indexOf(page)
        system.variantCount = page.variants.size
        system.variantIndex = page.variants.indexOf(next.variant)
        system.shownAt = SystemClock.uptimeMillis()
        applyDrift()
        queuePreview()
        clock.wake()
    }
    private fun position(view: View, vertical: Boolean): Float = if (vertical) view.translationY else view.translationX
    private fun position(view: View, value: Float, vertical: Boolean) {
        if (vertical) view.translationY = value else view.translationX = value
    }
    private fun syncAmbient() { ambient.covered(current?.coversAmbient == true && neighbor == null && axis == 0) }
    private fun applyDrift() {
        // Full-screen backdrops stay at the viewport edges, including in swipe previews.
        val backdropVisible = current?.opaque == true || neighbor?.opaque == true
        pageHost.translationX = if (backdropVisible) 0f else driftX
        pageHost.translationY = if (backdropVisible) 0f else driftY
        val x = if (backdropVisible) driftX.roundToInt() else 0
        val y = if (backdropVisible) driftY.roundToInt() else 0
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
            neighbor = takePrepared(page, variant) ?: newPage(page, variant).also { pageHost.addView(it, LayoutParams(-1, -1)) }
        } else clearPrepared()
        applyDrift()
    }
    private fun cancelGesture() {
        appTransition.snap()
        system.backProgress = 0f
        system.upProgress = 0f
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
        previewChecked = false
        applyDrift()
    }
    /** `tos.tile.animate` from a plugin (ADR-028). */
    fun playTileAnimation(pluginId: String, name: String) { current?.playTileAnimation(pluginId, name) }
    fun refreshSettings() {
        current?.renderSettingsChanged(); neighbor?.renderSettingsChanged(); prepared?.renderSettingsChanged()
        current?.tick(SystemClock.uptimeMillis(), store.current.attention); clock.wake()
    }
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
        panel.tick(now, governor.attention)
        // Reuse this clock's existing wakeups, without adding a tutorial frame loop or observer.
        if (practiceHint.visibility == VISIBLE) practiceHint.refreshMotion()
        if (now - lastDrift >= 1_000) {
            BurnIn.offset(now).let { driftX = it.first; driftY = it.second }
            lastDrift = now
        }
        applyDrift()
        val systemActive = now < wakeUntil || now - system.shownAt < 2_600
        if (systemActive || system.wakeAt != 0L && now >= wakeUntil) {
            if (now >= wakeUntil) system.wakeAt = 0
            system.invalidate()
        }
        governor.activeAnimations = themeAnimating || systemActive || panel.animating || appTransition.running || current?.activeAnimations == true || neighbor?.activeAnimations == true
        governor.ambientAnimations = current?.ambientAnimations == true || neighbor?.ambientAnimations == true
        queuePreview()
    }
    private fun applyColors(value: Palette) {
        ambient.colors(value)
        boot.setTextColor(value.primary)
        current?.colors(value)
        neighbor?.colors(value)
        prepared?.colors(value)
        overlayText.setTextColor(value.secondary)
        practiceHint.colors(value)
        panel.colors(value)
        system.palette = value
        system.invalidate()
    }
    private fun applyNight() {
        panel.night(night)
        current?.night(night)
        neighbor?.night(night)
        prepared?.night(night)
        if (night <= 0f) { setLayerType(LAYER_TYPE_NONE, null); return }
        val gray = floatArrayOf(.2126f, .7152f, .0722f)
        val matrix = FloatArray(20)
        val channels = floatArrayOf(1f, 59 / 255f, 47 / 255f)
        for (row in 0..2) for (column in 0..2) matrix[row * 5 + column] = (if (row == column) 1f - night else 0f) + gray[column] * channels[row] * night
        matrix[18] = 1f
        nightPaint.colorFilter = ColorMatrixColorFilter(matrix)
        setLayerType(LAYER_TYPE_HARDWARE, nightPaint)
    }
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        queuePreview()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clearPrepared()
        cancelGesture()
        endPanelTouch()
        finishPracticeFeedback(cancelled = true)
        touching = false
        store.update { it.copy(orientation = if (w > h) Orientation.LANDSCAPE else Orientation.PORTRAIT) }
    }
    private var nativeTouch = false
    /** The touch began on a seek bar: the component, not the page swipe, owns its horizontal movement. */
    private var nativeHorizontal = false
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
        // While the control sheet is up it owns every touch; the page under it must not start a swipe or a long press.
        if (panel.showing) return false
        if (longPressFired && event.actionMasked in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) return true
        if (router.settingsOpen) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                beginTouch(event)
                nativeTouch = current?.interactiveAt(event.x, event.y) == true
                nativeHorizontal = nativeTouch && current?.capturesHorizontalAt(event.x, event.y) == true
                return !nativeTouch
            }
            MotionEvent.ACTION_MOVE -> if (nativeTouch) {
                val dx = event.x - downX; val dy = event.y - downY
                velocity?.addMovement(event)
                if (max(abs(dx), abs(dy)) > slop) disarmLongPress()
                if (nativeHorizontal) return false
                if (event.pointerCount == 1 && abs(dx) > slop && abs(dx) > abs(dy) && !router.ownsSideways) {
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
                nativeHorizontal = false
                velocity?.recycle(); velocity = null
                return false
            }
        }
        return !nativeTouch
    }

    private var maintenanceX = 0f
    private var maintenanceY = 0f

    private enum class PanelTouch { IDLE, CANDIDATE, PULL, BACK, UP, SWALLOW }
    private var panelTouch = PanelTouch.IDLE
    private var panelDown: MotionEvent? = null
    private var panelDownY = 0f
    private var panelDownX = 0f
    /** What the touch that began at an edge could still become: the control sheet's pull-down, the back swipe, or both (the top-left corner). */
    private var pullPossible = false
    private var backPossible = false
    private var upPossible = false
    /** An undecided press goes to the page early enough to retain its pressed and long-press feedback. */
    private val edgePress = Runnable {
        if (panelTouch == PanelTouch.CANDIDATE) {
            val down = panelDown
            panelDown = null
            endPanelTouch()
            if (down != null) { super.dispatchTouchEvent(down); down.recycle() }
        }
    }

    private fun endPanelTouch() {
        panelTouch = PanelTouch.IDLE
        handler.removeCallbacks(edgePress)
        pullPossible = false; backPossible = false; upPossible = false
        panelDown?.recycle()
        panelDown = null
    }

    /**
     * A single finger that goes down in an edge zone is not told to the page until it is clear what it does. At the top edge
     * moving down pulls the control sheet (ADR-035); at the left edge moving right is Back (ADR-040), when something is layered over
     * the page sequence for Back to peel. If it turns out to be a tap or any other swipe, the touch is handed to the page exactly as it
     * began, so nothing the page did before is lost.
     */
    private fun panelGesture(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            endPanelTouch()
            val density = resources.displayMetrics.density
            pullPossible = !panel.showing && events.panelEnabled && !router.settingsOpen && router.panelPage != null && event.y <= events.panelEdgeDp * density
            backPossible = events.backEnabled && (panel.showing || router.canGoBack || current?.hasBack == true) && BackGesture.startsAtEdge(event.x, events.backEdgeDp * density)
            upPossible = events.upEnabled && !store.current.safeMode && router.homePage != null &&
                (panel.showing || router.currentPage != router.homePage || router.canGoBack) &&
                BackGesture.startsAtEdge(height - event.y, events.upEdgeDp * density)
            if (!pullPossible && !backPossible && !upPossible) return false
            panelTouch = PanelTouch.CANDIDATE
            panelDown = MotionEvent.obtain(event)
            panelDownX = event.x; panelDownY = event.y
            handler.postDelayed(edgePress, BackGesture.EDGE_HOLD_MILLIS)
            return true
        }
        return when (panelTouch) {
            PanelTouch.IDLE -> false
            PanelTouch.SWALLOW -> { if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) panelTouch = PanelTouch.IDLE; true }
            PanelTouch.CANDIDATE -> panelCandidate(event)
            PanelTouch.PULL -> panelPull(event)
            PanelTouch.BACK -> backSwipe(event)
            PanelTouch.UP -> upSwipe(event)
        }
    }

    private fun panelCandidate(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - panelDownY
                val dx = event.x - panelDownX
                when (BackGesture.classify(dx, dy, slop.toFloat(), backPossible, pullPossible, upPossible)) {
                    BackGesture.Verdict.PULL -> {
                        val down = panelDown
                        panelTouch = PanelTouch.PULL
                        handler.removeCallbacks(edgePress)
                        if (!panel.prepare(router.panelPage, widgets)) { replayPanelTouch(event); return true }
                        if (practiceActive()) {
                            velocity?.recycle()
                            velocity = VelocityTracker.obtain().also { tracker -> down?.let(tracker::addMovement); tracker.addMovement(event) }
                            onInteraction()
                            return true
                        }
                        clearPrepared()
                        panel.beginPull(width, height)
                        down?.let { panel.pullTo(0f, it) }
                        panel.pullTo(dy, event)
                        onInteraction()
                    }
                    BackGesture.Verdict.BACK -> beginBackSwipe(event)
                    BackGesture.Verdict.UP -> beginUpSwipe(event)
                    BackGesture.Verdict.PAGE -> replayPanelTouch(event)
                    BackGesture.Verdict.WAIT -> Unit
                }
            }
            MotionEvent.ACTION_UP -> {
                if (BackGesture.classify(event.x - panelDownX, event.y - panelDownY, slop.toFloat(), backPossible, pullPossible, upPossible) == BackGesture.Verdict.UP) {
                    beginUpSwipe(event); upSwipe(event)
                } else replayPanelTouch(event)
            }
            MotionEvent.ACTION_CANCEL -> endPanelTouch()
            MotionEvent.ACTION_POINTER_DOWN -> { endPanelTouch(); panelTouch = PanelTouch.SWALLOW }
        }
        return true
    }

    private fun panelPull(event: MotionEvent): Boolean {
        if (practiceActive()) {
            velocity?.addMovement(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> {
                    velocity?.computeCurrentVelocity(1000)
                    if (panel.pullWouldOpen(event.y - panelDownY, velocity?.yVelocity ?: 0f, width, height)) reportPractice("panel")
                    velocity?.recycle(); velocity = null
                    endPanelTouch()
                }
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    velocity?.recycle(); velocity = null
                    endPanelTouch()
                    if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) panelTouch = PanelTouch.SWALLOW
                }
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> panel.pullTo(event.y - panelDownY, event)
            MotionEvent.ACTION_UP -> { panel.pullTo(event.y - panelDownY, event); panel.release(false); endPanelTouch() }
            MotionEvent.ACTION_CANCEL -> { panel.release(true); endPanelTouch() }
            MotionEvent.ACTION_POINTER_DOWN -> { panel.release(true); endPanelTouch(); panelTouch = PanelTouch.SWALLOW }
        }
        return true
    }

    // ---- back (ADR-040) ----

    /** Back from the Back key. True if something took it: the control sheet, a menu on the page, an app that wanted it, or a layer that went. */
    fun back(): Boolean = dispatchBack(false)

    private fun dispatchBack(viaGesture: Boolean): Boolean {
        if (panel.showing) { panel.close(true); return true }
        appTransition.snap()
        if (current?.back() == true) return true
        return router.back(viaGesture) != DeckRouter.BackResult.NONE
    }

    private fun beginBackSwipe(event: MotionEvent) {
        val down = panelDown
        panelTouch = PanelTouch.BACK
        handler.removeCallbacks(edgePress)
        cancelGesture()
        clearPrepared()
        ambient.covered(false)
        // The page Back leads to slides in from the left under the finger; an app that wants Back for itself has nothing to show there.
        (if (practiceActive() || panel.showing || current?.hasBack == true) null else router.previewBack())?.let { (page, variant) ->
            neighbor = newPage(page, variant).also { pageHost.addView(it, LayoutParams(-1, -1)) }
        }
        dragDirection = -1
        axis = 1
        offset = 0f
        velocity = VelocityTracker.obtain().also { tracker -> down?.let(tracker::addMovement); tracker.addMovement(event) }
        onInteraction()
        applyDrift()
        moveBackSwipe(event)
    }

    private fun moveBackSwipe(event: MotionEvent) {
        offset = (event.x - panelDownX).coerceAtLeast(0f)
        if (practiceActive()) return
        current?.let { position(it, if (neighbor == null) offset * .16f else offset, false) }
        neighbor?.let { position(it, offset - width, false) }
        system.backProgress = BackGesture.progress(offset, width.toFloat())
        system.backY = event.y
        system.invalidate()
    }

    private fun backSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { velocity?.addMovement(event); moveBackSwipe(event) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                val speed = velocity?.xVelocity ?: 0f
                val up = event.actionMasked == MotionEvent.ACTION_UP
                if (up) moveBackSwipe(event)
                val commit = up && BackGesture.commits(offset, speed, width.toFloat(), resources.displayMetrics.density)
                system.backProgress = 0f
                system.invalidate()
                val generation = router.navigationGeneration
                if (commit) {
                    if (!reportPractice("back")) {
                        commitDrag = neighbor != null
                        dispatchBack(viaGesture = neighbor != null)
                    }
                }
                // Not far enough, or an app took Back for itself: the page returns to where it was.
                if (!commit || router.navigationGeneration == generation) {
                    commitDrag = false
                    settleBackSwipe(if (up) speed else 0f)
                }
                axis = 0
                endPanelTouch()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                system.backProgress = 0f
                system.invalidate()
                settleBackSwipe(0f)
                axis = 0
                endPanelTouch()
                panelTouch = PanelTouch.SWALLOW
            }
        }
        return true
    }

    private fun settleBackSwipe(speed: Float) {
        current?.let { view -> springs += Motion.spring(position(view, false), 0f, speed, { position(view, it, false) }) }
        neighbor?.let { view ->
            springs += Motion.spring(position(view, false), -width.toFloat(), speed, { position(view, it, false) }) { retire(view) }
        }
    }

    private fun beginUpSwipe(event: MotionEvent) {
        panelTouch = PanelTouch.UP
        handler.removeCallbacks(edgePress)
        cancelGesture()
        clearPrepared()
        // Home is built only after release commits. A drag moves just the current page and OS arrow.
        axis = 2
        offset = 0f
        velocity = VelocityTracker.obtain().also { tracker -> panelDown?.let(tracker::addMovement); tracker.addMovement(event) }
        onInteraction()
        moveUpSwipe(event)
    }

    private fun moveUpSwipe(event: MotionEvent) {
        offset = (panelDownY - event.y).coerceAtLeast(0f)
        if (practiceActive()) return
        current?.let { position(it, -offset * .16f, true) }
        system.upProgress = BackGesture.progress(offset, height.toFloat())
        system.invalidate()
    }

    private fun upSwipe(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> { velocity?.addMovement(event); moveUpSwipe(event) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                velocity?.addMovement(event)
                velocity?.computeCurrentVelocity(1000, ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat())
                val speed = -(velocity?.yVelocity ?: 0f)
                val released = event.actionMasked == MotionEvent.ACTION_UP
                if (released) moveUpSwipe(event)
                val commit = released && BackGesture.commits(offset, speed, height.toFloat(), resources.displayMetrics.density)
                system.upProgress = 0f; system.invalidate()
                if (commit) { cancelPracticeFeedback(); reportPractice("home") }
                if (commit) panel.close(false)
                if (!commit || !router.homeGesture()) settleUpSwipe(if (released) speed else 0f)
                axis = 0
                endPanelTouch()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                system.upProgress = 0f; system.invalidate()
                settleUpSwipe(0f)
                axis = 0
                endPanelTouch(); panelTouch = PanelTouch.SWALLOW
            }
        }
        return true
    }

    private fun settleUpSwipe(speed: Float) {
        current?.let { view ->
            if (governor.reducedMotion || !ValueAnimator.areAnimatorsEnabled()) position(view, 0f, true)
            else springs += Motion.spring(position(view, true), 0f, -speed * .16f, { position(view, it, true) })
        }
    }

    /** The finger was not pulling the sheet after all: the page gets the whole touch, beginning with its first point. */
    private fun replayPanelTouch(event: MotionEvent) {
        // Take the stored press out first: ending the panel touch recycles whatever is stored, and it is recycled here instead.
        val down = panelDown
        panelDown = null
        endPanelTouch()
        if (down != null) {
            super.dispatchTouchEvent(down)
            down.recycle()
        }
        super.dispatchTouchEvent(event)
    }

    /** The hidden recovery gesture (two fingers held top-left) must work on every page, form included. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> router.noteInteraction()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (moe.kiwi.thinkos.BuildConfig.DEBUG) { debugDownX = event.x; debugDownY = event.y; debugDownCount++ }
                // Only swallow the release of the press that opened settings. Settings
                // bypass beginTouch(), so its next tap must clear the old press flag here.
                longPressFired = false
                touching = true; maintenanceX = event.x; maintenanceY = event.y
            }
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
        practiceHint.touch(if (practiceActive() && event.actionMasked !in setOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL)) PointF(event.x, event.y) else null)
        if (panelGesture(event)) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
                touching = false; queuePreview(); finishPracticeFeedback()
            }
            return true
        }
        val handled = super.dispatchTouchEvent(event)
        // A seek bar owns its whole gesture, so its parent no longer sees the moves that would cancel the long press.
        if (nativeHorizontal && event.actionMasked == MotionEvent.ACTION_DOWN) disarmLongPress()
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            touching = false
            queuePreview()
            finishPracticeFeedback()
        }
        return handled
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
                if (practiceActive() && axis != 0) {
                    val extent = if (vertical) height else width
                    val commit = event.actionMasked == MotionEvent.ACTION_UP &&
                        (abs(offset) > extent * .22f || abs(speed) > 700 * resources.displayMetrics.density && offset * speed > 0)
                    if (commit) reportPractice(if (vertical) "variant" else "swipe")
                } else if (stackDrag) current?.releaseVertical(if (event.actionMasked == MotionEvent.ACTION_CANCEL) 0f else offset,
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
                                retire(view)
                            }
                        }
                    }
                } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                    if (current?.tapAt(event.x, event.y) != true) current?.openAt(event.x, event.y)?.let {
                        launchOrigin = PointF(event.x, event.y)
                        router.open(it)
                    }
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
        if (practiceActive()) {
            offset = if (axis == 2) dy else dx
            return
        }
        // An app, a takeover and the settings page keep their sideways swipes for themselves: no page change, no give at the edge (ADR-040).
        if (axis == 1 && router.ownsSideways) return
        val vertical = axis == 2
        offset = if (vertical) dy else dx
        stackDrag = vertical && current?.dragVertical(downX, downY, offset) == true
        if (!stackDrag) {
            prepareNeighbor(if (offset < 0) 1 else -1, vertical)
            current?.let { position(it, if (neighbor == null) offset * .16f else offset, vertical) }
            neighbor?.let { position(it, offset + dragDirection * if (vertical) height else width, vertical) }
        }
    }

    /** Static guidance plus one cached RenderThread pointer; recognition and navigation stay with ThinkStage. */
    private class PracticeHint(context: Context) : FrameLayout(context) {
        var onCompleteDraw: (() -> Unit)? = null
        var onUnavailable: (() -> Unit)? = null
        private val density = resources.displayMetrics.density
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * density; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val label = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TextScale.BODY * resources.displayMetrics.scaledDensity }
        private var name: String? = null
        private var text = ""
        private var complete = false
        private var touch: PointF? = null
        private var textLayout: android.text.StaticLayout? = null
        private var initialized = false
        private var motionEnabled = ValueAnimator.areAnimatorsEnabled()
        private val demonstration = PracticeDemonstration()
        private var reverse = false
        private var queued = false
        private var cycling = false
        private var finishDemo: Runnable? = null
        private val marker = Paint(stroke)
        private var demoPress = true
        private val demo = object : View(context) {
            override fun onDraw(canvas: Canvas) {
                val x = width / 2f; val y = height / 2f
                val radius = (min(width, height) - marker.strokeWidth).coerceAtLeast(0f) / 2f
                if (demoPress) canvas.drawCircle(x, y, radius, marker)
                else {
                    // Cache one right-pointing chevron; View rotation follows each physical leg.
                    canvas.drawLine(x - radius, y - radius * .7f, x + radius, y, marker)
                    canvas.drawLine(x + radius, y, x - radius, y + radius * .7f, marker)
                }
            }
        }.apply {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
            isClickable = false; isFocusable = false; visibility = INVISIBLE
            setLayerType(LAYER_TYPE_HARDWARE, null)
        }
        private val runDemo = object : Runnable {
            override fun run() {
                queued = false
                if (!canDemonstrate()) { stopDemo(); return }
                val path = PracticeDemonstration.path(name, width, height, density, reverse) ?: return
                if (demo.width <= 0 || demo.height <= 0) return
                val scale = runCatching { Settings.Global.getFloat(context.contentResolver,
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f)
                if (!scale.isFinite() || scale <= 0f) { stopDemo(); return }
                val ticket = demonstration.begin()
                if (demoPress != path.press) { demoPress = path.press; demo.invalidate() }
                val direction = if (path.press) 0f else Math.toDegrees(atan2(
                    (path.toY - path.fromY).toDouble(), (path.toX - path.fromX).toDouble())).toFloat()
                if (demo.rotation != direction) demo.rotation = direction
                demo.translationX = path.fromX - demo.width / 2f
                demo.translationY = path.fromY - demo.height / 2f
                demo.scaleX = 1f; demo.scaleY = 1f; demo.visibility = VISIBLE
                cycling = true
                val duration = when (name) { "variant", "home" -> Motion.VARIANT; "long-press" -> LONG_PRESS_MILLIS; else -> Motion.PAGE }
                demo.animate().translationX(path.toX - demo.width / 2f).translationY(path.toY - demo.height / 2f)
                    .scaleX(if (path.press) .65f else 1f).scaleY(if (path.press) .65f else 1f)
                    .setDuration(duration).setStartDelay(0).setInterpolator(Motion.EASE)
                    .setListener(null).setUpdateListener(null).withStartAction(null).withEndAction(null).start()
                // Actions/listeners would defeat API 27's RenderThread path. Like PropertyBreath,
                // schedule one scaled wakeup instead; no main-thread update is attached to a frame.
                val finish = Runnable {
                    if (demonstration.accepts(ticket, true)) {
                        finishDemo = null
                        if (!canDemonstrate()) stopDemo()
                        else {
                            cycling = false; demo.animate().cancel(); demo.visibility = INVISIBLE
                            reverse = !reverse
                            queued = postDelayed(this, Motion.BREATH / 2)
                        }
                    }
                }
                finishDemo = finish
                // Existing feedback token briefly holds the destination before the pointer lifts.
                postDelayed(finish, ((duration + Motion.FEEDBACK_EXIT) * scale).toLong().coerceAtLeast(1))
            }
        }
        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES; isClickable = false
            setWillNotDraw(false)
            val size = (16 * density).toInt().coerceAtLeast(1)
            // Physical LEFT: a right-to-left locale must not move the gesture origins.
            addView(demo, LayoutParams(size, size, Gravity.TOP or Gravity.LEFT))
            initialized = true
        }
        fun bind(name: String?, text: String, complete: Boolean) {
            if (!complete) onCompleteDraw = null
            if (this.name != name) reverse = false
            if (this.text != text) textLayout = null
            this.name = name; this.text = text; this.complete = complete
            contentDescription = text; invalidate(); syncDemo()
        }
        fun colors(palette: Palette) {
            stroke.color = palette.accentText; marker.color = palette.accentText; label.color = palette.primary
            demo.invalidate(); invalidate()
        }
        fun touch(value: PointF?) {
            val wasTouching = touch != null
            touch = value // Raw physical coordinates; no RTL or animation transform applies to the finger.
            if (wasTouching != (value != null)) syncDemo()
            if (visibility == VISIBLE) invalidate()
        }
        fun refreshMotion() {
            val enabled = ValueAnimator.areAnimatorsEnabled()
            if (enabled != motionEnabled || !queued && !cycling && canDemonstrate()) syncDemo()
        }
        private fun canDemonstrate() = PracticeDemonstration.eligible(name, complete, touch != null,
            isAttachedToWindow, isShown, hasWindowFocus(), windowVisibility == VISIBLE,
            motionEnabled && ValueAnimator.areAnimatorsEnabled())
        private fun stopDemo() {
            demonstration.cancel() // Invalidate a late animation-end callback before cancelling Android's animator.
            removeCallbacks(runDemo); queued = false; cycling = false
            finishDemo?.let(::removeCallbacks); finishDemo = null
            demo.animate().setListener(null).setUpdateListener(null).withStartAction(null).withEndAction(null).cancel()
            demo.visibility = INVISIBLE
        }
        private fun syncDemo() {
            if (!initialized) return
            stopDemo()
            motionEnabled = ValueAnimator.areAnimatorsEnabled() && !MotionOff.reduced(context)
            if (canDemonstrate()) queued = postDelayed(runDemo, Motion.PAGE)
        }
        override fun onAttachedToWindow() { super.onAttachedToWindow(); syncDemo() }
        override fun onDetachedFromWindow() {
            if (initialized) onUnavailable?.invoke()
            stopDemo(); super.onDetachedFromWindow()
        }
        override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
            super.onWindowFocusChanged(hasWindowFocus)
            if (initialized && !hasWindowFocus) onUnavailable?.invoke()
            syncDemo(); invalidate()
        }
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (initialized && visibility != VISIBLE) onUnavailable?.invoke()
            syncDemo()
        }
        override fun onVisibilityChanged(changedView: View, visibility: Int) {
            super.onVisibilityChanged(changedView, visibility)
            if (initialized && visibility != VISIBLE) onUnavailable?.invoke()
            syncDemo()
        }
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            textLayout = null; syncDemo()
        }
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!hasWindowFocus() || windowVisibility != VISIBLE) return
            val x = width / 2f; val y = height * .42f; val length = 48 * density; val head = 12 * density
            fun arrow(fromX: Float, fromY: Float, toX: Float, toY: Float, both: Boolean = false) {
                canvas.drawLine(fromX, fromY, toX, toY, stroke)
                fun end(ex: Float, ey: Float, sx: Float, sy: Float) {
                    val angle = atan2(ey - sy, ex - sx)
                    canvas.drawLine(ex, ey, ex - head * cos(angle - .6f), ey - head * sin(angle - .6f), stroke)
                    canvas.drawLine(ex, ey, ex - head * cos(angle + .6f), ey - head * sin(angle + .6f), stroke)
                }
                end(toX, toY, fromX, fromY)
                if (both) end(fromX, fromY, toX, toY)
            }
            if (complete) {
                val path = Path().apply { moveTo(x - length / 2, y); lineTo(x - length / 8, y + length / 3); lineTo(x + length / 2, y - length / 3) }
                canvas.drawPath(path, stroke)
            } else when (name) {
                "swipe" -> arrow(x - length, y, x + length, y, true)
                "variant" -> arrow(x, y - length, x, y + length, true)
                "panel" -> arrow(x, y - length, x, y + length)
                "back" -> arrow(x - length, y, x + length, y)
                "home" -> arrow(x, y + length, x, y - length)
                "long-press" -> { canvas.drawCircle(x, y, 28 * density, stroke); canvas.drawCircle(x, y, 4 * density, stroke) }
            }
            val textWidth = (width - 48 * density).toInt().coerceAtLeast(1)
            val layout = textLayout ?: android.text.StaticLayout.Builder.obtain(text, 0, text.length, label, textWidth)
                .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setIncludePad(false).build().also { textLayout = it }
            canvas.save(); canvas.translate((width - textWidth) / 2f, height * .6f); layout.draw(canvas); canvas.restore()
            touch?.let { canvas.drawCircle(it.x, it.y, 8 * density, stroke) }
            // Start feedback visibility only after its checkmark was actually drawn.
            if (complete) onCompleteDraw?.also { onCompleteDraw = null }?.invoke()
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
    /** The back swipe (ADR-040): how far along it is, 0 to 1, and where the finger is; the arrow comes in from the left edge with it. */
    var backProgress = 0f
    var backY = 0f
    var upProgress = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val density = resources.displayMetrics.density
        if (backProgress > 0f) {
            val radius = 20 * density
            val x = -radius + backProgress * (radius + 20 * density)
            val y = backY.coerceIn(radius, (height - radius).coerceAtLeast(radius))
            paint.style = Paint.Style.FILL
            paint.color = palette.secondary
            paint.alpha = (190 * backProgress).toInt()
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = Stroke.ICON * density
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = palette.bg
            paint.alpha = (255 * backProgress).toInt()
            val direction = if (layoutDirection == LAYOUT_DIRECTION_RTL) -1f else 1f
            canvas.drawLine(x + 3 * density * direction, y - 7 * density, x - 4 * density * direction, y, paint)
            canvas.drawLine(x - 4 * density * direction, y, x + 3 * density * direction, y + 7 * density, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
        if (upProgress > 0f) {
            val radius = 20 * density
            val x = width / 2f
            val y = height + radius - upProgress * (radius + 20 * density)
            paint.style = Paint.Style.FILL; paint.color = palette.secondary; paint.alpha = (190 * upProgress).toInt()
            canvas.drawCircle(x, y, radius, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = Stroke.ICON * density; paint.strokeCap = Paint.Cap.ROUND
            paint.color = palette.bg; paint.alpha = (255 * upProgress).toInt()
            canvas.drawLine(x - 7 * density, y + 3 * density, x, y - 4 * density, paint)
            canvas.drawLine(x, y - 4 * density, x + 7 * density, y + 3 * density, paint)
            paint.strokeCap = Paint.Cap.BUTT
        }
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
            paint.strokeWidth = Stroke.ICON * density
            paint.color = palette.accent
            paint.alpha = ((1 - progress) * 80).toInt()
            canvas.drawCircle(width / 2f, height / 2f, min(width, height) * (.12f + progress * .47f), paint)
        }
    }
}
