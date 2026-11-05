package moe.kiwi.thinkos.media

/** Waking releases admission, but never grants an old session permission to resume by itself. */
internal class SleepPlaybackGate(private val sleeping: () -> Boolean) {
    private var parked = false
    private var wasSleeping = false
    @Volatile var generation: Long = 0
        private set
    val blocked: Boolean get() = sleeping() || parked
    fun ticket(): Long = if (sleeping()) -1 else generation
    fun changed(): Boolean {
        val asleep = sleeping()
        if (asleep) {
            parked = true
            if (!wasSleeping) generation++
        }
        wasSleeping = asleep
        return blocked
    }
    fun requireAwake(ticket: Long? = null) {
        check(!sleeping() && (ticket == null || ticket == generation)) { "asleep" }
    }
    fun userPlayback(): Boolean {
        if (sleeping()) return false
        parked = false
        return true
    }
}
