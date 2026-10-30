package moe.kiwi.thinkos.ui

/** One scheduler per page, including tile templates inside stacks. Uses the scene's monotonic clock. */
class TileCadence {
    private var due: Long? = null
    private var interval = 0L
    private var previous = -1
    fun reset() { due = null }
    fun select(now: Long, allowed: Boolean, intervalMillis: Long, eligible: List<Boolean>, moving: Boolean): Int? {
        if (!allowed) { reset(); return null }
        if (due == null || interval != intervalMillis) { interval = intervalMillis; due = now + interval; return null }
        if (now < due!! || moving) return null
        due = now + interval
        if (eligible.isEmpty()) return null
        for (offset in 1..eligible.size) {
            val index = (previous + offset).mod(eligible.size)
            if (eligible[index]) { previous = index; return index }
        }
        return null
    }
}
