package moe.kiwi.thinkos.image

import kotlin.math.abs

/** A new pointer takes over the presented offset, rather than completing an interrupted page turn. */
internal class PhotoDrag(private val touchSlop: Float, private val pagingSlop: Float, private val flingVelocity: Float) {
    private var anchor = 0f
    private var pointerOrigin = 0f
    private var travel = 0f
    private var resumeCommit: Boolean? = null

    fun begin(presentedOffset: Float, firstDelta: Float, previousCommit: Boolean? = null, resistance: Float = 1f) {
        anchor = presentedOffset / resistance
        pointerOrigin = firstDelta.coerceIn(-touchSlop, touchSlop)
        travel = firstDelta
        resumeCommit = previousCommit
    }
    fun move(delta: Float): Float {
        travel = delta
        return anchor + delta - pointerOrigin
    }
    fun direction(offset: Float, previous: Int): Int =
        if (abs(offset) <= touchSlop) previous else if (offset < 0) 1 else -1

    fun commit(offset: Float, extent: Int, velocity: Float, cancelled: Boolean): Boolean {
        if (cancelled || extent <= 0 || !offset.isFinite() || !velocity.isFinite()) return false
        if (abs(travel) < pagingSlop) return resumeCommit ?: false
        // A deliberate reverse fling returns to the original image, even beyond the distance threshold.
        if (abs(velocity) >= flingVelocity) return offset * velocity > 0
        return abs(offset) > extent * .18f
    }
}
