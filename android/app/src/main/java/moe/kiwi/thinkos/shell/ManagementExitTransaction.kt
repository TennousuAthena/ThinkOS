package moe.kiwi.thinkos.shell

/** Irreversible identity transition, deliberately independent of Android for
 * fault-injection tests. Ownership is re-read even when clearOwner throws. */
internal object ManagementExitTransaction {
    enum class Record { PENDING, RELEASED }
    data class Outcome(val released: Boolean, val complete: Boolean, val failure: Exception? = null)
    interface Ports {
        fun prepare()
        fun isOwner(): Boolean
        fun releasePolicies()
        fun clearOwner()
        fun isSelfAdminActive(): Boolean
        fun removeSelfAdmin()
        fun restorePolicies()
        fun abort()
        fun commit()
        fun finish()
    }
    fun run(ports: Ports): Outcome {
        try { ports.prepare() } catch (error: Exception) { return Outcome(false, false, error) }
        var failure: Exception? = null
        try {
            if (ports.isOwner()) { ports.releasePolicies(); ports.clearOwner() }
        } catch (error: Exception) { failure = error }
        val stillOwner = try { ports.isOwner() } catch (error: Exception) {
            // Unknown ownership is neither success nor permission to recreate it.
            return Outcome(false, false, error)
        }
        if (stillOwner) {
            try { ports.restorePolicies(); ports.abort() }
            catch (error: Exception) { failure = error }
            return Outcome(false, false, failure ?: IllegalStateException("Device Owner remains"))
        }
        // AOSP can clear owner then throw while reactivating backup. Never try
        // to reinstate Device Owner or its policies after this branch.
        var complete = failure == null
        try {
            if (ports.isSelfAdminActive()) ports.removeSelfAdmin()
        } catch (error: Exception) { failure = error; complete = false }
        // Owner and active-admin are distinct identities. Removal is async on
        // API27: even a successful call may leave the admin active until its
        // ordered disabled broadcast completes. Never wait on the main thread
        // or turn a later non-owner retry into an unverified uninstall promise.
        val adminInactive = try { !ports.isSelfAdminActive() }
                            catch (error: Exception) { failure = error; false }
        if (!adminInactive) complete = false
        try { ports.commit() } catch (error: Exception) { failure = error; complete = false }
        try { ports.finish() } catch (error: Exception) { failure = error; complete = false }
        return Outcome(true, complete, failure)
    }

    /** A persisted pending transaction after process death is also an exit
     * once the OS confirms owner is gone. An old record cannot override a new DO. */
    fun suppressRecovery(record: Record?, isOwner: Boolean): Boolean = record != null && !isOwner
}
