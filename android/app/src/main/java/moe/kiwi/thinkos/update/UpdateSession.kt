package moe.kiwi.thinkos.update

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.kiwi.thinkos.core.Attention
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Production check/download/install workflow. No implicit network on construction.
 * The runtime adapter owns settings, scheduling and five-second manual authorization. */
internal class UpdateSession(
    private val channel: String,
    private val client: UpdateCatalogClient,
    private val state: UpdateState,
    private val downloads: File,
    private val verifier: UpdatePackageVerifier,
    private val device: () -> UpdateCatalog.Device,
    private val time: () -> UpdateState.Time,
    private val installs: UpdateInstallStore? = null,
    private val modules: ModuleUpdateRuntime? = null,
    private val moduleFetcher: Fetcher? = null,
) {
    data class Environment(val attention: Attention, val video: Boolean, val casting: Boolean, val foreign: Boolean = false, val media: Boolean = video) {
        fun allows(manual: Boolean) = !foreign && UpdateSchedule.downloadAllowed(attention, manual, video, casting)
        fun allowsInstall() = !video && !media && !casting && !foreign
    }
    private class Paused : CancellationException("Update paused by live media/attention policy")
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(UpdateStateDocument(1, channel, UpdateState.Phase.IDLE.name, 0, 0))
    val status: StateFlow<UpdateStateDocument> = mutable
    data class Offer(val digest: String, val release: UpdateRelease, val ineligible: UpdateCatalog.Ineligible?)
    @Volatile var offer: Offer? = null; private set
    private var catalog: UpdateCatalog? = null
    private var recovered = false
    init { require(channel in setOf("stable", "beta")) }

    /** No network. READY is emitted only after reauthenticating metadata and parsing
     * the actual cached APK again. A half-written transfer becomes AVAILABLE. */
    suspend fun restore(): UpdateStateDocument = mutex.withLock { restoreLocked().also { recovered = true } }

    private suspend fun restoreLocked(): UpdateStateDocument {
        val cached = client.cached(channel)
        catalog = cached
        offer = null
        if (cached == null) return publish(UpdateIo.run { state.idle(channel) })
        if (modules?.pendingDigest() == cached.digest) {
            offer = Offer(cached.digest, cached.document.release, null)
            val prior = UpdateIo.run { state.snapshot(channel) }
            if (prior.catalog_digest == cached.digest && prior.phase == UpdateState.Phase.INSTALLING.name) return publish(prior)
        }
        val reason = eligibility(cached)
        offer = Offer(cached.digest, cached.document.release, reason)
        if (reason != null) return publish(UpdateIo.run { state.idle(channel) })
        var prior = try { UpdateIo.run { state.snapshot(channel) } } catch (_: UpdateState.Corrupt) {
            UpdateIo.run { state.rebuild(cached) }
        }
        if (prior.phase == UpdateState.Phase.INSTALLING.name) {
            val pending = UpdateIo.run { installs?.snapshot()?.pending }
            if (pending?.catalog_digest == cached.digest) return publish(prior)
            return publish(UpdateIo.run { state.failed(channel, UpdateState.Error.INTERRUPTED) })
        }
        if (prior.catalog_digest != cached.digest || prior.phase !in setOf(UpdateState.Phase.READY.name, UpdateState.Phase.DOWNLOADING.name)) {
            prior = UpdateIo.run { state.available(cached, partialBytes(cached)) }
        }
        val target = UpdateIo.run { target(cached) }
        if (prior.phase in setOf(UpdateState.Phase.READY.name, UpdateState.Phase.DOWNLOADING.name) && target.exists()) {
            try {
                val verified = verify(cached, target)
                val ready = modulesReady(cached, verified)
                return publish(UpdateIo.run { state.ready(cached, verified, time(), ready) })
            } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                // Keep authenticated metadata and .part; corrupt final bytes are
                // rechecked/removed by Fetcher when a permitted download starts.
            }
        }
        // Explicitly clear the old READY/INSTALLING hint before offering a retry.
        if (prior.phase == UpdateState.Phase.READY.name) UpdateIo.run { state.failed(channel, UpdateState.Error.PACKAGE) }
        return publish(UpdateIo.run { state.available(cached, partialBytes(cached)) })
    }

    suspend fun check(candidates: List<String>): UpdateCatalogClient.Checked = mutex.withLock {
        try {
            val checked = client.checkDetailed(candidates, channel)
            if (!recovered) { restoreLocked(); recovered = true }
            catalog = checked.catalog
            if (checked.catalog.document.release.modules.isNotEmpty()) modules?.refresh()
            val reason = eligibility(checked.catalog)
            offer = Offer(checked.catalog.digest, checked.catalog.document.release, reason)
            if (modules != null && !modules.view.value.active && modules.pendingDigest() == null &&
                UpdateIo.run { installs?.snapshot()?.pending } == null) {
                UpdateIo.run {
                    if (state.snapshot(channel).phase == UpdateState.Phase.INSTALLING.name) state.failed(channel, UpdateState.Error.INTERRUPTED)
                }
            }
            val next = UpdateIo.run {
                if (reason == null) state.available(checked.catalog, partialBytes(checked.catalog)) else state.idle(channel)
            }
            publish(next)
            checked
        } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
            recordFailure(UpdateState.Error.NETWORK)
            throw error
        }
    }

    suspend fun download(manual: Boolean, environment: StateFlow<Environment>): UpdatePackageVerifier.Verified? = mutex.withLock {
        if (!recovered) { restoreLocked(); recovered = true }
        val selected = catalog ?: client.cached(channel)?.also { catalog = it } ?: throw IOException("No authenticated update catalog")
        require(eligibility(selected) == null) { "Update is no longer eligible" }
        if (!environment.value.allows(manual)) return@withLock null
        require(selected.document.release.modules.isEmpty() || modules != null && moduleFetcher != null) { "Module downloads require the module updater" }
        publish(UpdateIo.run {
            state.available(selected, partialBytes(selected))
            state.downloading(selected, partialBytes(selected))
        })
        val paused = AtomicBoolean(false)
        try {
            val verified = coroutineScope {
                val transfer = async {
                    if (!environment.value.allows(manual)) { paused.set(true); throw Paused() }
                    val verified = if (selected.document.release.code == UpdateIo.run(device).versionCode && modules != null)
                        modules.verifyApk(selected, UpdateIo.run { target(selected) }) else
                        client.downloadVerifiedApk(selected, UpdateIo.run(device), downloads, verifier) { bytes ->
                        val before = mutable.value
                        val next = if (bytes < before.downloaded) state.downloading(selected, bytes) else state.progress(selected, bytes)
                        publish(next)
                    }
                    if (modules != null && moduleFetcher != null) modules.download(selected, moduleFetcher, verified)
                    verified
                }
                val watcher = launch {
                    environment.first { !it.allows(manual) }
                    paused.set(true); transfer.cancel(Paused())
                }
                try { transfer.await() } finally { watcher.cancelAndJoin() }
            }
            val ready = modulesReady(selected, verified)
            publish(UpdateIo.run { state.ready(selected, verified, time(), ready) })
            verified
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                publish(UpdateIo.run { state.available(selected, partialBytes(selected)) })
            }
            if (paused.get()) null else throw cancelled
        } catch (error: Exception) {
            recordFailure(when (error) {
                is Fetcher.StorageFailure -> UpdateState.Error.STORAGE
                is IllegalArgumentException -> UpdateState.Error.PACKAGE
                else -> UpdateState.Error.NETWORK
            })
            throw error
        }
    }

    suspend fun install(manual: Boolean, environment: StateFlow<Environment>,
                        perform: suspend (UpdateCatalog, File, Boolean) -> UpdateInstaller.Outcome): Boolean = mutex.withLock {
        if (!recovered) { restoreLocked(); recovered = true }
        val selected = catalog ?: return@withLock false
        if (mutable.value.phase != UpdateState.Phase.READY.name || eligibility(selected) != null || !environment.value.allowsInstall()) return@withLock false
        val file = UpdateIo.run { target(selected) }
        try {
            val verified = verify(selected, file)
            require(modulesReady(selected, verified))
            if (!environment.value.allowsInstall()) return@withLock false
            publish(UpdateIo.run { state.installing(selected, verified) })
            when (perform(selected, file, manual)) {
                UpdateInstaller.Outcome.SUBMITTED -> true
                UpdateInstaller.Outcome.CANCELLED -> {
                    restoreReady(selected, verified); false
                }
                else -> { recordFailure(UpdateState.Error.INTERRUPTED); false }
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                if (UpdateIo.run { installs?.snapshot()?.pending } == null) recordFailure(UpdateState.Error.INTERRUPTED)
            }
            throw cancelled
        } catch (error: Exception) {
            recordFailure(if (error is IllegalArgumentException) UpdateState.Error.PACKAGE else UpdateState.Error.STORAGE)
            throw error
        }
    }

    private suspend fun restoreReady(selected: UpdateCatalog, verified: UpdatePackageVerifier.Verified) {
        val ready = modulesReady(selected, verified)
        publish(UpdateIo.run {
            state.failed(channel, UpdateState.Error.INTERRUPTED)
            state.downloading(selected, verified.bytes)
            state.ready(selected, verified, time(), ready)
        })
    }

    private suspend fun eligibility(selected: UpdateCatalog): UpdateCatalog.Ineligible? {
        val current = UpdateIo.run(device)
        val reason = if (modules != null) modules.eligibility(selected, current) else selected.ineligible(current)
        return reason ?: UpdateIo.run { if (installs?.blocked(selected.document.release.code) == true) UpdateCatalog.Ineligible.BLACKLIST else null }
    }
    private suspend fun verify(selected: UpdateCatalog, file: File) = if (modules != null && selected.document.release.modules.isNotEmpty())
        modules.verifyApk(selected, file) else verifier.verify(selected, file)
    private suspend fun modulesReady(selected: UpdateCatalog, verified: UpdatePackageVerifier.Verified): Boolean =
        modules?.ready(selected, verified) ?: selected.document.release.modules.isEmpty()

    private suspend fun recordFailure(error: UpdateState.Error) {
        try { publish(UpdateIo.run { state.failed(channel, error) }) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            // The last in-memory status must not claim READY after an IO failure.
            mutable.value = UpdateStateDocument(1, channel, UpdateState.Phase.FAILED.name, 0, 0, error = UpdateState.Error.STORAGE.name.lowercase())
        }
    }

    private fun publish(value: UpdateStateDocument): UpdateStateDocument { mutable.value = value; return value }
    private fun target(catalog: UpdateCatalog): File {
        val file = File(downloads.canonicalFile, catalog.document.release.sha256+".apk")
        return if (catalog.document.release.modules.isNotEmpty()) modules?.apkFile(catalog, file) ?: file else file
    }
    private fun partialBytes(catalog: UpdateCatalog): Long {
        val path = File(downloads.canonicalFile, catalog.document.release.sha256+".apk.part")
        return if (path.isFile && !Files.isSymbolicLink(path.toPath())) path.length().coerceIn(0, catalog.document.release.size) else 0
    }
}
