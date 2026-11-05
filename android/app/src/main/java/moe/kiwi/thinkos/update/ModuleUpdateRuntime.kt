package moe.kiwi.thinkos.update

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import moe.kiwi.thinkos.BuildConfig
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.nio.file.Files

/** Optional root transaction adapter. No shell, downloaded trust roots or
 * success inferred from a receipt. Every mutating intent is fsynced first;
 * only STATUS evidence may acknowledge an operation after its reply was lost. */
internal class ModuleUpdateRuntime private constructor(private val context: Context) {
    data class View(val available: Boolean = false, val active: Boolean = false, val readyDigest: String? = null,
                    val completedDigest: String? = null,
                    val selectedChanges: Boolean = false, val ownedDigest: String? = null,
                    val rootKeys: Int = 0, val apkChanges: Boolean = false,
                    val metadataDigest: String? = null, val installedCodes: Map<String, Int> = emptyMap(),
                    val downloaded: Map<String, Long> = emptyMap(), val sizes: Map<String, Long> = emptyMap(),
                    val results: Map<String, String> = emptyMap(),
                    val inventoryReadable: Boolean = false, val inventoryManaged: Boolean = false,
                    val states: Map<String, String> = emptyMap(), val error: String? = null)
    private val mutable = MutableStateFlow(View())
    val view: StateFlow<View> = mutable
    private val mutex = Mutex()
    private val client = UpdaterClient()
    private val directory = UpdateInstallContext.directory(context)
    private val records = ModuleUpdateStore(directory)
    private val artifacts = File(directory, "modules")
    private val verifier = UpdatePackageVerifier.forContext(context)
    private var systemKeys: ModuleManifest.Keys? = null
    private var root: UpdaterStatusDocument? = null
    private var attached = false
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var frame = false
    private var catalogReady = false
    private var healthStart: UpdateInstallTime? = null
    private var resumeAllowed: suspend () -> Boolean = { false }
    private var features: () -> Set<String> = { emptySet() }
    private var enableFeature: suspend (String) -> Boolean = { false }
    private var limits: () -> UpdateInstallStore.Limits = { error("Module update settings unavailable") }
    private var pollMillis: () -> Long = { error("Module update settings unavailable") }

    /** Called by the first-line guard, before native/plugin initialization. Only
     * the final post-reboot target process counts as a trial. */
    fun bootstrap() {
        if (!File(directory, "module-pending.json").exists()) return
        val pending = records.snapshot() ?: return
        if (ModuleUpdateStore.terminal(pending)) return
        mutable.value = mutable.value.copy(active = true, metadataDigest = pending.catalog_digest,
            states = pending.selected.associate { it.id to if (it.id in pending.staged) "staged" else "available" })
        val now = UpdateInstallContext.now(context)
        if (executing(pending.to) && ModuleUpdatePolicy.bootReady(pending, now.boot)) {
            records.change(pending.id) { old -> ModuleUpdatePolicy.startup(old, now, UpdateInstaller.newId()) }
        }
    }

    fun frame() { frame = true }
    fun catalog() { catalogReady = true }
    fun silentFor(digest: String?): Boolean = mutable.value.let {
        !File(directory, UpdateRepairFiles.MARKER).exists() &&
            ModuleUpdatePolicy.rootAdmission(digest, it.readyDigest, it.rootKeys, it.active, it.ownedDigest, it.apkChanges, it.selectedChanges)
    }
    suspend fun pendingDigest(): String? = UpdateIo.run { records.snapshot()?.takeUnless { ModuleUpdateStore.terminal(it) }?.catalog_digest }

    fun attach(scope: CoroutineScope, limits: () -> UpdateInstallStore.Limits, pollMillis: () -> Long,
               features: () -> Set<String>, admitted: suspend () -> Boolean,
               enableFeature: suspend (String) -> Boolean = { false }) {
        this.limits = limits; this.pollMillis = pollMillis; this.features = features; resumeAllowed = admitted
        this.enableFeature = enableFeature
        if (attached) return
        attached = true
        scope.launch {
            refresh()
            while (isActive) {
                val pending = UpdateIo.run { records.snapshot() }
                if (pending != null && !ModuleUpdateStore.terminal(pending)) {
                    try { mutex.withLock { drive(resumeAllowed) } }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { mutable.value = mutable.value.copy(active = true, error = "interrupted") }
                    delay(pending.poll_ms)
                } else wake.receive()
            }
        }
    }

    suspend fun refresh() {
        val keys = ModuleManifest.loadKeys(context).getOrNull()
        val status = if (client.hello()) client.status().getOrNull() else null
        systemKeys = keys; root = status
        // Inventory does not depend on channel configuration or a readable
        // update journal. Only the authenticated bounded local STATUS feeds it.
        mutable.value = mutable.value.copy(inventoryReadable = status != null,
            inventoryManaged = keys != null && status != null && status.keys in 1..2,
            installedCodes = status?.modules?.associate { it.id to it.code }.orEmpty())
        val own = UpdateIo.run { records.snapshot() }
        if (status != null) publishRoot(status, own)
        else mutable.value = mutable.value.copy(installedCodes = emptyMap())
        mutable.value = mutable.value.copy(available = keys != null && status != null && status.keys in 1..2,
            rootKeys = status?.keys ?: 0,
            active = own?.let { !ModuleUpdateStore.terminal(it) } == true || status?.transaction == true,
            error = if (status?.transaction == true && (own == null || own.root_id.isNotEmpty() && own.root_id != status.id)) "interrupted" else null)
    }

