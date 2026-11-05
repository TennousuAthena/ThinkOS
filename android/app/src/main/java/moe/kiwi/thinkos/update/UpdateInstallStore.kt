package moe.kiwi.thinkos.update

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Durable app-only install journal. Blocking IO; no Android or installation calls.
 * History commits before destructive cleanup. Per-code failure files remain the
 * blacklist authority even after their entries leave the recent-history window. */
internal class UpdateInstallStore(internal val directory: File) {
    data class Limits(val maxAttempts: Int, val healthMillis: Long, val noticeMillis: Long, val failureLimit: Int) {
        init { require(maxAttempts in 1..10 && healthMillis in 600_000..3_600_000 && noticeMillis in 60_000..604_800_000 && failureLimit in 1..10) }
    }
    data class Running(val version: String, val code: Int, val signer: String) {
        init { require(UpdateCatalog.versionCode(version) == code && signer.matches(Regex("[a-f0-9]{64}"))) }
        fun matches(value: UpdateApkIdentity) = code == value.code && version == value.version && signer == value.signer
    }
    data class Snapshot(val pending: UpdatePending?, val history: UpdateInstallHistory)
    enum class Action { NONE, WAITING, TRIAL, ROLLBACK, ROLLBACK_PENDING, ROLLED_BACK, COMPLETED, MISMATCH }
    data class Startup(val action: Action, val pending: UpdatePending? = null, val clearRecovery: String? = null)
    class Corrupt(cause: Exception) : IOException("Corrupt install transaction record", cause)
    private val files = UpdateFiles(directory, "install.lock")

    private fun <T> locked(block: (File) -> T): T = synchronized(locks.computeIfAbsent(directory.canonicalPath) { Any() }) {
        files.locked(blocking = true, block = block)
    }

    fun snapshot(): Snapshot = locked { Snapshot(pending(it), history(it)) }
    fun repairSnapshot(): UpdateRepairFiles.Snapshot = locked { root ->
        val badPending = try { pending(root); false } catch (_: Corrupt) { true }
        val badHistory = try { history(root); false } catch (_: Corrupt) { true }
        UpdateRepairFiles.snapshot(root, badPending, badHistory)
    }
    fun isolateCorrupt(expected: UpdateRepairFiles.Snapshot, evidence: () -> UpdateRepairFiles.Evidence,
                       checkActive: () -> Unit = {}): UpdateRepairRecord = locked { root ->
        val stillBad = try { pending(root); false } catch (_: Corrupt) { true }
        history(root) // never reset a damaged blacklist/history authority
        if ((!stillBad && UpdateRepairFiles.marker(root)?.state != "PREPARED") || !expected.needed)
            throw UpdateRepairFiles.Changed()
        UpdateRepairFiles.isolate(root, expected, evidence, checkActive)
    }
    fun repairAllowsRecoveryClear(id: String): Boolean = locked { UpdateRepairFiles.allowsClear(it, id) }
    fun blocked(code: Int): Boolean = locked { root -> require(code > 0); failure(root, code)?.blocked == true }
    fun autoBlocked(code: Int, limit: Int): Boolean = locked { root ->
        require(code > 0 && limit in 1..10); failure(root, code)?.let { it.blocked || it.count >= limit } == true
    }

    /** The adapter supplies the installed APK's native-verified identity. Copy,
     * bounded hash and fsync complete BEFORE pending.json can reference it. */
    fun prepare(catalog: UpdateCatalog, verified: UpdatePackageVerifier.Verified, installed: UpdateApkIdentity,
                source: File, mode: String, limits: Limits, now: UpdateInstallTime, id: String): UpdatePending = locked { root ->
        require(pending(root) == null) { "Another install transaction is pending" }
        UpdateRepairFiles.requireInstallable(root, installed)
        val release = catalog.document.release
        require(release.modules.isEmpty()) { "System component staging requires the module updater" }
        val target = UpdateApkIdentity(release.version, release.code, release.sha256, release.size, release.signer)
        require(verified.catalogDigest == catalog.digest && verified.sha256 == target.sha256 && verified.bytes == target.size &&
            verified.versionCode == target.code && verified.signer == target.signer) { "Unrelated APK verification" }
        val value = UpdatePending(1, id, "PREPARED", catalog.document.channel, catalog.digest, catalog.document.seq, installed, target,
            mode, 0, limits.maxAttempts, limits.healthMillis, limits.noticeMillis, limits.failureLimit, now, release.notes)
        validate(value)
        val failure = failure(root, target.code)
        require(failure?.blocked != true && (mode != "automatic" || failure == null || failure.count < limits.failureLimit)) { "Update version is blocked" }
        copyBackup(root, source, installed)
        UpdateRepairFiles.bind(root, id, installed)
        write(root, value)
        value
    }

