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

    /** Same matrix as ThinkStage. Black/white are chosen after composition in night mode. */
    fun filtered(color: Int, night: Float): Int {
        val n = night.coerceIn(0f, 1f).toDouble()
        val r = color ushr 16 and 255; val g = color ushr 8 and 255; val b = color and 255
        val gray = .2126 * r + .7152 * g + .0722 * b
        fun channel(c: Int, tint: Double) = (c * (1-n) + gray * tint * n).toInt().coerceIn(0,255)
        return 0xff000000.toInt() or (channel(r, 1.0) shl 16) or (channel(g, 59/255.0) shl 8) or channel(b, 47/255.0)
    }
    fun nightForeground(color: Int, night: Float): Int = if (contrast(filtered(color, night), filtered(0xff000000.toInt(), night)) >= contrast(filtered(color, night), filtered(-1, night))) 0xff000000.toInt() else -1

    /** The red matrix compresses contrast. Adjust only when neither composed foreground is readable. */
    fun nightSafe(color: Int, night: Float): Int {
        fun readable(c: Int) = contrast(filtered(c, night), filtered(nightForeground(c, night), night)) >= 4.5
        if (night <= 0 || readable(color)) return color
        fun mix(to: Int, t: Double): Int {
            fun c(s: Int) = (((color ushr s and 255) * (1-t)) + (to ushr s and 255) * t).toInt()
            return 0xff000000.toInt() or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
        fun towards(to: Int): Pair<Double, Int> {
            var low = 0.0; var high = 1.0
            repeat(16) { val t = (low+high)/2; if (readable(mix(to,t))) high = t else low = t }
            return high to mix(to,high)
        }
        return listOf(towards(0xff000000.toInt()), towards(-1)).minBy { it.first }.second
    }
}