    /** Same-code catalogs require actual PackageManager identity AND full APK
     * hash/size equality, plus a selected newer component. No version bypass is
     * granted merely because a signed catalog contains a modules array. */
    suspend fun eligibility(catalog: UpdateCatalog, device: UpdateCatalog.Device,
                            additionalFeatures: Set<String> = emptySet()): UpdateCatalog.Ineligible? {
        if (UpdateIo.run { records.blocked(catalog.digest) }) return UpdateCatalog.Ineligible.BLACKLIST
        val ordinary = catalog.ineligible(device)
        if (ordinary != UpdateCatalog.Ineligible.VERSION || catalog.document.release.code != device.versionCode) return ordinary
        if (!mutable.value.available) refresh()
        if (!mutable.value.available || catalog.document.release.modules.isEmpty()) return ordinary
        return try {
            if (selection(catalog, additionalFeatures).isEmpty()) ordinary else {
                val local = verifier.inspectInstalled(installedFile())
                if (local.identity == identity(catalog)) null else ordinary
            }
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { ordinary }
    }

    fun apkFile(catalog: UpdateCatalog, fallback: File): File =
        if (catalog.document.release.code == BuildConfig.VERSION_CODE) installedFile() else fallback

    suspend fun verifyApk(catalog: UpdateCatalog, file: File,
                          additionalFeatures: Set<String> = emptySet()): UpdatePackageVerifier.Verified {
        if (catalog.document.release.code != BuildConfig.VERSION_CODE) return verifier.verify(catalog, file)
        require(eligibility(catalog, UpdateIo.run { installedDevice() }, additionalFeatures) == null)
        val local = verifier.inspectInstalled(installedFile())
        require(local.identity == identity(catalog))
        return UpdatePackageVerifier.Verified(local.file, catalog.digest, local.identity.sha256,
            local.identity.size, local.identity.code, local.identity.signer)
    }

    /** APK-only fallback is intentional when the optional updater/system keys
     * are absent. Existing APK OTA still uses its native installer/guard. */
    suspend fun download(catalog: UpdateCatalog, fetcher: Fetcher, verified: UpdatePackageVerifier.Verified) = mutex.withLock {
        requireApkProof(catalog, verified)
        refresh()
        require(!mutable.value.active) { "Module transaction already active" }
        if (!mutable.value.available) {
            require(catalog.document.release.code > BuildConfig.VERSION_CODE)
            mutable.value = mutable.value.copy(readyDigest = catalog.digest, selectedChanges = false,
                apkChanges = catalog.document.release.code > BuildConfig.VERSION_CODE,
                metadataDigest = catalog.digest, sizes = catalog.document.release.modules.associate { it.id to it.size },
                downloaded = emptyMap(), results = emptyMap(),
                states = catalog.document.release.modules.associate { it.id to "requires_updater" })
            return@withLock
        }
        val selected = selection(catalog)
        val states = catalog.document.release.modules.associate { module ->
            module.id to if (selected.any { it.id == module.id }) "downloading" else if (
                root!!.modules.firstOrNull { it.id == module.id }?.code?.let { it >= module.code } == true) "current" else "skipped"
        }.toMutableMap()
        mutable.value = mutable.value.copy(readyDigest = null, selectedChanges = selected.isNotEmpty(), states = states.toMap(),
            metadataDigest = catalog.digest, sizes = catalog.document.release.modules.associate { it.id to it.size },
            downloaded = if (mutable.value.metadataDigest == catalog.digest) mutable.value.downloaded else emptyMap(), results = emptyMap())
        val keys = requireNotNull(systemKeys)
        for (module in selected) {
            fetchSelected(module, fetcher, keys) { bytes ->
                mutable.value = mutable.value.copy(downloaded = mutable.value.downloaded + (module.id to bytes.coerceIn(0, module.size)))
            }
            states[module.id] = "ready"
            mutable.value = mutable.value.copy(states = states.toMap(), downloaded = mutable.value.downloaded + (module.id to module.size))
        }
        mutable.value = mutable.value.copy(readyDigest = catalog.digest, apkChanges = catalog.document.release.code > BuildConfig.VERSION_CODE)
    }

    suspend fun ready(catalog: UpdateCatalog, verified: UpdatePackageVerifier.Verified): Boolean {
        requireApkProof(catalog, verified)
        if (catalog.document.release.modules.isEmpty() || !mutable.value.available) {
            val newer = catalog.document.release.code > BuildConfig.VERSION_CODE
            if (newer) mutable.value = mutable.value.copy(readyDigest = catalog.digest, apkChanges = true, selectedChanges = false)
            return newer
        }
        return try {
            val keys = systemKeys ?: return false
            val selected = selection(catalog)
            selected.forEach { verifyFiles(it, keys) }
            mutable.value = mutable.value.copy(readyDigest = catalog.digest, selectedChanges = selected.isNotEmpty(),
                apkChanges = catalog.document.release.code > BuildConfig.VERSION_CODE,
                metadataDigest = catalog.digest, sizes = catalog.document.release.modules.associate { it.id to it.size },
                downloaded = selected.associate { it.id to it.size },
                results = if (mutable.value.metadataDigest == catalog.digest) mutable.value.results else emptyMap(),
                states = catalog.document.release.modules.associate { module -> module.id to when {
                    selected.any { it.id == module.id } -> "ready"
                    root!!.modules.singleOrNull { it.id == module.id }?.code?.let { it >= module.code } == true -> "current"
                    else -> "skipped"
                } })
            true
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { false }
    }

    suspend fun install(catalog: UpdateCatalog, apk: File, automatic: Boolean,
                        admitted: suspend () -> Boolean): UpdateInstaller.Outcome =
        installInternal(catalog, apk, automatic, null, admitted)

    suspend fun installFeature(plan: FeaturePreparedPlan, admitted: suspend () -> Boolean): UpdateInstaller.Outcome =
        installInternal(plan.catalog, plan.apk, false, plan, admitted)

    /** Native preparation never publishes shared readyDigest or writes UpdateState.
     * Only verified immutable module artifacts are cached; its plan stays in RAM. */
    suspend fun prepareFeature(catalog: UpdateCatalog, verified: UpdatePackageVerifier.Verified,
                               fetcher: Fetcher, scope: FeatureScope,
                               progress: (Long, Long) -> Unit): FeaturePreparedPlan = mutex.withLock {
        require(scope.valid)
        requireApkProof(catalog, verified)
        refresh()
        require(mutable.value.available && !mutable.value.active)
        val selected = selection(catalog, setOf(FeatureScope.MODULE))
        val binding = featureBinding(scope, catalog, selected)
        val total = selected.sumOf { it.size }
        val counts = mutableMapOf<String, Long>()
        for (module in selected) {
            require(scope.valid)
            fetchSelected(module, fetcher, requireNotNull(systemKeys)) { bytes ->
                counts[module.id] = bytes
                if (scope.valid) progress(counts.values.sum(), total)
            }
        }
        selected.forEach { verifyFiles(it, requireNotNull(systemKeys)) }
        require(scope.valid)
        // Inventory is re-read at final INSTALL; a changed plan requires a new
        // preparation and new physical confirmation instead of a silent retarget.
        val release = catalog.document.release
        val ui = FeaturePlanUi(binding.fingerprint(), release.version, release.code,
            release.code != BuildConfig.VERSION_CODE, release.size,
            selected.map { module -> FeatureModuleUi(module.id, module.version, module.code,
                root!!.modules.single { it.id == module.id }.code, module.size) })
        FeaturePreparedPlan(scope, catalog, verified.file, binding, ui)
    }

    private fun featureBinding(scope: FeatureScope, catalog: UpdateCatalog,
                               selected: List<UpdateModule>): FeaturePlanBinding {
        require(scope.valid && selected.any { it.id == FeatureScope.MODULE && it.policy == "feature" })
        val status = requireNotNull(root)
        require(status.keys in 1..2 && !status.transaction)
        return FeaturePlanBinding(scope.nonce, catalog.digest, catalog.document.channel, identity(catalog), status.id,
            selected.map { module ->
                val prior = status.modules.single { it.id == module.id }
                FeatureTargetBinding(module.id, module.version, module.code, module.sha256, module.size, prior.code, prior.floor)
            }.sortedBy { it.id })
    }

    private suspend fun installInternal(catalog: UpdateCatalog, apk: File, automatic: Boolean,
                        plan: FeaturePreparedPlan?, admitted: suspend () -> Boolean): UpdateInstaller.Outcome = mutex.withLock {
        if (!admitted()) return@withLock UpdateInstaller.Outcome.CANCELLED
        refresh()
        require(mutable.value.rootKeys in 1..2 && !mutable.value.active)
        if (plan == null) require(silentFor(catalog.digest))
        else require(!automatic && plan.scope.valid && plan.catalog.digest == catalog.digest)
        val additional = if (plan != null) setOf(FeatureScope.MODULE) else emptySet()
        val verified = verifyApk(catalog, apk, additional)
        val local = verifier.inspectInstalled(installedFile())
        val selected = if (mutable.value.available) selection(catalog, additional) else emptyList()
        if (plan != null) require(featureBinding(plan.scope, catalog, selected) == plan.binding)
        mutable.value = mutable.value.copy(selectedChanges = selected.isNotEmpty())
        require(selected.isNotEmpty() || local.identity != identity(catalog)) { "No update changes" }
        selected.forEach { verifyFiles(it, requireNotNull(systemKeys)) }
        if (!admitted()) return@withLock UpdateInstaller.Outcome.CANCELLED
        val config = limits(); val poll = pollMillis()
        require(poll in 1000..60000)
        val before = requireNotNull(root); require(!before.transaction)
        val created = UpdateInstallContext.now(context)
        require(created.boot != null) { "Cannot prove module update boot/health without a boot counter" }
        val pending = ModuleUpdatePending(schema = 1, id = UpdateInstaller.newId(), root_id = "", previous_root_id = before.id,
            catalog_digest = catalog.digest, channel = catalog.document.channel, from = local.identity, to = identity(catalog),
            mode = if (automatic) "automatic" else "manual", phase = "PREPARED", operation = "",
            selected = selected.map { ModuleUpdateTarget(it.id, it.code) }, staged = emptyList(), created = created,
            attempts = 0, max_attempts = config.maxAttempts, health_ms = config.healthMillis, poll_ms = poll,
            feature = if (plan != null) FeatureScope.FEATURE else null)
        require(verified.sha256 == pending.to.sha256 && verified.bytes == pending.to.size)
        if (plan != null) require(plan.scope.consume())
        // Only this existing atomic PREPARED record persists final native consent.
        // A crash before it leaves no enabled setting or recoverable feature permit.
        if (plan != null) withContext(NonCancellable) {
            UpdateIo.run { records.prepare(pending, catalog) }
            plan.scope.prepared()
            mutable.value = mutable.value.copy(active = true, ownedDigest = catalog.digest)
            wake.trySend(Unit)
        } else {
            UpdateIo.run { records.prepare(pending, catalog) }
            wake.trySend(Unit)
            mutable.value = mutable.value.copy(active = true, ownedDigest = catalog.digest)
        }
        try { drive(admitted) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutable.value = mutable.value.copy(error = "interrupted") }
        val current = UpdateIo.run { records.snapshot() }
        when (current?.phase) {
            "PREPARED" -> if (mutable.value.error != null) UpdateInstaller.Outcome.FAILED else UpdateInstaller.Outcome.SUBMITTED
            "CANCELLED" -> UpdateInstaller.Outcome.CANCELLED
            "FAILED" -> UpdateInstaller.Outcome.FAILED
            else -> UpdateInstaller.Outcome.SUBMITTED // accepted, never "healthy"
        }
    }

    private suspend fun drive(admitted: suspend () -> Boolean) {
        var pending = UpdateIo.run { records.snapshot() } ?: return
        if (ModuleUpdateStore.terminal(pending)) return
        val status = client.status().getOrNull() ?: return
        root = status
        publishRoot(status, pending)
        if (pending.root_id.isEmpty()) {
            if (pending.operation == "BEGIN") {
                when (ModuleUpdatePolicy.recoverBegin(pending, status)) {
                    ModuleUpdatePolicy.Begin.NOT_ACCEPTED -> {
                        change(pending) { it.copy(phase = "CANCELLED", operation = "", reason = "interrupted") }
                        mutable.value = mutable.value.copy(active = false, error = "interrupted")
                        return
                    }
                    ModuleUpdatePolicy.Begin.ADOPT -> Unit
                    ModuleUpdatePolicy.Begin.CONFLICT -> {
                        mutable.value = mutable.value.copy(active = true, error = "interrupted")
                        return
                    }
                }
                pending = change(pending) { it.copy(root_id = status.id, phase = "ACTIVE", operation = "") }
            } else {
                if (!admitted()) {
                    // An explicitly confirmed feature stays PREPARED until its
                    // environment is safe; a closed dialog cannot erase consent
                    // already committed with this exact durable transaction.
                    if (pending.feature == null) change(pending) { it.copy(phase = "CANCELLED") }
                    return
                }
                require(!status.transaction && status.id == pending.previous_root_id)
                if (pending.feature != null) {
                    // Reauthenticate before restoring only this final-confirmed
                    // feature. No RAM plan, mutable trust file or orphan artifact
                    // can enter this branch. A failed flush must precede BEGIN.
                    val keys = UpdateKeys.load(context).getOrThrow()
                    val catalog = UpdateIo.run { records.catalog(keys) }
                    require(catalog.document.release.modules.any { it.id == FeatureScope.MODULE && it.policy == "feature" } &&
                        pending.selected.any { it.id == FeatureScope.MODULE })
                    if (!enableFeature(pending.feature!!)) {
                        mutable.value = mutable.value.copy(active = true, error = "interrupted")
                        return
                    }
                    if (!admitted()) return
                    mutable.value = mutable.value.copy(error = null)
                }
                pending = change(pending) { it.copy(operation = "BEGIN") }
                val reply = client.begin() // no retry even when the response is lost
                val rejected = reply.exceptionOrNull() as? UpdaterClient.Rejected
                if (rejected != null && rejected.code != "io") {
                    val actual = client.status().getOrNull()
                    if (actual != null && !actual.transaction && actual.id == pending.previous_root_id) {
                        change(pending) { it.copy(phase = "FAILED", operation = "", reason = rejected.code) }
                        mutable.value = mutable.value.copy(active = false, error = "interrupted")
                        return
                    }
                }
                return drive(admitted)
            }
        }
        require(status.id == pending.root_id) { "Root transaction changed" }
        if (!status.transaction) {
            if (status.result == 1 && pending.operation == "HEALTHY") {
                if (!executing(pending.to)) { mutable.value = mutable.value.copy(error = "package"); return }
                val current = try { verifier.verifyInstalled(pending.to, installedFile()).identity }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { mutable.value = mutable.value.copy(error = "package"); return }
                if (!ModuleUpdatePolicy.completedHealthy(pending, status, current)) {
                    mutable.value = mutable.value.copy(error = "interrupted"); return
                }
                val wasSafe = moe.kiwi.thinkos.shell.CrashRecovery.isSafeMode(context)
                val clearRecovery = pending.reason != "crash_loop"
                if (clearRecovery && !moe.kiwi.thinkos.shell.CrashRecovery.clearForUpdate(context, pending.id)) return
                change(pending) { it.copy(phase = "COMPLETE", operation = "", reason = null) }
                mutable.value = mutable.value.copy(active = false, completedDigest = pending.catalog_digest,
                    states = pending.selected.associate { it.id to "current" })
                if (clearRecovery && wasSafe && !moe.kiwi.thinkos.shell.CrashRecovery.isSafeMode(context)) {
                    moe.kiwi.thinkos.shell.CrashRecovery.schedule(context, 1_000)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            } else if (status.result == 2) {
                change(pending) { it.copy(phase = if (it.operation == "ROLLBACK" && it.reason == null) "CANCELLED" else "FAILED",
                    operation = "", reason = "rolled_back") }
                mutable.value = mutable.value.copy(active = false, error = "interrupted")
            }
            return
        }
        if (pending.operation == "ROLLBACK") {
            when (ModuleUpdatePolicy.recoverRollback(pending, status)) {
                ModuleUpdatePolicy.Rollback.START -> { client.rollback() }
                else -> mutable.value = mutable.value.copy(error = "interrupted")
            }
            return
        }
        if (status.phase == 6 && !status.rollback_attempted && pending.operation != "ROLLBACK") {
            pending = change(pending) { it.copy(operation = "ROLLBACK", operation_id = null, reason = "install") }
            client.rollback()
            return drive(admitted)
        }
        if (status.phase in setOf(5, 6) || status.rollback_attempted || pending.reason == "crash_loop") {
            healthStart = null
            mutable.value = mutable.value.copy(error = "interrupted", states = pending.selected.associate { it.id to "failed" })
            return
        }
        if (pending.operation == "STAGE") {
            val target = pending.selected.single { it.id == pending.operation_id }
            if (status.phase != 2 || status.modules.singleOrNull { it.id == target.id }?.staged != target.code) {
                if (status.phase in setOf(1, 2)) {
                    // STATUS held the root operation lock: this stage has no
                    // acknowledged side effect. Cancel the owned transaction,
                    // never send the uncertain STAGE a second time.
                    pending = change(pending) { it.copy(operation = "ROLLBACK", operation_id = null, reason = "interrupted") }
                    client.rollback(); drive(admitted)
                }
                return
            }
            pending = change(pending) { it.copy(operation = "", operation_id = null, staged = it.staged + target.id) }
            mutable.value = mutable.value.copy(states = mutable.value.states + (target.id to "staged"))
        }
        if (pending.operation == "INSTALL_APK") {
            // PackageManager publishing the new package in this OLD process is
            // insufficient: only code compiled into the new MAIN process may
            // advance to reboot/trial. Phase 3 is always unresolved.
            if (status.phase != 2 || !executing(pending.to)) {
                if (status.phase in setOf(1, 2) && UpdateIo.run { UpdateInstallContext.running(context).matches(pending.from) }) {
                    pending = change(pending) { it.copy(operation = "ROLLBACK", reason = "interrupted") }
                    client.rollback(); drive(admitted)
                }
                return
            }
            pending = change(pending) { it.copy(operation = "") }
        }
        if (pending.operation == "REBOOT") {
            if (!ModuleUpdatePolicy.rebootAcknowledged(pending, status, UpdateInstallContext.now(context).boot)) {
                if (status.phase == 2) {
                    pending = change(pending) { it.copy(operation = "ROLLBACK", reason = "interrupted") }
                    client.rollback(); drive(admitted)
                }
                return
            }
            pending = change(pending) { it.copy(operation = "") }
        }
        if (pending.operation.isNotEmpty()) return
        val keys = UpdateKeys.load(context).getOrNull() ?: return
        val catalog = UpdateIo.run { records.catalog(keys) }
        mutable.value = mutable.value.copy(metadataDigest = catalog.digest,
            sizes = catalog.document.release.modules.associate { it.id to it.size })
        if (pending.staged.size == pending.selected.size && executing(pending.to) &&
            ModuleUpdatePolicy.bootReady(pending, UpdateInstallContext.now(context).boot)) {
            // Trial/HEALTHY does not require an idle user: playback during an
            // otherwise healthy trial must not trigger a spurious rollback.
            verifier.verifyInstalled(pending.to, installedFile())
            if (!ModuleUpdatePolicy.modulesActive(pending, status)) { healthStart = null; return }
            finishHealth(pending)
            return
        }
        if (!admitted()) {
            // No new stage/install/reboot after live admission is lost. Before
            // APK submission an explicit rollback can safely cancel all stages.
            if (executing(pending.from) && pending.phase != "TRIAL") {
                pending = change(pending) { it.copy(operation = "ROLLBACK") }
                client.rollback(); drive { false }
            }
            return
        }
        val next = pending.selected.firstOrNull { it.id !in pending.staged }
        if (next != null) {
            val module = catalog.document.release.modules.single { it.id == next.id && it.code == next.code }
            verifyFiles(module, systemKeys ?: ModuleManifest.loadKeys(context).getOrNull() ?: return)
            pending = change(pending) { it.copy(operation = "STAGE", operation_id = module.id) }
            rejectOrPreserve(pending, sendStage(module))
            return drive(admitted)
        }
        if (!executing(pending.to)) {
            require(executing(pending.from))
            val file = apkFile(catalog, File(File(directory, "downloads"), pending.to.sha256 + ".apk"))
            verifier.verify(catalog, file)
            pending = change(pending) { it.copy(operation = "INSTALL_APK") }
            val reply = withContext(Dispatchers.IO) { FileInputStream(file).use { client.installApk(it.fd) } }
            rejectOrPreserve(pending, reply)
            return drive(admitted)
        }
        verifier.verifyInstalled(pending.to, installedFile())
        if (!ModuleUpdatePolicy.bootReady(pending, UpdateInstallContext.now(context).boot)) {
            require(pending.selected.isNotEmpty() && status.phase == 2)
            require(pending.reboot_boot == null) { "A recorded reboot cannot be submitted twice" }
            val beforeReboot = requireNotNull(UpdateInstallContext.now(context).boot)
            pending = change(pending) { it.copy(operation = "REBOOT", reboot_boot = beforeReboot) }
            rejectOrPreserve(pending, client.reboot()) // one persisted request, not a timer-driven retry
            return
        }
    }

    private suspend fun finishHealth(value: ModuleUpdatePending) {
        var pending = value
        if (pending.phase != "TRIAL") {
            val now = UpdateInstallContext.now(context)
            pending = change(pending) { it.copy(phase = "TRIAL", attempts = (it.attempts + 1).coerceAtMost(100),
                trial_id = UpdateInstaller.newId(), trial_started = now) }
        }
        val now = UpdateInstallContext.now(context)
        if (!frame || !catalogReady || now.boot == null || now.boot != pending.trial_started?.boot) { healthStart = null; return }
        val start = healthStart ?: now.also { healthStart = it }
        val finalStatus = client.status().getOrNull() ?: return
        if (!ModuleUpdatePolicy.healthReady(pending, finalStatus, start, now, frame, catalogReady)) return
        pending = change(pending) { it.copy(phase = "HEALTHY_PENDING", operation = "HEALTHY") }
        val reply = client.healthy()
        val rejected = reply.exceptionOrNull() as? UpdaterClient.Rejected
        if (rejected != null) {
            val actual = client.status().getOrNull()
            if (actual != null && ModuleUpdatePolicy.rollbackRejectedHealth(pending, actual)) {
                pending = change(pending) { it.copy(operation = "ROLLBACK", reason = rejected.code) }
                client.rollback()
            } else mutable.value = mutable.value.copy(error = "interrupted")
        }
        drive { true }
    }

    private suspend fun change(value: ModuleUpdatePending, transform: (ModuleUpdatePending) -> ModuleUpdatePending) =
        UpdateIo.run { records.change(value.id, transform) }
    private fun requireApkProof(catalog: UpdateCatalog, verified: UpdatePackageVerifier.Verified) {
        val target = identity(catalog)
        require(verified.catalogDigest == catalog.digest && verified.sha256 == target.sha256 && verified.bytes == target.size &&
            verified.versionCode == target.code && verified.signer == target.signer) { "Unrelated APK readiness proof" }
    }

    /** Only socket-authenticated STATUS contributes installed codes/results.
     * Target-scoped metadata is kept separate from the current installation. */
    private fun publishRoot(status: UpdaterStatusDocument, pending: ModuleUpdatePending?) {
        val result = when {
            pending == null || pending.root_id.isEmpty() || pending.root_id != status.id -> null
            !status.transaction && status.result == 1 -> "updated"
            !status.transaction && status.result == 2 -> if (pending.phase == "CANCELLED" ||
                pending.operation == "ROLLBACK" && pending.reason == null) "cancelled" else "rolled_back"
            status.phase == 6 && status.rollback_attempted && status.result == 3 -> "rollback_failed"
            !status.transaction && status.result == 3 -> "install_failed"
            else -> null
        }
        val outcomes = if (result != null && pending != null) pending.selected.filter { target ->
            status.modules.singleOrNull { it.id == target.id }?.staged == target.code
        }.associate { it.id to result } else emptyMap()
        mutable.value = mutable.value.copy(installedCodes = status.modules.associate { it.id to it.code },
            inventoryReadable = true, inventoryManaged = systemKeys != null && status.keys in 1..2,
            metadataDigest = if (outcomes.isNotEmpty()) pending!!.catalog_digest else mutable.value.metadataDigest,
            states = if (outcomes.isNotEmpty()) mutable.value.states + outcomes.mapValues { (_, outcome) -> when (outcome) {
                "updated" -> "current"; "cancelled" -> "skipped"; else -> "failed"
            } } else mutable.value.states,
            results = if (outcomes.isNotEmpty()) outcomes else mutable.value.results)
    }

    private suspend fun rejectOrPreserve(value: ModuleUpdatePending, result: Result<Unit>) {
        val rejection = result.exceptionOrNull() as? UpdaterClient.Rejected ?: return
        val actual = client.status().getOrNull() ?: return
        if (actual.id != value.root_id || !actual.transaction || actual.phase == 3 || actual.rollback_attempted) return
        // A complete rejection is a failure, not a cancelled/successful update.
        // Rollback is itself a once-recorded operation; transport loss leaves it
        // unresolved for the root guard instead of resubmitting an install.
        change(value) { it.copy(operation = "ROLLBACK", operation_id = null, reason = rejection.code) }
        client.rollback()
    }
    private fun selection(catalog: UpdateCatalog, additionalFeatures: Set<String> = emptySet()): List<UpdateModule> = ModuleUpdatePolicy.select(
        catalog.document.release.modules, requireNotNull(root), features() + additionalFeatures)

    private suspend fun fetchSelected(module: UpdateModule, fetcher: Fetcher, keys: ModuleManifest.Keys,
                                      progress: (Long) -> Unit) {
        val signed = fetchManifest(module, fetcher, keys)
        fetcher.download(module.urls, Fetcher.Artifact(Fetcher.ArtifactKind.MODULE, module.size, module.sha256), artifacts, progress)
        UpdateIo.run {
            val files = UpdateFiles(artifacts, "module-metadata.lock")
            files.locked(blocking = true) { dir ->
                UpdateFiles.write(dir, module.sha256 + ".manifest", signed.bytes(), 64 * 1024)
                UpdateFiles.write(dir, module.sha256 + ".sig", signed.signature(), 1024)
            }
        }
    }

    private suspend fun fetchManifest(module: UpdateModule, fetcher: Fetcher, keys: ModuleManifest.Keys): ModuleManifest {
        val failure = IOException("No authenticated system module manifest")
        for (url in module.manifest_urls) {
            try {
                val raw = fetcher.readDocument(listOf(url), Fetcher.DocumentKind.MANIFEST) as Fetcher.Document.Body
                val signature = fetcher.readDocument(listOf(UpdateCatalogClient.signatureUrl(url)), Fetcher.DocumentKind.SIGNATURE) as Fetcher.Document.Body
                return UpdateIo.run { ModuleManifest.read(raw.bytes, signature.bytes, keys, module) }
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failure.addSuppressed(error) }
        }
        throw failure
    }
    private suspend fun verifyFiles(module: UpdateModule, keys: ModuleManifest.Keys) = UpdateIo.run {
        val root = artifacts.canonicalFile
        ModuleManifest.read(requireNotNull(UpdateFiles.read(root, module.sha256 + ".manifest", 64 * 1024)),
            requireNotNull(UpdateFiles.read(root, module.sha256 + ".sig", 1024)), keys, module)
        val file = UpdateFiles.safe(root, module.sha256 + ".zip")
        require(file.isFile && !Files.isSymbolicLink(file.toPath()) && file.length() == module.size)
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) {
            val count = input.read(bytes); if (count < 0) break
            size += count; require(size <= module.size); hash.update(bytes, 0, count)
        } }
        require(size == module.size && hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == module.sha256)
    }
    private suspend fun sendStage(module: UpdateModule) = withContext(Dispatchers.IO) {
        val root = artifacts.canonicalFile
        FileInputStream(UpdateFiles.safe(root, module.sha256 + ".manifest")).use { manifest ->
            FileInputStream(UpdateFiles.safe(root, module.sha256 + ".sig")).use { signature ->
                FileInputStream(UpdateFiles.safe(root, module.sha256 + ".zip")).use { payload ->
                    client.stage(manifest.fd, signature.fd, payload.fd)
                }
            }
        }
    }
    private fun installedFile() = File(context.packageManager.getApplicationInfo(context.packageName, 0).sourceDir)
    @Suppress("DEPRECATION")
    private fun installedDevice(): UpdateCatalog.Device {
        val info = context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_SIGNATURES)
        return UpdateCatalog.Device(android.os.Build.DEVICE, android.os.Build.VERSION.SDK_INT, info.versionCode,
            UpdateCatalog.sha256(info.signatures!!.single().toByteArray()))
    }
    private fun executing(expected: UpdateApkIdentity) = BuildConfig.VERSION_CODE == expected.code && BuildConfig.VERSION_NAME == expected.version &&
        runCatching { UpdateInstallContext.running(context).matches(expected) }.getOrDefault(false)
    companion object {
        @Volatile private var instance: ModuleUpdateRuntime? = null
        fun get(context: Context): ModuleUpdateRuntime = instance ?: synchronized(this) {
            instance ?: ModuleUpdateRuntime(context.applicationContext).also { instance = it }
        }
        fun identity(catalog: UpdateCatalog) = catalog.document.release.let { UpdateApkIdentity(it.version, it.code, it.sha256, it.size, it.signer) }
    }
}

