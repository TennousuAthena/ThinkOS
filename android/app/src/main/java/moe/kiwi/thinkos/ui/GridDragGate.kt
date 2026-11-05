package moe.kiwi.thinkos.ui

import kotlin.math.abs

/** A horizontal drag is never a cell tap; an established vertical scroll keeps its direction. */
internal class GridDragGate(private val slop: Float) {
    private enum class Axis { PENDING, VERTICAL, HORIZONTAL }
    private var axis = Axis.PENDING
    private var x = 0f
    private var y = 0f

    fun down(x: Float, y: Float) { this.x = x; this.y = y; axis = Axis.PENDING }
    fun move(x: Float, y: Float): Boolean {
        val dx = abs(x - this.x); val dy = abs(y - this.y)
        if (axis == Axis.PENDING && maxOf(dx, dy) > slop) axis = if (dx > dy) Axis.HORIZONTAL else Axis.VERTICAL
        return axis == Axis.HORIZONTAL
    }
}
