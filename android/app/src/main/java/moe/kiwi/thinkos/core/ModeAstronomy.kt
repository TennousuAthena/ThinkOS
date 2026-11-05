package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.astro.Place
import moe.kiwi.thinkos.astro.PolarState
import moe.kiwi.thinkos.astro.Solar
import moe.kiwi.thinkos.tz.ZoneClock
import moe.kiwi.thinkos.tz.Zones

/** Cached adapters to ThinkOS's bundled TZif rules and the calendar's exact Sun calculation. */
internal class ModeAstronomy(
    private val home: () -> ZoneClock,
    private val location: () -> Place?,
    private val database: () -> Any? = { Zones.database },
    private val calculate: (Long, ZoneClock, Place) -> SolarDay = { noon, zone, place ->
        val day = Solar.day(noon, zone, place)
        SolarDay(ModeController.SunTimes(day.sunrise, day.sunset), when (day.polar) {
            PolarState.MIDNIGHT_SUN -> true
            PolarState.POLAR_NIGHT -> false
            else -> null
        })
    },
) : ModeController.CivilClock, ModeController.SolarClock {
    internal data class SolarDay(val events: ModeController.SunTimes, val polarDay: Boolean? = null)
    private var zone: ZoneClock? = null
    private var zoneId: String? = null
    private var zoneDatabase: Any? = null
    private var here: Place? = null
    private var dirty = true
    private var trusted: Boolean? = null
    private val boundaries = object : LinkedHashMap<Pair<Long, Int>, Long>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<Long, Int>, Long>) = size > 128
    }
    private val days = object : LinkedHashMap<Long, SolarDay>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SolarDay>) = size > 8
    }

    fun invalidate() { dirty = true }

    /** Return a trusted wall time only when civil conversions use our bundled data. */
    fun prepare(wallMillis: Long): Long? {
        val next = home()
        val nextDatabase = database()
        val nextTrusted = wallMillis >= TRUSTED_AFTER && next.bundled
        if (dirty || zoneId != next.id || zoneDatabase !== nextDatabase || trusted != nextTrusted) {
            zone = next.takeIf { it.bundled }
            zoneId = next.id
            zoneDatabase = nextDatabase
            here = if (nextTrusted) location() else null
            boundaries.clear(); days.clear()
            trusted = nextTrusted
            dirty = false
        }
        return wallMillis.takeIf { nextTrusted }
    }

    override fun dayAt(wallMillis: Long): Long = requireNotNull(zone).fields(wallMillis).days

    override fun atLocal(day: Long, minuteOfDay: Int): Long {
        require(minuteOfDay in 0 until 24 * 60)
        return boundaries.getOrPut(day to minuteOfDay) {
            val clock = requireNotNull(zone)
            val nominal = day * DAY_MILLIS + minuteOfDay * 60_000L
            // Offsets on either side of a civil transition, from our own TZif.
            // First occurrence for folds; preserve minutes and shift forwards for gaps.
            val candidates = (-2L..2L).map { clock.offsetMillis(nominal + it * DAY_MILLIS) }
                .distinct().map { nominal - it }
            fun local(at: Long) = at + clock.offsetMillis(at)
            candidates.filter { local(it) == nominal }.minOrNull()
                ?: candidates.filter { local(it) > nominal }.minOrNull()
                ?: error("Cannot resolve bundled civil time")
        }
    }

    override fun times(localDay: Long): ModeController.SunTimes? = solar(localDay)?.events

    private fun solar(day: Long): SolarDay? {
        val place = here ?: return null
        val clock = zone ?: return null
        return days.getOrPut(day) { calculate(atLocal(day, 12 * 60), clock, place) }
    }

    /** LIGHT/DARK by the same cached solar cycle, including polar day/night. */
    fun daylight(wallMillis: Long): Boolean? {
        if (trusted != true) return null
        val day = dayAt(wallMillis)
        val today = solar(day) ?: return null
        today.polarDay?.let { return it }
        val events = (-1L..2L).flatMap { offset ->
            solar(day + offset)?.events?.let { listOfNotNull(
                it.sunriseMillis?.let { at -> at to true },
                it.sunsetMillis?.let { at -> at to false },
            ) }.orEmpty()
        }.sortedBy { it.first }
        return events.lastOrNull { it.first <= wallMillis }?.second
            ?: events.firstOrNull { it.first > wallMillis }?.let { !it.second }
    }

    fun inWindow(wallMillis: Long, window: ModeController.LocalWindow): Boolean {
        if (zone == null) return false
        val day = dayAt(wallMillis)
        return (-1L..1L).any { offset ->
            val startDay = day + offset
            val start = atLocal(startDay, window.startMinute)
            val end = atLocal(startDay + if (window.endMinute < window.startMinute) 1 else 0, window.endMinute)
            wallMillis >= start && wallMillis < end
        }
    }

    companion object {
        // Existing UI/time-sync trust epoch: this device has no RTC.
        const val TRUSTED_AFTER = 1_577_836_800_000L
        private const val DAY_MILLIS = 86_400_000L
    }
}
