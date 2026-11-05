package moe.kiwi.thinkos.ui

/** One-use credit for a native button release. performClick/accessibility calls
 * have no release credit; stale, obscured or multiple pointers invalidate it. */
internal class NativeTouchGate {
    private var downAt: Long? = null
    private var releasedAt: Long? = null
    fun down(now: Long, eligible: Boolean) {
        releasedAt = null
        downAt = now.takeIf { eligible && now >= 0 }
    }
    fun up(now: Long, eligible: Boolean, inside: Boolean) {
        val down = downAt
        releasedAt = now.takeIf { down != null && eligible && inside && now >= down && now - down <= 5_000 }
        downAt = null
    }
    fun cancel() { downAt = null; releasedAt = null }
    fun consume(now: Long, focused: Boolean): Boolean {
        val release = releasedAt
        cancel()
        return focused && release != null && now >= release && now - release <= 500
    }
}