    /** Persist the ONE fully-written session id before commit. Re-entering with
     * that same id is safe; replacing it with a different session is forbidden. */
    fun session(id: String, session: Int, rollback: Boolean = false): UpdatePending = locked { root ->
        require(session >= 0)
        val old = requirePending(root, id)
        val next = if (rollback) {
            require(old.phase == "ROLLING_BACK" && (old.rollback_session_id == null || old.rollback_session_id == session))
            old.copy(rollback_session_id = session)
        } else {
            require(old.phase in setOf("PREPARED", "INSTALLING") && (old.session_id == null || old.session_id == session))
            old.copy(phase = "INSTALLING", session_id = session)
        }
        write(root, next); next
    }

    /** Called before the MAIN process loads the runtime. Auxiliary receipt
     * processes must not call it. A previous result makes interrupted cleanup
     * idempotent and prevents an already healthy version being rolled back. */
    fun startup(running: Running, now: UpdateInstallTime, trialId: String): Startup = locked { root ->
        val old = pending(root)
        val recent = history(root)
        val completed = recent.result
        if (old == null) {
            // A crash after pending unlink but before backup unlink leaves an
            // orphan. A terminal result allows that last cleanup to finish.
            if (completed != null) cleanup(root)
            return@locked Startup(Action.NONE, clearRecovery = completed?.takeIf {
                it.kind == "updated" && it.needs_recovery_clear && it.code == running.code && it.version == running.version
            }?.transaction_id)
        }
        if (completed?.transaction_id == old.transaction_id && completed.kind in setOf("updated", "rolled_back", "install_failed", "rollback_failed")) {
            cleanup(root)
            return@locked Startup(Action.COMPLETED, clearRecovery = completed.takeIf {
                it.kind == "updated" && it.needs_recovery_clear && running.matches(old.to)
            }?.transaction_id)
        }
        if (running.matches(old.from)) {
            if (old.phase in setOf("TRIAL", "ROLLING_BACK")) {
                failedVersion(root, old, "crash_loop", true)
                result(root, old, "rolled_back", now, "crash_loop")
                cleanup(root)
                return@locked Startup(Action.ROLLED_BACK)
            }
            return@locked Startup(Action.WAITING, old)
        }
        if (!running.matches(old.to)) return@locked Startup(Action.MISMATCH, old)
        if (old.phase == "ROLLING_BACK") return@locked Startup(Action.ROLLBACK_PENDING, old)
        val attempts = (old.attempts + 1).coerceAtMost(100)
        if (attempts > old.max_attempts) {
            // A crash between these writes cannot double count this transaction.
            failedVersion(root, old, "crash_loop", true)
            val next = old.copy(phase = "ROLLING_BACK", attempts = attempts, trial_id = null, trial_started = null)
            write(root, next); Startup(Action.ROLLBACK, next)
        } else {
            val next = old.copy(phase = "TRIAL", attempts = attempts, trial_id = trialId, trial_started = now)
            write(root, next); Startup(Action.TRIAL, next)
        }
    }

    fun healthy(current: UpdateApkIdentity, proof: UpdateHealthGate.Proof): Boolean = locked { root ->
        val old = pending(root) ?: return@locked false
        if (old.transaction_id != proof.transactionId || old.trial_id != proof.trialId || old.phase != "TRIAL" || old.to != current) return@locked false
        val start = old.trial_started ?: return@locked false
        if (proof.ready.boot != start.boot || proof.now.boot != start.boot || proof.ready.elapsed < start.elapsed ||
            proof.now.elapsed < proof.ready.elapsed || proof.now.elapsed - proof.ready.elapsed < old.health_ms) return@locked false
        result(root, old, "updated", proof.now)
        cleanup(root)
        true
    }

