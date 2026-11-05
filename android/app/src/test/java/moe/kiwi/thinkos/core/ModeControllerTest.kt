package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.core.ModeController.CivilClock
import moe.kiwi.thinkos.core.ModeController.Config
import moe.kiwi.thinkos.core.ModeController.Input
import moe.kiwi.thinkos.core.ModeController.LocalWindow
import moe.kiwi.thinkos.core.ModeController.ManualAction
import moe.kiwi.thinkos.core.ModeController.ManualHold
import moe.kiwi.thinkos.core.ModeController.SolarClock
import moe.kiwi.thinkos.core.ModeController.SunTimes
import moe.kiwi.thinkos.core.ModeController.Trigger
import moe.kiwi.thinkos.tz.Civil
import moe.kiwi.thinkos.tz.FileTzSource
import moe.kiwi.thinkos.tz.TzDatabase
import moe.kiwi.thinkos.tz.ZoneClock
import moe.kiwi.thinkos.tz.Zones
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class ModeControllerTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val dayMillis = 24 * hour
    private fun day(year: Int, month: Int, date: Int) = Civil.daysFromCivil(year, month, date)
    private fun wall(day: Long, hour: Int, minute: Int = 0) = day * dayMillis + hour * this.hour + minute * this.minute
    private val utc = object : CivilClock {
        override fun dayAt(wallMillis: Long) = Math.floorDiv(wallMillis, dayMillis)
        override fun atLocal(day: Long, minuteOfDay: Int) = day * dayMillis + minuteOfDay * minute
    }
    private fun normalSun(civil: CivilClock = utc) = SolarClock { date ->
        SunTimes(civil.atLocal(date, 6 * 60), civil.atLocal(date, 18 * 60))
    }
    // Explicit fixture settings; production code has no hard-coded product defaults.
    private fun limits(trigger: Trigger = Trigger.LUX, sleep: Boolean = false) = Config(
        trigger, LocalWindow(19 * 60, 7 * 60), LocalWindow(19 * 60, 7 * 60), sleep, LocalWindow(23 * 60, 7 * 60),
        5f, 15f, 10_000, 50f, 120f, 30_000, 120_000, 600_000, 600_000)
    private fun policy(config: Config = limits(), civil: CivilClock = utc, sun: SolarClock = normalSun(civil)) =
        ModeController({ config }, civil, sun)

    @Test fun continuousLuxDwellAndBothHysteresisEdgesAreTableDriven() {
        data class Step(val at: Long, val lux: Float, val mode: Mode, val next: Long?)
        val controller = policy(limits().copy(minimumSwitchMillis = 0))
        val steps = listOf(
            Step(0, 5f, Mode.DAY, null), // equal-to-enter is not below
            Step(1_000, 4f, Mode.DAY, 11_000),
            Step(5_000, 6f, Mode.DAY, null), // flicker resets continuous dwell
            Step(10_000, 4f, Mode.DAY, 20_000),
            Step(19_999, 4f, Mode.DAY, 20_000),
            Step(20_000, 4f, Mode.REDSHIFT, null),
            Step(21_000, 15f, Mode.REDSHIFT, null), // equal-to-exit stays latched
            Step(22_000, 16f, Mode.REDSHIFT, 32_000),
            Step(25_000, 10f, Mode.REDSHIFT, null),
            Step(30_000, 16f, Mode.REDSHIFT, 40_000),
            Step(40_000, 16f, Mode.DAY, null))
        for (step in steps) {
            val result = controller.evaluate(Input(step.at, null, step.lux, false))
            assertEquals(step.toString(), step.mode, result.mode)
            assertEquals(step.toString(), step.next, result.nextEvaluationElapsedMillis)
        }
    }

    @Test fun automaticModeChangesRespectTheInjectedMinimumInterval() {
        val controller = policy(limits().copy(veryDarkDwellMillis = 0))
        assertEquals(Mode.REDSHIFT, controller.evaluate(Input(0, null, 0f, false)).mode)
        val waiting = controller.evaluate(Input(1, null, 100f, false))
        assertEquals(Mode.REDSHIFT, waiting.mode)
        assertTrue(waiting.deferred)
        assertEquals(120_000L, waiting.nextEvaluationElapsedMillis)
        assertEquals(Mode.REDSHIFT, controller.evaluate(Input(119_999, null, 100f, false)).mode)
        assertEquals(Mode.DAY, controller.evaluate(Input(120_000, null, 100f, false)).mode)
    }

    @Test fun sunsetWindowUsesItsOwnDwellAndSunriseClosesOnlyTheWindowCause() {
        val date = day(2026, 11, 5)
        val start = wall(date, 17, 59)
        val controller = policy(limits(Trigger.SUN).copy(minimumSwitchMillis = 0))
        data class Step(val wall: Long, val lux: Float, val mode: Mode)
        for (step in listOf(
            Step(start, 20f, Mode.DAY),
            Step(wall(date, 18), 20f, Mode.DAY),
            Step(wall(date, 18) + 29_999, 20f, Mode.DAY),
            Step(wall(date, 18) + 30_000, 20f, Mode.REDSHIFT),
            Step(wall(date, 18, 1), 130f, Mode.REDSHIFT),
            Step(wall(date, 18, 1) + 30_000, 130f, Mode.DAY),
            Step(wall(date + 1, 5, 59), 20f, Mode.DAY),
            Step(wall(date + 1, 5, 59) + 30_000, 20f, Mode.REDSHIFT),
            Step(wall(date + 1, 6), 20f, Mode.DAY))) {
            assertEquals(step.toString(), step.mode, controller.evaluate(Input(step.wall - start, step.wall, step.lux, false)).mode)
        }
    }

    @Test fun fixedWindowsAndSleepUseHalfOpenIntervalsAcrossMidnight() {
        val date = day(2026, 11, 5)
        val start = wall(date, 22, 59)
        val controller = policy(limits(Trigger.FIXED, sleep = true).copy(minimumSwitchMillis = 0))
        val steps = listOf(start to Mode.REDSHIFT, wall(date, 23) to Mode.SLEEP,
            wall(date + 1, 0) to Mode.SLEEP, wall(date + 1, 6, 59) to Mode.SLEEP, wall(date + 1, 7) to Mode.DAY)
        for ((at, expected) in steps) {
            val decision = controller.evaluate(Input(at - start, at, null, false))
            assertEquals(at.toString(), expected, decision.mode)
            if (at == wall(date + 1, 7)) assertTrue(decision.exitSleepViaRedshift)
        }
    }

    @Test fun untrustedTimeCannotConsultSolarOrAutomaticallySleepButStillUsesLux() {
        val controller = policy(limits(Trigger.SUN, sleep = true).copy(minimumSwitchMillis = 0),
            sun = SolarClock { error("No solar/time schedule access with an untrusted clock") })
        assertEquals(Mode.DAY, controller.evaluate(Input(0, null, 100f, false)).mode)
        assertEquals(Mode.DAY, controller.evaluate(Input(1, null, 0f, false)).mode)
        assertEquals(Mode.REDSHIFT, controller.evaluate(Input(10_001, null, 0f, false)).mode)
        assertEquals(Mode.DAY, controller.evaluate(Input(10_002, null, null, false)).mode)
        assertEquals(Mode.SLEEP, controller.evaluate(Input(10_003, null, null, false,
            manual = ManualAction.Set(Mode.SLEEP, ManualHold.UNTIL_CHANGED))).mode)
    }

    @Test fun absentLocationFallsBackToLuxRatherThanPretendingItIsPolarNight() {
        val start = wall(day(2026, 11, 5), 20)
        val controller = policy(limits(Trigger.SUN).copy(minimumSwitchMillis = 0), sun = SolarClock { null })
        assertEquals(Mode.DAY, controller.evaluate(Input(0, start, 20f, false)).mode)
        assertEquals(Mode.DAY, controller.evaluate(Input(1, start + 1, null, false)).mode)
        controller.evaluate(Input(2, start + 2, 0f, false))
        assertEquals(Mode.REDSHIFT, controller.evaluate(Input(10_002, start + 10_002, 0f, false)).mode)
    }

    @Test fun polarAndFailedSensorCasesUseOnlyTheConfiguredValidWindow() {
        data class Case(val trigger: Trigger, val hour: Int, val times: SunTimes?, val expected: Mode)
        val date = day(2026, 11, 5)
        for (case in listOf(
            Case(Trigger.SUN, 20, SunTimes(null, null), Mode.REDSHIFT),
            Case(Trigger.SUN, 12, SunTimes(null, null), Mode.DAY),
            Case(Trigger.SUN, 20, SunTimes(wall(date, 6), null), Mode.REDSHIFT),
            Case(Trigger.SUN, 20, null, Mode.DAY),
            Case(Trigger.LUX, 20, SunTimes(null, null), Mode.DAY),
            Case(Trigger.OFF, 20, SunTimes(null, null), Mode.DAY),
            Case(Trigger.FIXED, 20, null, Mode.REDSHIFT),
            Case(Trigger.FIXED, 12, null, Mode.DAY))) {
            val controller = policy(limits(case.trigger), sun = SolarClock { case.times })
            assertEquals(case.toString(), case.expected, controller.evaluate(Input(0, wall(date, case.hour), null, false)).mode)
        }
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, -1f)) {
            val controller = policy(limits(Trigger.FIXED))
            assertEquals(Mode.REDSHIFT, controller.evaluate(Input(0, wall(date, 20), bad, false)).mode)
        }
    }

    @Test fun manualNextBoundaryHasPriorityOverSleepStartAndExpiresAtTheNearestRealEvent() {
        val date = day(2026, 11, 5)
        val start = wall(date, 22)
        val controller = policy(limits(Trigger.SUN, sleep = true).copy(minimumSwitchMillis = 0))
        val first = controller.evaluate(Input(0, start, 100f, true,
            manual = ManualAction.Set(Mode.DAY, ManualHold.NEXT_BOUNDARY)))
        assertEquals(Mode.DAY, first.mode)
        assertEquals(wall(date + 1, 6), first.manual?.expiresAtWallMillis)
        assertEquals(Mode.DAY, controller.evaluate(Input(hour, wall(date, 23), 100f, false)).mode)
        val expired = controller.evaluate(Input(8 * hour, wall(date + 1, 6), 100f, false))
        assertNull(expired.manual)
        assertEquals(Mode.SLEEP, expired.mode)
    }

    @Test fun manualExpiryCandidatesAreSleepEndSunsetOrSunriseNeverSleepStart() {
        val date = day(2026, 11, 5)
        data class Case(val now: Long, val sleepEnd: Int, val expected: Long)
        for (case in listOf(
            Case(wall(date, 22), 5 * 60, wall(date + 1, 5)),
            Case(wall(date, 22), 7 * 60, wall(date + 1, 6)),
            Case(wall(date, 17), 7 * 60, wall(date, 18)))) {
            val config = limits(Trigger.FIXED, sleep = true).copy(sleepWindow = LocalWindow(23 * 60, case.sleepEnd))
            val controller = policy(config)
            val result = controller.evaluate(Input(0, case.now, 100f, false,
                manual = ManualAction.Set(Mode.REDSHIFT, ManualHold.NEXT_BOUNDARY)))
            assertEquals(case.toString(), case.expected, result.manual?.expiresAtWallMillis)
        }
    }

    @Test fun manualChangesBypassDwellAndIntervalsAndAnIndefiniteHoldCrossesScheduledBoundaries() {
        val date = day(2026, 11, 5)
        val start = wall(date, 12)
        val controller = policy(limits(sleep = true).copy(veryDarkDwellMillis = 0))
        assertEquals(Mode.REDSHIFT, controller.evaluate(Input(0, start, 0f, false)).mode)
        val held = controller.evaluate(Input(1, start + 1, 0f, true,
            manual = ManualAction.Set(Mode.DAY, ManualHold.UNTIL_CHANGED)))
        assertEquals(Mode.DAY, held.mode)
        assertNull(held.manual?.expiresAtWallMillis)
        assertEquals(Mode.DAY, controller.evaluate(Input(11 * hour, wall(date, 23), 0f, false)).mode)
        val released = controller.evaluate(Input(11 * hour + 1, wall(date, 23) + 1, 0f, false, manual = ManualAction.Clear))
        assertNull(released.manual)
        assertEquals(Mode.SLEEP, released.mode)
    }

    @Test fun aManualRequestWithUnknownTimeArmsWhenTrustedAndDoesNotSurviveANewController() {
        val date = day(2026, 11, 5)
        val config = limits(Trigger.SUN).copy(minimumSwitchMillis = 0)
        val controller = policy(config)
        val held = controller.evaluate(Input(0, null, 100f, false,
            manual = ManualAction.Set(Mode.REDSHIFT, ManualHold.NEXT_BOUNDARY)))
        assertNull(held.manual?.expiresAtWallMillis)
        val armed = controller.evaluate(Input(1, wall(date, 22), 100f, false))
        assertEquals(wall(date + 1, 6), armed.manual?.expiresAtWallMillis)
        assertEquals(Mode.DAY, controller.evaluate(Input(2, wall(date + 1, 6), 100f, false)).mode)
        assertNull(policy(config).evaluate(Input(0, wall(date, 22), 100f, false)).manual)
    }

    @Test fun engagementAndRepeatedTouchCannotExtendTheMaximumAutomaticDeferral() {
        val controller = policy(limits().copy(veryDarkDwellMillis = 0, minimumSwitchMillis = 0))
        for (at in listOf(0L, 1L, 300_000L, 599_999L)) {
            val waiting = controller.evaluate(Input(at, null, 0f, true, touched = true))
            assertEquals(Mode.DAY, waiting.mode)
            assertEquals(600_000L, waiting.nextEvaluationElapsedMillis)
            assertTrue(waiting.deferred)
        }
        assertEquals(Mode.REDSHIFT, controller.evaluate(Input(600_000, null, 0f, true, touched = true)).mode)
        val idleEarlier = policy(limits().copy(veryDarkDwellMillis = 0, minimumSwitchMillis = 0))
        idleEarlier.evaluate(Input(0, null, 0f, false, touched = true))
        assertEquals(Mode.REDSHIFT, idleEarlier.evaluate(Input(1, null, 0f, false)).mode)
    }

    @Test fun sleepStartDeferralUsesItsScheduledStartIncludingLateEvaluationAndCanEndAtIdle() {
        val date = day(2026, 11, 5)
        val start = wall(date, 23)
        val config = limits(Trigger.OFF, sleep = true).copy(minimumSwitchMillis = 0)
        val controller = policy(config)
        for ((elapsed, now) in listOf(0L to start + 8 * minute, minute to start + 9 * minute)) {
            val waiting = controller.evaluate(Input(elapsed, now, 100f, true, touched = true))
            assertEquals(Mode.DAY, waiting.mode)
            assertEquals(2 * minute, waiting.nextEvaluationElapsedMillis)
        }
        assertEquals(Mode.SLEEP, controller.evaluate(Input(2 * minute, start + 10 * minute, 100f, true)).mode)
        val earlyIdle = policy(config)
        assertEquals(Mode.DAY, earlyIdle.evaluate(Input(0, start, 100f, true)).mode)
        assertEquals(Mode.SLEEP, earlyIdle.evaluate(Input(30_000, start + 30_000, 100f, false)).mode)
    }

    @Test fun sleepEndsAtItsBoundaryEvenWhenEngagedOrWithinTheMinimumInterval() {
        val date = day(2026, 11, 5)
        val controller = policy(limits(Trigger.OFF, sleep = true))
        assertEquals(Mode.SLEEP, controller.evaluate(Input(0, wall(date, 6, 59), 100f, false)).mode)
        val exit = controller.evaluate(Input(minute, wall(date, 7), 100f, true, touched = true))
        assertEquals(Mode.DAY, exit.mode)
        assertTrue(exit.exitSleepViaRedshift)
        assertTrue(exit.suppressTouch)
        assertFalse(exit.deferred)
    }

    @Test fun theSleepStartHardLimitWinsOverALongerConfiguredSwitchInterval() {
        val date = day(2026, 11, 5)
        val start = wall(date, 23)
        for (engaged in listOf(false, true)) {
            val config = limits(sleep = true).copy(veryDarkDwellMillis = 0,
                minimumSwitchMillis = 15 * minute, sleepDelayMillis = 10 * minute)
            val controller = policy(config)
            assertEquals(Mode.REDSHIFT, controller.evaluate(Input(0, start - 1_000, 0f, false)).mode)
            val waiting = controller.evaluate(Input(1_000, start, 0f, engaged))
            assertEquals(Mode.REDSHIFT, waiting.mode)
            assertTrue(waiting.deferred)
            assertEquals(1_000 + 10 * minute, waiting.nextEvaluationElapsedMillis)
            assertEquals(Mode.REDSHIFT, controller.evaluate(Input(10 * minute, start + 10 * minute - 1_000, 0f, engaged)).mode)
            assertEquals(Mode.SLEEP, controller.evaluate(Input(1_000 + 10 * minute, start + 10 * minute, 0f, engaged)).mode)
        }
    }

    @Test fun disablingRedshiftIsAnExplicitSettingRatherThanAnotherDelayedLuxSample() {
        var config = limits().copy(veryDarkDwellMillis = 0)
        val controller = ModeController({ config }, utc, normalSun())
        assertEquals(Mode.REDSHIFT, controller.evaluate(Input(0, null, 0f, false)).mode)
        config = config.copy(trigger = Trigger.OFF)
        assertEquals(Mode.DAY, controller.evaluate(Input(1, null, 0f, true)).mode)
    }

    /** Test adapter uses ONLY the bundled ZoneClock. No java.time/ROM ZoneRules.
     * Gaps move forward by their offset change; a fold selects its first occurrence. */
    private inner class BundledCivil(private val zone: ZoneClock) : CivilClock {
        override fun dayAt(wallMillis: Long): Long = zone.fields(wallMillis).days
        override fun atLocal(day: Long, minuteOfDay: Int): Long {
            val nominal = day * dayMillis + minuteOfDay * minute
            val offsets = (-2L..2L).map { zone.offsetMillis(nominal + it * dayMillis) }.distinct()
            val candidates = offsets.map { nominal - it }
            fun shifted(at: Long) = at + zone.offsetMillis(at)
            return candidates.filter { shifted(it) == nominal }.minOrNull()
                ?: candidates.filter { shifted(it) > nominal }.minOrNull()
                ?: error("No bounded civil-time conversion")
        }
    }
    private fun withBerlin(block: (CivilClock) -> Unit) {
        val previous = Zones.database
        try {
            Zones.install(TzDatabase(FileTzSource(File(requireNotNull(System.getProperty("tzdata.dir"))))))
            val zone = Zones.clock("Europe/Berlin")
            assertTrue("DST tests must use ThinkOS tzdb", zone.bundled)
            block(BundledCivil(zone))
        } finally { Zones.install(previous) }
    }

    @Test fun sleepSpansTheActualSevenOrNineHourDstNightInsteadOfAssumingEightHours() = withBerlin { civil ->
        for ((date, hours) in listOf(day(2027, 3, 28) to 7L, day(2027, 10, 31) to 9L)) {
            val start = civil.atLocal(date - 1, 23 * 60)
            val end = civil.atLocal(date, 7 * 60)
            assertEquals(hours * hour, end - start)
            val controller = policy(limits(Trigger.OFF, sleep = true).copy(minimumSwitchMillis = 0), civil, normalSun(civil))
            assertEquals(Mode.SLEEP, controller.evaluate(Input(0, start, 100f, false)).mode)
            assertEquals(Mode.SLEEP, controller.evaluate(Input(end - start - 1, end - 1, 100f, false)).mode)
            assertEquals(Mode.DAY, controller.evaluate(Input(end - start, end, 100f, false)).mode)
        }
    }

    @Test fun anAlreadyEndedFoldWindowDoesNotRestartDuringTheRepeatedLocalHour() = withBerlin { civil ->
        val date = day(2027, 10, 31)
        val start = civil.atLocal(date, 30)
        val end = civil.atLocal(date, 2 * 60 + 30)
        val config = limits(Trigger.OFF, sleep = true).copy(sleepWindow = LocalWindow(30, 150), minimumSwitchMillis = 0)
        val controller = policy(config, civil, normalSun(civil))
        assertEquals(Mode.SLEEP, controller.evaluate(Input(0, start, 100f, false)).mode)
        assertEquals(Mode.DAY, controller.evaluate(Input(end - start, end, 100f, false)).mode)
        val repeated = end + 40 * minute
        assertEquals(Mode.DAY, controller.evaluate(Input(repeated - start, repeated, 100f, false)).mode)
    }

    @Test fun aGapBoundaryUsesTheInjectedBundledResolutionRatherThanAClockDurationGuess() = withBerlin { civil ->
        val date = day(2027, 3, 28)
        val start = civil.atLocal(date, 150) // nonexistent 02:30 -> 03:30
        val before = civil.atLocal(date, 3 * 60 + 15)
        val end = civil.atLocal(date, 4 * 60)
        val config = limits(Trigger.OFF, sleep = true).copy(sleepWindow = LocalWindow(150, 240), minimumSwitchMillis = 0)
        val controller = policy(config, civil, normalSun(civil))
        assertEquals(Mode.DAY, controller.evaluate(Input(0, before, 100f, false)).mode)
        assertEquals(Mode.SLEEP, controller.evaluate(Input(start - before, start, 100f, false)).mode)
        assertEquals(Mode.DAY, controller.evaluate(Input(end - before, end, 100f, false)).mode)
    }

    @Test fun nextEvaluationRefreshesDailySolarInputsAtTheActualCivilMidnight() {
        val date = day(2026, 11, 5)
        val controller = policy(limits(Trigger.SUN))
        val result = controller.evaluate(Input(100, wall(date, 23, 30), null, false))
        assertEquals(Mode.REDSHIFT, result.mode)
        assertEquals(100 + 30 * minute, result.nextEvaluationElapsedMillis)
    }

    @Test fun invalidConfigurationAndBackwardsElapsedTimeCannotSilentlyChangePolicy() {
        assertThrows(IllegalArgumentException::class.java) { limits().copy(engagedDelayMillis = -1) }
        assertThrows(IllegalArgumentException::class.java) { limits().copy(windowEnterLux = 120f) }
        assertThrows(IllegalArgumentException::class.java) { limits().copy(veryDarkExitLux = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { LocalWindow(0, 0) }
        val controller = policy()
        controller.evaluate(Input(100, null, 100f, false))
        assertThrows(IllegalArgumentException::class.java) { controller.evaluate(Input(99, null, 100f, false)) }
    }
}
