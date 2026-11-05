package moe.kiwi.thinkos

import android.content.Context
import moe.kiwi.thinkos.update.UpdateApkIdentity
import moe.kiwi.thinkos.update.UpdatePending

/** No marker reader, RAM lease, crash worker or commit hook exists in release. */
internal object DebugUpdateFault {
    @Suppress("UNUSED_PARAMETER")
    fun check(context: Context) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun beforeCommit(context: Context, pending: UpdatePending, session: Int) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun arm(context: Context, nonce: String, physical: String, expectedPhysical: String,
            channel: String, seq: Long, digest: String, from: UpdateApkIdentity, to: UpdateApkIdentity,
            notes: Map<String, String>): Unit = throw UnsupportedOperationException("Debug fault arm is unavailable in release")

    @Suppress("UNUSED_PARAMETER")
    fun readOnlyCompatibility(context: Context, nonce: String, physical: String,
                              expectedPhysical: String, installed: UpdateApkIdentity): org.json.JSONObject =
        throw UnsupportedOperationException("Debug compatibility is unavailable in release")

    @Suppress("UNUSED_PARAMETER")
    fun revoke(nonce: String) = Unit

    @Suppress("UNUSED_PARAMETER")
    fun state(nonce: String): String = "UNAVAILABLE"
}
