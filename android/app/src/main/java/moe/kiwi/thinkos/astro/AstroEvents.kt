package moe.kiwi.thinkos.astro

import moe.kiwi.thinkos.tz.ZoneClock

/** Half-open UTC interval for one local calendar date; its duration can be 23 or 25 hours. */
internal data class DayWindow(val start: Long, val end: Long)
internal data class Crossing(val time: Long, val rising: Boolean)

/** Numerical event searches shared by the solar and lunar ephemerides. No Android or ROM tzdata. */
internal object AstroEvents {
    fun dayWindow(epochMillis: Long, zone: ZoneClock): DayWindow {
        val day = zone.fields(epochMillis).days
        fun boundary(target: Long): Long {
            var low = epochMillis - 3 * Astro.DAY_MILLIS
            var high = epochMillis + 3 * Astro.DAY_MILLIS
            while (high - low > 1) {
                val mid = low + (high - low) / 2
                if (zone.fields(mid).days < target) low = mid else high = mid
            }
            return high
        }
        return DayWindow(boundary(day), boundary(day + 1))
    }

    /**
     * Scan a smooth signed altitude function and refine each crossing to under one second.
     * Around each sampled extremum, also find the actual extremum. A grazing rise/set pair can
     * otherwise fit between two ten-minute samples, especially around the polar circles.
     */
    fun crossings(start: Long, end: Long, altitude: (Long) -> Double): List<Crossing> {
        require(end >= start)
        if (start == end) return emptyList()
        val samples = ArrayList<Pair<Long, Double>>()
        var t = start
        while (t < end) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException("astronomy task cancelled")
            samples += t to altitude(t)
            t = minOf(t + 600_000, end)
        }
        samples += end to altitude(end)
        val extrema = ArrayList<Pair<Long, Double>>()
        // Guard samples let us detect a short pair even within the first/last scan interval.
        val guarded = listOf(start - 600_000 to altitude(start - 600_000)) + samples +
            listOf(end + 600_000 to altitude(end + 600_000))
        for (i in 1 until guarded.lastIndex) {
            val before = guarded[i - 1]
            val current = guarded[i]
            val after = guarded[i + 1]
            val maximum = current.second > before.second && current.second >= after.second
            val minimum = current.second < before.second && current.second <= after.second
            if (!maximum && !minimum) continue
            var low = before.first
            var high = after.first
            while (high - low > 250) {
                val a = low + (high - low) / 3
                val b = high - (high - low) / 3
                if ((altitude(a) < altitude(b)) == maximum) low = a else high = b
            }
            val extremum = low + (high - low) / 2
            if (extremum in start..end) extrema += extremum to altitude(extremum)
        }
        val points = (samples + extrema).sortedBy { it.first }
        val found = ArrayList<Crossing>()
        for (i in 0 until points.lastIndex) {
            val (a, fa) = points[i]
            val (b, fb) = points[i + 1]
            if ((fa < 0 && fb > 0) || (fa > 0 && fb < 0)) {
                val rising = fb > fa
                var low = a
                var high = b
                while (high - low > 250) {
                    val mid = low + (high - low) / 2
                    if ((altitude(mid) > 0) == rising) high = mid else low = mid
                }
                val time = low + (high - low) / 2
                if (time < end) found += Crossing(time, rising)
            } else if (fa == 0.0 && a < end) {
                val before = altitude(a - 1)
                val after = altitude(a + 1)
                if (before < 0 && after > 0) found += Crossing(a, true)
                if (before > 0 && after < 0) found += Crossing(a, false)
            }
        }
        return found.distinctBy { it.time }
    }

    /** Integrate the positive part of a signed function between its crossings. */
    fun positiveDuration(start: Long, end: Long, crossings: List<Crossing>, value: (Long) -> Double): Long {
        var positive = value(start) > 0
        var previous = start
        var duration = 0L
        for (crossing in crossings) {
            if (positive) duration += crossing.time - previous
            previous = crossing.time
            positive = crossing.rising
        }
        if (positive) duration += end - previous
        return duration
    }
}
