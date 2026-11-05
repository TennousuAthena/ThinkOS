package moe.kiwi.thinkos

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.kiwi.thinkos.update.ModuleManifest
import moe.kiwi.thinkos.update.UpdateInstallContext
import moe.kiwi.thinkos.update.UpdateJson
import moe.kiwi.thinkos.update.UpdateKeys
import moe.kiwi.thinkos.update.UpdateModels
import moe.kiwi.thinkos.update.UpdatePackageVerifier
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.MessageDigest

/** Test APK only. Observe the installed target APK and actual production loaders.
 * No RuntimeHost/Activity, work override, test roots, network, update store,
 * installer, setting mutation or diagnostic file is created by this probe.
 * Instrumentation itself still binds the target's ordinary Application first.
 */
internal object ProductionRootsProbe {
    private const val LIMIT = 64 * 1024

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun bytes(input: InputStream, limit: Int): ByteArray = input.use {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = it.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
            if (count < 0) break
            require(count <= limit - output.size()) { "Asset exceeds its fixed bound" }
            output.write(buffer, 0, count)
        }
        require(output.size() > 0) { "Asset is empty" }
        output.toByteArray()
    }

    private class Asset(val report: JSONObject, val raw: ByteArray?)

    private fun asset(context: Context, name: String, limit: Int = LIMIT, optional: Boolean = false): Asset {
        return try {
            val raw = bytes(context.assets.open("update/$name"), limit)
            Asset(JSONObject().put("present", true).put("bytes", raw.size).put("sha256", sha(raw)), raw)
        } catch (_: FileNotFoundException) {
            val report = JSONObject().put("present", false)
            if (!optional) report.put("failure", "missing_asset")
            Asset(report, null)
        } catch (error: Exception) {
            Asset(JSONObject().put("present", true).put("failure", "unreadable_or_invalid_asset")
                .put("failure_type", error.javaClass.simpleName.take(80)), null)
        }
    }

    /** No arguments besides the target Context. No path/root/key inputs exist. */
    @Suppress("DEPRECATION")
    suspend fun read(context: Context): JSONObject = withContext(Dispatchers.IO) {
        check(Looper.myLooper() != Looper.getMainLooper())
        val report = JSONObject().put("schema", 1).put("complete", false)
            .put("scope", "installed_apk_assets_and_production_key_loaders_only")
            .put("owner_public_hashes_compared", false)
            .put("count_basis", "validated_APK_asset_declarations_after_actual_loaders")
            .put("probe_writes_files", false).put("probe_changes_settings", false)
            .put("probe_starts_runtime", false).put("probe_calls_installer", false)
        val failures = JSONArray()
        report.put("failures", failures)
        val packageName = context.packageName
        var source: File? = null
        var before: moe.kiwi.thinkos.update.UpdateInstallStore.Running? = null
        try {
            require(packageName == "moe.kiwi.thinkos") { "Production target package required" }
            val info = context.packageManager.getApplicationInfo(packageName, 0)
            require(context.applicationInfo.sourceDir == info.sourceDir) { "Running and installed APK differ" }
            val installedSource = File(info.sourceDir)
            source = installedSource
            before = UpdateInstallContext.running(context)
            // Reuse the bounded actual archive/package/signer/API27/ABI verifier;
            // this inspects PackageManager's installed file, not a supplied APK.
            val installed = UpdatePackageVerifier.forContext(context).inspectInstalled(installedSource)
            report.put("apk", JSONObject().put("package", packageName).put("version", installed.identity.version)
                .put("code", installed.identity.code).put("sha256", installed.identity.sha256)
                .put("size", installed.identity.size).put("signer", installed.identity.signer)
                .put("debuggable", info.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                .put("sdk", android.os.Build.VERSION.SDK_INT).put("target_sdk", info.targetSdkVersion))
        } catch (error: Exception) {
            failures.put("installed_apk_identity_unavailable")
            report.put("apk_failure_type", error.javaClass.simpleName.take(80))
        }

        val update = asset(context, "keys.json")
        val system = asset(context, "system-keys.json")
        val debug = asset(context, "debug-keys.json", 4096, optional = true)
        report.put("assets", JSONObject().put("keys.json", update.report)
            .put("system-keys.json", system.report).put("debug-keys.json", debug.report))
        val actualUpdate = UpdateKeys.load(context)
        val actualSystem = ModuleManifest.loadKeys(context)
        report.put("update_keys_load_ok", actualUpdate.isSuccess).put("system_keys_load_ok", actualSystem.isSuccess)
        actualUpdate.exceptionOrNull()?.let { report.put("update_keys_failure_type", it.javaClass.simpleName.take(80)) }
        actualSystem.exceptionOrNull()?.let { report.put("system_keys_failure_type", it.javaClass.simpleName.take(80)) }
        if (actualUpdate.isFailure) failures.put("production_update_keys_load_failed")
        if (actualSystem.isFailure) failures.put("production_system_keys_load_failed")

        if (update.raw == null || system.raw == null) failures.put("required_public_assets_missing_or_invalid")
        if (debug.report.getBoolean("present")) failures.put("debug_root_asset_present")
        if (update.raw != null && system.raw != null && actualUpdate.isSuccess && actualSystem.isSuccess) try {
            val declaredUpdate = UpdateModels.readUpdateKeyDocument(UpdateJson.read(update.raw, LIMIT))
            val declaredSystem = UpdateModels.readUpdateSystemKeyDocument(UpdateJson.read(system.raw, LIMIT))
            val entries = listOf(declaredUpdate.channel.active, declaredUpdate.channel.backup,
                declaredUpdate.registry.active, declaredUpdate.registry.backup, declaredSystem.active, declaredSystem.backup)
            val independent = entries.map { it.spki }.distinct().size == 6 && entries.map { it.id }.distinct().size == 6
            val fixtureNamespace = entries.any { it.id.startsWith("reg2b-") }
            report.put("declared_role_counts", JSONObject().put("channel", 2).put("registry", 2).put("system", 2))
                .put("six_roles_independent", independent).put("reg_fixture_namespace_present", fixtureNamespace)
            if (!independent) failures.put("cross_role_key_or_id_reuse")
            if (fixtureNamespace) failures.put("registry_fixture_roots_are_not_owner_roots")
        } catch (error: Exception) {
            failures.put("public_asset_declaration_validation_failed")
            report.put("declaration_failure_type", error.javaClass.simpleName.take(80))
        }

        try {
            val initialSource = requireNotNull(source) { "No initial installed APK" }
            val initialIdentity = requireNotNull(before) { "No initial installed identity" }
            val after = context.packageManager.getApplicationInfo(packageName, 0)
            require(after.sourceDir == initialSource.path && context.applicationInfo.sourceDir == after.sourceDir &&
                UpdateInstallContext.running(context) == initialIdentity) { "Package changed during observation" }
            // Same actual immutable installed artifact must still pass the full
            // production verifier after assets/loaders have been observed.
            val first = report.getJSONObject("apk")
            val verified = UpdatePackageVerifier.forContext(context).inspectInstalled(initialSource)
            require(verified.identity.sha256 == first.getString("sha256") && verified.identity.size == first.getLong("size"))
            report.put("apk_stable_during_probe", true)
        } catch (error: Exception) {
            failures.put("installed_apk_changed_or_unavailable")
            report.put("apk_stable_during_probe", false).put("stability_failure_type", error.javaClass.simpleName.take(80))
        }
        report.put("complete", failures.length() == 0)
        check(report.toString().toByteArray(Charsets.UTF_8).size <= 8192) { "Observation exceeds report bound" }
        report
    }
}
