package moe.kiwi.thinkos.update

/** Process-local readiness. Activity recreation cannot restart a healthy timer;
 * a new ART process receives a new trial id and a new gate. No wall-clock jumps. */
internal class UpdateHealthGate(private val pending: UpdatePending, private val clock: () -> UpdateInstallTime) {
    data class Proof(val transactionId: String, val trialId: String, val ready: UpdateInstallTime, val now: UpdateInstallTime)
    private var frame = false
    private var catalog = false
    private var ready: UpdateInstallTime? = null
    init { require(pending.phase == "TRIAL" && pending.trial_id != null && pending.trial_started != null) }
    fun frame() { frame = true; ready() }
    fun catalog() { catalog = true; ready() }
    private fun ready() {
        if (frame && catalog && ready == null) ready = clock()
    }
    fun remaining(): Long? {
        val start = ready ?: return null
        val now = clock()
        if (now.boot != start.boot || now.elapsed < start.elapsed) return null
        return (pending.health_ms - (now.elapsed - start.elapsed)).coerceAtLeast(0)
    }
    fun proof(): Proof? {
        val start = ready ?: return null
        val now = clock()
        if (now.boot != start.boot || now.elapsed < start.elapsed || now.elapsed - start.elapsed < pending.health_ms) return null
        return Proof(pending.transaction_id, pending.trial_id!!, start, now)
    }
}
