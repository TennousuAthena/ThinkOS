package moe.kiwi.thinkos.update

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.kiwi.thinkos.BuildConfig
import java.io.File

/** Native update UI only; no tos.* mutation API or data from a damaged transaction. */
internal data class UpdateRepairUi(val stage: Stage, val reason: String? = null) {
    enum class Stage { NONE, NEEDED, CHECKING, READY, BLOCKED, DONE, FAILED }
}

internal object UpdateRepairPolicy {
    fun root(presence: UpdateRepairPresence.State, moduleJournalPresent: Boolean, transaction: Boolean?): String {
        return when (presence) {
            UpdateRepairPresence.State.ABSENT -> {
                // A local root intent with no daemon may still be recovered later by its external guard.
                check(!moduleJournalPresent) { "root_unknown" }; "absent"
            }
            UpdateRepairPresence.State.PRESENT -> { check(transaction == false) { if (transaction == true) "root_busy" else "root_unknown" }; "idle" }
            UpdateRepairPresence.State.UNKNOWN -> error("root_unknown")
        }
    }
    fun sessions(count: Int) { check(count == 0) { "platform_busy" } }
    fun executing(installed: UpdateApkIdentity, version: String, code: Int) {
        check(installed.version == version && installed.code == code) { "package_changed" }
    }
    fun source(executingPath: String, installedPath: String) {
        check(executingPath == installedPath) { "package_changed" }
    }
}

internal class UpdateRepairRuntime(private val context: Context, private val records: UpdateInstallStore) {
    private val mutable = MutableStateFlow(UpdateRepairUi(UpdateRepairUi.Stage.NONE))
    val state: StateFlow<UpdateRepairUi> = mutable
    private val modules = ModuleUpdateStore(records.directory)
    private data class Candidate(val snapshot: UpdateRepairFiles.Snapshot, val current: UpdateApkIdentity)
    private var candidate: Candidate? = null

    suspend fun inspect() {
        candidate = null
        mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.CHECKING)
        try {
            if (listOf("pending.json", "install-history.json", UpdateRepairFiles.MARKER).none {
                    File(records.directory, it).exists()
                }) { mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.NONE); return }
            val snapshot = UpdateIo.run { records.repairSnapshot() }
            if (!snapshot.needed) { mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.NONE); return }
            if (snapshot.corruptHistory) { mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.BLOCKED, "history"); return }
            val proof = UpdateIo.run { modules.withRepairFence { present -> evidence(present) } }
            // The final action repeats every proof under both journals' locks.
            candidate = Candidate(snapshot, proof.current)
            mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.READY)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.BLOCKED, reason(error)) }
    }

    suspend fun isolate(): Boolean {
        val expected = candidate ?: return false
        candidate = null
        mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.CHECKING)
        val task = currentCoroutineContext()
        return try {
            UpdateIo.run {
                // Consistent lock order: module journal, then APK install journal.
                modules.withRepairFence { present ->
                    records.isolateCorrupt(expected.snapshot, { evidence(present).also {
                        check(it.current == expected.current) { "package_changed" }
                    } }, { task.ensureActive() })
                }
            }
            mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.DONE)
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { mutable.value = UpdateRepairUi(UpdateRepairUi.Stage.FAILED, reason(error)); false }
    }

    private fun evidence(moduleJournalPresent: Boolean): UpdateRepairFiles.Evidence {
        val pm = context.packageManager
        val source = File(pm.getApplicationInfo(context.packageName, 0).sourceDir)
        // PackageManager may already describe a same-code replacement while
        // this old LoadedApk/ART process is still executing from its old path.
        val executing = File(context.packageCodePath).canonicalPath
        UpdateRepairPolicy.source(executing, source.canonicalPath)
        val verifier = UpdatePackageVerifier.forContext(context)
        val first = verifier.inspectInstalledForRecovery(source)
        UpdateRepairPolicy.executing(first.identity, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        // Never abandon, commit or retry any session found here, even if it is
        // unsealed or its package/label is null. Damaged JSON cannot identify it.
        UpdateRepairPolicy.sessions(pm.packageInstaller.mySessions.size)
        val presence = UpdateRepairPresence.read()
        val status = if (presence == UpdateRepairPresence.State.PRESENT) UpdaterClient().statusForRepair().getOrNull() else null
        val root = UpdateRepairPolicy.root(presence, moduleJournalPresent, status?.transaction)
        // PM can finish replacing this app without having killed its old ART process yet.
        val latestSource = File(pm.getApplicationInfo(context.packageName, 0).sourceDir)
        UpdateRepairPolicy.source(executing, latestSource.canonicalPath)
        check(latestSource.canonicalFile == first.file) { "package_changed" }
        val latest = verifier.inspectInstalledForRecovery(latestSource)
        check(latest.identity == first.identity) { "package_changed" }
        UpdateRepairPolicy.executing(latest.identity, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        UpdateRepairPolicy.sessions(pm.packageInstaller.mySessions.size)
        return UpdateRepairFiles.Evidence(latest.identity, root, UpdateInstallContext.now(context))
    }

    private fun reason(error: Exception): String = when {
        error is UpdateRepairFiles.Changed -> "record_changed"
        error.message in setOf("root_busy", "root_unknown", "platform_busy", "package_changed") -> requireNotNull(error.message)
        else -> "records"
    }
}
