package moe.kiwi.thinkos

import org.json.JSONObject
import java.lang.reflect.InvocationTargetException

/** Real cross-thread completion state used by the test-APK codec probe.
 * Observer failure is terminal. Worker completion is only a candidate; the
 * observer must also have seen the sole worker's final latch before success.
 */
internal class CallH264ProbeObservation(private val value: JSONObject) {
    private var terminalFailure = false
    private var workerSuccess = false

    @Synchronized fun put(key: String, next: Any?): CallH264ProbeObservation {
        require(key !in setOf("functional_complete", "worker_finished", "terminal_failure",
            "actual_codec_created", "actual_codec_identity_verified", "init_encode_attempted"))
        value.put(key, next)
        return this
    }

    /** initEncode may allocate and then fail or block. Unknown is distinct
     * from a call that was never attempted. The fixed SDK's OK path necessarily
     * passed actual MediaCodec creation/configure/start before returning. */
    @Synchronized fun codecInitStarted() {
        value.put("init_encode_attempted", true).put("actual_codec_created", JSONObject.NULL)
    }

    @Synchronized fun codecInitReturned(status: String) {
        value.put("init_status", status)
        if (status == "OK") value.put("actual_codec_created", true).put("codec_creation_basis", "SDK_initEncode_OK")
    }

    @Synchronized fun codecIdentityVerified() {
        check(value.opt("actual_codec_created") == true)
        value.put("actual_codec_identity_verified", true)
    }

    @Synchronized fun stage(name: String) { value.put("stage", name) }

    @Synchronized fun failure(reason: String, error: Throwable) {
        terminalFailure = true
        val cause = (error as? InvocationTargetException)?.targetException ?: error
        if (!value.has("error_reason")) value.put("error_reason", reason).put("error_stage", value.optString("stage"))
            .put("error_class", cause.javaClass.simpleName.take(80))
    }

    @Synchronized fun failed() = terminalFailure
    @Synchronized fun workerCompleted(success: Boolean) { workerSuccess = success }

    @Synchronized fun snapshot(observedDone: Boolean): JSONObject = JSONObject(value.toString())
        .put("worker_finished", observedDone).put("terminal_failure", terminalFailure)
        .put("functional_complete", workerSuccess && observedDone && !terminalFailure)
}
