@file:OptIn(kotlin.js.ExperimentalJsExport::class)
package thinkos.simulator.reference

import kotlin.js.JsExport
import moe.kiwi.thinkos.core.NavStack

@JsExport fun nativeSlot(layout: String, role: String, width: Int, height: Int, density: Double, header: Boolean, footer: Boolean): String = slot(layout, role, width, height, density, header, footer)
@JsExport fun nativeTiles(sizes: String, width: Int, height: Int, density: Double, rtl: Boolean): String = tiles(sizes, width, height, density, rtl)
@JsExport fun nativeVertical(available: Int, measured: String, weights: String, gap: Int): String = vertical(available, measured, weights, gap)
@JsExport fun nativePanel(width: Int, height: Int, density: Double, header: Boolean, quick: Boolean, levels: Int, media: Boolean, footer: Boolean, update: Boolean, quickCount: Int): String = panel(width, height, density, header, quick, levels, media, footer, update, quickCount)
@JsExport fun nativeTileColors(value: String, night: Double): String = foreground(value, night)

/** Independent browser instance of the unchanged production navigation stack. */
@JsExport class NativeNav {
    private val stack = NavStack()
    val depth: Int get() = stack.depth
    val topKind: String? get() = stack.top?.kind?.name
    val topPage: String? get() = stack.top?.frame?.page
    val topVariant: String? get() = stack.top?.frame?.variant
    fun has(kind: String): Boolean = stack.has(NavStack.Kind.valueOf(kind))
    fun push(fromPage: String, fromVariant: String, kind: String, page: String, variant: String): Boolean = stack.push(NavStack.Frame(fromPage, fromVariant), NavStack.Kind.valueOf(kind), NavStack.Frame(page, variant))
    fun pop(): String? = stack.pop()?.let { it.page + "\n" + it.variant }
    fun clear(): String? = stack.clear()?.let { it.page + "\n" + it.variant }
    fun variantChanged(variant: String) = stack.variantChanged(variant)
    fun replaceTop(page: String, variant: String): Boolean = stack.replaceTop(NavStack.Frame(page, variant))
    /** Null = unchanged, empty = home, otherwise page + newline + variant. */
    private fun encode(change: NavStack.Change): String? = when (change) {
        NavStack.Change.None -> null
        NavStack.Change.Home -> ""
        is NavStack.Change.Show -> change.frame.page + "\n" + change.frame.variant
    }
    fun remove(page: String): String? = encode(stack.remove(page))
    fun removeKind(kind: String): String? = encode(stack.removeKind(NavStack.Kind.valueOf(kind)))
    fun unwindTo(page: String): String? = encode(stack.unwindTo(page))
    fun prune(pages: String): String? { val known = pages.split('\n').toSet(); return encode(stack.prune { it in known }) }
}
