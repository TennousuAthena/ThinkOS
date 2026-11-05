package moe.kiwi.thinkos.core

enum class Mode { DAY, REDSHIFT, SLEEP }

/**
 * Deterministic, process-local mode policy (docs/45 §4). No clock, sensor, IO,
 * Android time-zone rules or persistence is accessed here. MODE-2 supplies
 * settings and adapters backed by Zones.home()/the bundled tzdb and Sun.
 * MODE-3 owns peek/blackout, rendering, audio and wake gesture effects.
 */
class ModeController(
    private val config: () -> Config,
    private val civil: CivilClock,
    private val sun: SolarClock,
    initialMode: Mode = Mode.DAY,
) {
    /** Local dates are epoch-day numbers, not fixed 24-hour UTC intervals.
     * atLocal must resolve DST gaps/folds consistently using the bundled rules;
     * all evaluations of the same boundary must return the same instant. */
    interface CivilClock {
        fun dayAt(wallMillis: Long): Long
        fun atLocal(day: Long, minuteOfDay: Int): Long
    }
    fun interface SolarClock {
        /** null = no configured location. Missing rise/set = polar fallback. */
        fun times(localDay: Long): SunTimes?
    }
    data class SunTimes(val sunriseMillis: Long?, val sunsetMillis: Long?)
    enum class Trigger { SUN, LUX, FIXED, OFF }
    enum class ManualHold { NEXT_BOUNDARY, UNTIL_CHANGED }
    data class LocalWindow(val startMinute: Int, val endMinute: Int) {
        init {
            require(startMinute in 0 until 24 * 60 && endMinute in 0 until 24 * 60)
            require(startMinute != endMinute) { "A mode window needs distinct boundaries" }
        }
    }
    /** Every configurable threshold/duration is injected; no product defaults. */
    data class Config(
        val trigger: Trigger,
        val fixedWindow: LocalWindow,
        val polarWindow: LocalWindow,
        val sleepEnabled: Boolean,
        val sleepWindow: LocalWindow,
        val veryDarkEnterLux: Float,
        val veryDarkExitLux: Float,
        val veryDarkDwellMillis: Long,
        val windowEnterLux: Float,
        val windowExitLux: Float,
        val windowDwellMillis: Long,
        val minimumSwitchMillis: Long,
        val engagedDelayMillis: Long,
        val sleepDelayMillis: Long,
    ) {
        init {
            require(veryDarkEnterLux.isFinite() && veryDarkExitLux.isFinite() && veryDarkEnterLux >= 0 && veryDarkExitLux > veryDarkEnterLux)
            require(windowEnterLux.isFinite() && windowExitLux.isFinite() && windowEnterLux >= 0 && windowExitLux > windowEnterLux)
            require(listOf(veryDarkDwellMillis, windowDwellMillis, minimumSwitchMillis, engagedDelayMillis, sleepDelayMillis).all { it >= 0 })
        }
    }
    sealed interface ManualAction {
        data class Set(val mode: Mode, val hold: ManualHold) : ManualAction
        data object Clear : ManualAction
    }
    data class Input(
        val elapsedMillis: Long,
        /** null means untrusted; time schedules and solar windows are disabled. */
        val wallMillis: Long?,
        /** null, negative or nonfinite means sensor failure, never darkness. */
        val lux: Float?,
        val engaged: Boolean,
        /** A real touch before Attention has caught up also defers switching. */
        val touched: Boolean = false,
        /** One-shot event; do not replay Set on every evaluation. */
        val manual: ManualAction? = null,
    )
    data class ManualOverride(val mode: Mode, val hold: ManualHold, val expiresAtWallMillis: Long?)
    data class Decision(
        val mode: Mode,
        /** Absolute monotonic time; null means wait for an external input. */
        val nextEvaluationElapsedMillis: Long?,
        val manual: ManualOverride?,
        val deferred: Boolean,
        /** Effects must leave SLEEP through minimum-brightness redshift. */
        val exitSleepViaRedshift: Boolean,
        /** MODE-3 must consume this touch; it is not an underlying-page tap. */
        val suppressTouch: Boolean,
    )

    var mode: Mode = initialMode; private set
    private var manualOverride: ManualOverride? = null
    private var lastElapsed: Long? = null
    private var lastConfig: Config? = null
    private var lastAutomaticSwitch: Long? = null
    private var pendingMode: Mode? = null
    private var pendingSince = 0L
    private var pendingSleepStart: Long? = null
    private val veryDark = LuxLatch()
    private val windowDark = LuxLatch()

    fun evaluate(input: Input): Decision {
        require(input.elapsedMillis >= 0 && (lastElapsed == null || input.elapsedMillis >= lastElapsed!!)) {
            "A new process/reboot requires a new mode controller"
        }
        lastElapsed = input.elapsedMillis
        val limits = config()
        val explicitlyDisabled = lastConfig?.trigger?.let { it != Trigger.OFF && limits.trigger == Trigger.OFF } == true
        if (lastConfig != limits) {
            veryDark.reset(); windowDark.reset(); clearPending()
            lastConfig = limits
        }
        val frame = timeFrame(input, limits)
        val lux = input.lux?.takeIf { it.isFinite() && it >= 0 }
        if (limits.trigger == Trigger.OFF || lux == null) veryDark.reset()
        else veryDark.sample(lux, limits.veryDarkEnterLux, limits.veryDarkExitLux, limits.veryDarkDwellMillis, input.elapsedMillis)
        if (!frame.redshiftWindow || lux == null) windowDark.reset()
        else windowDark.sample(lux, limits.windowEnterLux, limits.windowExitLux, limits.windowDwellMillis, input.elapsedMillis)

        when (val action = input.manual) {
            is ManualAction.Set -> manualOverride = ManualOverride(action.mode, action.hold,
                if (action.hold == ManualHold.NEXT_BOUNDARY) frame.manualBoundaries.minOrNull() else null)
            ManualAction.Clear -> manualOverride = null
            null -> Unit
        }
        manualOverride?.takeIf { it.hold == ManualHold.NEXT_BOUNDARY }?.let { held ->
            if (input.wallMillis != null && held.expiresAtWallMillis != null && input.wallMillis >= held.expiresAtWallMillis) {
                manualOverride = null
            } else if (held.expiresAtWallMillis == null) {
                // An untrusted clock/no available event never invents an expiry.
                manualOverride = held.copy(expiresAtWallMillis = frame.manualBoundaries.minOrNull())
            }
        }

        val before = mode
        val target = when {
            frame.sleep != null -> Mode.SLEEP
            veryDark.active || windowDark.active || lux == null && frame.redshiftWindow -> Mode.REDSHIFT
            else -> Mode.DAY
        }
        val deadlines = mutableListOf<Long>()
        veryDark.deadline()?.let(deadlines::add)
        windowDark.deadline()?.let(deadlines::add)
        var deferred = false
        val manual = manualOverride
        if (manual != null) {
            mode = manual.mode
            clearPending()
        } else if (target == mode) clearPending()
        else {
            if (pendingMode != target || target == Mode.SLEEP && pendingSleepStart != frame.sleep?.start) {
                pendingMode = target
                pendingSleepStart = frame.sleep?.start
                // Budget sleep-start deferral from the actual scheduled start,
                // including a first evaluation that arrives several minutes late.
                pendingSince = if (target == Mode.SLEEP) toElapsed(requireNotNull(frame.sleep).start, input) else input.elapsedMillis
            }
            val interactionDeadline = add(pendingSince,
                if (target == Mode.SLEEP) limits.sleepDelayMillis else limits.engagedDelayMillis)
            val intervalDeadline = lastAutomaticSwitch?.let { add(it, limits.minimumSwitchMillis) }
            val engaged = input.engaged || input.touched
            // At its end the sleep schedule must release the device even if a
            // touch/ENGAGED or a recent transition would defer other changes.
            val mustExitSleep = mode == Mode.SLEEP
            // The sleep-start budget is a hard bound even when an injected
            // minimum switch interval is longer than that budget.
            val mustEnterSleep = target == Mode.SLEEP && input.elapsedMillis >= interactionDeadline
            val explicitOff = explicitlyDisabled && mode == Mode.REDSHIFT && target != Mode.SLEEP
            if (mustExitSleep || mustEnterSleep || explicitOff ||
                (intervalDeadline == null || input.elapsedMillis >= intervalDeadline) && (!engaged || input.elapsedMillis >= interactionDeadline)) {
                mode = target
                lastAutomaticSwitch = input.elapsedMillis
                clearPending()
            } else {
                deferred = true
                intervalDeadline?.let(deadlines::add)
                if (engaged || target == Mode.SLEEP) deadlines += interactionDeadline
            }
        }
        frame.nextBoundaries.forEach { deadlines += toElapsed(it, input) }
        manualOverride?.expiresAtWallMillis?.takeIf { input.wallMillis != null }?.let { deadlines += toElapsed(it, input) }
        return Decision(mode, deadlines.filter { it > input.elapsedMillis }.minOrNull(), manualOverride, deferred,
            before == Mode.SLEEP && mode != Mode.SLEEP, input.touched && before == Mode.SLEEP)
    }

    private data class Period(val start: Long, val end: Long) {
        fun contains(now: Long) = now >= start && now < end
    }
    private data class SolarEvent(val at: Long, val sunset: Boolean)
    private data class Frame(val sleep: Period?, val redshiftWindow: Boolean,
                             val nextBoundaries: List<Long>, val manualBoundaries: List<Long>)

    private fun timeFrame(input: Input, limits: Config): Frame {
        val now = input.wallMillis ?: return Frame(null, false, emptyList(), emptyList())
        val day = civil.dayAt(now)
        val future = mutableListOf<Long>()
        val manual = mutableListOf<Long>()
        val sleeps = if (limits.sleepEnabled) periods(limits.sleepWindow, day) else emptyList()
        for (period in sleeps) {
            future += period.start; future += period.end
            // Sleep START is deliberately not an expiry of a manual override.
            manual += period.end
        }
        val solar = (-1L..2L).associate { offset -> day + offset to sun.times(day + offset) }
        val events = solar.values.flatMap { times -> listOfNotNull(
            times?.sunriseMillis?.let { SolarEvent(it, false) }, times?.sunsetMillis?.let { SolarEvent(it, true) }) }
            .sortedWith(compareBy<SolarEvent> { it.at }.thenBy { it.sunset })
        events.forEach { future += it.at; manual += it.at }
        val redshift = when (limits.trigger) {
            Trigger.OFF, Trigger.LUX -> false
            Trigger.FIXED -> periods(limits.fixedWindow, day).also { windows ->
                windows.forEach { future += it.start; future += it.end }
            }.any { it.contains(now) }
            Trigger.SUN -> {
                val today = solar[day]
                when {
                    today == null -> false // no location: lux-only, not a guessed solar window
                    today.sunriseMillis == null || today.sunsetMillis == null -> periods(limits.polarWindow, day).also { windows ->
                        windows.forEach { future += it.start; future += it.end }
                    }.any { it.contains(now) }
                    else -> events.lastOrNull { it.at <= now }?.sunset ?: (events.firstOrNull { it.at > now }?.sunset == false)
                }
            }
        }
        // Re-query daily solar/polar data after a civil midnight, whose epoch
        // distance may be 23/25 hours. Never manufacture a fixed 24h deadline.
        future += civil.atLocal(day + 1, 0)
        return Frame(sleeps.firstOrNull { it.contains(now) }, redshift,
            future.filter { it > now }.distinct(), manual.filter { it > now }.distinct())
    }

    private fun periods(window: LocalWindow, day: Long): List<Period> = (-1L..1L).mapNotNull { offset ->
        val startDay = day + offset
        val start = civil.atLocal(startDay, window.startMinute)
        val end = civil.atLocal(startDay + if (window.endMinute < window.startMinute) 1 else 0, window.endMinute)
        // A skipped civil date can collapse an interval; it is not an all-day window.
        if (end > start) Period(start, end) else null
    }

    private fun clearPending() { pendingMode = null; pendingSleepStart = null }

    private class LuxLatch {
        var active = false; private set
        private var candidate: Boolean? = null
        private var since = 0L
        private var dwell = 0L
        fun reset() { active = false; candidate = null }
        fun sample(lux: Float, enter: Float, exit: Float, duration: Long, now: Long) {
            val next = if (active) lux <= exit else lux < enter
            if (next == active) { candidate = null; return }
            if (candidate != next) { candidate = next; since = now }
            dwell = duration
            if (now >= add(since, dwell)) { active = next; candidate = null }
        }
        fun deadline(): Long? = candidate?.let { add(since, dwell) }
    }

    companion object {
        private fun add(a: Long, b: Long): Long = try { Math.addExact(a, b) }
            catch (_: ArithmeticException) { if (b >= 0) Long.MAX_VALUE else Long.MIN_VALUE }
        private fun toElapsed(wall: Long, input: Input): Long {
            val now = input.wallMillis ?: return Long.MAX_VALUE
            val delta = try { Math.subtractExact(wall, now) }
                catch (_: ArithmeticException) { if (wall >= now) Long.MAX_VALUE else Long.MIN_VALUE }
            return add(input.elapsedMillis, delta)
        }
    }
}
