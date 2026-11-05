package moe.kiwi.thinkos

import moe.kiwi.thinkos.update.UpdateApkIdentity
import moe.kiwi.thinkos.update.UpdateCatalog
import moe.kiwi.thinkos.update.UpdateInstallStore
import moe.kiwi.thinkos.update.UpdateJson
import moe.kiwi.thinkos.update.UpdateModels
import moe.kiwi.thinkos.update.UpdatePending
import moe.kiwi.thinkos.update.UpdateInstallTime
import org.json.JSONObject

/** Debug build only. A RAM lease is not a startup fault and never authorizes
 * installing. Only a fully staged exact manual transaction may publish a marker.
 */
internal class DebugCommitArm {
    data class Expected(val nonce: String, val physical: String, val channel: String, val seq: Long,
                        val digest: String, val from: UpdateApkIdentity, val to: UpdateApkIdentity,
                        val notes: Map<String, String>, val issuedElapsed: Long)
    data class Bound(val nonce: String, val pending: UpdatePending)
    private var expected: Expected? = null
    private var bound: Bound? = null

    @Synchronized fun arm(value: Expected, expectedPhysical: String) {
        require(expected == null && bound == null) { "A debug arm already owns this process" }
        requireSpare(value.physical, expectedPhysical)
        require(value.nonce.matches(HEX32) && value.channel in setOf("stable", "beta") && value.seq > 0 &&
            value.digest.matches(HEX64) && value.issuedElapsed >= 0)
        identity(value.from); identity(value.to)
        require(value.to.code > value.from.code && value.from.signer == value.to.signer)
        expected = value.copy(notes = value.notes.toMap())
    }

    @Synchronized fun isIdle(): Boolean = expected == null && bound == null

    @Synchronized fun revoke(nonce: String) {
        if (expected?.nonce == nonce) expected = null
        // A published commit marker belongs to the existing native transaction.
        // Closing the test phase cannot erase it or grant another arm.
    }

    @Synchronized fun state(nonce: String, nowElapsed: Long): String {
        if (bound?.nonce == nonce) return "BOUND_TO_PENDING"
        val value = expected?.takeIf { it.nonce == nonce } ?: return "NONE"
        if (!fresh(value, nowElapsed)) { expected = null; return "EXPIRED" }
        return "ARMED"
    }

    /** Called only after production admission, staging/fsync and live unsealed
     * session checks. Consume BEFORE publication, including on write failure.
     * The callback runs in the same serial critical section; no double commit,
     * revoke race or automatic retry may publish a second marker.
     */
    @Synchronized fun beforeCommit(pending: UpdatePending, session: Int, nowElapsed: Long,
                                   publish: (Bound) -> Unit): Boolean {
        val value = expected ?: return false
        expected = null
        if (!fresh(value, nowElapsed) || !staged(pending, session) ||
            pending.channel != value.channel || pending.seq != value.seq || pending.catalog_digest != value.digest ||
            pending.from != value.from || pending.to != value.to || pending.notes != value.notes) return false
        val proof = Bound(value.nonce, pending.copy(notes = pending.notes.toMap()))
        publish(proof)
        bound = proof
        return true
    }

