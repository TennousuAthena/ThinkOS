package moe.kiwi.thinkos.location

import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.astro.Place
import moe.kiwi.thinkos.astro.Sun

/**
 * Is the Sun's upper limb above the horizon at the configured place? That is the sunrise-and-sunset theme
 * mode. It uses the same horizon as the almanac, so the theme flips at the sunrise and sunset the clock shows.
 * Null while the clock is untrusted (the device has no RTC), so the caller can fall back to the ambient light.
 * Main-thread use only; one Sun evaluation per [REFRESH_MILLIS] or when the place changes.
 */
class DaylightProbe(
    private val place: () -> Place?,
    private val clock: () -> Long = WallClock::now,
) {
    private var checkedAt = 0L
    private var checkedPlace: Place? = null
    private var day: Boolean? = null

    fun isDay(): Boolean? {
        val now = clock()
        if (now < TRUSTED_AFTER) return null              // 2020-01-01, the same untrusted-time rule as the clock
        val here = place() ?: return null
        if (here != checkedPlace || now < checkedAt || now - checkedAt >= REFRESH_MILLIS) {
            day = Sun.altitude(now, here) > Sun.horizonAltitude(now, here)
            checkedPlace = here
            checkedAt = now
        }
        return day
    }

    private companion object {
        const val TRUSTED_AFTER = 1_577_836_800_000L
        const val REFRESH_MILLIS = 30_000L
    }
}
