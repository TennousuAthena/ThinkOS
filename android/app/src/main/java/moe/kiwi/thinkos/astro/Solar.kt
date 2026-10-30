package moe.kiwi.thinkos.astro

import moe.kiwi.thinkos.tz.ZoneClock

enum class PolarState { NORMAL, MIDNIGHT_SUN, POLAR_NIGHT, TRANSITION }
data class Twilight(val dawn: Long?, val dusk: Long?)
data class AstroInterval(val start: Long, val end: Long)

/**
 * Events for the solar cycle whose upper transit falls on the requested local date. Sunrise may
 * precede local midnight and sunset may follow it when civil and solar time are far apart.
 * Durations describe the 24-hour solar window (noon ±12 h), not a civil DST day's length.
 */
data class SunDay(
    val dateStart: Long,
    val dateEnd: Long,
    val noon: Long,
    val sunrise: Long?,
    val sunset: Long?,
    val dayLengthMillis: Long,
    val dayLengthChangeMillis: Long,
    val maximumAltitude: Double,
    val polar: PolarState,
    val civil: Twilight,
    val nautical: Twilight,
    val astronomical: Twilight,
    /** Intervals clipped to noon ±12 h; polar bands can contain zero, one or two intervals. */
    val golden: List<AstroInterval>,
    val blue: List<AstroInterval>,
)

/** Solar almanac calculated entirely on device, with the bundled IANA time zone rules. */
object Solar {
    fun position(epochMillis: Long, place: Place): Horizontal = Sun.position(epochMillis, place)

    fun day(epochMillis: Long, zone: ZoneClock, place: Place): SunDay {
        val window = AstroEvents.dayWindow(epochMillis, zone)
        val noon = transit(window, place)
        val today = cycle(noon, place)
        val yesterday = cycle(transit(AstroEvents.dayWindow(window.start - 1, zone), place), place)
        fun twilight(altitude: Double): Twilight {
            val events = crossings(noon, place) { altitude }
            return Twilight(events.firstOrNull { it.rising }?.time, events.lastOrNull { !it.rising }?.time)
        }
        return SunDay(window.start, window.end, noon, today.rise, today.set, today.duration,
            today.duration - yesterday.duration, maximumAltitude(noon, place), today.polar,
            twilight(-6.0), twilight(-12.0), twilight(-18.0),
            band(noon, place, -4.0, 6.0), band(noon, place, -8.0, -4.0))
    }

    private data class Cycle(val rise: Long?, val set: Long?, val duration: Long, val polar: PolarState)

    private fun cycle(noon: Long, place: Place): Cycle {
        val start = noon - Astro.DAY_MILLIS / 2
        val end = noon + Astro.DAY_MILLIS / 2
        val value = { t: Long -> Sun.altitude(t, place) - Sun.horizonAltitude(t, place) }
        val events = crossings(noon, place) { Sun.horizonAltitude(it, place) }
        val duration = AstroEvents.positiveDuration(start, end, events, value)
        val polar = when {
            events.size >= 2 -> PolarState.NORMAL
            events.isNotEmpty() -> PolarState.TRANSITION
            value(noon) > 0 -> PolarState.MIDNIGHT_SUN
            else -> PolarState.POLAR_NIGHT
        }
        return Cycle(events.firstOrNull { it.rising }?.time, events.lastOrNull { !it.rising }?.time, duration, polar)
    }

    private fun crossings(noon: Long, place: Place, threshold: (Long) -> Double): List<Crossing> {
        val value = { t: Long -> Sun.altitude(t, place) - threshold(t) }
        // Split at transit so even a daylight interval shorter than a scan step has a bracket.
        return AstroEvents.crossings(noon - Astro.DAY_MILLIS / 2, noon, value) +
            AstroEvents.crossings(noon, noon + Astro.DAY_MILLIS / 2, value)
    }

    private fun band(noon: Long, place: Place, low: Double, high: Double): List<AstroInterval> {
        val start = noon - Astro.DAY_MILLIS / 2
        val end = noon + Astro.DAY_MILLIS / 2
        val boundaries = (listOf(start, end) + crossings(noon, place) { low }.map { it.time } +
            crossings(noon, place) { high }.map { it.time }).distinct().sorted()
        return boundaries.zipWithNext().mapNotNull { (a, b) ->
            if (Sun.altitude(a + (b - a) / 2, place) in low..high) AstroInterval(a, b) else null
        }
    }

    /** Find the negative-to-positive hour-angle crossing; reject the ±180° wrap at midnight. */
    private fun transit(window: DayWindow, place: Place): Long {
        var a = window.start
        var ha = Sun.hourAngle(a, place)
        var best: Long? = null
        val middle = window.start + (window.end - window.start) / 2
        while (a < window.end) {
            val b = minOf(a + 3_600_000, window.end)
            val hb = Sun.hourAngle(b, place)
            if (ha <= 0 && hb >= 0 && hb - ha < 180) {
                var low = a
                var high = b
                while (high - low > 250) {
                    val mid = low + (high - low) / 2
                    if (Sun.hourAngle(mid, place) >= 0) high = mid else low = mid
                }
                val t = low + (high - low) / 2
                if (best == null || kotlin.math.abs(t - middle) < kotlin.math.abs(best - middle)) best = t
            }
            a = b
            ha = hb
        }
        // Extremely mismatched civil/solar zones can put a transit just outside a short DST date.
        if (best != null) return best
        val approximate = middle - (Sun.hourAngle(middle, place) / 360.0 * Astro.DAY_MILLIS).toLong()
        return approximate - (Sun.hourAngle(approximate, place) / 360.0 * Astro.DAY_MILLIS).toLong()
    }

    private fun maximumAltitude(noon: Long, place: Place): Double {
        var low = noon - 3_600_000
        var high = noon + 3_600_000
        repeat(28) {
            val a = low + (high - low) / 3
            val b = high - (high - low) / 3
            if (Sun.altitude(a, place) < Sun.altitude(b, place)) low = a else high = b
        }
        return Sun.altitude(low + (high - low) / 2, place)
    }
}
