package moe.kiwi.thinkos.update

import java.io.File
import java.io.IOException

/** Durable workflow checkpoint, not signing/installation authority. Blocking IO.
 * A recovered READY record is only a hint to reverify the cached catalog and APK. */
internal class UpdateState(directory: File, private val sequences: UpdateSequenceStore) {
    enum class Phase { IDLE, AVAILABLE, DOWNLOADING, READY, INSTALLING, FAILED }
    enum class Error { NETWORK, METADATA, STORAGE, PACKAGE, INTERRUPTED, UNAVAILABLE, KEYS }
    data class Time(val boot: Long?, val elapsed: Long, val wall: Long?) {
        init { require((boot == null || boot >= 0) && elapsed >= 0 && (wall == null || wall >= 0)) }
    }
    class Corrupt(cause: Exception) : IOException("Corrupt update workflow state", cause)
    private val files = UpdateFiles(directory, "workflow.lock")

    fun snapshot(channel: String): UpdateStateDocument = files.locked { read(it, channel) }

    fun idle(channel: String): UpdateStateDocument = change(channel, null) { _, floor ->
        UpdateStateDocument(1, channel, Phase.IDLE.name, 0, 0, floor?.seq, floor?.digest)
    }

    fun available(catalog: UpdateCatalog, downloaded: Long = 0): UpdateStateDocument = change(catalog.document.channel, catalog) { old, floor ->
        require(downloaded in 0..catalog.document.release.size)
        if (old.catalog_digest == catalog.digest && old.size == catalog.document.release.size && old.downloaded == old.size &&
            old.phase in setOf(Phase.READY.name, Phase.INSTALLING.name)) old
        else UpdateStateDocument(1, catalog.document.channel, Phase.AVAILABLE.name, downloaded, catalog.document.release.size, floor!!.seq, floor.digest)
    }

    fun downloading(catalog: UpdateCatalog, downloaded: Long): UpdateStateDocument = change(catalog.document.channel, catalog) { old, floor ->
        require(old.catalog_digest == catalog.digest && old.phase in setOf(Phase.AVAILABLE.name, Phase.FAILED.name, Phase.DOWNLOADING.name, Phase.READY.name))
        require(downloaded in 0..catalog.document.release.size)
        UpdateStateDocument(1, old.channel, Phase.DOWNLOADING.name, downloaded, catalog.document.release.size, floor!!.seq, floor.digest)
    }

    fun progress(catalog: UpdateCatalog, downloaded: Long): UpdateStateDocument = change(catalog.document.channel, catalog) { old, _ ->
        require(old.phase == Phase.DOWNLOADING.name && old.catalog_digest == catalog.digest)
        require(downloaded in old.downloaded..old.size) { "Update progress moved backwards" }
        old.copy(downloaded = downloaded)
    }

    fun ready(catalog: UpdateCatalog, verified: UpdatePackageVerifier.Verified, now: Time, modulesReady: Boolean = false): UpdateStateDocument =
        change(catalog.document.channel, catalog) { old, floor ->
            require(catalog.document.release.modules.isEmpty() || modulesReady) { "Module readiness requires the module updater" }
            require(verified.catalogDigest == catalog.digest && verified.sha256 == catalog.document.release.sha256 &&
                    verified.bytes == catalog.document.release.size && verified.versionCode == catalog.document.release.code &&
                    verified.signer == catalog.document.release.signer) { "Verified APK belongs to a different update" }
            require(old.catalog_digest == catalog.digest && old.phase in setOf(Phase.DOWNLOADING.name, Phase.READY.name))
            if (old.phase == Phase.READY.name && old.size == verified.bytes && old.downloaded == verified.bytes) old else UpdateStateDocument(1, old.channel, Phase.READY.name,
                verified.bytes, verified.bytes, floor!!.seq, floor.digest,
                ready_elapsed = now.elapsed, ready_boot = now.boot, ready_wall = now.wall)
        }

    /** Only an installation adapter which has reverified bytes may call this.
     * This method does not submit a session or reset the separate transaction guard. */
    fun installing(catalog: UpdateCatalog, verified: UpdatePackageVerifier.Verified): UpdateStateDocument = change(catalog.document.channel, catalog) { old, _ ->
        require(old.phase == Phase.READY.name && old.catalog_digest == catalog.digest && old.size == verified.bytes)
        require(verified.catalogDigest == catalog.digest && verified.sha256 == catalog.document.release.sha256 &&
                verified.bytes == catalog.document.release.size && verified.versionCode == catalog.document.release.code &&
                verified.signer == catalog.document.release.signer)
        old.copy(phase = Phase.INSTALLING.name)
    }