/** Pure protocol/policy decisions, independent of Android, socket or timers. */
internal object ModuleUpdatePolicy {
    fun rootAdmission(digest: String?, readyDigest: String?, keys: Int, active: Boolean, ownedDigest: String?,
                      apkChanges: Boolean, moduleChanges: Boolean): Boolean =
        digest != null && digest.matches(Regex("[a-f0-9]{64}")) && digest == readyDigest && keys in 1..2 &&
            (!active || ownedDigest == digest) && (apkChanges || moduleChanges)
    fun startup(value: ModuleUpdatePending, now: UpdateInstallTime, trialId: String): ModuleUpdatePending {
        val attempts = (value.attempts + 1).coerceAtMost(100)
        return value.copy(phase = when {
            attempts > value.max_attempts -> "UNKNOWN"
            value.operation == "HEALTHY" -> "HEALTHY_PENDING"
            else -> "TRIAL"
        }, attempts = attempts, trial_id = trialId, trial_started = now,
            reason = if (attempts > value.max_attempts) "crash_loop" else value.reason)
    }
    enum class Begin { ADOPT, NOT_ACCEPTED, CONFLICT }
    /** Full STATUS is serialized behind root operations. A durable intent with
     * no accepted root transaction is terminal, never a reason to resend BEGIN. */
    fun recoverBegin(value: ModuleUpdatePending, root: UpdaterStatusDocument): Begin {
        require(value.root_id.isEmpty() && value.operation == "BEGIN")
        return when {
            !root.transaction && root.id == value.previous_root_id -> Begin.NOT_ACCEPTED
            root.transaction && root.id != value.previous_root_id && root.phase == 1 && !root.rollback_attempted -> Begin.ADOPT
            else -> Begin.CONFLICT
        }
    }
    enum class Rollback { START, WAIT, TERMINAL, CONFLICT }
    fun recoverRollback(value: ModuleUpdatePending, root: UpdaterStatusDocument): Rollback {
        require(value.root_id.isNotEmpty() && value.operation == "ROLLBACK")
        return when {
            root.id != value.root_id -> Rollback.CONFLICT
            !root.transaction && root.result == 2 -> Rollback.TERMINAL
            root.transaction && root.keys in 1..2 && !root.rollback_attempted && root.phase in setOf(1, 2, 6) -> Rollback.START
            else -> Rollback.WAIT
        }
    }
    fun rollbackRejectedHealth(value: ModuleUpdatePending, root: UpdaterStatusDocument): Boolean =
        value.operation == "HEALTHY" && root.transaction && root.id == value.root_id && root.keys in 1..2 &&
            !root.rollback_attempted && root.phase in setOf(2, 4)
    /** A completed root ledger is historical evidence. Later user disabling a
     * module does not invalidate it; clearing THIS app's recovery still needs
     * its exact target bytes, signer and compiled/PM identity at the caller. */
    fun completedHealthy(value: ModuleUpdatePending, root: UpdaterStatusDocument, actual: UpdateApkIdentity): Boolean =
        value.operation == "HEALTHY" && root.id == value.root_id && !root.transaction && root.phase == 0 && root.result == 1 &&
            actual == value.to && value.selected.all { target -> root.modules.singleOrNull { it.id == target.id }?.floor?.let { it >= target.code } == true }
    fun select(modules: List<UpdateModule>, root: UpdaterStatusDocument, enabled: Set<String>): List<UpdateModule> {
        require(root.keys in 1..2 && !root.transaction)
        require(modules.map { it.id }.distinct().size == modules.size)
        require(root.modules.map { it.id }.distinct().size == root.modules.size)
        return modules.filter { module ->
            val installed = requireNotNull(root.modules.singleOrNull { it.id == module.id }) { "Unknown installed module" }
            require(installed.code >= 0) { "Unreadable installed module" }
            val chosen = when (module.policy) { "always" -> true; "installed" -> installed.code > 0; "feature" -> module.id in enabled; else -> error("Unknown module policy") }
            chosen && module.code > maxOf(installed.code, installed.floor)
        }
    }
    fun bootReady(value: ModuleUpdatePending, currentBoot: Long?): Boolean = value.selected.isEmpty() ||
        currentBoot != null && value.created.boot != null && value.reboot_boot != null &&
            value.reboot_boot >= value.created.boot && currentBoot > value.reboot_boot
    fun rebootAcknowledged(value: ModuleUpdatePending, root: UpdaterStatusDocument, currentBoot: Long?): Boolean =
        root.transaction && root.id == value.root_id && root.phase == 4 && !root.rollback_attempted && bootReady(value, currentBoot)
    fun modulesActive(value: ModuleUpdatePending, root: UpdaterStatusDocument): Boolean =
        root.transaction && root.id == value.root_id && (root.phase == 4 || value.selected.isEmpty() && root.phase == 2) && !root.rollback_attempted &&
            value.selected.all { target -> root.modules.singleOrNull { it.id == target.id }?.let {
                it.code == target.code && it.staged == target.code && it.active == true
            } == true }
    fun healthReady(value: ModuleUpdatePending, root: UpdaterStatusDocument, ready: UpdateInstallTime, now: UpdateInstallTime,
                    frame: Boolean, catalog: Boolean): Boolean = frame && catalog && value.phase == "TRIAL" &&
        value.attempts in 1..value.max_attempts && value.trial_started != null && now.boot != null &&
        bootReady(value, now.boot) && modulesActive(value, root) && now.boot == value.trial_started.boot &&
        ready.boot == now.boot && ready.elapsed >= value.trial_started.elapsed && now.elapsed >= ready.elapsed &&
        now.elapsed - ready.elapsed >= value.health_ms
}
