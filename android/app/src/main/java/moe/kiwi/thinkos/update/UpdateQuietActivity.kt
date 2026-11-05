package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.core.Attention

/** Process-local monotonic observations. A restart cannot invent a long idle
 * period or reuse an old asleep interval. Wall time is never an elapsed clock. */
internal class UpdateQuietActivity(started: Long) {
    private var lastTouch = started
    private var asleepSince: Long? = null
    private var previous = Attention.AWAKE
    init { require(started >= 0) }

    fun observe(attention: Attention, touch: Long, now: Long) {
        require(now >= 0)
        if (touch in lastTouch..now) lastTouch = touch
        if (attention != Attention.ASLEEP) asleepSince = null
        else if (previous != Attention.ASLEEP || asleepSince == null || asleepSince!! > now) asleepSince = now
        previous = attention
    }
    fun sinceTouch(now: Long): Long = (now - lastTouch).coerceAtLeast(0)
    fun asleep(now: Long): Long = asleepSince?.let { (now - it).coerceAtLeast(0) } ?: 0
}

internal data class UpdateAutomaticPolicy(val enabled: Boolean, val quiet: UpdateSchedule.QuietPolicy, val pollMillis: Long) {
    init { require(pollMillis in 1000..3_600_000) }
}

internal data class UpdateScreens(val settings: Boolean = false, val onboarding: Boolean = false)
