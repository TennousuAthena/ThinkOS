package moe.kiwi.thinkos

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeoutException

class CallH264ProbeObservationTest {
    @Test fun lateWorkerSuccessCannotOverwriteObserverTimeoutEvenAfterDoneArrives() {
        val actual = CallH264ProbeObservation(JSONObject())
        actual.stage("release")
        // The worker may already have computed true and be descheduled before
        // its completion write. Model that exact reviewed interleaving.
        actual.failure("observation_expired", TimeoutException())
        actual.workerCompleted(true)
        actual.stage("finished")
        val report = actual.snapshot(true)
        assertFalse(report.getBoolean("functional_complete"))
        assertTrue(report.getBoolean("worker_finished"))
        assertTrue(report.getBoolean("terminal_failure"))
        assertEquals("observation_expired", report.getString("error_reason"))
        assertEquals("release", report.getString("error_stage"))
    }

    @Test fun interruptedObserverNeverQualifiesAWorkerThatFinishesLater() {
        val actual = CallH264ProbeObservation(JSONObject())
        actual.workerCompleted(true)
        actual.failure("observation_interrupted", InterruptedException())
        assertFalse(actual.snapshot(false).getBoolean("functional_complete"))
        assertFalse(actual.snapshot(true).getBoolean("functional_complete"))
        assertThrows(IllegalArgumentException::class.java) { actual.put("functional_complete", true) }
    }

    @Test fun successfulCandidateRequiresObservedLatchAndReportsDetachedSnapshots() {
        val actual = CallH264ProbeObservation(JSONObject())
        actual.workerCompleted(true)
        val unfinished = actual.snapshot(false)
        assertFalse(unfinished.getBoolean("functional_complete"))
        assertFalse(unfinished.getBoolean("worker_finished"))
        assertTrue(actual.snapshot(true).getBoolean("functional_complete"))
        // Future worker updates cannot mutate an already returned report.
        actual.failure("post_release_gate_failed", IllegalStateException())
        assertFalse(actual.snapshot(true).getBoolean("functional_complete"))
        assertFalse(unfinished.getBoolean("terminal_failure"))
    }
    @Test fun identityReflectionFailureAfterInitOkStillReportsActualCodecCreation() {
        val actual = CallH264ProbeObservation(JSONObject().put("actual_codec_created", false).put("actual_codec_identity_verified", false))
        actual.codecInitStarted()
        actual.codecInitReturned("OK")
        actual.failure("encoding_failed", NoSuchFieldException("codec identity observation"))
        actual.put("release_completed", true)
        val report = actual.snapshot(true)
        assertTrue(report.getBoolean("actual_codec_created"))
        assertFalse(report.getBoolean("actual_codec_identity_verified"))
        assertEquals("SDK_initEncode_OK", report.getString("codec_creation_basis"))
        assertFalse(report.getBoolean("functional_complete"))
    }

    @Test fun partialFailedOrUnreturnedInitDoesNotClaimThatNoCodecWasAllocated() {
        for (status in listOf<String?>(null, "FALLBACK_SOFTWARE")) {
            val actual = CallH264ProbeObservation(JSONObject().put("actual_codec_created", false).put("actual_codec_identity_verified", false))
            actual.codecInitStarted()
            status?.let(actual::codecInitReturned)
            actual.failure("encoding_failed", IllegalStateException())
            actual.put("release_completed", true)
            val report = actual.snapshot(true)
            assertTrue(report.getBoolean("init_encode_attempted"))
            assertTrue(report.isNull("actual_codec_created"))
            assertFalse(report.getBoolean("actual_codec_identity_verified"))
            assertFalse(report.getBoolean("functional_complete"))
        }
    }

}