    companion object {
        const val LEASE_MS = 180_000L
        const val MARKER_LIMIT = UpdateInstallStore.MAX_BYTES + 1024
        private val HEX32 = Regex("[a-f0-9]{32}")
        private val HEX64 = Regex("[a-f0-9]{64}")

        fun requireSpare(actual: String, expected: String) {
            require(listOf(actual, expected).all { it.matches(Regex("[A-Za-z0-9_.:-]{1,100}")) &&
                it.uppercase(java.util.Locale.ROOT) !in setOf("HUA0BWUT", "UNKNOWN", "NULL", "0") }) { "Known explicit spare required" }
            require(actual == expected) { "Physical spare differs" }
        }

        private fun identity(value: UpdateApkIdentity) {
            require(value.code == UpdateCatalog.versionCode(value.version) && value.code > 0 &&
                value.size in 1..64L * 1024 * 1024 && value.sha256.matches(HEX64) && value.signer.matches(HEX64))
        }

        private fun fresh(value: Expected, now: Long) = now >= value.issuedElapsed && now - value.issuedElapsed <= LEASE_MS

        private fun staged(pending: UpdatePending, session: Int): Boolean =
            pending.schema == 1 && pending.transaction_id.matches(HEX32) && pending.phase == "INSTALLING" &&
                pending.mode == "manual" && session >= 0 && pending.session_id == session && pending.rollback_session_id == null &&
                pending.attempts == 0 && pending.trial_id == null && pending.trial_started == null &&
                pending.max_attempts == 3 && pending.health_ms == 600_000L

        fun marker(value: Bound): ByteArray {
            require(value.nonce.matches(HEX32) && value.pending.session_id != null && staged(value.pending, value.pending.session_id))
            return JSONObject().put("schema", 2).put("nonce", value.nonce)
                .put("pending", UpdateModels.writeUpdatePending(value.pending)).toString().toByteArray(Charsets.UTF_8).also {
                    require(it.size in 1..MARKER_LIMIT)
                }
        }

        fun parse(raw: ByteArray): Bound {
            val json = UpdateJson.read(raw, MARKER_LIMIT)
            require(json.keys().asSequence().toSet() == setOf("schema", "nonce", "pending") && json.get("schema") == 2L)
            val nonce = json.getString("nonce")
            val pending = UpdateModels.readUpdatePending(json.getJSONObject("pending"))
            require(nonce.matches(HEX32) && pending.session_id != null && staged(pending, pending.session_id))
            return Bound(nonce, pending)
        }

        /** Read-only compatibility exercise. Every policy below is a new local
         * RAM object; no global DebugUpdateFault arm, file, installer or worker
         * is touched. The synthetic pending is NEVER a native transaction. */
        fun compatibility(installed: UpdateApkIdentity, nonce: String, physical: String): JSONObject {
            identity(installed); require(installed.code > 1 && nonce.matches(HEX32))
            requireSpare(physical, physical)
            val previous = UpdateApkIdentity("0.0.1", 1, "4".repeat(64), 1, installed.signer)
            val pending = UpdatePending(1, nonce, "INSTALLING", "beta", "a".repeat(64), 1,
                previous, installed, "manual", 0, 3, 600_000, 86_400_000, 3,
                UpdateInstallTime(100, 1, 1000), mapOf("en" to "Readonly v2 policy compatibility"), session_id = 23)
            fun armed() = DebugCommitArm().apply {
                arm(Expected(nonce, physical, pending.channel, pending.seq, pending.catalog_digest,
                    previous, installed, pending.notes, 100), physical)
            }
            val local = armed(); var count = 0; var bytes: ByteArray? = null
            val admitted = local.beforeCommit(pending, 23, 101) { count++; bytes = marker(it) }
            val raw = requireNotNull(bytes)
            val proof = parse(raw)
            fun trial(attempt: Int) = pending.copy(phase = "TRIAL", attempts = attempt,
                trial_id = nonce, trial_started = UpdateInstallTime(200, 1, 2000))
            fun denied(change: UpdatePending, now: Long = 101, session: Int = 23): Boolean {
                var publications = 0
                return !armed().beforeCommit(change, session, now) { publications++ } && publications == 0
            }
            val observations = JSONObject()
                .put("manual_commit_admitted", admitted && count == 1)
                .put("marker_roundtrip_equal", proof == Bound(nonce, pending))
                .put("second_commit_rejected", !local.beforeCommit(pending, 23, 102) { count++ } && count == 1)
                .put("automatic_commit_rejected", denied(pending.copy(mode = "automatic")))
                .put("wrong_session_rejected", denied(pending, session = 24))
                .put("expired_commit_rejected", denied(pending, now = 100 + LEASE_MS + 1))
                .put("trial_1_eligible", shouldCrash(proof, trial(1), installed))
                .put("trial_2_eligible", shouldCrash(proof, trial(2), installed))
                .put("trial_3_eligible", shouldCrash(proof, trial(3), installed))
                .put("no_pending_eligible", shouldCrash(proof, null, installed))
                .put("unsubmitted_eligible", shouldCrash(proof, pending.copy(phase = "PREPARED", session_id = null), installed))
                .put("installing_eligible", shouldCrash(proof, pending, installed))
                .put("trial_4_eligible", shouldCrash(proof, trial(4), installed))
                .put("wrong_installed_hash_eligible", shouldCrash(proof, trial(1), installed.copy(sha256 = "5".repeat(64))))
                .put("wrong_installed_signer_eligible", shouldCrash(proof, trial(1), installed.copy(signer = "6".repeat(64))))
                .put("changed_catalog_eligible", shouldCrash(proof, trial(1).copy(catalog_digest = "b".repeat(64)), installed))
                .put("changed_notes_eligible", shouldCrash(proof, trial(1).copy(notes = mapOf("en" to "different")), installed))
                .put("legacy_marker_rejected", runCatching { parse("{\"transaction\":\"$nonce\"}".toByteArray()) }.isFailure)
            val positive = setOf("manual_commit_admitted", "marker_roundtrip_equal", "second_commit_rejected",
                "automatic_commit_rejected", "wrong_session_rejected", "expired_commit_rejected",
                "trial_1_eligible", "trial_2_eligible", "trial_3_eligible", "legacy_marker_rejected")
            val complete = observations.keys().asSequence().all { observations.getBoolean(it) == (it in positive) }
            return JSONObject().put("schema", 1).put("scope", "policy_roundtrip_only").put("complete", complete)
                .put("marker_base64", java.util.Base64.getEncoder().encodeToString(raw))
                .put("marker_sha256", UpdateCatalog.sha256(raw)).put("observed", observations)
        }

        /** Startup is inert unless BOTH native durable trial and actual fully
         * verified installed APK match. Only native trial fields may advance;
         * every other journal field, including session/from/to, stays exact.
         */
        fun shouldCrash(marker: Bound, current: UpdatePending?, installed: UpdateApkIdentity): Boolean {
            if (current == null || current.phase != "TRIAL" || current.attempts !in 1..3 ||
                current.trial_id?.matches(HEX32) != true || current.trial_started == null || installed != current.to) return false
            val pinned = marker.pending
            if (pinned.session_id == null || !staged(pinned, pinned.session_id)) return false
            return pinned.copy(phase = current.phase, attempts = current.attempts,
                trial_id = current.trial_id, trial_started = current.trial_started) == current
        }
    }
}
