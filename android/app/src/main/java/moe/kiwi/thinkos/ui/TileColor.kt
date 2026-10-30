package moe.kiwi.thinkos.ui

import kotlin.math.pow

/** Opaque sRGB only. No Android dependency: validation and contrast are tested on the JVM. */
object TileColor {
    fun parse(value: String?): Int? = value?.takeIf { it.matches(Regex("#[0-9A-Fa-f]{6}")) }
        ?.substring(1)?.toInt(16)?.or(0xff000000.toInt())

    fun resolve(page: String?, card: String?, accent: Int): Int = parse(page) ?: parse(card) ?: accent

    fun luminance(color: Int): Double {
        fun channel(shift: Int): Double {
            val c = (color ushr shift and 255) / 255.0
            return if (c <= .04045) c / 12.92 else ((c + .055) / 1.055).pow(2.4)
        }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    fun contrast(a: Int, b: Int): Double {
        val x = luminance(a); val y = luminance(b)
        return (maxOf(x, y) + .05) / (minOf(x, y) + .05)
    }
    fun foreground(background: Int): Int = if (contrast(background, 0xff000000.toInt()) >= contrast(background, -1)) 0xff000000.toInt() else -1
}
