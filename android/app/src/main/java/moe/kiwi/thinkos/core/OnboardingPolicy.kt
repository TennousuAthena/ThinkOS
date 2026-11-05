package moe.kiwi.thinkos.core

/** Flow versioning is independent of APK releases; a patch never reopens completed setup. */
object OnboardingPolicy {
    const val VERSION = 1
    fun adoptExisting(hadLocalSettings: Boolean, hasCompletionValue: Boolean, initialized: Boolean): Boolean =
        !initialized && hadLocalSettings && !hasCompletionValue
    fun prompt(done: Int, attempts: Int, maxPrompts: Int, safeMode: Boolean): Boolean =
        !safeMode && done < VERSION && attempts < maxPrompts

    /** A physical gesture can complete only the visible owner's currently armed lesson. */
    fun practiceMatches(open: Boolean, onboardingRole: Boolean, currentOwner: String?,
                        practiceOwner: String?, practiceName: String?, requested: String): Boolean =
        open && onboardingRole && currentOwner != null && currentOwner == practiceOwner && practiceName == requested
}

/** Process-local admission: an explicit Home wins over a catalog that is still loading. */
internal class OnboardingEntryPolicy {
    private var pending = true

    fun homeRequested() { pending = false }

    fun claimInitialCatalog(): Boolean {
        if (!pending) return false
        pending = false
        return true
    }
}
