package moe.kiwi.thinkos.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest

/** Native-only read model. No Lua method, timestamp factory or installer capability. */
internal enum class FeatureStage { CLOSED, CHECKING, UNAVAILABLE, IDLE, PREPARING, READY, INSTALLING, SUBMITTED, FAILED, INSTALLED }
internal data class FeatureModuleUi(val id: String, val version: String, val code: Int, val installedCode: Int?, val size: Long)
internal data class FeaturePlanUi(val fingerprint: String, val apkVersion: String, val apkCode: Int,
                                 val apkChanges: Boolean, val apkSize: Long, val modules: List<FeatureModuleUi>)
internal data class FeatureUiState(val stage: FeatureStage = FeatureStage.CLOSED, val token: String? = null,
                                  val reason: String? = null, val plan: FeaturePlanUi? = null,
                                  val downloaded: Long = 0, val size: Long = 0)

/** One window, one preparation generation. Only the final native INSTALL can
 * consume its one-use plan; PREPARE cannot persist installation authority. */
internal class FeatureScope(val token: String, val policy: UpdatePolicy, val nonce: String = UpdateInstaller.newId(),
                            val settingEpoch: Long = 0) {
    @Volatile var valid = true; private set
    @Volatile var durable = false; private set
    private var consumed = false
    @Synchronized fun revoke() { valid = false }
    @Synchronized fun consume(): Boolean {
        if (!valid || consumed) return false
        consumed = true
        return true
    }
    fun prepared() { durable = true }
    companion object { const val MODULE = "thinkos-cast"; const val FEATURE = "miracast" }
}

/** Immutable exact selection, including the prior inventory/floors. The full
 * release remains authenticated; a cast-only title never authorizes other bytes. */
internal data class FeaturePlanBinding(val nonce: String, val catalogDigest: String, val channel: String,
                                      val apk: UpdateApkIdentity, val previousRoot: String,
                                      val selected: List<FeatureTargetBinding>) {
    fun fingerprint(): String {
        val rows = listOf(nonce, catalogDigest, channel, apk.version, apk.code.toString(), apk.sha256,
            apk.size.toString(), apk.signer, previousRoot) + selected.sortedBy { it.id }.flatMap {
            listOf(it.id, it.version, it.code.toString(), it.sha256, it.size.toString(),
                it.installedCode.toString(), it.floor.toString())
        }
        require(rows.none { '\u0000' in it || '\n' in it })
        return MessageDigest.getInstance("SHA-256").digest(rows.joinToString("\u0000").toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
internal data class FeatureTargetBinding(val id: String, val version: String, val code: Int, val sha256: String,
                                        val size: Long, val installedCode: Int, val floor: Int)
internal data class FeaturePreparedPlan(val scope: FeatureScope, val catalog: UpdateCatalog, val apk: File,
                                       val binding: FeaturePlanBinding, val ui: FeaturePlanUi)

internal interface FeatureModuleWork {
    suspend fun prepareFeature(scope: FeatureScope, progress: (Long, Long) -> Unit): FeaturePreparedPlan
    suspend fun installFeature(plan: FeaturePreparedPlan, admitted: suspend () -> Boolean): UpdateInstaller.Outcome
}

/** Reuses the real catalog/sequence/cache/fetcher/verifier and module adapter,
 * while deliberately never touching ordinary UpdateState DOWNLOADING/READY. */
internal class NativeFeaturePreparation(private val config: UpdatePolicy, private val client: UpdateCatalogClient,
    private val modules: ModuleUpdateRuntime, private val fetcher: Fetcher, private val downloads: File,
    private val verifier: UpdatePackageVerifier, private val device: () -> UpdateCatalog.Device,
    private val environment: StateFlow<UpdateSession.Environment>,
    private val requireCurrent: suspend (UpdateCatalog) -> Unit,
) : FeatureModuleWork {
    override suspend fun prepareFeature(scope: FeatureScope, progress: (Long, Long) -> Unit): FeaturePreparedPlan = coroutineScope {
        require(scope.valid && scope.policy == config.copy(enabled = true) && config.sources.isNotEmpty())
        require(environment.value.allows(true))
        val transfer = async {
            modules.refresh()
            require(modules.view.value.available && !modules.view.value.active &&
                modules.view.value.inventoryReadable && modules.view.value.installedCodes[FeatureScope.MODULE] == 0)
            require(scope.valid)
            val checked = client.checkDetailed(config.sources, config.channel)
            val catalog = checked.catalog
            require(scope.valid)
            modules.refresh()
            require(modules.eligibility(catalog, UpdateIo.run(device), setOf(FeatureScope.MODULE)) == null)
            val apkChanges = catalog.document.release.code != UpdateIo.run(device).versionCode
            val verified = if (!apkChanges) modules.verifyApk(catalog, modules.apkFile(catalog, File(downloads, "unused.apk")),
                setOf(FeatureScope.MODULE)) else client.downloadVerifiedApk(catalog, UpdateIo.run(device), downloads, verifier) {
                if (scope.valid) progress(it, catalog.document.release.size)
            }
            require(scope.valid && environment.value.allows(true))
            val plan = modules.prepareFeature(catalog, verified, fetcher, scope) { count, size ->
                if (scope.valid) progress(count + if (apkChanges) verified.bytes else 0,
                    size + if (apkChanges) verified.bytes else 0)
            }
            requireCurrent(catalog)
            require(scope.valid && environment.value.allows(true))
            plan
        }
        val watcher = launch {
            environment.first { !it.allows(true) }
            transfer.cancel(CancellationException("Feature preparation paused by the live environment"))
        }
        try { transfer.await() } finally { watcher.cancelAndJoin() }
    }

    override suspend fun installFeature(plan: FeaturePreparedPlan, admitted: suspend () -> Boolean): UpdateInstaller.Outcome {
        require(plan.scope.valid && plan.scope.policy == config.copy(enabled = true))
        requireCurrent(plan.catalog)
        // No platform APK-only fallback is ever available to this path.
        return modules.installFeature(plan) { admitted() && environment.value.allowsInstall() }
    }
}
