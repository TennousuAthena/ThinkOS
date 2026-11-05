package moe.kiwi.thinkos

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.kiwi.thinkos.update.UpdateApkIdentity
import moe.kiwi.thinkos.update.UpdateCatalog
import moe.kiwi.thinkos.update.UpdateIo
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Installed-candidate compatibility only. No Main/Runtime, real arm, marker,
 * transaction, installer, crash worker, network or configurable work/root path.
 * Instrumentation binds the ordinary target Application before this phase;
 * the owner must choose a quiescent spare without an existing native trial. */
internal object DebugCommitFaultCapabilityProbe {
    suspend fun run(instrumentation: Instrumentation, args: Bundle): JSONObject {
        require(args.keySet() == setOf("phase", "run_id", "physical_serial"))
        val nonce = requireNotNull(args.getString("run_id"))
        require(nonce.matches(Regex("[a-f0-9]{32}")))
        val expected = requireNotNull(args.getString("physical_serial"))
        val actual = DebugCommitFaultProbe.physical(instrumentation)
        require(actual == expected) // physical() rejects HUA and unknown first; debug helper repeats both checks.
        val context = instrumentation.targetContext
        require(context.packageName == "moe.kiwi.thinkos" && Process.myUid() == context.applicationInfo.uid &&
            context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        return withContext(Dispatchers.IO) {
            val audio = context.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager
            require((1..5).all { audio.getStreamVolume(it) == 0 })
            fun noLiveTransaction() {
                val data = File(context.applicationInfo.dataDir)
                for (relative in listOf("files/update/pending.json", "files/update/pending.json.bak",
                        "files/update/module-pending.json", "files/update/module-pending.json.bak",
                        "cache/update-guard-crash.json")) {
                    require(!Files.exists(File(data, relative).toPath(), LinkOption.NOFOLLOW_LINKS)) {
                        "Existing native transaction/fault evidence must be preserved"
                    }
                }
            }
            noLiveTransaction()
            val executing = File(context.packageCodePath).canonicalFile
            fun sameExecutingPath() {
                val current = context.packageManager.getApplicationInfo(context.packageName, 0)
                require(File(context.packageCodePath).canonicalFile == executing &&
                    File(current.sourceDir).canonicalFile == executing &&
                    DebugUpdateFault::class.java.classLoader === context.classLoader) {
                    "Executing target path/classloader changed"
                }
            }
            sameExecutingPath()
            val roots = ProductionRootsProbe.read(context)
            require(roots.getBoolean("complete")) { "Actual production loaders must accept installed owner roots" }
            val apk = roots.getJSONObject("apk")
            require(apk.getBoolean("debuggable"))
            val installed = UpdateApkIdentity(apk.getString("version"), apk.getInt("code"), apk.getString("sha256"),
                apk.getLong("size"), apk.getString("signer"))
            // Calls code in the installed target's DebugUpdateFault/DebugCommitArm,
            // never test-local stand-ins, a reflected class-name presence or a flag.
            val vectors = UpdateIo.run {
                DebugUpdateFault.readOnlyCompatibility(context, nonce, actual, expected, installed)
            }
            require(vectors.getBoolean("complete"))
            val after = ProductionRootsProbe.read(context)
            require(after.getBoolean("complete") && after.getJSONObject("apk").toString() == apk.toString() &&
                after.getJSONObject("assets").toString() == roots.getJSONObject("assets").toString())
            sameExecutingPath()
            noLiveTransaction()
            require((1..5).all { audio.getStreamVolume(it) == 0 })
            JSONObject().put("schema", 1).put("complete", true).put("scope", "policy_roundtrip_only")
                .put("run_id", nonce).put("spare_identity_sha256", UpdateCatalog.sha256(actual.toByteArray(Charsets.UTF_8)))
                .put("apk", apk).put("roots", roots).put("vectors", vectors)
                .put("apk_stable_during_probe", true).put("executing_apk_identity_verified", vectors.getBoolean("executing_apk_identity_verified"))
                .put("helper_target_classloader_equal", vectors.getBoolean("helper_target_classloader_equal")).put("probe_starts_main", false).put("probe_starts_runtime", false)
                .put("probe_calls_installer", false).put("probe_writes_files", false).put("global_arm_changed", false)
                .put("ordinary_application_bound_before_phase", true)
                .put("runtime_crash_verified", false).put("commit_hook_executed", false).also {
                    require(it.toString().toByteArray(Charsets.UTF_8).size <= 32768)
                }
        }
    }
}
