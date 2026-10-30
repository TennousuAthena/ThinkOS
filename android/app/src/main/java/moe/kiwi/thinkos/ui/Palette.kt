package moe.kiwi.thinkos.ui

import android.graphics.Color
import moe.kiwi.thinkos.core.Theme

data class Palette(val bg: Int, val surface: Int, val primary: Int, val secondary: Int, val accent: Int, val accentText: Int, val divider: Int, val warn: Int = 0xfff26b5e.toInt(), val warnText: Int = 0xffc0392f.toInt()) {
    fun blend(other: Palette, t: Float) = Palette(mix(bg,other.bg,t),mix(surface,other.surface,t),mix(primary,other.primary,t),mix(secondary,other.secondary,t),mix(accent,other.accent,t),mix(accentText,other.accentText,t),mix(divider,other.divider,t),mix(warn,other.warn,t),mix(warnText,other.warnText,t))
    companion object {
        val LIGHT = Palette(0xfff7f8fa.toInt(),0xffffffff.toInt(),0xff1b1d21.toInt(),0xff6b7280.toInt(),0xff5b7cfa.toInt(),0xff3f5fe0.toInt(),0xffe8eaef.toInt(),0xfff26b5e.toInt(),0xffc0392f.toInt())
        val DARK = Palette(0xff0e0f12.toInt(),0xff1b1d22.toInt(),0xfff2f3f5.toInt(),0xff8d929b.toInt(),0xff7c95ff.toInt(),0xff7c95ff.toInt(),0xff2a2d33.toInt(),0xffff7a6b.toInt(),0xffff7a6b.toInt())
        fun forTheme(theme: Theme) = if (theme == Theme.LIGHT) LIGHT else DARK
        fun mix(a: Int,b: Int,t: Float): Int = Color.argb((Color.alpha(a)+(Color.alpha(b)-Color.alpha(a))*t).toInt(),(Color.red(a)+(Color.red(b)-Color.red(a))*t).toInt(),(Color.green(a)+(Color.green(b)-Color.green(a))*t).toInt(),(Color.blue(a)+(Color.blue(b)-Color.blue(a))*t).toInt())
    }
}
