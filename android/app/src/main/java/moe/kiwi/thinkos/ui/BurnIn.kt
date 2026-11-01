package moe.kiwi.thinkos.ui

import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin

/**
 * The slow wander of the whole picture that keeps static content from burning into the panel: up to [MAX_X] px across and
 * [MAX_Y] px up and down, a full cycle in 6 and 8 minutes. Pages that fill the screen edge to edge (tiles, the Aero
 * desktop, photo and video stages) must not move with it, or the backdrop shows along the edges.
 */
object BurnIn {
    const val MAX_X = 8f
    const val MAX_Y = 6f

    /** Debug builds can pin the offset (the real one moves too slowly to test); nothing in a release build sets it. */
    @Volatile var forced: Pair<Float, Float>? = null

    fun offset(now: Long): Pair<Float, Float> = forced ?: Pair(
        (round(sin(now / 180_000.0 * Math.PI) * 64) / 8).toFloat(),
        (round(cos(now / 240_000.0 * Math.PI) * 48) / 8).toFloat(),
    )
}
