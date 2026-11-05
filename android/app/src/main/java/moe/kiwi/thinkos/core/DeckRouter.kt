package moe.kiwi.thinkos.core

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.util.Log
import moe.kiwi.thinkos.plugin.PluginPage
import org.json.JSONArray
import org.json.JSONObject

class DeckRouter(
    context: Context,
    private val store: Store,
    private val onPageEvent: (String, String, JSONArray) -> Unit = { _, _, _ -> },
    /** How long AMBIENT lasts before the `ambient` role page appears; a system setting. */
    private val ambientDelayMillis: () -> Long = { 300_000L },
    private val shouldStartOnboarding: () -> Boolean = { false },
    private val onOnboardingPrompt: () -> Unit = {},
    private val onboardingIdleMillis: () -> Long = { 1_800_000L },
) {
    private val preferences = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
    var lastNavigationReason = "initial"; private set
    var navigationGeneration = 0L; private set
    var pages: List<PluginPage> = emptyList(); private set
    private var lastLocal = Long.MIN_VALUE
    private var previousAttention = store.current.attention
    private val handler = Handler(Looper.getMainLooper())
    /** Everything layered over the swipe sequence: apps, takeovers, the settings page (ADR-040). */
    private val nav = NavStack()
    private val gate = BackGate()
    private val onboardingEntry = OnboardingEntryPolicy()
    private val onboardingIdle = Runnable { if (onboardingOpen) homeKey() }
    val onboardingOpen: Boolean get() = nav.has(NavStack.Kind.ONBOARDING)
    private val ambientRole = Runnable {
        if (store.current.attention == Attention.AMBIENT && store.current.theme != Theme.NIGHT && !nav.has(NavStack.Kind.TAKEOVER) && !settingsOpen && !onboardingOpen) {
            pages.firstOrNull { "ambient" in it.roles }?.let { showBase(it, reason = "system") }
        }
    }
    val currentPage get() = pages.firstOrNull { it.key == store.current.deck.page }

    /** The page the control sheet shows: the page that holds the `panel` role (ADR-035). It is an overlay, never in the deck. */
    val panelPage: PluginPage? get() = pages.firstOrNull { "panel" in it.roles }
    val launcherPage: PluginPage? get() = pages.firstOrNull { "launcher" in it.roles }
    val homePage: PluginPage? get() = pages.firstOrNull { "home" in it.roles }

    /** Pages reachable by swiping. The settings page is entered by long press and is never in the sequence. */
    val navigablePages: List<PluginPage> get() = pages.filter {
        "settings" !in it.roles && "panel" !in it.roles && "onboarding" !in it.roles && (store.current.theme != Theme.NIGHT || "ambient" !in it.roles) &&
            // A live page is entered by its plugin (a film, an alarm), never by swiping through the deck.
            (!it.live || it.key == store.current.deck.page) &&
            // An app's page is entered from a tile or the app list; the sequence stays as short as it was without apps.
            (!it.app || it.key == store.current.deck.page)
    }

    /** Something is layered over the swipe sequence, so Back has somewhere to go. */
    val canGoBack: Boolean get() = !nav.isEmpty

    /**
     * Sideways swipes belong to the screen's content: an app, a takeover or the settings page is not part of the sequence, so the
     * shell does not move it, not even with the give an edge of the sequence has (ADR-040).
     */
    val ownsSideways: Boolean get() = !nav.isEmpty || currentPage?.let { it.app || it.live } == true

    private var previousTheme = store.current.theme
    fun applyTheme(theme: Theme) {
        if (theme == previousTheme) return
        previousTheme = theme
        handler.removeCallbacks(ambientRole)
        if (onboardingOpen) return
        if (theme == Theme.NIGHT) {
            if (!settingsOpen && currentPage?.roles?.contains("ambient") == true) {
                pages.firstOrNull { "sleep" in it.roles }?.let { showBase(it, reason = "system") }
            }
        } else if (store.current.attention == Attention.AMBIENT && !settingsOpen) handler.postDelayed(ambientRole, ambientDelayMillis())
    }

    // ---- settings (ADR-017) ----
    private val settingsIdle = Runnable { closeSettings() }
    val settingsOpen: Boolean get() = nav.has(NavStack.Kind.SETTINGS)

    /** Opens the settings page for [scope] (`system` or a plugin id). False if no settings plugin is running. */
    fun openSettings(scope: String): Boolean {
        if (onboardingOpen) return false
        val page = pages.firstOrNull { "settings" in it.roles } ?: return false
        val params = JSONObject().put("scope", scope)
        if (settingsOpen) select(page, 1, false, params = params)           // already there: another scope
        else if (!push(NavStack.Kind.SETTINGS, page, 1, params = params)) return false
        noteInteraction()
        return true
    }

    /** Back to the page and variant the user came from. */
    fun closeSettings() {
        if (!settingsOpen) return
        handler.removeCallbacks(settingsIdle)
        apply(nav.removeKind(NavStack.Kind.SETTINGS), -1, "settings")
    }

    /**
     * The control sheet opened or closed. Its plugin hears it as `page.enter` and `page.leave` of its own page, so it can refresh
     * what it shows; the page under the sheet is not told anything, because nothing happened to it.
     */
    fun panelChanged(open: Boolean) {
        val page = panelPage ?: return
        onPageEvent(page.pluginId, if (open) "page.enter" else "page.leave", JSONArray().put(page.id).also { if (open) it.put("main") })
    }

    /** Any touch keeps the settings page open for another 30 seconds. */
    fun noteInteraction() {
        if (onboardingOpen) {
            handler.removeCallbacks(onboardingIdle)
            handler.postDelayed(onboardingIdle, onboardingIdleMillis())
        }
        if (!settingsOpen) return
        handler.removeCallbacks(settingsIdle)
        handler.postDelayed(settingsIdle, SETTINGS_IDLE_MILLIS)
    }

    fun setPages(next: List<PluginPage>) {
        pages = next
        if (next.isEmpty()) { nav.clear(); store.update { it.copy(deck = DeckState()) }; return }
        // A plugin that vanished (removed, disabled, crashed) takes its layers with it, the settings page included.
        val wasSettings = settingsOpen
        val change = nav.prune { key -> next.any { it.key == key } }
        if (wasSettings && !settingsOpen) handler.removeCallbacks(settingsIdle)
        apply(change, -1, "gone")
        val current = currentPage
        val start = navigablePages
        if (current == null) select(start.firstOrNull { "home" in it.roles } ?: start.firstOrNull() ?: next.first(), 0, false)
        else if (store.current.deck.variant !in current.variants && current.variants.isNotEmpty()) select(current, 0, false)
        if (onboardingEntry.claimInitialCatalog()) {
            if (!store.current.safeMode && shouldStartOnboarding() && openOnboarding("continue")) onOnboardingPrompt()
        }
    }

    /** A system-owned entry, distinct from a plugin's ordinary focus requests. */
    fun openOnboarding(mode: String): Boolean {
        if (mode !in setOf("restart", "resume", "continue") || store.current.safeMode) return false
        val candidates = pages.filter { "onboarding" in it.roles }
        val page = candidates.firstOrNull { it.id == "hello" } ?: candidates.firstOrNull() ?: return false
        handler.removeCallbacks(settingsIdle)
        handler.removeCallbacks(ambientRole)
        if (onboardingOpen) {
            nav.replaceTop(NavStack.Frame(page.key, resolveVariant(page, null)))
            select(page, 1, false, params = JSONObject().put("mode", mode), reason = "onboarding")
        } else {
            homeKey()
            if (!push(NavStack.Kind.ONBOARDING, page, 1, params = JSONObject().put("mode", mode), reason = "onboarding")) return false
        }
        noteInteraction()
        return true
    }
    fun previewPage(delta: Int): PluginPage? {
        val deck = navigablePages
        if (deck.size < 2 || !nav.isEmpty) return null
        val index = deck.indexOfFirst { it.key == store.current.deck.page }.coerceAtLeast(0)
        return deck[Math.floorMod(index + delta, deck.size)]
    }
    fun previewVariant(delta: Int): String? {
        val page = currentPage ?: return null
        if (page.variants.size < 2) return null
        val index = page.variants.indexOf(store.current.deck.variant).coerceAtLeast(0)
        return page.variants[Math.floorMod(index + delta, page.variants.size)]
    }
    fun rememberedVariant(page: PluginPage): String = preferences.getString(page.key, null)?.takeIf { it in page.variants } ?: page.variants.firstOrNull() ?: "digital"
    fun navigate(delta: Int) {
        val deck = navigablePages
        if (deck.size < 2 || !nav.isEmpty) return
        lastLocal = SystemClock.elapsedRealtime()
        val index = deck.indexOfFirst { it.key == store.current.deck.page }.coerceAtLeast(0)
        select(deck[Math.floorMod(index + delta, deck.size)], delta, false, reason = "swipe")
    }

    /** A tile or card was tapped. An app (or a page that is only ever a layer) goes over what is on screen; anything else is a step along the sequence. */
    fun open(pageKey: String) {
        if (settingsOpen || onboardingOpen) return
        val page = pages.firstOrNull { it.key == pageKey } ?: return
        if ("onboarding" in page.roles) return
        lastLocal = SystemClock.elapsedRealtime()
        if (page.key == store.current.deck.page) { select(page, 1, false, reason = "open"); return }
        if (nav.unwindTo(page.key) != NavStack.Change.None) { apply(NavStack.Change.Show(nav.top!!.frame), -1, "open"); return }
        if (page.app || page.live || !nav.isEmpty) push(NavStack.Kind.APP, page, 1, reason = "open", transition = if (page.app) Transition.LAUNCH else Transition.SLIDE)
        else select(page, 1, false, reason = "open")
    }

    /** The Home key always means "take me home": every layer goes, and the page is the home page. */
    fun homeKey() {
        onboardingEntry.homeRequested()
        handler.removeCallbacks(settingsIdle)
        handler.removeCallbacks(onboardingIdle)
        val home = homePage ?: return
        if (nav.isEmpty && store.current.deck.page == home.key) return
        nav.clear()
        select(home, -1, false, reason = "home")
    }

    /** Global physical bottom edge: always Home, regardless of the page/application we left. */
    fun homeGesture(): Boolean {
        onboardingEntry.homeRequested()
        val target = homePage ?: return false
        handler.removeCallbacks(settingsIdle)
        handler.removeCallbacks(onboardingIdle)
        lastLocal = SystemClock.elapsedRealtime()
        if (nav.isEmpty && store.current.deck.page == target.key) return false
        nav.clear()
        select(target, 1, true, reason = "home_gesture", transition = Transition.RISE)
        return true
    }

    /** The panel's apps button and public launcher target open the application tiles. */
    fun launcher(): Boolean {
        val target = launcherPage ?: return false
        handler.removeCallbacks(settingsIdle)
        handler.removeCallbacks(onboardingIdle)
        lastLocal = SystemClock.elapsedRealtime()
        nav.clear()
        select(target, 1, true, reason = "launcher", transition = Transition.RISE)
        return true
    }

    // ---- back (ADR-040) ----

    enum class BackResult { NONE, DELIVERED, POPPED }

    /** The page Back would show, and the variant it was showing. Null when there is nothing to show, or the app wants Back for itself. */
    fun previewBack(): Pair<PluginPage, String>? {
        if (nav.isEmpty || topIntercepted()) return null
        val below = nav.below ?: return null
        val page = pages.firstOrNull { it.key == below.page } ?: return null
        return page to below.variant
    }

    /** True when the layer on top has asked to hear Back first. */
    fun topIntercepted(): Boolean = topOwner()?.let(gate::intercepts) == true

    /**
     * Back from the Back key or the edge swipe. An app that declared it wants Back is told with a `back` event; otherwise the top
     * layer goes. [viaGesture]: the finger already carried the page away, so it is not flipped closed on top of that.
     */
    fun back(viaGesture: Boolean = false): BackResult {
        val top = nav.top ?: return BackResult.NONE
        lastLocal = SystemClock.elapsedRealtime()
        val page = pages.firstOrNull { it.key == top.frame.page }
        if (page != null && gate.back(page.pluginId, SystemClock.elapsedRealtime()) == BackGate.Verdict.DELIVER) {
            onPageEvent(page.pluginId, "back", JSONArray().put(page.id))
            return BackResult.DELIVERED
        }
        if (top.kind == NavStack.Kind.SETTINGS) handler.removeCallbacks(settingsIdle)
        if (top.kind == NavStack.Kind.ONBOARDING) {
            homeKey()
            return BackResult.POPPED
        }
        val transition = if (top.kind == NavStack.Kind.APP && page?.app == true && !viaGesture) Transition.RETURN else Transition.SLIDE
        val shown = nav.pop()
        if (shown == null) apply(NavStack.Change.Home, -1, "back") else apply(NavStack.Change.Show(shown), -1, "back", transition)
        return BackResult.POPPED
    }

    /** `tos.back.intercept` from [pluginId]. */
    fun interceptBack(pluginId: String, on: Boolean) = gate.intercept(pluginId, on)

    /** [pluginId] reacted to the `back` it was sent. */
    fun backAnswered(pluginId: String) = gate.answered(pluginId)

    private fun topOwner(): String? = nav.top?.let { top -> pages.firstOrNull { it.key == top.frame.page }?.pluginId }

    fun changeVariant(delta: Int) {
        if (settingsOpen || onboardingOpen) return
        val page = currentPage ?: return
        if (page.variants.size < 2) return
        lastLocal = SystemClock.elapsedRealtime()
        val current = page.variants.indexOf(store.current.deck.variant).coerceAtLeast(0)
        val variant = page.variants[Math.floorMod(current + delta, page.variants.size)]
        preferences.edit().putString(page.key, variant).apply()
        nav.variantChanged(variant)
        store.update { it.copy(deck = it.deck.copy(variant = variant, direction = delta, vertical = true, transition = Transition.SLIDE)) }
        onPageEvent(page.pluginId, "variant", JSONArray().put(page.id).put(variant))
    }
    /** [answersTap]: the request answers a tap the user just made on this plugin's own page, so it is not a background takeover. */
    fun requestFocus(pageKey: String, live: Boolean, release: Boolean, answersTap: Boolean = false) {
        val page = pages.firstOrNull { it.key == pageKey } ?: return
        if (onboardingOpen) {
            // Only the verified onboarding owner can replace its own step. No takeover can steal it.
            if (page.pluginId != currentPage?.pluginId || "onboarding" !in page.roles) return
            if (release) homeKey()
            else {
                nav.replaceTop(NavStack.Frame(page.key, resolveVariant(page, null)))
                select(page, 1, false, reason = "onboarding_step")
            }
            return
        }
        if ("onboarding" in page.roles) return
        if (settingsOpen) return              // nothing may pull the user out of the settings page
        if (release) {
            // An app that closes itself is closed the way Back closes it.
            val closesApp = page.app && nav.top?.let { it.kind == NavStack.Kind.APP && it.frame.page == pageKey } == true
            apply(nav.remove(pageKey), -1, "release", if (closesApp) Transition.RETURN else Transition.SLIDE)
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (store.current.attention == Attention.ASLEEP || !answersTap && lastLocal != Long.MIN_VALUE && now - lastLocal < 30_000) return
        if (live && !page.live) return
        val active = currentPage
        val top = nav.top
        if (top?.kind == NavStack.Kind.TAKEOVER && active != null && page.priority < active.priority) return
        when {
            page.key == store.current.deck.page -> select(page, 1, false)
            nav.unwindTo(page.key) != NavStack.Change.None -> apply(NavStack.Change.Show(nav.top!!.frame), -1, "focus")
            live && top?.kind == NavStack.Kind.TAKEOVER -> {
                // One takeover replaces the one before it; Back still leads to where the first one began.
                val variant = resolveVariant(page, null)
                nav.replaceTop(NavStack.Frame(page.key, variant))
                select(page, 1, false, variant)
            }
            live -> push(NavStack.Kind.TAKEOVER, page, 1)
            page.app -> push(NavStack.Kind.APP, page, 1, transition = Transition.LAUNCH)
            else -> { nav.clear(); select(page, 1, false) }
        }
    }
    fun applyAttention(level: Attention) {
        if (level == previousAttention) return
        val wasAsleep = previousAttention == Attention.ASLEEP
        previousAttention = level
        handler.removeCallbacks(ambientRole)
        if (settingsOpen || onboardingOpen) return
        if (level == Attention.AMBIENT) handler.postDelayed(ambientRole, ambientDelayMillis())
        when {
            level == Attention.ASLEEP -> pages.firstOrNull { "sleep" in it.roles }?.let { showBase(it) }
            // Waking from sleep goes home, unless a live page already has the screen (a film started while it slept).
            wasAsleep && currentPage?.live != true -> pages.firstOrNull { "home" in it.roles }?.let { showBase(it) }
        }
    }

    // ---- moving ----

    private fun resolveVariant(page: PluginPage, forced: String?): String {
        val stored = forced ?: preferences.getString(page.key, null)
        return stored?.takeIf { it in page.variants } ?: page.variants.firstOrNull() ?: "digital"
    }

    /** Puts [page] on top of what is on screen. False when the stack is full: nothing happens. */
    private fun push(kind: NavStack.Kind, page: PluginPage, direction: Int, params: JSONObject? = null, reason: String = "system", transition: Transition = Transition.SLIDE): Boolean {
        val variant = resolveVariant(page, null)
        val from = store.current.deck.let { NavStack.Frame(it.page, it.variant) }
        if (!nav.push(from, kind, NavStack.Frame(page.key, variant))) {
            Log.w("ThinkOS", "navigation too deep: ${page.key} not opened")
            return false
        }
        select(page, direction, false, variant, params, reason, transition)
        return true
    }

    /** A page of the sequence replaces everything layered over it (waking, sleeping, the ambient role). */
    private fun showBase(page: PluginPage, reason: String = "system") {
        nav.clear()
        select(page, 0, false, reason = reason)
    }

    /** Shows what [change] left on screen. A page that is gone is replaced by home. */
    private fun apply(change: NavStack.Change, direction: Int, reason: String, transition: Transition = Transition.SLIDE) {
        when (change) {
            is NavStack.Change.None -> Unit
            is NavStack.Change.Show -> {
                val page = pages.firstOrNull { it.key == change.frame.page }
                if (page != null) select(page, direction, false, change.frame.variant, reason = reason, transition = transition)
                else apply(NavStack.Change.Home, direction, reason)
            }
            is NavStack.Change.Home -> {
                val home = navigablePages.firstOrNull { "home" in it.roles } ?: navigablePages.firstOrNull() ?: return
                select(home, direction, false, reason = reason)
            }
        }
    }

    private fun select(page: PluginPage, direction: Int, vertical: Boolean, forcedVariant: String? = null, params: JSONObject? = null, reason: String = "system", transition: Transition = Transition.SLIDE) {
        if (!onboardingOpen) handler.removeCallbacks(onboardingIdle)
        if (store.current.theme == Theme.NIGHT && "ambient" in page.roles) {
            pages.firstOrNull { "sleep" in it.roles && "ambient" !in it.roles }?.let { select(it, direction, false) }
            return
        }
        val old = currentPage
        if (old?.key != page.key) {
            lastNavigationReason = reason; navigationGeneration++
            Log.i("ThinkOS", "page=${page.key} from=${old?.key} reason=$reason attention=${store.current.attention}")
        }
        val variant = resolveVariant(page, forcedVariant)
        if (old?.key != page.key) old?.let {
            // What an app declared for its page ends with the page.
            gate.forget(it.pluginId)
            onPageEvent(it.pluginId, "page.leave", JSONArray().put(it.id))
        }
        store.update { it.copy(deck = DeckState(page.key, variant, direction, vertical, transition)) }
        onPageEvent(page.pluginId, "page.enter", JSONArray().put(page.id).put(variant).also { if (params != null) it.put(params) })
    }

    companion object { const val SETTINGS_IDLE_MILLIS = 30_000L }
}
