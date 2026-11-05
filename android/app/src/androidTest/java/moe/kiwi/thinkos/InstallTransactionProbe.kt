package moe.kiwi.thinkos

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import kotlinx.coroutines.*
import moe.kiwi.thinkos.update.*
import moe.kiwi.thinkos.shell.CrashRecovery
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Native PackageManager, fsync/rename, app-UID staging and cancellation. The
 * suite never commits. The explicit submit phase installs a fresh worktree APK
 * and leaves all startup/health/rollback decisions to the production guard. */
internal object InstallTransactionProbe {
    private val limits = UpdateInstallStore.Limits(3, 600_000, 86_400_000, 3)
    private fun catalog(context: android.content.Context, file: File): UpdateCatalog {
        @Suppress("DEPRECATION")
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES))
        val fixture = UpdateFixture()
        return fixture.verified(fixture.catalog().apply {
            getJSONObject("release").put("version", archive.versionName).put("code", archive.versionCode)
                .put("signer", UpdateCatalog.sha256(archive.signatures!!.single().toByteArray()))
                .put("size", file.length()).put("sha256", UpdateCatalog.sha256(file.readBytes()))
                .put("notes", JSONObject().put("zh-Hans", "# 更新验证\n\n已完成安装。"))
        })
    }

    suspend fun suite(instrumentation: Instrumentation, directory: File, result: JSONObject,
                      test: suspend (String, suspend () -> Unit) -> Unit) {
        val context = instrumentation.targetContext
        val verifier = UpdatePackageVerifier.forContext(context)
        val source = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
        val installed = verifier.inspectInstalled(source)
        val candidate = File(directory, "packages/valid.apk")
        val selected = catalog(context, candidate)
        val root = File(directory, "install-transaction")
        val store = UpdateInstallStore(root)
        val installer = UpdateInstaller(context, store) { UpdateInstallContext.now(context) }
        val platform = context.packageManager.packageInstaller
        var session: Int? = null
        result.put("installed_identity", JSONObject().put("code", installed.identity.code).put("sha256", installed.identity.sha256)
            .put("signer", installed.identity.signer).put("size", installed.identity.size))
        test("native installed archive and exact content agree with the actual PackageManager") {
            check(verifier.verifyInstalled(installed.identity, source).identity == installed.identity)
            var refused = false
            try { verifier.verifyInstalled(installed.identity.copy(sha256 = "f".repeat(64)), source) } catch (_: IllegalArgumentException) { refused = true }
            check(refused)
        }
        test("denied admission creates no journal, backup or platform session") {
            val before = platform.mySessions.map { it.sessionId }.toSet()
            check(installer.install(selected, candidate, false, limits) { false } == UpdateInstaller.Outcome.CANCELLED)
            check(!root.exists() && platform.mySessions.map { it.sessionId }.toSet() == before)
        }
        test("app UID stages fsynced bytes only after a verified predecessor backup; final policy denial cancels without a version failure") {
            var calls = 0; var backup = false
            val outcome = installer.install(selected, candidate, false, limits) {
                calls++
                if (calls == 3) {
                    val pending = UpdateModels.readUpdatePending(UpdateJson.read(File(root, "pending.json").readBytes(), UpdateInstallStore.MAX_BYTES))
                    session = requireNotNull(pending.session_id)
                    backup = File(root, "previous.apk").length() == installed.identity.size && pending.from == installed.identity && pending.phase == "INSTALLING"
                    val info = requireNotNull(platform.getSessionInfo(session!!))
                    check(info.installerPackageName == context.packageName && !info.isSealed)
                    false
                } else true
            }
            check(outcome == UpdateInstaller.Outcome.CANCELLED && calls == 3 && backup)
            check(platform.getSessionInfo(requireNotNull(session)) == null)
            check(store.snapshot().pending == null && store.snapshot().history.failures.isEmpty() && !store.previous().exists())
            result.put("cancelled_session", session)
        }
        test("native durable trial simulation counts main starts, requests one rollback and preserves its code blacklist") {
            val verified = verifier.verify(selected, candidate)
            val pending = store.prepare(selected, verified, installed.identity, source, "manual", limits,
                UpdateInstallContext.now(context), UpdateInstaller.newId())
            val target = UpdateInstallStore.Running(pending.to.version, pending.to.code, pending.to.signer)
            repeat(3) { index ->
                val next = UpdateInstallStore(root).startup(target, UpdateInstallContext.now(context), UpdateInstaller.newId())
                check(next.action == UpdateInstallStore.Action.TRIAL && next.pending!!.attempts == index + 1)
            }
            check(store.startup(target, UpdateInstallContext.now(context), UpdateInstaller.newId()).action == UpdateInstallStore.Action.ROLLBACK)
            check(store.startup(target, UpdateInstallContext.now(context), UpdateInstaller.newId()).action == UpdateInstallStore.Action.ROLLBACK_PENDING)
            check(store.snapshot().pending!!.attempts == 4 && store.blocked(pending.to.code))
            check(store.startup(UpdateInstallContext.running(context), UpdateInstallContext.now(context), UpdateInstaller.newId()).action == UpdateInstallStore.Action.ROLLED_BACK)
            check(store.snapshot().history.failures.single().count == 1 && store.snapshot().pending == null)
            check(store.blocked(pending.to.code))
        }
        test("hard-blacklisted version cannot prepare another native installation") {
            val before = platform.mySessions.map { it.sessionId }.toSet()
            var refused = false
            try { installer.install(selected, candidate, false, limits) { true } } catch (_: IllegalArgumentException) { refused = true }
            check(refused && platform.mySessions.map { it.sessionId }.toSet() == before && store.snapshot().pending == null)
        }
        test("a healthy transaction clears safe mode once; a lost acknowledgement cannot clear a later unrelated safe mode") {
            val prefs = context.getSharedPreferences("recovery", android.content.Context.MODE_PRIVATE)
            val old = prefs.all.toMap()
            try {
                val id = UpdateInstaller.newId()
                check(CrashRecovery.enterSafeMode(context))
                check(CrashRecovery.clearForUpdate(context, id) && !CrashRecovery.isSafeMode(context))
                check(CrashRecovery.enterSafeMode(context))
                check(CrashRecovery.clearForUpdate(context, id) && CrashRecovery.isSafeMode(context))
                check(CrashRecovery.clearForUpdate(context, UpdateInstaller.newId()) && !CrashRecovery.isSafeMode(context))
            } finally {
                val editor = prefs.edit().clear()
                for ((key, value) in old) when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.map { it as String }.toSet())
                    else -> error("Unknown recovery preference type")
                }
                check(editor.commit())
            }
        }
        root.deleteRecursively()
    }

    @Suppress("DEPRECATION")
    suspend fun submit(instrumentation: Instrumentation, directory: File, token: String) {
        val context = instrumentation.targetContext
        val folder = File(directory, "install")
        val candidate = File(folder, "candidate.apk")
        val manifest = JSONObject(File(folder, "builds.json").readText())
        val source = manifest.getJSONObject("from"); val target = manifest.getJSONObject("to")
        // Historical component probe, NOT the OTA-5 source/UI path. New crash
        // execution is spare-only and uses the same commit hook as production.
        val physical = if (manifest.optBoolean("crash")) {
            DebugCommitFaultProbe.requireDebugCandidate(context, candidate)
            DebugCommitFaultProbe.physical(instrumentation) // rejects protected HUA and unknown before installer
        } else null
        val installed = UpdateInstallContext.running(context)
        check(installed.code == source.getInt("code") && target.getInt("code") > installed.code)
        check(candidate.length() == target.getLong("size") && UpdateCatalog.sha256(candidate.readBytes()) == target.getString("sha256"))
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val host = withContext(Dispatchers.Main) { (context.applicationContext as ThinkApplication).runtime }
        val store = UpdateInstallContext.store(context)
        check(store.snapshot().pending == null)
        val before = JSONObject().put("run_id", token).put("uid", Process.myUid()).put("pid", Process.myPid())
            .put("from", source).put("to", target)
        FileOutputStream(File(folder, "request.json")).use { it.write(before.toString(2).toByteArray()); it.fd.sync() }
        android.util.Log.i("ThinkOS", "PRODUCTION_UPDATE_PROBE from=${installed.code} to=${target.getInt("code")}")
        if (manifest.optBoolean("crash")) {
            check(host.system.updateInstallLimits.maxAttempts == 3 && host.system.updateInstallLimits.healthMillis == 600_000L)
        }
        val installer = UpdateInstaller(context, store) { UpdateInstallContext.now(context) }
        var admissions = 0
        val outcome = installer.install(catalog(context, candidate), candidate, automatic = false, host.system.updateInstallLimits) {
            admissions++
            if (admissions == 3 && manifest.optBoolean("crash")) {
                withContext(Dispatchers.IO) {
                    val pending = requireNotNull(store.snapshot().pending)
                    val spare = requireNotNull(physical)
                    DebugUpdateFault.arm(context, token, spare, spare, pending.channel, pending.seq, pending.catalog_digest,
                        pending.from, pending.to, pending.notes)
                }
            }
            !activity.isFinishing && host.updates.environment.value.allowsInstall()
        }
        error("Install returned without replacing the process: $outcome")
    }
}
