package moe.kiwi.thinkos.update

import android.app.Application
import android.content.pm.PackageInstaller
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import kotlinx.coroutines.*
import moe.kiwi.thinkos.shell.CrashRecovery
import java.io.File

/** Runs before any application/runtime initialization. Only the MAIN process
 * counts as a trial; a PackageInstaller callback in :update_result never does.
 * A rollback holds every runtime entry point until replacement or safe mode. */
internal class UpdateGuard private constructor(private val app: Application, val auxiliary: Boolean) {
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store by lazy { UpdateInstallContext.store(app) }
    private val installer by lazy { UpdateInstaller(app, store) { now() } }
    private val modules by lazy { ModuleUpdateRuntime.get(app) }
    private var gate: UpdateHealthGate? = null
    private var healthTask: Job? = null
    private var recoveryTask: Job? = null
    private var watcher: FileObserver? = null
    private var callback: PackageInstaller.SessionCallback? = null
    @Volatile private var holding = false
    val allowsRuntime: Boolean get() = !auxiliary && !holding

    private fun bootstrap() {
        if (auxiliary) return
        try { modules.bootstrap() }
        catch (error: Exception) {
            CrashRecovery.enterSafeMode(app)
            Log.e("ThinkOS", "Module update journal unavailable; retaining root recovery authority", error)
        }
        val directory = UpdateInstallContext.directory(app)
        if (!File(directory, "pending.json").exists() && !File(directory, "install-history.json").exists()) return
        try {
            // This small fsynced journal write is deliberately synchronous. If
            // native code crashes next, the attempt has already been counted.
            val startup = store.startup(UpdateInstallContext.running(app), now(), UpdateInstaller.newId())
            Log.i("ThinkOS", "Update guard ${startup.action} attempts=${startup.pending?.attempts ?: 0}")
            when (startup.action) {
                UpdateInstallStore.Action.TRIAL -> gate = UpdateHealthGate(requireNotNull(startup.pending)) { now() }
                UpdateInstallStore.Action.ROLLBACK, UpdateInstallStore.Action.ROLLBACK_PENDING -> {
                    holding = true
                    watch()
                    handler.post { recover() }
                }
                UpdateInstallStore.Action.WAITING -> {
                    watch()
                    handler.post { reconcileOld() }
                }
                UpdateInstallStore.Action.MISMATCH -> {
                    CrashRecovery.enterSafeMode(app)
                    Log.e("ThinkOS", "Installed package does not match the pending update; no installation attempted")
                }
                else -> Unit
            }
            startup.clearRecovery?.let { transaction ->
                store.acknowledgeRecovery(transaction) { CrashRecovery.clearForUpdate(app, transaction) }
            }
        } catch (error: Exception) {
            // Preserve the journal/backup for native recovery; malformed input
            // cannot trigger installation or prevent the repair UI from opening.
            CrashRecovery.enterSafeMode(app)
            Log.e("ThinkOS", "Update guard record unavailable; entering safe mode", error)
        }
    }

    fun frame() { modules.frame(); gate?.frame(); scheduleHealth() }
    fun catalog() { modules.catalog(); gate?.catalog(); scheduleHealth() }

    private fun scheduleHealth() {
        if (gate == null || healthTask?.isActive == true) return
        val remaining = gate?.remaining() ?: return
        Log.i("ThinkOS", "Update trial ready; health_ms=$remaining elapsed=${now().elapsed}")
        healthTask = scope.launch {
            delay(remaining)
            val proof = gate?.proof() ?: return@launch
            try {
                val pending = UpdateIo.run { store.snapshot().pending } ?: return@launch
                val source = File(app.packageManager.getApplicationInfo(app.packageName, 0).sourceDir)
                val current = UpdatePackageVerifier.forContext(app).verifyInstalled(pending.to, source)
                val healthy = UpdateIo.run { store.healthy(current.identity, proof) }
                if (healthy) {
                    val wasSafe = CrashRecovery.isSafeMode(app)
                    UpdateIo.run { store.acknowledgeRecovery(proof.transactionId) { CrashRecovery.clearForUpdate(app, proof.transactionId) } }
                    gate = null
                    Log.i("ThinkOS", "Update healthy: first frame, catalog and continuous trial completed")
                    // Plugins loaded while safe must be reconstructed after the
                    // healthy journal is committed, rather than kept disabled.
                    if (wasSafe && !CrashRecovery.isSafeMode(app)) restart()
                }
            } catch (error: Exception) {
                Log.e("ThinkOS", "Unable to commit update health; preserving trial", error)
            }
        }
    }

