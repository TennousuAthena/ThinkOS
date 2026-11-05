package moe.kiwi.thinkos.media

import org.junit.Assert.*
import org.junit.Test

/** Real broker callbacks/queue, no Android permissions or dialogs are mocked into success. */
class StoragePermissionTest {
    private class Fixture {
        var granted = false
        var canLaunch = true
        val launches = mutableListOf<Long>()
        val posted = ArrayDeque<() -> Unit>()
        val broker = StoragePermission({ granted }, { launches.add(it); canLaunch }, { posted.add(it) }, {})
        fun drain() { while (posted.isNotEmpty()) posted.removeFirst().invoke() }
    }

    @Test fun firstBackgroundAccessStillRequestsAndConcurrentPluginsShareOnePrompt() {
        val f = Fixture(); val results = mutableListOf<Boolean>()
        f.broker.request("player:1") { granted, _ -> results.add(granted) }
        f.broker.request("gallery:2") { granted, _ -> results.add(granted) }
        assertEquals(1, f.launches.size); assertTrue(results.isEmpty())
        f.granted = true; f.broker.result(f.launches.single(), true)
        assertEquals(listOf(true, true), results)
    }

    @Test fun actualPlayerAudioToVideoContinuationAndOtherPluginRetryDoNotRepromptAfterDenial() {
        val f = Fixture(); val results = mutableListOf<Boolean>(); val errors = mutableListOf<String?>()
        f.broker.request("player:1") { granted, error ->
            results.add(granted); errors.add(error)
            // player.scan resumes from audio and immediately asks for video.
            f.broker.request("player:1") { next, nextError -> results.add(next); errors.add(nextError) }
        }
        f.broker.result(f.launches.single(), false)
        repeat(4) { f.broker.request("gallery:2") { granted, error -> results.add(granted); errors.add(error) } }
        assertEquals(1, f.launches.size)
        assertEquals(List(6) { false }, results)
        assertTrue(errors.all { it == "READ_EXTERNAL_STORAGE permission was denied" })
    }

    @Test fun realExternalGrantWorksImmediatelyAndAnObservedGrantResetsAnOldDenial() {
        val f = Fixture(); val results = mutableListOf<Boolean>()
        f.broker.request("player:1") { granted, _ -> results.add(granted) }
        f.broker.result(f.launches.single(), false)
        f.granted = true
        f.broker.request("player:1") { granted, _ -> results.add(granted) }
        assertEquals(listOf(false, true), results); assertEquals(1, f.launches.size)
        f.granted = false // Actual later OS revoke permits a new first request.
        f.broker.request("player:1") { granted, _ -> results.add(granted) }
        assertEquals(2, f.launches.size); assertEquals(2, results.size)
    }

    @Test fun staleDenialCannotCancelOrPoisonANewerOutstandingRequest() {
        val f = Fixture(); val results = mutableListOf<Boolean>()
        f.broker.request("player:1") { _, _ -> }
        val old = f.launches.single(); f.broker.hostClosed(old)
        f.broker.request("gallery:2") { granted, _ -> results.add(granted) }
        val current = f.launches.last()
        f.broker.result(old, false)
        f.broker.request("player:3") { granted, _ -> results.add(granted) }
        assertEquals(2, f.launches.size); assertTrue(results.isEmpty())
        f.granted = true; f.broker.result(current, true)
        assertEquals(listOf(true, true), results)
    }

    @Test fun cancelledCallerNeverResumesButItsExplicitSystemDenialStillPreventsRetryPrompts() {
        val f = Fixture()
        val ticket = f.broker.request("removed-plugin:1") { _, _ -> fail("Cancelled caller resumed") }
        ticket.close(); f.drain()
        f.broker.result(f.launches.single(), false)
        var denied = false
        f.broker.request("player:2") { granted, _ -> denied = !granted }
        assertTrue(denied); assertEquals(1, f.launches.size)
    }

    @Test fun missingActivityAndDestroyedHostDoNotPretendTheUserDenied() {
        val f = Fixture(); f.canLaunch = false
        var failures = 0
        f.broker.request("player:1") { granted, _ -> if (!granted) failures++ }
        assertEquals(1, failures)
        f.canLaunch = true
        f.broker.request("player:1") { granted, _ -> if (!granted) failures++ }
        f.broker.hostClosed(f.launches.last())
        f.broker.request("player:1") { _, _ -> }
        assertEquals(3, f.launches.size); assertEquals(2, failures)
    }

    @Test fun resultClaimingGrantWithoutActualAndroidGrantIsStillDenialAndNewRuntimeStartsFresh() {
        val f = Fixture(); var denied = false
        f.broker.request("player:1") { granted, _ -> denied = !granted }
        f.broker.result(f.launches.single(), true)
        f.broker.request("gallery:2") { granted, _ -> assertFalse(granted) }
        assertTrue(denied); assertEquals(1, f.launches.size)
        val nextRuntime = Fixture(); nextRuntime.broker.request("player:1") { _, _ -> }
        assertEquals(1, nextRuntime.launches.size)
    }
}
