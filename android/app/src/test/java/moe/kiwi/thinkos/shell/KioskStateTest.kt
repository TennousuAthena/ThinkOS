package moe.kiwi.thinkos.shell

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KioskStateTest {
    @After fun clear() { KioskState.leaveForeign(); KioskState.notePaused(KioskState.foregroundTaskId) }

    @Test fun userReturnWakesOnlyBeforeTheForeignDeadline() {
        assertFalse(KioskState.returningBeforeTimeout(100))
        KioskState.enterForeign(1_000, 100)
        assertTrue(KioskState.returningBeforeTimeout(1_099))
        assertFalse(KioskState.returningBeforeTimeout(1_100))
        assertFalse(KioskState.returningBeforeTimeout(1_101))
        KioskState.leaveForeign()
        assertFalse(KioskState.returningBeforeTimeout(101))
    }

    @Test fun anUnlimitedForeignSessionStillTreatsBackAsUserInteraction() {
        KioskState.enterForeign(0, 100)
        assertTrue(KioskState.returningBeforeTimeout(10_000_000))
        KioskState.extendForeign(60_000, 200)
        assertTrue(KioskState.returningBeforeTimeout(10_000_000))
    }

    @Test fun activeAudioExtendsTheFiniteDeadline() {
        KioskState.enterForeign(1_000, 100)
        KioskState.extendForeign(1_000, 900)
        assertTrue(KioskState.returningBeforeTimeout(1_899))
        assertFalse(KioskState.returningBeforeTimeout(1_900))
    }

    @Test fun onlyTheResumedTaskHandlesLaunchesAndAnOlderPauseCannotReplaceIt() {
        KioskState.noteResumed(10)
        assertTrue(KioskState.ownsForegroundTask(10))
        assertFalse(KioskState.ownsForegroundTask(20))
        KioskState.noteResumed(20)
        KioskState.notePaused(10)
        assertFalse(KioskState.ownsForegroundTask(10))
        assertTrue(KioskState.ownsForegroundTask(20))
        KioskState.notePaused(20)
        assertFalse(KioskState.ownsForegroundTask(20))
        assertFalse(KioskState.ownsForegroundTask(-1))
    }
}
