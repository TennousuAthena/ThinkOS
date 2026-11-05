package moe.kiwi.thinkos.update

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.DebugUpdateFault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** APK-only platform adapter. The journal, not receipt delivery, owns the
 * transaction. A sealed session is never replaced or committed a second time.
 * Neither this class nor the application invokes a root shell. */
internal class UpdateInstaller(
    private val context: Context,
    private val store: UpdateInstallStore,
    private val clock: () -> UpdateInstallTime,
) {
    enum class Outcome { SUBMITTED, CANCELLED, FAILED, CHANGED }
    private val platform get() = context.packageManager.packageInstaller
    private val verifier = UpdatePackageVerifier.forContext(context)

    fun silent(): Boolean = context.getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(context.packageName)

    /** The controller has already checked the original tap credit. Admission is
     * checked again after expensive verification and immediately before commit,
     * without expiring a legitimate tap while the installed APK is backed up. */
    suspend fun install(catalog: UpdateCatalog, source: File, automatic: Boolean, limits: UpdateInstallStore.Limits,
                        admitted: suspend () -> Boolean): Outcome {
        if (automatic && !silent() || !withContext(Dispatchers.Main.immediate) { admitted() }) return Outcome.CANCELLED
        val verified = verifier.verify(catalog, source)
        val installed = verifier.inspectInstalled(installedFile())
        if (!withContext(Dispatchers.Main.immediate) { admitted() }) return Outcome.CANCELLED
        val pending = UpdateIo.run { store.prepare(catalog, verified, installed.identity, installed.file,
            if (automatic) "automatic" else "manual", limits, clock(), newId()) }
        return try {
            val session = stage(pending, verified.file, pending.to, rollback = false)
            if (!withContext(Dispatchers.Main.immediate) { admitted() }) {
                cancelUnsubmitted(pending, session); Outcome.CANCELLED
            } else {
                val outcome = submit(pending, session, rollback = false)
                if (outcome == Outcome.SUBMITTED) awaitInstall(pending) else outcome
            }
        } catch (error: Throwable) {
            // Cancellation or a failed binder call can race commit. Reconcile
            // before changing the journal; an unknown/sealed session stays live.
            withContext(NonCancellable) { reconcileUnsubmitted(pending, rollback = false, cancelled = error is CancellationException) }
            throw error
        }
    }

    /** Called before the runtime for a fourth unclean start. Verify the current
     * target and exact saved predecessor, then use only the saved rollback id. */
    suspend fun rollback(pending: UpdatePending): Outcome {
        require(pending.phase == "ROLLING_BACK")
        return try {
            verifier.verifyInstalled(pending.to, installedFile())
            val previous = verifier.verifyPrevious(pending.from, UpdateIo.run { store.previous() })
            if (!silent()) {
                fail(pending, rollback = true, reason = "rollback_failed"); return Outcome.FAILED
            }
            val existing = pending.rollback_session_id
            if (existing != null) {
                val info = UpdateIo.run { ownSession(existing) }
                if (info == null) {
                    fail(pending, rollback = true, reason = "rollback_failed"); Outcome.FAILED
                } else if (info.isSealed) Outcome.SUBMITTED
                else {
                    // The id was recorded only after fsync. Check those actual
                    // staged bytes too before committing an interrupted stage.
                    UpdateIo.run { checkStaged(existing, pending.from) }
                    submit(pending, existing, rollback = true)
                }
            } else submit(pending, stage(pending, previous.file, pending.from, rollback = true), rollback = true)
        } catch (error: Exception) {
            android.util.Log.e("ThinkOS", "Update rollback did not complete", error)
            reconcileUnsubmitted(pending, rollback = true)
        }
    }

    /** The old version is still running. A sealed session belongs to Android;
     * wait for it, even if the callback is missing. No timeout creates a retry. */
    suspend fun reconcile(pending: UpdatePending): Outcome {
        val rollback = pending.phase == "ROLLING_BACK"
        val expected = if (rollback) pending.to else pending.from
        verifier.verifyInstalled(expected, installedFile())
        val id = if (rollback) pending.rollback_session_id else pending.session_id
        if (id == null) {
            if (rollback) return rollback(pending)
            UpdateIo.run { abandonOrphanStages(pending); store.cancelUnsubmitted(pending.transaction_id) }
            return Outcome.CANCELLED
        }
        val info = UpdateIo.run { ownSession(id) }
        if (info?.isSealed == true) return Outcome.SUBMITTED
        if (rollback && info != null) return rollback(pending)
        if (info != null) {
            cancelUnsubmitted(pending, id)
            return Outcome.CANCELLED
        }
        fail(pending, rollback, "interrupted")
        return Outcome.FAILED
    }

    /** Terminal platform status is supplementary evidence. A success receipt
     * does not mark healthy; only the new MAIN process can earn that result. */
    suspend fun receipt(transaction: String, id: Int, status: Int): Boolean {
        val pending = UpdateIo.run { store.snapshot().pending } ?: return false
        if (pending.transaction_id != transaction) return false
        val rollback = pending.phase == "ROLLING_BACK"
        if (id != (if (rollback) pending.rollback_session_id else pending.session_id)) return false
        if (status == PackageInstaller.STATUS_SUCCESS || status == PackageInstaller.STATUS_PENDING_USER_ACTION) return true
        require(status in PackageInstaller.STATUS_FAILURE..PackageInstaller.STATUS_FAILURE_INCOMPATIBLE)
        if (!rollback && pending.phase !in setOf("PREPARED", "INSTALLING")) return false
        // Check native package + signer + full content. A late failure callback
        // must never cancel a package that is actually installed and on trial.
        verifier.verifyInstalled(if (rollback) pending.to else pending.from, installedFile())
        UpdateIo.run {
            val info = ownSession(id)
            if (info != null) platform.abandonSession(id)
            store.fail(transaction, if (rollback) "rollback_failed" else "install", clock(), rollback)
        }
        return true
    }

    suspend fun rejectConfirmation(pending: UpdatePending, id: Int) {
        require(pending.session_id == id || pending.rollback_session_id == id)
        UpdateIo.run { ownSession(id)?.let { platform.abandonSession(id) } }
        fail(pending, pending.phase == "ROLLING_BACK", "install")
    }

    private suspend fun stage(pending: UpdatePending, file: File, identity: UpdateApkIdentity, rollback: Boolean): Int {
        val job = currentCoroutineContext()
        return UpdateIo.run {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName); setSize(identity.size); setAppLabel(label(pending))
                if (rollback) {
                    // API27 on this device. Do not gate on Build.TYPE: the
                    // actual app reports "user" although owner downgrade works.
                    javaClass.getMethod("setAllowDowngrade", Boolean::class.javaPrimitiveType).invoke(this, true)
                    require(javaClass.getField("installFlags").getInt(this) and 0x80 != 0)
                }
            }
            val id = platform.createSession(params)
            var recorded = false
            try {
                platform.openSession(id).use { session ->
                    session.openWrite("base.apk", 0, identity.size).use { output ->
                        val digest = MessageDigest.getInstance("SHA-256"); var count = 0L
                        file.inputStream().use { input ->
                            val buffer = ByteArray(65536)
                            while (true) {
                                job.ensureActive()
                                val length = input.read(buffer); if (length < 0) break
                                count += length; require(count <= identity.size)
                                digest.update(buffer, 0, length); output.write(buffer, 0, length)
                            }
                        }
                        require(count == identity.size && hex(digest.digest()) == identity.sha256) { "APK changed while staging" }
                        session.fsync(output)
                    }
                }
                store.session(pending.transaction_id, id, rollback); recorded = true
                id
            } finally { if (!recorded) platform.abandonSession(id) }
        }
    }

    private suspend fun submit(pending: UpdatePending, id: Int, rollback: Boolean): Outcome = UpdateIo.run {
        val info = requireNotNull(ownSession(id))
        if (info.isSealed) return@run Outcome.SUBMITTED
        val latest = requireNotNull(store.snapshot().pending)
        require(latest.transaction_id == pending.transaction_id &&
            (if (rollback) latest.rollback_session_id else latest.session_id) == id)
        val receipt = Intent(context, UpdateResultReceiver::class.java).setAction(ACTION)
            .setData(Uri.parse("thinkos-update://receipt/${pending.transaction_id}/$id"))
            .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
        // Mutable on API27: PackageInstaller fills EXTRA_STATUS. The component
        // is private and the fixed data binds a receipt to this transaction/id.
        val sender = PendingIntent.getBroadcast(context, id, receipt, PendingIntent.FLAG_UPDATE_CURRENT)
        // Debug OTA-5 only: an existing RAM lease can bind this exact staged,
        // admitted transaction. This grants no install permission and release
        // compiles out the branch; rollback never consumes or creates a marker.
        if (BuildConfig.DEBUG && !rollback) DebugUpdateFault.beforeCommit(context, latest, id)
        platform.openSession(id).use { it.commit(sender.intentSender) }
        android.util.Log.i("ThinkOS", "Update ${if (rollback) "rollback" else "install"} submitted session=$id")
        Outcome.SUBMITTED
    }

    private suspend fun cancelUnsubmitted(pending: UpdatePending, id: Int) = UpdateIo.run {
        val info = ownSession(id)
        require(info?.isSealed != true) { "A submitted install cannot be cancelled as an unsubmitted stage" }
        if (info != null) platform.abandonSession(id)
        store.cancelUnsubmitted(pending.transaction_id)
    }

    /** Success normally kills this process. While it is still the old version,
     * watch both durable receipts and platform session completion. Register
     * before the initial read so even a very quick failure cannot be missed. */
    private suspend fun awaitInstall(pending: UpdatePending): Outcome {
        callbackFlow {
            @Suppress("DEPRECATION")
            val observer = object : FileObserver(store.directory.path, MOVED_TO or DELETE) {
                override fun onEvent(event: Int, path: String?) {
                    if (path == "pending.json" || path == "install-history.json") trySend(Unit)
                }
            }
            val callback = object : PackageInstaller.SessionCallback() {
                override fun onCreated(id: Int) = Unit
                override fun onBadgingChanged(id: Int) = Unit
                override fun onActiveChanged(id: Int, active: Boolean) = Unit
                override fun onProgressChanged(id: Int, progress: Float) = Unit
                override fun onFinished(id: Int, success: Boolean) { trySend(Unit) }
            }
            observer.startWatching()
            platform.registerSessionCallback(callback, Handler(Looper.getMainLooper()))
            trySend(Unit)
            awaitClose { observer.stopWatching(); platform.unregisterSessionCallback(callback) }
        }.first {
            val current = UpdateIo.run { store.snapshot().pending }
            if (current?.transaction_id != pending.transaction_id) true
            else {
                reconcile(current)
                UpdateIo.run { store.snapshot().pending?.transaction_id != pending.transaction_id }
            }
        }
        return Outcome.FAILED
    }

    private suspend fun reconcileUnsubmitted(pending: UpdatePending, rollback: Boolean, cancelled: Boolean = false): Outcome {
        // PackageManager may publish the new package before killing this old
        // process. In that window a missing session or callback is success, not
        // evidence that the old version survived. Preserve the new guard record.
        try { verifier.verifyInstalled(if (rollback) pending.to else pending.from, installedFile()) }
        catch (_: Exception) { return Outcome.CHANGED }
        return UpdateIo.run {
        val latest = store.snapshot().pending
        if (latest?.transaction_id != pending.transaction_id) return@run Outcome.CHANGED
        val id = if (rollback) latest.rollback_session_id else latest.session_id
        val info = id?.let(::ownSession)
        if (info?.isSealed == true) return@run Outcome.SUBMITTED
        if (info != null) platform.abandonSession(id!!)
        abandonOrphanStages(latest)
        if (cancelled && !rollback) {
            store.cancelUnsubmitted(latest.transaction_id)
            return@run Outcome.CANCELLED
        }
        store.fail(latest.transaction_id, if (rollback) "rollback_failed" else "install", clock(), rollback)
        Outcome.FAILED
        }
    }

    private suspend fun fail(pending: UpdatePending, rollback: Boolean, reason: String) = UpdateIo.run {
        store.fail(pending.transaction_id, reason, clock(), rollback)
    }

    private fun ownSession(id: Int): PackageInstaller.SessionInfo? = platform.getSessionInfo(id)?.also {
        require(it.installerPackageName == context.packageName && it.appPackageName == context.packageName)
    }

    private fun abandonOrphanStages(pending: UpdatePending) {
        platform.mySessions.filter { !it.isSealed && it.appLabel?.toString() == label(pending) }.forEach { platform.abandonSession(it.sessionId) }
    }

    private fun checkStaged(id: Int, expected: UpdateApkIdentity) {
        platform.openSession(id).use { session ->
            require(session.names.toSet() == setOf("base.apk"))
            val hash = MessageDigest.getInstance("SHA-256"); var size = 0L
            session.openRead("base.apk").use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    size += count; require(size <= expected.size); hash.update(buffer, 0, count)
                }
            }
            require(size == expected.size && hex(hash.digest()) == expected.sha256)
        }
    }

    private fun installedFile(): File = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)

    companion object {
        const val ACTION = "moe.kiwi.thinkos.UPDATE_RESULT"
        fun newId(): String = UUID.randomUUID().toString().replace("-", "")
        private fun label(pending: UpdatePending) = "ThinkOS ${pending.transaction_id}"
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
