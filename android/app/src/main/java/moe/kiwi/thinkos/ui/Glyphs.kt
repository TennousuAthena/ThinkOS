package moe.kiwi.thinkos.ui

import android.content.Context
import android.graphics.drawable.Drawable

/**
 * The icons that come from Iconfont (`assets/icons`, `tools/gen_glyphs.py`): white vector drawables that take whatever colour a
 * view tints them. They are drawn at the size they are shown at, never scaled down from a bitmap, so their edges stay sharp.
 */
internal object Glyphs {
    val names: Set<String> get() = GlyphResources.ids.keys

    fun has(name: String): Boolean = name in GlyphResources.ids

    /** A drawable of its own: tint and bounds belong to the drawable, so views must not share one. Null for a name that is not one of these. */
    fun drawable(context: Context, name: String): Drawable? = GlyphResources.ids[name]?.let { context.getDrawable(it)?.mutate() }
}
