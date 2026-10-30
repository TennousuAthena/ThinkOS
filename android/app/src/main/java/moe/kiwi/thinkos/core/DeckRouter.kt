package moe.kiwi.thinkos.core

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import moe.kiwi.thinkos.plugin.PluginPage
import org.json.JSONArray
import org.json.JSONObject

class DeckRouter(
    context: Context,
    private val store: Store,
    private val onPageEvent: (String, String, JSONArray) -> Unit = { _, _, _ -> },
    /** How long AMBIENT lasts before the `ambient` role page appears; a system setting. */
    private val ambientDelayMillis: () -> Long = { 300_000L },
) {
    private val preferences = context.getSharedPreferences("deck", Context.MODE_PRIVATE)
    var pages: List<PluginPage> = emptyList(); private set
    private var lastLocal = Long.MIN_VALUE
    private var previousLive: DeckState? = null
    private var livePage: String? = null
    private var previousAttention = store.current.attention
    private val handler = Handler(Looper.getMainLooper())
    private val ambientRole = Runnable {
        if (store.current.attention == Attention.AMBIENT && store.current.theme != Theme.NIGHT && livePage == null && !settingsOpen) {
            pages.firstOrNull { "ambient" in it.roles }?.let { select(it, 0, false) }
        }
    }
    val currentPage get() = pages.firstOrNull { it.key == store.current.deck.page }

    /** Pages reachable by swiping. The settings page is entered by long press and is never in the sequence. */
    val navigablePages: List<PluginPage> get() = pages.filter { "settings" !in it.roles && (store.current.theme != Theme.NIGHT || "ambient" !in it.roles) }

    private var previousTheme = store.current.theme
    fun applyTheme(theme: Theme) {
        if (theme == previousTheme) return
        previousTheme = theme
        handler.removeCallbacks(ambientRole)
        if (theme == Theme.NIGHT) {
            if (!settingsOpen && currentPage?.roles?.contains("ambient") == true) {
                pages.firstOrNull { "sleep" in it.roles }?.let { select(it, 0, false) }
            }
        } else if (store.current.attention == Attention.AMBIENT && !settingsOpen) handler.postDelayed(ambientRole, ambientDelayMillis())
    }

    // ---- settings (ADR-017) ----
    private var settingsReturn: DeckState? = null
    private val settingsIdle = Runnable { closeSettings() }
    val settingsOpen: Boolean get() = settingsReturn != null

    /** Opens the settings page for [scope] (`system` or a plugin id). False if no settings plugin is running. */
    fun openSettings(scope: String): Boolean {
        val page = pages.firstOrNull { "settings" in it.roles } ?: return false
        if (settingsReturn == null) settingsReturn = store.current.deck
        previousLive = null; livePage = null
        select(page, 1, false, params = JSONObject().put("scope", scope))
        noteInteraction()
        return true
    }

    /** Back to the page and variant the user came from. */
    fun closeSettings() {
        val back = settingsReturn ?: return
        settingsReturn = null
        handler.removeCallbacks(settingsIdle)
        val page = pages.firstOrNull { it.key == back.page } ?: navigablePages.firstOrNull { "home" in it.roles } ?: navigablePages.firstOrNull() ?: return
        select(page, -1, false, back.variant)
    }

    /** Any touch keeps the settings page open for another 30 seconds. */
    fun noteInteraction() {
        if (settingsReturn == null) return
        handler.removeCallbacks(settingsIdle)
        handler.postDelayed(settingsIdle, SETTINGS_IDLE_MILLIS)
    }

    fun setPages(next: List<PluginPage>) {
        pages = next
        if (next.isEmpty()) { store.update { it.copy(deck = DeckState()) }; return }
        val current = currentPage
        val start = navigablePages
        // The settings plugin vanished while its page was open: fall back to the normal deck.
        if (settingsReturn != null && next.none { "settings" in it.roles }) { settingsReturn = null; handler.removeCallbacks(settingsIdle) }
        if (current == null) select(start.firstOrNull { "home" in it.roles } ?: start.firstOrNull() ?: next.first(), 0, false)
        else if (store.current.deck.variant !in current.variants && current.variants.isNotEmpty()) select(current, 0, false)
    }
    fun previewPage(delta: Int): PluginPage? {
        val deck = navigablePages
        if (deck.size < 2 || settingsOpen) return null
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
        if (deck.size < 2 || settingsOpen) return
        lastLocal = SystemClock.elapsedRealtime()
        previousLive = null; livePage = null
        val index = deck.indexOfFirst { it.key == store.current.deck.page }.coerceAtLeast(0)
        select(deck[Math.floorMod(index + delta, deck.size)], delta, false)
    }
    fun open(pageKey: String) {
        if (settingsOpen) return
        val page = pages.firstOrNull { it.key == pageKey } ?: return
        lastLocal = SystemClock.elapsedRealtime()
        previousLive = null; livePage = null
        select(page, 1, false)
    }
    fun changeVariant(delta: Int) {
        if (settingsOpen) return
        val page = currentPage ?: return
        if (page.variants.size < 2) return
        lastLocal = SystemClock.elapsedRealtime()
        val current = page.variants.indexOf(store.current.deck.variant).coerceAtLeast(0)
        val variant = page.variants[Math.floorMod(current + delta, page.variants.size)]
        preferences.edit().putString(page.key, variant).apply()
        store.update { it.copy(deck = it.deck.copy(variant = variant, direction = delta, vertical = true)) }
        onPageEvent(page.pluginId, "variant", JSONArray().put(page.id).put(variant))
    }
    fun requestFocus(pageKey: String, live: Boolean, release: Boolean) {
        val page = pages.firstOrNull { it.key == pageKey } ?: return
        if (settingsOpen) return              // nothing may pull the user out of the settings page
        if (release) {
            if (livePage == pageKey) {
                val restore = previousLive
                previousLive = null; livePage = null
                restore?.let { old -> pages.firstOrNull { it.key == old.page }?.let { select(it, -1, false, old.variant) } }
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (store.current.attention == Attention.ASLEEP || lastLocal != Long.MIN_VALUE && now - lastLocal < 30_000) return
        if (live && !page.live) return
        val active = currentPage
        if (livePage != null && active != null && page.priority < active.priority) return
        if (live && livePage != pageKey) { if (previousLive == null) previousLive = store.current.deck; livePage = pageKey }
        select(page, 1, false)
    }
    fun applyAttention(level: Attention) {
        if (level == previousAttention) return
        val wasAsleep = previousAttention == Attention.ASLEEP
        previousAttention = level
        handler.removeCallbacks(ambientRole)
        if (settingsOpen) return              // the role pages resume once settings close
        if (level == Attention.AMBIENT) handler.postDelayed(ambientRole, ambientDelayMillis())
        when {
            level == Attention.ASLEEP -> pages.firstOrNull { "sleep" in it.roles }?.let { select(it, 0, false) }
            wasAsleep -> pages.firstOrNull { "home" in it.roles }?.let { select(it, 0, false) }
        }
    }
    private fun select(page: PluginPage, direction: Int, vertical: Boolean, forcedVariant: String? = null, params: JSONObject? = null) {
        if (store.current.theme == Theme.NIGHT && "ambient" in page.roles) {
            pages.firstOrNull { "sleep" in it.roles && "ambient" !in it.roles }?.let { select(it, direction, false) }
            return
        }
        val old = currentPage
        val stored = forcedVariant ?: preferences.getString(page.key, null)
        val variant = stored?.takeIf { it in page.variants } ?: page.variants.firstOrNull() ?: "digital"
        if (old?.key != page.key) old?.let { onPageEvent(it.pluginId, "page.leave", JSONArray().put(it.id)) }
        store.update { it.copy(deck = DeckState(page.key, variant, direction, vertical)) }
        onPageEvent(page.pluginId, "page.enter", JSONArray().put(page.id).put(variant).also { if (params != null) it.put(params) })
    }

    companion object { const val SETTINGS_IDLE_MILLIS = 30_000L }
}
