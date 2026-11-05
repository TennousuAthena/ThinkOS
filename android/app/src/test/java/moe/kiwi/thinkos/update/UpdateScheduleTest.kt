package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.*
import org.junit.Test

class UpdateScheduleTest {
    private val minute = 60_000L
    private val hour = 60 * minute
    private val day = 24 * hour
    private val policy = UpdateSchedule.QuietPolicy(3, 3, 10 * minute, 3 * day, hour, 3)
    private val ready = UpdateSchedule.InstallState(true, Attention.ASLEEP, true, 3, 10 * minute,
        0, 0, false, false, false, false, false, 300, 100)
    private fun allowed(state: UpdateSchedule.InstallState = ready, p: UpdateSchedule.QuietPolicy = policy) =
        UpdateSchedule.automaticInstallAllowed(p, state)

    @Test fun theNormalWindowIncludesThreeAndExcludesSix() {
        for (h in 0..23) assertEquals("hour $h", h in 3..5, allowed(ready.copy(localHour = h)))
    }

    @Test fun aConfiguredWindowCanCrossMidnight() {
        val overnight = policy.copy(startHour = 22)
        for (h in 0..23) assertEquals("hour $h", h in setOf(22, 23, 0), allowed(ready.copy(localHour = h), overnight))
    }

    @Test fun activityAndMediaInterlocksEachBlockAutomaticInstallation() {
        val busy = listOf(ready.copy(autoInstall = false), ready.copy(mediaSession = true), ready.copy(casting = true),
            ready.copy(settingsOpen = true), ready.copy(onboardingOpen = true), ready.copy(foreignApp = true)) +
            listOf(Attention.AMBIENT, Attention.AWAKE, Attention.ENGAGED).map { ready.copy(attention = it) }
        busy.forEach { assertFalse(it.toString(), allowed(it)) }
    }

    @Test fun aRecentTouchRestartsTheQuietPeriod() {
        assertTrue(allowed())
        assertFalse(allowed(ready.copy(sinceTouchMillis = 10 * minute - 1)))
        assertFalse(allowed(ready.copy(sinceTouchMillis = -1)))
    }

    @Test fun fallbackRequiresBothThreeDaysWaitingAndOneHourAsleep() {
        val outside = ready.copy(localHour = 12, waitingReadyMillis = 3 * day, asleepMillis = hour)
        assertTrue(allowed(outside))
        assertFalse(allowed(outside.copy(waitingReadyMillis = 3 * day - 1)))
        assertFalse(allowed(outside.copy(asleepMillis = hour - 1)))
        assertFalse(allowed(outside.copy(mediaSession = true)))
        assertFalse(allowed(outside.copy(sinceTouchMillis = 0)))
    }

    @Test fun anUntrustedClockUsesSleepButRetainsTheOtherInterlocks() {
        val unknown = ready.copy(timeTrusted = false, localHour = null)
        assertTrue(allowed(unknown))
        assertFalse(allowed(unknown.copy(attention = Attention.AMBIENT)))
        assertFalse(allowed(unknown.copy(settingsOpen = true)))
        assertFalse(allowed(unknown.copy(sinceTouchMillis = 0)))
    }

    @Test fun storageChecksIncludeTheBoundaryAndCannotOverflow() {
        assertTrue(allowed())
        assertFalse(allowed(ready.copy(freeBytes = 299)))
        assertFalse(allowed(ready.copy(apkBytes = 0)))
        assertFalse(allowed(ready.copy(freeBytes = -1)))
        assertFalse(allowed(ready.copy(apkBytes = Long.MAX_VALUE, freeBytes = Long.MAX_VALUE)))
    }

    @Test fun invalidTimeInputsCannotEnableInstallation() {
        assertFalse(allowed(ready.copy(localHour = null)))
        assertFalse(allowed(ready.copy(localHour = 24)))
        assertFalse(allowed(ready.copy(waitingReadyMillis = -1)))
        assertFalse(allowed(ready.copy(asleepMillis = -1)))
    }

    @Test fun videoAndCastingBlockManualAndAutomaticDownloads() {
        for (attention in Attention.entries) {
            assertEquals(attention in setOf(Attention.ASLEEP, Attention.AMBIENT), UpdateSchedule.downloadAllowed(attention, false, false, false))
            assertTrue(UpdateSchedule.downloadAllowed(attention, true, false, false))
            for (manual in listOf(false, true)) {
                assertFalse(UpdateSchedule.downloadAllowed(attention, manual, true, false))
                assertFalse(UpdateSchedule.downloadAllowed(attention, manual, false, true))
            }
        }
    }

    @Test fun invalidPolicyValuesAreRejectedAtTheSettingsBoundary() {
        for (change in listOf<() -> Unit>({ policy.copy(startHour = -1) }, { policy.copy(hours = 0) },
            { policy.copy(hours = 25) }, { policy.copy(spaceFactor = 0) }, { policy.copy(touchGraceMillis = -1) },
            { policy.copy(fallbackWaitMillis = -1) }, { policy.copy(fallbackAsleepMillis = -1) }))
            assertThrows(IllegalArgumentException::class.java) { change() }
    }
}
