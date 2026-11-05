package moe.kiwi.thinkos.ui

/** Hairlines are physical pixels. ICON and TRACK are dp, converted by their drawing surface. */
object Stroke {
    fun hairline(): Float = 1f
    const val ICON = 2f
    const val TRACK = 4f
}
