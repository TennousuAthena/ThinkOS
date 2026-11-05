package moe.kiwi.thinkos.ui

/** The four UI text sizes (sp). Clock faces, icon glyphs and video subtitles are exceptions (ADR-044). */
object TextScale {
    const val DISPLAY = 72f
    const val TITLE = 32f
    const val BODY = 18f
    const val CAPTION = 14f

    fun presets(maximum: Float = DISPLAY): IntArray =
        intArrayOf(CAPTION.toInt(), BODY.toInt(), TITLE.toInt(), DISPLAY.toInt()).filter { it <= maximum }.toIntArray()
}
