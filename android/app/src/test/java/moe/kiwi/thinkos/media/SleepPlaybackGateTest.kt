package moe.kiwi.thinkos.media

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

class SleepPlaybackGateTest {
    @Test fun sleepRefusesNewPlaybackAndWakeDoesNotResumeAnOldSession() {
        var asleep = false
        val gate = SleepPlaybackGate { asleep }
        assertTrue(gate.userPlayback())
        asleep = true; assertTrue(gate.changed())
        assertFalse(gate.userPlayback())
        asleep = false; assertTrue(gate.changed())
        assertTrue("old retry remains fenced after wake", gate.blocked)
        assertTrue("explicit new play may resume", gate.userPlayback())
        assertFalse(gate.blocked)
    }

    @Test fun inFlightPrepareCannotStartFromALateCallbackOrNetworkRetry() {
        var asleep = false
        val gate = SleepPlaybackGate { asleep }
        var playWhenReady = gate.userPlayback()
        asleep = true
        if (gate.changed()) playWhenReady = false
        var starts = 0
        fun preparedOrRetry() { if (!gate.blocked && playWhenReady) starts++ }
        preparedOrRetry()
        asleep = false; gate.changed()
        preparedOrRetry()
        assertEquals(0, starts)
        assertFalse(playWhenReady)
    }

    @Test fun asyncPermissionOrLoadTicketIsInvalidEvenAfterSleepHasEnded() {
        var asleep = false
        val gate = SleepPlaybackGate { asleep }
        val ticket = gate.generation
        asleep = true; gate.changed()
        asleep = false; gate.changed()
        try { gate.requireAwake(ticket); fail("stale load was admitted") }
        catch (error: IllegalStateException) { assertEquals("asleep", error.message) }
        gate.requireAwake(gate.generation)
        assertTrue(gate.userPlayback())
    }

    @Test fun duplicateSleepNotificationsDoNotCreateExtraTransitions() {
        var asleep = true
        val gate = SleepPlaybackGate { asleep }
        gate.changed(); val ticket = gate.generation
        gate.changed(); assertEquals(ticket, gate.generation)
        asleep = false; gate.changed()
        asleep = true; gate.changed()
        assertEquals(ticket + 1, gate.generation)
    }

    @Test fun aRequestMadeWhileSleepingCannotBeAdmittedByALateWake() {
        var asleep = true
        val gate = SleepPlaybackGate { asleep }
        gate.changed()
        val request = gate.ticket()
        asleep = false; gate.changed()
        try { gate.requireAwake(request); fail("sleeping request was deferred into playback") }
        catch (error: IllegalStateException) { assertEquals("asleep", error.message) }
    }

    @Test fun queuedCastUrlAndMirrorLoadsCannotAutoplayAcrossSleepAndWake() {
        for (kind in listOf("url", "mirror")) {
            var asleep = false
            val gate = SleepPlaybackGate { asleep }
            val loaded = mutableListOf<String>()
            val shown = mutableListOf<String>()
            // Like EngineCastPlayer: capture on the network caller, check inside its main-thread FutureTask.
            fun enqueue(): FutureTask<Unit> {
                val ticket = gate.ticket()
                return FutureTask<Unit> {
                    gate.requireAwake(ticket)
                    loaded += kind
                    shown += kind
                }
            }
            val queued = enqueue()
            asleep = true; gate.changed()
            asleep = false; gate.changed()
            // FutureTask contains the rejection; running the main queue must not throw.
            queued.run()
            try { queued.get(); fail("stale $kind cast load was admitted") }
            catch (error: ExecutionException) {
                assertTrue(error.cause is IllegalStateException)
                assertEquals("asleep", error.cause?.message)
            }
            assertTrue("$kind did not load", loaded.isEmpty())
            assertTrue("$kind did not focus a cast page", shown.isEmpty())
            val fresh = enqueue()
            fresh.run(); fresh.get()
            assertEquals(listOf(kind), loaded)
            assertEquals(listOf(kind), shown)
        }
    }
}
