package moe.kiwi.thinkos

import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.*
import moe.kiwi.thinkos.update.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.zip.ZipFile

/** Explicit spare-only test phase. Observe the ONE production READY plan and
 * arm one debug fault; never check/download/install, change a setting or invent
 * tap credit. Main/native UI still owns the real final installation consent.
 */
internal object DebugCommitFaultProbe {
    private const val APP = "moe.kiwi.thinkos"
    private val HEX = Regex("[a-f0-9]{64}")

    private fun read(input: InputStream, limit: Int): ByteArray = input.use {
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = it.read(buffer, 0, minOf(buffer.size, limit - result.size() + 1))
            if (count < 0) break
            require(count <= limit - result.size())
            result.write(buffer, 0, count)
        }
        result.toByteArray()
    }

    /** API27 target27 cannot use Build.SERIAL as physical proof. The shell
     * command is fixed/read-only in the test APK, never an app/Lua/root channel.
     * Closing its pipe bounds a stuck read; serial bytes are never reported.
     */
    suspend fun physical(instrumentation: Instrumentation): String = coroutineScope {
        val automation = instrumentation.uiAutomation
        try {
            val descriptor = automation.executeShellCommand("getprop ro.serialno")
            val closer = launch(Dispatchers.IO) { delay(5000); runCatching { descriptor.close() } }
            try {
                withContext(Dispatchers.IO) { read(ParcelFileDescriptor.AutoCloseInputStream(descriptor), 128).toString(Charsets.US_ASCII).trim() }
                    .also { actual -> requireSpare(actual, actual) }
            } finally {
                runCatching { descriptor.close() }
                closer.cancelAndJoin()
            }
        } finally {
            // API27 test-only cleanup, matching the existing native UI probes.
            // disconnect alone leaves Instrumentation caching a dead object;
            // destroy also releases the global slot for external ThinkHierarchy.
            automation.javaClass.getDeclaredMethod("destroy").apply { isAccessible = true }.invoke(automation)
        }
    }

    private fun requireSpare(actual: String, expected: String) {
        require(listOf(actual, expected).all { it.matches(Regex("[A-Za-z0-9_.:-]{1,100}")) &&
            it.uppercase(java.util.Locale.ROOT) !in setOf("HUA0BWUT", "UNKNOWN", "NULL", "0") }) { "Known explicit spare required" }
        require(actual == expected) { "Physical spare differs" }
    }

    internal fun requireDebugCandidate(context: Context, file: File) {
        @Suppress("DEPRECATION")
        val archive = requireNotNull(context.packageManager.getPackageArchiveInfo(file.path, 0))
        require(archive.packageName == APP && ((archive.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            "An explicit debug fault candidate is required"
        }
    }

    private fun samePublicAssets(context: Context, target: File) {
        try {
            context.assets.open("update/debug-keys.json").close()
            error("Extra debug roots are not allowed")
        } catch (_: FileNotFoundException) { /* Expected absence. */ }
        ZipFile(target).use { zip ->
            val entries = zip.entries().asSequence().take(20_001).toList()
            require(entries.size <= 20_000 && entries.none { it.name == "assets/update/debug-keys.json" })
            for (name in listOf("keys.json", "system-keys.json")) {
                val matching = entries.filter { it.name == "assets/update/$name" }
                require(matching.size == 1 && matching.single().size in 1..65536 && !matching.single().isDirectory)
                val expected = read(context.assets.open("update/$name"), 65536)
                val actual = read(zip.getInputStream(matching.single()), 65536)
                require(expected.isNotEmpty() && actual.contentEquals(expected)) { "Fault candidate changes deployment roots" }
            }
        }
    }

    suspend fun run(instrumentation: Instrumentation, args: Bundle): JSONObject {
        require(args.keySet() == setOf("phase", "run_id", "physical_serial", "catalog_sha256", "target_sha256"))
        val nonce = requireNotNull(args.getString("run_id"))
        val expectedPhysical = requireNotNull(args.getString("physical_serial"))
        val catalogHash = requireNotNull(args.getString("catalog_sha256"))
        val targetHash = requireNotNull(args.getString("target_sha256"))
        require(nonce.matches(Regex("[a-f0-9]{32}")) && catalogHash.matches(HEX) && targetHash.matches(HEX))
        val context = instrumentation.targetContext
        require(context.packageName == APP && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val actualPhysical = physical(instrumentation)
        requireSpare(actualPhysical, expectedPhysical) // before Activity, arm or any test mutation
        require(ProductionRootsProbe.read(context).getBoolean("complete")) { "Production deployment roots are unavailable" }
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val host = withContext(Dispatchers.Main) { (context.applicationContext as ThinkApplication).runtime }
        require(withContext(Dispatchers.Main) { !host.system.updateAutoInstall }) { "Automatic installation must already be off" }
        val before = withTimeout(45_000) {
            while (true) {
                val state = withContext(Dispatchers.Main) { host.updates.status.value }
                if (state.configured && !state.busy && state.state.phase == "READY") break
                delay(100)
            }
            withContext(Dispatchers.Main) { host.updates.status.value }
        }
        val offer = requireNotNull(before.offer)
        require(before.state.catalog_digest == catalogHash && offer.digest == catalogHash && offer.release.sha256 == targetHash &&
            offer.ineligible == null && offer.release.modules.isEmpty()) { "Exact production APK-only READY plan required" }
        val root = File(context.filesDir, "update")
        val verified = withContext(Dispatchers.IO) {
            require(root.isDirectory && File(root, "metadata").isDirectory && File(root, "ledger").isDirectory)
            val keys = UpdateKeys.load(context).getOrThrow()
            val cached = requireNotNull(UpdateCatalogCache(File(root, "metadata"), keys).read(before.state.channel)).catalog
            UpdateSequenceStore(File(root, "ledger")).requireCurrent(cached)
            require(cached.digest == catalogHash && cached.document.release == offer.release && cached.document.seq == before.state.seq)
            require(UpdateInstallContext.store(context).snapshot().pending == null) { "Another native transaction is active" }
            val file = File(root, "downloads/$targetHash.apk")
            val verifier = UpdatePackageVerifier.forContext(context)
            verifier.verify(cached, file)
            requireDebugCandidate(context, file)
            samePublicAssets(context, file)
            val source = File(context.packageManager.getApplicationInfo(APP, 0).sourceDir)
            cached to verifier.inspectInstalled(source).identity
        }
        val catalog = verified.first
        val from = verified.second
        val release = catalog.document.release
        val to = UpdateApkIdentity(release.version, release.code, release.sha256, release.size, release.signer)
        fun current(): Boolean {
            val status = host.updates.status.value
            return !activity.isFinishing && !activity.isDestroyed && !host.router.onboardingOpen && !host.system.updateAutoInstall &&
                host.updates.environment.value.allowsInstall() && status.configured &&
                status.state.channel == catalog.document.channel && status.state.seq == catalog.document.seq &&
                status.state.catalog_digest == catalogHash && status.offer?.digest == catalogHash &&
                status.state.phase in setOf("READY", "INSTALLING")
        }
        require(withContext(Dispatchers.Main) { current() && host.system.updateInstallLimits.maxAttempts == 3 &&
            host.system.updateInstallLimits.healthMillis == 600_000L && host.updates.status.value.state.phase == "READY" })
        withContext(Dispatchers.IO) {
            DebugUpdateFault.arm(context, nonce, actualPhysical, expectedPhysical, catalog.document.channel, catalog.document.seq,
                catalogHash, from, to, release.notes)
        }
        fun report(stage: String) = JSONObject().put("stage", stage).put("nonce", nonce).put("catalog_sha256", catalogHash)
            .put("target_sha256", targetHash).put("complete", false).put("installed", false)
            .put("platform_submission_verified", false).put("scope", "debug_commit_fault_arm_only")
        fun emit(stage: String) = instrumentation.sendStatus(0, Bundle().apply { putString("debug_arm", report(stage).toString()) })
        var last = ""
        try {
            return withTimeoutOrNull(360_000) {
                while (currentCoroutineContext().isActive) {
                    val state = DebugUpdateFault.state(nonce)
                    if (state == "ARMED" && !withContext(Dispatchers.Main) { current() }) {
                        DebugUpdateFault.revoke(nonce)
                        return@withTimeoutOrNull report("REVOKED")
                    }
                    if (state != last) { emit(state); last = state }
                    if (state in setOf("EXPIRED", "NONE", "UNAVAILABLE")) return@withTimeoutOrNull report(state)
                    if (state == "BOUND_TO_PENDING") {
                        val pending = withContext(Dispatchers.IO) { UpdateInstallContext.store(context).snapshot().pending }
                        if (pending == null) return@withTimeoutOrNull report("NATIVE_TRANSACTION_ENDED_WITHOUT_OBSERVED_REPLACEMENT")
                    }
                    delay(100)
                }
                report("ARM_OBSERVER_CANCELLED")
            } ?: report(if (last == "BOUND_TO_PENDING") "UNKNOWN_NATIVE_RESULT_PRESERVED" else "EXPIRED")
        } finally {
            // Revokes only RAM. Bound marker/pending/previous/history/floor are
            // never erased by timeout, dialog close, test failure or disconnect.
            DebugUpdateFault.revoke(nonce)
        }
    }
}
