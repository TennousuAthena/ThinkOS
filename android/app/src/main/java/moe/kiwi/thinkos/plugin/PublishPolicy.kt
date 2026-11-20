package moe.kiwi.thinkos.plugin

internal object PublishPolicy {
    private const val USER_WINDOW_MS = 300L
    private const val MIN_SPACING_MS = 16L
    private const val DEFAULT_DELAY_MS = 50L

    fun isUserEvent(name: String): Boolean = when (name) {
        "tap", "form.change", "back", "variant", "page.enter" -> true
        else -> false
    }

    fun inUserWindow(now: Long, lastUserEvent: Long?): Boolean =
        lastUserEvent != null && now - lastUserEvent in 0..USER_WINDOW_MS

    fun delay(now: Long, lastUserEvent: Long?, lastFastPublish: Long?): Long {
        if (!inUserWindow(now, lastUserEvent)) return DEFAULT_DELAY_MS
        return lastFastPublish?.let { (MIN_SPACING_MS - (now - it)).coerceAtLeast(0) } ?: 0
    }
}