    /** Commit/permission failure for a transaction known not to have installed.
     * The platform adapter must first reconcile the real package/session. */
    fun fail(id: String, reason: String, now: UpdateInstallTime, rollback: Boolean = false): Boolean = locked { root ->
        val old = pending(root) ?: return@locked false
        if (old.transaction_id != id) return@locked false
        if (!rollback) require(old.phase in setOf("PREPARED", "INSTALLING"))
        else require(old.phase == "ROLLING_BACK")
        failedVersion(root, old, reason, rollback)
        result(root, old, if (rollback) "rollback_failed" else "install_failed", now, reason)
        cleanup(root); true
    }

    /** The adapter has abandoned the known unsubmitted session. A user starting
     * media or leaving during preparation is not a failure of this version. */
    fun cancelUnsubmitted(id: String): Boolean = locked { root ->
        val old = pending(root) ?: return@locked false
        if (old.transaction_id != id) return@locked false
        require(old.phase in setOf("PREPARED", "INSTALLING"))
        cleanup(root); true
    }

    /** The native recovery clear and its authorization share install.lock with
     * isolation. An old healthy result must not clear a newer incident between
     * checking its fence and committing the recovery preferences. */
    fun acknowledgeRecovery(id: String, clearRecovery: () -> Boolean = { true }): Boolean = locked { root ->
        val old = history(root)
        val result = old.result
        if (result?.transaction_id == id && result.kind == "updated" && result.needs_recovery_clear && UpdateRepairFiles.allowsClear(root, id)) {
            if (!clearRecovery()) return@locked false
            // Keep needs_recovery_clear true until the repair fence is gone.
            // If either fsync/unlink fails, the next process can retry this exact ACK.
            UpdateRepairFiles.acknowledged(root, id)
            writeHistory(root, old.copy(result = result.copy(needs_recovery_clear = false)))
            true
        } else false
    }

    fun previous(): File = locked { UpdateFiles.safe(it, "previous.apk") }

    private fun pending(root: File): UpdatePending? = decode(root, "pending.json", MAX_BYTES) {
        UpdateModels.readUpdatePending(UpdateJson.read(it, MAX_BYTES)).also(::validate)
    }
    private fun history(root: File): UpdateInstallHistory = decode(root, "install-history.json", MAX_BYTES) {
        UpdateModels.readUpdateInstallHistory(UpdateJson.read(it, MAX_BYTES)).also { value ->
            require(value.failures.map { it.code }.toSet().size == value.failures.size)
            value.failures.forEach(::validateFailure)
            value.result?.let { result ->
                require(UpdateCatalog.versionCode(result.version) == result.code)
                require((result.kind == "updated") == (result.reason == null))
                require(!result.needs_recovery_clear || result.kind == "updated")
            }
        }
    } ?: UpdateInstallHistory(1, emptyList())

    private fun failure(root: File, code: Int): UpdateInstallFailure? = decode(root, "failure-$code.json", FAILURE_BYTES) {
        UpdateModels.readUpdateFailureDocument(UpdateJson.read(it, FAILURE_BYTES)).entry.also { value ->
            require(value.code == code); validateFailure(value)
        }
    } ?: history(root).failures.firstOrNull { it.code == code }

    private fun failedVersion(root: File, pending: UpdatePending, reason: String, block: Boolean) {
        val old = failure(root, pending.to.code)
        val count = if (old?.last_transaction == pending.transaction_id) old.count else ((old?.count ?: 0) + 1).coerceAtMost(100)
        val next = UpdateInstallFailure(pending.to.code, pending.to.version, count,
            old?.blocked == true || block, pending.transaction_id, reason)
        val document = UpdateFailureDocument(1, next)
        UpdateFiles.write(root, "failure-${next.code}.json", UpdateModels.writeUpdateFailureDocument(document).toString().toByteArray(), FAILURE_BYTES)
        val recent = history(root)
        writeHistory(root, recent.copy(failures = (recent.failures.filter { it.code != next.code } + next).takeLast(64)))
    }

