package thinkos.simulator.reference

import moe.kiwi.thinkos.ui.RenderGeometry
import moe.kiwi.thinkos.ui.PanelGeometry
import moe.kiwi.thinkos.ui.TileColor

internal fun box(value: RenderGeometry.Box?): String = value?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "null"
internal fun panelBox(value: PanelGeometry.Box?): String = value?.let { "[${it.left},${it.top},${it.right},${it.bottom}]" } ?: "null"

fun slot(layout: String, role: String, width: Int, height: Int, density: Double, header: Boolean, footer: Boolean): String =
    box(RenderGeometry.slot(layout, role, width, height, density.toFloat(), header, footer))

fun tiles(sizes: String, width: Int, height: Int, density: Double, rtl: Boolean): String =
    RenderGeometry.tiles(if (sizes.isEmpty()) emptyList() else sizes.split(','), width, height, density.toFloat(), rtl).joinToString(prefix = "[", postfix = "]") { box(it) }

fun vertical(available: Int, measured: String, weights: String, gap: Int): String {
    val heights = if (measured.isEmpty()) emptyList() else measured.split(',').map(String::toInt)
    val shares = if (weights.isEmpty()) emptyList() else weights.split(',').map(String::toDouble)
    val result = RenderGeometry.verticalHeights(available, heights, shares, gap)
    return "{\"heights\":${result.heights.joinToString(prefix = "[", postfix = "]")},\"gap\":${result.gap},\"offset\":${result.offset}}"
}

fun panel(width: Int, height: Int, density: Double, header: Boolean, quick: Boolean, levels: Int, media: Boolean, footer: Boolean, update: Boolean, quickCount: Int): String {
    val result = PanelGeometry.plan(width, height, density.toFloat(), header, quick, levels, media, footer, update = update, quickCount = quickCount)
    return "{\"width\":${result.width},\"height\":${result.height},\"header\":${panelBox(result.header)},\"quick\":${panelBox(result.quick)},\"levels\":${panelBox(result.levels)},\"media\":${panelBox(result.media)},\"footer\":${panelBox(result.footer)},\"update\":${panelBox(result.update)}}"
}

fun foreground(value: String, night: Double): String {
    val background = TileColor.parse(value) ?: 0xff7c95ff.toInt()
    val safe = TileColor.nightSafe(background, night.toFloat())
    val fg = TileColor.nightForeground(safe, night.toFloat())
    fun hex(v: Int) = "#" + (v and 0xffffff).toString(16).padStart(6, '0')
    return "{\"background\":\"${hex(TileColor.filtered(safe, night.toFloat()))}\",\"foreground\":\"${hex(TileColor.filtered(fg, night.toFloat()))}\"}"
}
