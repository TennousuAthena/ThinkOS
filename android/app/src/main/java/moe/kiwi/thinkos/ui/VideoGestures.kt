package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.core.Attention
import kotlin.math.abs

/** The rules of the cinema view's touch gestures, free of Android so that they are tested on the JVM. */
internal object VideoGestures {
    enum class Zone { REWIND, TOGGLE, FORWARD }

    /** A double tap: the left quarter goes back, the right quarter goes ahead, the middle half pauses and resumes. */
    fun zone(x: Float, width: Float): Zone = when {
        width <= 0f -> Zone.TOGGLE
        x < width * .25f -> Zone.REWIND
        x >= width * .75f -> Zone.FORWARD
        else -> Zone.TOGGLE
    }

    /** A vertical drag: the left half of the screen is brightness, the right half volume. */
    fun isBrightness(x: Float, width: Float): Boolean = x < width / 2f

    /**
     * Whether the transport controls are up. A touch raises attention to ENGAGED, which shows them; a tap on the picture sets
     * [hidden] to put them away again. Errors, the end of the film and a pause keep them up whatever [hidden] says.
     */
    fun controlsShown(attention: Attention, hidden: Boolean, status: String): Boolean =
        attention == Attention.ENGAGED && !hidden || status == "error" || status == "ended" || status == "paused" && attention >= Attention.AWAKE

    /** The level a drag leads to: [base] when it began, moved up by a finger going up, over [travel] px for the whole scale. */
    fun level(base: Float, offset: Float, travel: Float, minimum: Float): Float =
        (base - offset / travel.coerceAtLeast(1f)).coerceIn(minimum, 1f)
}

/** Tells a second tap from a first one: it must follow within [timeoutMillis] and land within [slop] px of it. */
internal class DoubleTap(private val timeoutMillis: Long, private val slop: Float) {
    private var at = Long.MIN_VALUE
    private var x = 0f
    private var y = 0f

    /** True when this tap completes a double tap; otherwise it is remembered as the possible first tap of one. */
    fun tap(now: Long, px: Float, py: Float): Boolean {
        val second = at != Long.MIN_VALUE && now - at in 0..timeoutMillis && abs(px - x) <= slop && abs(py - y) <= slop
        if (second) at = Long.MIN_VALUE else { at = now; x = px; y = py }
        return second
    }

    fun reset() { at = Long.MIN_VALUE }
}

/** Seconds skipped by a run of double taps. The viewer sees them add up ("+30") until a pause, or a change of direction, starts over. */
internal class SkipTally(private val windowMillis: Long) {
    private var total = 0
    private var last = Long.MIN_VALUE

    fun add(now: Long, seconds: Int): Int {
        if (last == Long.MIN_VALUE || now - last > windowMillis || total != 0 && (total > 0) != (seconds > 0)) total = 0
        total += seconds
        last = now
        return total
    }
}
