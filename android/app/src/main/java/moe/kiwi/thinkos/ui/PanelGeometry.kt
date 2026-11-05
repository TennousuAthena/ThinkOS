package moe.kiwi.thinkos.ui

import kotlin.math.max
import kotlin.math.min

/**
 * Where the slots of the control sheet sit (ADR-035). Pure arithmetic, so it is tested without a device.
 *
 * A portrait sheet is one column. A landscape sheet is wide and short: the toggles and sliders stand on the left and the media
 * controls on the right, so the sheet stays well under the 492 dp height of the screen.
 */
internal object PanelGeometry {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** [width] and [height] are the whole sheet; a slot that is not shown has no box. */
    class Plan(val width: Int, val height: Int, val header: Box?, val quick: Box?, val levels: Box?, val media: Box?, val footer: Box?, val update: Box? = null)

    private const val SHEET_MAX_DP = 720f
    private const val PAD_X_DP = 24f
    private const val PAD_TOP_DP = 12f
    private const val PAD_BOTTOM_DP = 20f
    private const val GAP_DP = 8f
    private const val HEADER_DP = 48f
    private const val QUICK_DP = 88f
    private const val LEVEL_DP = 52f
    private const val MEDIA_DP = 148f
    private const val FOOTER_DP = 52f
    private const val UPDATE_DP = 56f

    /** Four legacy toggles keep their row; six readable controls use three columns. */
    fun quickColumns(count: Int): Int = if (count > 4) 3 else count.coerceAtLeast(1)
    fun quickRows(count: Int): Int = (count.coerceAtLeast(1) + quickColumns(count) - 1) / quickColumns(count)

    fun plan(screenWidth: Int, screenHeight: Int, density: Float, header: Boolean, quick: Boolean, levels: Int, media: Boolean, footer: Boolean,
             footerTextWidth: Int? = null, update: Boolean = false, quickCount: Int = 4): Plan {
        fun px(dp: Float) = (dp * density).toInt()
        val landscape = screenWidth > screenHeight
        val width = if (landscape) min(screenWidth, px(SHEET_MAX_DP)) else screenWidth
        val padX = px(PAD_X_DP)
        val gap = px(GAP_DP)
        val inner = (width - 2 * padX).coerceAtLeast(0)
        var y = px(PAD_TOP_DP)
        var any = false
        fun next(height: Int) { y += height + gap; any = true }

        var headerBox: Box? = null
        var footerBox: Box? = null
        // Landscape is short: the time and the network line share the top row instead of taking a row each.
        val sharedRow = landscape && header && footer
        if (sharedRow) {
            // A localized date needs most of the row. Reserve the network
            // caption's natural width instead of splitting it into equal halves.
            val footerWidth = (footerTextWidth ?: ((inner - gap) / 4)).coerceIn(0, (inner - gap).coerceAtLeast(0) / 3)
            val headerWidth = (inner - gap - footerWidth).coerceAtLeast(0)
            headerBox = Box(padX, y, padX + headerWidth, y + px(HEADER_DP))
            footerBox = Box(padX + headerWidth + gap, y, padX + inner, y + px(HEADER_DP))
            next(px(HEADER_DP))
        } else if (header) { headerBox = Box(padX, y, padX + inner, y + px(HEADER_DP)); next(px(HEADER_DP)) }

        val levelHeight = levels * px(LEVEL_DP)
        val quickHeight = px(QUICK_DP) * quickRows(quickCount)
        val leftHeight = (if (quick) quickHeight else 0) + (if (quick && levels > 0) gap else 0) + levelHeight
        val mediaHeight = if (media) px(MEDIA_DP) else 0
        val twoColumns = landscape && (media || update) && leftHeight > 0
        var quickBox: Box? = null
        var levelsBox: Box? = null
        var mediaBox: Box? = null
        var updateBox: Box? = null
        if (twoColumns) {
            val left = (inner - gap) / 2
            var ly = y
            if (quick) { quickBox = Box(padX, ly, padX + left, ly + quickHeight); ly += quickHeight + gap }
            if (levels > 0) levelsBox = Box(padX, ly, padX + left, ly + levelHeight)
            if (media) mediaBox = Box(padX + left + gap, y, padX + inner, y + mediaHeight)
            val updateY = y + (if (media) mediaHeight + gap else 0)
            if (update) updateBox = Box(padX + left + gap, updateY, padX + inner, updateY + px(UPDATE_DP))
            val rightHeight = mediaHeight + (if (media && update) gap else 0) + (if (update) px(UPDATE_DP) else 0)
            next(max(leftHeight, rightHeight))
        } else {
            if (quick) { quickBox = Box(padX, y, padX + inner, y + quickHeight); next(quickHeight) }
            if (levels > 0) { levelsBox = Box(padX, y, padX + inner, y + levelHeight); next(levelHeight) }
            if (media) { mediaBox = Box(padX, y, padX + inner, y + mediaHeight); next(mediaHeight) }
        }
        if (update && !twoColumns) {
            // Portrait shares the footer row rather than covering more of the
            // clock. The result opens full notes; the network link stays present.
            val reserved = if (footer && !sharedRow) (footerTextWidth ?: inner / 4).coerceIn(0, (inner-gap).coerceAtLeast(0)/3) else 0
            val right = if (reserved > 0) padX + inner - reserved - gap else padX + inner
            updateBox = Box(padX, y, right, y + px(UPDATE_DP))
            if (footer && !sharedRow) footerBox = Box(padX + inner - reserved, y, padX + inner, y + px(UPDATE_DP))
            next(px(UPDATE_DP))
        } else if (footer && !sharedRow) { footerBox = Box(padX, y, padX + inner, y + px(FOOTER_DP)); next(px(FOOTER_DP)) }
        // The last block has no gap after it; the bottom padding follows its edge.
        val bottom = if (any) y - gap else y
        return Plan(width, bottom + px(PAD_BOTTOM_DP), headerBox, quickBox, levelsBox, mediaBox, footerBox, updateBox)
    }

    /** How far down a finger must pull, as a fraction of the sheet, for the sheet to open when it is let go. */
    const val OPEN_FRACTION = .22f
    /** A flick faster than this opens (or closes) the sheet whatever the distance, in dp per second. */
    const val FLICK_DP_PER_SECOND = 700f

    /** What happens on release: open, or fall back closed. [progress] is 0 (closed) to 1 (open). */
    fun settlesOpen(progress: Float, velocityPxPerSecond: Float, density: Float, wasOpen: Boolean): Boolean {
        val flick = FLICK_DP_PER_SECOND * density
        if (velocityPxPerSecond > flick) return true
        if (velocityPxPerSecond < -flick) return false
        // The same share of the sheet decides both ways: a closed sheet opens after that much pull down, an open one closes after that much pull up.
        return if (wasOpen) progress > 1f - OPEN_FRACTION else progress > OPEN_FRACTION
    }
}
