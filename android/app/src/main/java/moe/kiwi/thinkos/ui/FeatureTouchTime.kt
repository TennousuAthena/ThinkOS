package moe.kiwi.thinkos.ui

/** MotionEvent uses uptime; installation policy uses elapsedRealtime. Convert
 * the event's actual release, preserving both queue age and deep-sleep offset. */
internal object FeatureTouchTime {
    fun releaseElapsed(eventUptime: Long, uptimeNow: Long, elapsedNow: Long): Long? {
        if (eventUptime < 0 || uptimeNow < 0 || elapsedNow < 0 ||
            eventUptime > uptimeNow || elapsedNow < uptimeNow) return null
        return elapsedNow - (uptimeNow - eventUptime)
    }
}
