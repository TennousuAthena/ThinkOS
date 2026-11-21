package moe.kiwi.thinkos.core

/** Flow versioning is independent of APK releases; a patch never reopens completed setup. */
object OnboardingPolicy {
    const val VERSION = 1
    fun adoptExisting(hadLocalSettings: Boolean, hasCompletionValue: Boolean, initialized: Boolean): Boolean =
        !initialized && hadLocalSettings && !hasCompletionValue
    /** [maxPrompts] 0 (the default since 2026-11-21) means no limit: unfinished setup returns on every start. */
    fun prompt(done: Int, attempts: Int, maxPrompts: Int, safeMode: Boolean): Boolean =
        !safeMode && done < VERSION && (maxPrompts <= 0 || attempts < maxPrompts)

    /** Unfinished first-run setup on screen: Home, the bottom-edge swipe and Back stay on it (owner decision 2026-11-21). */
    fun holdsScreen(open: Boolean, done: Int): Boolean = open && done < VERSION

    /** Home from an Android screen that setup itself opened (Wi-Fi, date) means "back to setup"; any other Home leaves it. */
    fun homeKeepsSetup(openedFromSetup: Boolean, setupOpen: Boolean): Boolean = openedFromSetup && setupOpen

    /** First-run setup keeps the day look: a scheduled redshift or sleep must not dim it. A mode the person chose stays. */
    fun displayMode(decided: Mode, chosenByPerson: Boolean, onboardingOpen: Boolean): Mode =
        if (onboardingOpen && !chosenByPerson) Mode.DAY else decided

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