    private fun result(root: File, value: UpdatePending, kind: String, now: UpdateInstallTime, reason: String? = null) {
        val next = UpdateInstallResult(value.transaction_id, kind, value.to.code, value.to.version, now, value.notice_ms,
            value.notes, reason = reason, needs_recovery_clear = kind == "updated")
        writeHistory(root, history(root).copy(result = next))
    }
    private fun writeHistory(root: File, value: UpdateInstallHistory) = UpdateFiles.write(root, "install-history.json",
        UpdateModels.writeUpdateInstallHistory(value).toString().toByteArray(Charsets.UTF_8), MAX_BYTES)
    private fun write(root: File, value: UpdatePending) {
        validate(value)
        UpdateFiles.write(root, "pending.json", UpdateModels.writeUpdatePending(value).toString().toByteArray(Charsets.UTF_8), MAX_BYTES)
    }
    private fun requirePending(root: File, id: String) = requireNotNull(pending(root)).also { require(it.transaction_id == id) }
    private fun cleanup(root: File) {
        UpdateFiles.delete(root, "pending.json")
        UpdateFiles.delete(root, "previous.apk")
    }
    private fun <T> decode(root: File, name: String, max: Int, read: (ByteArray) -> T): T? {
        return try { UpdateFiles.read(root, name, max)?.let(read) } catch (error: Exception) { throw Corrupt(error) }
    }

    private fun copyBackup(root: File, source: File, expected: UpdateApkIdentity) {
        require(source.isFile && !Files.isSymbolicLink(source.toPath()) && source.length() == expected.size)
        val target = UpdateFiles.safe(root, "previous.apk")
        val temporary = File.createTempFile("previous-", ".tmp", root)
        try {
            val digest = MessageDigest.getInstance("SHA-256"); var size = 0L
            FileOutputStream(temporary).use { output ->
                source.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        val count = input.read(buffer); if (count < 0) break
                        size += count; require(size <= expected.size)
                        digest.update(buffer, 0, count); output.write(buffer, 0, count)
                    }
                }
                require(size == expected.size && digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == expected.sha256) { "Installed APK changed while backing up" }
                output.fd.sync()
            }
            if (!temporary.renameTo(target)) throw IOException("Cannot commit previous APK")
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } finally { temporary.delete() }
    }

    private fun validate(value: UpdatePending) {
        UpdateModels.writeUpdatePending(value)
        require(value.from.code < value.to.code && value.from.signer == value.to.signer)
        require(UpdateCatalog.versionCode(value.from.version) == value.from.code && UpdateCatalog.versionCode(value.to.version) == value.to.code)
        if (value.phase == "TRIAL") require(value.trial_id != null && value.trial_started != null)
        else require(value.trial_id == null && value.trial_started == null)
        if (value.phase == "PREPARED") require(value.attempts == 0 && value.session_id == null && value.rollback_session_id == null)
        if (value.phase != "ROLLING_BACK") require(value.rollback_session_id == null)
        if (value.phase == "TRIAL") require(value.attempts in 1..value.max_attempts)
        if (value.phase == "ROLLING_BACK") require(value.attempts > value.max_attempts)
    }
    private fun validateFailure(value: UpdateInstallFailure) { require(UpdateCatalog.versionCode(value.version) == value.code) }

    companion object {
        const val MAX_BYTES = 256 * 1024
        private const val FAILURE_BYTES = 4096
        private val locks = ConcurrentHashMap<String, Any>()

        fun recent(value: UpdateInstallResult?, now: UpdateInstallTime): UpdateInstallResult? {
            return value?.takeIf { noticeRemaining(it.recorded, it.notice_ms, now) != null }
        }

        fun noticeRemaining(recorded: UpdateInstallTime, duration: Long, now: UpdateInstallTime): Long? {
            val age = when {
                now.boot != null && now.boot == recorded.boot && now.elapsed >= recorded.elapsed -> now.elapsed - recorded.elapsed
                now.wall != null && recorded.wall != null && now.wall >= recorded.wall -> now.wall - recorded.wall
                else -> return null
            }
            return (duration - age).takeIf { it > 0 }
        }
    }
}
