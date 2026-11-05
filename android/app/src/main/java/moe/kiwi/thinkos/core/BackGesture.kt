package moe.kiwi.thinkos.core

import kotlin.math.abs

/**
 * The rules of the back swipe (ADR-040): a finger that goes down in the leftmost strip and moves right. Left is the physical left
 * of the screen, not the start of the reading direction, just as page swipes are not mirrored either.
 */
object BackGesture {
    /** The finger leaves a swipe that does not start at the left edge to the page. */
    fun startsAtEdge(x: Float, edgePx: Float): Boolean = x >= 0f && x <= edgePx

    enum class Verdict {
        /** Not moved far enough to tell. */
        WAIT,
        /** A back swipe. */
        BACK,
        /** The control sheet is being pulled down. */
        PULL,
        /** The physical bottom-edge swipe to Home (ADR-046). */
        UP,
        /** Something else: the page gets the whole touch, beginning with its first point. */
        PAGE,
    }

    /**
     * A touch that began where a back swipe and the control sheet's pull-down can both begin (the top-left corner) is decided by
     * whichever the finger shows first. [back] and [pull] say which of them this touch could still become.
     */
    fun classify(dx: Float, dy: Float, slop: Float, back: Boolean, pull: Boolean, up: Boolean = false): Verdict = when {
        back && dx > slop && dx > abs(dy) -> Verdict.BACK
        pull && dy > slop && dy > abs(dx) -> Verdict.PULL
        up && -dy > slop && -dy > abs(dx) -> Verdict.UP
        maxOf(abs(dx), abs(dy)) > slop -> Verdict.PAGE
        else -> Verdict.WAIT
    }

    /** The same distance and speed as a page swipe: 22 % of the width, or a fling faster than 700 dp/s. */
    fun commits(offset: Float, speed: Float, width: Float, density: Float): Boolean =
        offset > width * COMMIT_FRACTION || speed > COMMIT_SPEED_DP * density && offset > 0f

    /** How far along the swipe is, 0 to 1, for the arrow at the edge. */
    fun progress(offset: Float, width: Float): Float = if (width <= 0f) 0f else (offset / (width * COMMIT_FRACTION)).coerceIn(0f, 1f)

    const val COMMIT_FRACTION = .22f
    const val COMMIT_SPEED_DP = 700f
    const val EDGE_HOLD_MILLIS = 150L
}
