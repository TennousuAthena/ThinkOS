package moe.kiwi.thinkos.ui

import kotlin.math.abs
import kotlin.math.sign

internal class DragPrediction(private val millis: Int) {
    private var previousTime = 0L
    private var previousPosition = 0f
    private var previousDirection = 0f

    fun sample(position: Float, eventTime: Long, velocity: Float): Float {
        val elapsed = eventTime - previousTime
        val delta = position - previousPosition
        val direction = delta.sign
        val project = elapsed > 0L && direction != 0f && direction == previousDirection && direction == velocity.sign
        previousTime = eventTime
        previousPosition = position
        previousDirection = direction
        if (!project) return position
        val speed = minOf(abs(delta) / elapsed, abs(velocity) / 1_000f)
        val projected = position + direction * speed * millis
        return if (projected.sign == position.sign) projected else 0f
    }
}
