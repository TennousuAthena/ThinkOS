package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.core.Attention

/** Pure eligibility rules (docs/34 §3.4/4.4). The runtime settings adapter must
 * supply all policy values from the settings schema; no timers or defaults here. */
internal object UpdateSchedule {
    data class QuietPolicy(
        val startHour: Int,
        val hours: Int,
        val touchGraceMillis: Long,
        val fallbackWaitMillis: Long,
        val fallbackAsleepMillis: Long,
        val spaceFactor: Int,
    ) {
        init {
            require(startHour in 0..23 && hours in 1..24)
            require(touchGraceMillis >= 0 && fallbackWaitMillis >= 0 && fallbackAsleepMillis >= 0)
            require(spaceFactor >= 1)
        }
    }

    /** Durations are supplied by a monotonic/persisted-state adapter. This
     * policy neither reads wall time nor treats a clock change as elapsed time. */
    data class InstallState(
        val autoInstall: Boolean,
        val attention: Attention,
        val timeTrusted: Boolean,
        val localHour: Int?,
        val sinceTouchMillis: Long,
        val waitingReadyMillis: Long,
        val asleepMillis: Long,
        val mediaSession: Boolean,
        val casting: Boolean,
        val settingsOpen: Boolean,
        val onboardingOpen: Boolean,
        val foreignApp: Boolean,
        val freeBytes: Long,
        val apkBytes: Long,
    )

    /** A manual download bypasses attention, never active video or casting.
     * Caller applies update.enabled and validates fresh manual authorization. */
    fun downloadAllowed(attention: Attention, manual: Boolean, videoSession: Boolean, casting: Boolean): Boolean =
        !videoSession && !casting && (manual || attention == Attention.ASLEEP || attention == Attention.AMBIENT)

    /** Automatic installs only. A manual install has its own user-action path;
     * it must not be blocked by the settings screen containing its button. */
    fun automaticInstallAllowed(policy: QuietPolicy, state: InstallState): Boolean {
        if (!state.autoInstall || state.attention != Attention.ASLEEP || state.mediaSession || state.casting ||
            state.settingsOpen || state.onboardingOpen || state.foreignApp) return false
        if (state.sinceTouchMillis < policy.touchGraceMillis || state.waitingReadyMillis < 0 || state.asleepMillis < 0) return false
        // Division avoids overflowing for malicious/invalid APK sizes.
        if (state.apkBytes <= 0 || state.freeBytes < 0 || state.freeBytes / state.apkBytes < policy.spaceFactor) return false
        if (!state.timeTrusted) return true
        val hour = state.localHour?.takeIf { it in 0..23 } ?: return false
        val inWindow = (hour - policy.startHour + 24) % 24 < policy.hours
        return inWindow || state.waitingReadyMillis >= policy.fallbackWaitMillis && state.asleepMillis >= policy.fallbackAsleepMillis
    }
}
