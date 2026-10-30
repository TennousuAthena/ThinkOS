package moe.kiwi.thinkos.ui

import android.graphics.Rect

data class LayoutSpec(val width: Int, val height: Int, val density: Float) {
    val landscape get() = width > height
    fun dp(value: Float) = (value*density).toInt()
    fun stack(index: Int): Rect {
        val edge=dp(20f); val gap=dp(16f)
        return if (landscape) {
            val cell=(width-edge*2-gap)/2
            Rect(edge+index*(cell+gap),edge,edge+index*(cell+gap)+cell,height-edge)
        } else {
            val cell=(height-edge*2-gap)/2
            Rect(edge,edge+index*(cell+gap),width-edge,edge+index*(cell+gap)+cell)
        }
    }
}
