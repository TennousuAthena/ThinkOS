package moe.kiwi.thinkos.ui.form

/** Fixed OS form geometry; no plugin-provided dimensions (SET-06). */
internal object FormColumns {
    data class Plan(val wide: Boolean, val body: Int, val navigation: Int, val gap: Int) {
        val content: Int get() = body - navigation - gap
    }

    fun plan(width: Int, density: Float, hasNavigation: Boolean): Plan {
        val available = width.coerceAtLeast(0)
        val wide = hasNavigation && available >= 700 * density
        val navigation = if (wide) (240 * density).toInt() else 0
        val gap = if (wide) (24 * density).toInt() else 0
        return Plan(wide, minOf(available, (560 * density).toInt() + navigation + gap), navigation, gap)
    }
}