    fun failed(channel: String, error: Error): UpdateStateDocument = change(channel, null) { old, floor ->
        val same = floor != null && old.seq == floor.seq && old.catalog_digest == floor.digest
        UpdateStateDocument(1, channel, Phase.FAILED.name, if (same) old.downloaded else 0,
            if (same) old.size else 0, floor?.seq, floor?.digest, error.name.lowercase())
    }

    /** Explicit repair loses only disposable progress. It requires current signed
     * metadata and can only produce AVAILABLE, never READY or INSTALLING. */
    fun rebuild(catalog: UpdateCatalog): UpdateStateDocument = files.locked { root ->
        sequences.requireCurrent(catalog)
        val old = try { read(root, catalog.document.channel) } catch (_: Corrupt) { null }
        if (old?.seq != null && (old.seq > catalog.document.seq || old.seq == catalog.document.seq && old.catalog_digest != catalog.digest)) {
            throw UpdateSequenceStore.Rollback()
        }
        require(old?.phase != Phase.INSTALLING.name) { "Installation recovery belongs to the transaction guard" }
        val result = UpdateStateDocument(1, catalog.document.channel, Phase.AVAILABLE.name, 0,
            catalog.document.release.size, catalog.document.seq, catalog.digest)
        write(root, result); result
    }

    private fun change(channel: String, catalog: UpdateCatalog?, block: (UpdateStateDocument, UpdateSequence?) -> UpdateStateDocument): UpdateStateDocument =
        files.locked { root ->
            catalog?.let { require(it.document.channel == channel); sequences.requireCurrent(it) }
            val ledger = sequences.snapshot()
            val floor = if (channel == "stable") ledger.stable else ledger.beta
            val old = read(root, channel)
            if (old.seq != null && (floor == null || old.seq > floor.seq || old.seq == floor.seq && old.catalog_digest != floor.digest)) {
                throw IOException("Update state and sequence ledger disagree")
            }
            val next = block(old, floor)
            validate(next)
            if (next != old || !UpdateFiles.safe(root, name(channel)).exists()) write(root, next)
            next
        }

    private fun read(root: File, channel: String): UpdateStateDocument {
        val raw = try { UpdateFiles.read(root, name(channel), MAX_BYTES) } catch (error: UpdateFiles.Oversized) { throw Corrupt(error) }
            ?: return UpdateStateDocument(1, channel, Phase.IDLE.name, 0, 0)
        return try {
            UpdateModels.readUpdateStateDocument(UpdateJson.read(raw, MAX_BYTES)).also {
                require(it.channel == channel); validate(it)
            }
        } catch (error: Exception) { throw Corrupt(error) }
    }

    private fun write(root: File, value: UpdateStateDocument) {
        validate(value)
        UpdateFiles.write(root, name(value.channel), UpdateModels.writeUpdateStateDocument(value).toString().toByteArray(Charsets.UTF_8), MAX_BYTES)
    }

    private fun name(channel: String): String {
        require(channel in setOf("stable", "beta"))
        return "state-$channel.json"
    }

    private fun validate(value: UpdateStateDocument) {
        require(value.downloaded in 0..value.size && (value.seq == null) == (value.catalog_digest == null))
        val phase = Phase.valueOf(value.phase)
        require((phase == Phase.FAILED) == (value.error != null))
        if (phase in setOf(Phase.AVAILABLE, Phase.DOWNLOADING, Phase.READY, Phase.INSTALLING)) require(value.seq != null && value.size > 0)
        if (phase == Phase.IDLE) require(value.size == 0L)
        if (phase in setOf(Phase.READY, Phase.INSTALLING)) require(value.downloaded == value.size && value.ready_elapsed != null)
        else require(value.ready_elapsed == null && value.ready_boot == null && value.ready_wall == null)
    }

    companion object {
        private const val MAX_BYTES = 16 * 1024
        fun waitingReadyMillis(value: UpdateStateDocument, now: Time): Long = when {
            value.phase !in setOf(Phase.READY.name, Phase.INSTALLING.name) -> 0
            now.boot != null && value.ready_boot == now.boot && value.ready_elapsed != null && now.elapsed >= value.ready_elapsed -> now.elapsed - value.ready_elapsed
            now.wall != null && value.ready_wall != null && now.wall >= value.ready_wall -> now.wall - value.ready_wall
            else -> 0
        }
    }
}