    private fun watch() {
        if (watcher != null) return
        @Suppress("DEPRECATION")
        watcher = object : FileObserver(UpdateInstallContext.directory(app).path, MOVED_TO or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                if (path == "pending.json" || path == "install-history.json") handler.post {
                    if (holding) recover() else reconcileOld()
                }
            }
        }.also { it.startWatching() }
        callback = object : PackageInstaller.SessionCallback() {
            override fun onCreated(id: Int) = Unit
            override fun onBadgingChanged(id: Int) = Unit
            override fun onActiveChanged(id: Int, active: Boolean) = Unit
            override fun onProgressChanged(id: Int, progress: Float) = Unit
            override fun onFinished(id: Int, success: Boolean) { if (holding) recover() else reconcileOld() }
        }.also { app.packageManager.packageInstaller.registerSessionCallback(it, handler) }
    }

    private fun recover() {
        if (!holding || recoveryTask?.isActive == true) return
        recoveryTask = scope.launch {
            try {
                val pending = UpdateIo.run { store.snapshot().pending }
                if (pending == null) { safeRestart(); return@launch }
                val outcome = installer.rollback(pending)
                if (outcome in setOf(UpdateInstaller.Outcome.FAILED, UpdateInstaller.Outcome.CANCELLED)) safeRestart()
            } catch (error: Exception) {
                Log.e("ThinkOS", "Unable to reconcile rollback; preserving backup", error)
                // If an IO failure prevents the terminal record from being
                // written, do not restart into another automatic rollback loop.
                val recorded = runCatching {
                    UpdateIo.run {
                        val pending = store.snapshot().pending
                        if (pending != null) store.fail(pending.transaction_id, "rollback_failed", now(), rollback = true)
                        store.snapshot().pending == null
                    }
                }.getOrDefault(false)
                if (recorded) safeRestart()
            }
        }
    }

    private fun reconcileOld() {
        if (holding || recoveryTask?.isActive == true) return
        recoveryTask = scope.launch {
            try {
                val pending = UpdateIo.run { store.snapshot().pending }
                if (pending == null || pending.phase !in setOf("PREPARED", "INSTALLING")) { stopWatching(); return@launch }
                installer.reconcile(pending)
            } catch (error: Exception) { Log.e("ThinkOS", "Unable to reconcile old-version install session", error) }
        }
    }

    private fun safeRestart() {
        if (!CrashRecovery.enterSafeMode(app)) { Log.e("ThinkOS", "Unable to persist recovery mode; retaining runtime hold"); return }
        restart()
    }

    private fun stopWatching() {
        watcher?.stopWatching(); watcher = null
        callback?.let { app.packageManager.packageInstaller.unregisterSessionCallback(it) }; callback = null
    }

    private fun restart() {
        CrashRecovery.schedule(app, 1_000)
        Process.killProcess(Process.myPid())
    }

    private fun now() = UpdateInstallContext.now(app)

    companion object {
        fun beforeApplication(app: Application): UpdateGuard {
            val name = File("/proc/self/cmdline").inputStream().use { input ->
                val bytes = ByteArray(256); val count = input.read(bytes)
                String(bytes, 0, count.coerceAtLeast(0), Charsets.UTF_8).substringBefore('\u0000')
            }
            return UpdateGuard(app, name != app.packageName).also { it.bootstrap() }
        }
    }
}
