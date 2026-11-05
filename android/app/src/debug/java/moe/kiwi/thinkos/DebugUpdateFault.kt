package moe.kiwi.thinkos

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.runBlocking
import moe.kiwi.thinkos.update.UpdateApkIdentity
import moe.kiwi.thinkos.update.UpdateFiles
import moe.kiwi.thinkos.update.UpdateInstallContext
import moe.kiwi.thinkos.update.UpdatePackageVerifier
import moe.kiwi.thinkos.update.UpdatePending
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.atomic.AtomicBoolean

/** Test-APK-only arming API in debug builds. An unconsumed lease is RAM only;
 * startup ignores legacy/partial markers and never consumes an uncommitted arm.
 */
internal object DebugUpdateFault {
    private const val MARKER = "update-guard-crash.json"
    private val policy = DebugCommitArm()
    private val startupChecked = AtomicBoolean()

    private fun debugTarget(context: Context) {
        require(context.packageName == "moe.kiwi.thinkos" &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Debug production target required" }
    }

    fun arm(context: Context, nonce: String, physical: String, expectedPhysical: String,
            channel: String, seq: Long, digest: String, from: UpdateApkIdentity, to: UpdateApkIdentity,
            notes: Map<String, String>) {
        debugTarget(context)
        DebugCommitArm.requireSpare(physical, expectedPhysical)
        val target = UpdateFiles.safe(context.cacheDir.canonicalFile, MARKER)
        require(!Files.exists(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "Existing fault evidence must be inspected, not overwritten" }
        policy.arm(DebugCommitArm.Expected(nonce, physical, channel, seq, digest, from, to, notes, SystemClock.elapsedRealtime()), expectedPhysical)
    }

    /** Test APK may observe the installed debug APK's real v2 policy without
     * creating a production lease/marker or entering an install transaction. */
    fun readOnlyCompatibility(context: Context, nonce: String, physical: String,
                              expectedPhysical: String, installed: UpdateApkIdentity): org.json.JSONObject {
        debugTarget(context); DebugCommitArm.requireSpare(physical, expectedPhysical)
        check(android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
        require(installed.version == BuildConfig.VERSION_NAME && installed.code == BuildConfig.VERSION_CODE)
        require(DebugUpdateFault::class.java.classLoader === context.classLoader) { "Executing helper classloader differs" }
        val executing = File(context.packageCodePath)
        val canonical = executing.canonicalFile
        fun verifiedExecution() {
            val current = context.packageManager.getApplicationInfo(context.packageName, 0)
            require(File(context.packageCodePath).canonicalFile == canonical &&
                File(current.sourceDir).canonicalFile == canonical) { "Executing and installed APK paths differ" }
            // Full actual executing-byte identity, not only PM's same version/code.
            // The readonly phase calls this synchronous verifier on UpdateIo.
            val actual = UpdatePackageVerifier.forContext(context).inspectInstalledForRecovery(executing)
            require(actual.file == canonical && actual.identity == installed) { "Executing APK tuple differs" }
        }
        verifiedExecution()
        check(policy.isIdle()) { "An existing debug arm must be preserved" }
        val observation = DebugCommitArm.compatibility(installed, nonce, physical)
        verifiedExecution()
        check(policy.isIdle())
        return observation.put("executing_apk_identity_verified", true).put("helper_target_classloader_equal", true)
    }

    fun revoke(nonce: String) = policy.revoke(nonce)
    fun state(nonce: String): String = policy.state(nonce, SystemClock.elapsedRealtime())

    /** UpdateInstaller calls this on UpdateIo only after its last live admission
     * and unsealed-session check. The journal and staged tuple remain installer
     * authority. A write failure consumes the arm and propagates to its existing
     * reconciliation; it never triggers a retry or makes an ordinary startup bad.
     */
    fun beforeCommit(context: Context, pending: UpdatePending, session: Int) {
        policy.beforeCommit(pending, session, SystemClock.elapsedRealtime()) { proof ->
            debugTarget(context)
            val root = context.cacheDir.canonicalFile
            val target = UpdateFiles.safe(root, MARKER)
            val raw = DebugCommitArm.marker(proof)
            // Exclusive creation: neither an old marker nor a symlink is replaced.
            // A partial file cannot cause a fault: platform commit happens only
            // after this write/fsync succeeds and startup requires exact TRIAL.
            FileOutputStream(Os.open(target.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or
                OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 0x180)).use {
                it.write(raw); it.fd.sync()
            }
            FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    /** No APK hashing/socket/journal IO on Main. Only the deliberate debug fault
     * path does native installed-byte verification, on one low-priority thread.
     * The final durable snapshot is reread AFTER verification, so a retired or
     * rolled-back trial cannot be crashed by an old background result.
     */
    fun check(context: Context) {
        if (!startupChecked.compareAndSet(false, true)) return
        Thread({
            val requested = runCatching {
                debugTarget(context)
                val root = context.cacheDir.canonicalFile
                val path = UpdateFiles.safe(root, MARKER)
                if (!Files.isRegularFile(path.toPath(), LinkOption.NOFOLLOW_LINKS)) return@runCatching false
                val raw = UpdateFiles.read(root, MARKER, DebugCommitArm.MARKER_LIMIT) ?: return@runCatching false
                val marker = DebugCommitArm.parse(raw)
                val store = UpdateInstallContext.store(context)
                val first = store.snapshot().pending ?: return@runCatching false
                if (first.phase != "TRIAL" || first.to.code != BuildConfig.VERSION_CODE) return@runCatching false
                val source = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
                val installed = runBlocking { UpdatePackageVerifier.forContext(context).inspectInstalled(source) }
                DebugCommitArm.shouldCrash(marker, store.snapshot().pending, installed.identity)
            }.getOrDefault(false)
            if (requested) error("Deliberate debug update-trial startup crash")
        }, "ThinkOS-DebugUpdateFault").apply { isDaemon = true; priority = Thread.MIN_PRIORITY; start() }
    }
}
