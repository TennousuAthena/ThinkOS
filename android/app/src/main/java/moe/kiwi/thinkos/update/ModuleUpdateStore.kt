package moe.kiwi.thinkos.update

import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** Own journal, separate from the proven APK-only PackageInstaller guard. Root
 * owns backups/rollback. A local completed record is written only after STATUS
 * proves this exact root transaction is healthy; lost replies retain intent. */
internal class ModuleUpdateStore(private val directory: File) {
    private val files = UpdateFiles(directory, "module-update.lock")
    private fun <T> locked(block: (File) -> T): T = synchronized(locks.computeIfAbsent(directory.canonicalPath) { Any() }) {
        files.locked(blocking = true, block = block)
    }
    fun snapshot(): ModuleUpdatePending? = locked(::read)
    /** Recovery never starts/cancels a root operation. Hold this across APK isolation. */
    fun <T> withRepairFence(block: (Boolean) -> T): T = locked { root ->
        val local = read(root)
        require(local == null || terminal(local)) { "Unresolved module transaction" }
        block(local != null)
    }
    fun blocked(digest: String): Boolean = locked { root ->
        require(digest.matches(Regex("[a-f0-9]{64}")))
        UpdateFiles.read(root, "module-failed-$digest.json", MAX_BYTES)?.let {
            val record = UpdateModels.readModuleUpdatePending(UpdateJson.read(it, MAX_BYTES)).also(::validate)
            require(record.catalog_digest == digest && record.phase == "FAILED")
            true
        } ?: false
    }
    fun prepare(value: ModuleUpdatePending, catalog: UpdateCatalog): ModuleUpdatePending = locked { root ->
        require(!File(root, UpdateRepairFiles.MARKER).exists()) { "APK recovery must complete before root distribution" }
        require(read(root)?.let(::terminal) != false) { "An unresolved module transaction exists" }
        require(value.catalog_digest == catalog.digest && value.phase == "PREPARED" && value.operation.isEmpty())
        UpdateFiles.write(root, "module-catalog.json", catalog.bytes(), SignedDocument.Kind.CATALOG.maxBytes)
        UpdateFiles.write(root, "module-catalog.sig", catalog.signature(), 1024)
        write(root, value); value
    }
    fun change(id: String, transform: (ModuleUpdatePending) -> ModuleUpdatePending): ModuleUpdatePending = locked { root ->
        val old = requireNotNull(read(root)); require(old.id == id)
        transform(old).also { next ->
            require(next.id == old.id && next.catalog_digest == old.catalog_digest && next.from == old.from && next.to == old.to &&
                next.selected == old.selected && next.created == old.created && next.health_ms == old.health_ms && next.poll_ms == old.poll_ms &&
                next.max_attempts == old.max_attempts && next.mode == old.mode && next.channel == old.channel && next.feature == old.feature &&
                next.previous_root_id == old.previous_root_id && (old.root_id.isEmpty() || next.root_id == old.root_id) && next.attempts >= old.attempts &&
                (old.reboot_boot == null || old.reboot_boot == next.reboot_boot))
            write(root, next)
        }
    }
    fun catalog(keys: UpdateKeys): UpdateCatalog = locked { root ->
        val pending = requireNotNull(read(root))
        UpdateCatalog.read(requireNotNull(UpdateFiles.read(root, "module-catalog.json", SignedDocument.Kind.CATALOG.maxBytes)),
            requireNotNull(UpdateFiles.read(root, "module-catalog.sig", 1024)), keys, pending.channel).also { catalog ->
            require(catalog.digest == pending.catalog_digest)
            val release = catalog.document.release
            require(UpdateApkIdentity(release.version, release.code, release.sha256, release.size, release.signer) == pending.to)
            require(pending.selected.all { target -> catalog.document.release.modules.any { it.id == target.id && it.code == target.code } })
            if (pending.feature != null) require(catalog.document.release.modules.any { it.id == FeatureScope.MODULE && it.policy == "feature" })
        }
    }
    private fun read(root: File): ModuleUpdatePending? = try {
        UpdateFiles.read(root, "module-pending.json", MAX_BYTES)?.let {
            UpdateModels.readModuleUpdatePending(UpdateJson.read(it, MAX_BYTES)).also(::validate)
        }
    } catch (error: Exception) { throw IOException("Invalid module update journal", error) }
    private fun write(root: File, value: ModuleUpdatePending) {
        validate(value)
        val raw = UpdateModels.writeModuleUpdatePending(value).toString().toByteArray()
        if (value.phase == "FAILED") UpdateFiles.write(root, "module-failed-${value.catalog_digest}.json", raw, MAX_BYTES)
        UpdateFiles.write(root, "module-pending.json", raw, MAX_BYTES)
    }
    companion object {
        private const val MAX_BYTES = 64 * 1024
        private val locks = ConcurrentHashMap<String, Any>()
        fun terminal(value: ModuleUpdatePending) = value.phase in setOf("COMPLETE", "CANCELLED", "FAILED") && value.operation.isEmpty()
        fun validate(value: ModuleUpdatePending) {
            UpdateModels.writeModuleUpdatePending(value)
            require(value.from.signer == value.to.signer && value.from.code <= value.to.code)
            require(value.from.code != value.to.code || value.from == value.to)
            require(value.selected.map { it.id }.distinct().size == value.selected.size)
            if (value.feature != null) require(value.feature == FeatureScope.FEATURE && value.mode == "manual" &&
                value.selected.any { it.id == FeatureScope.MODULE })
            require(value.staged.all { id -> value.selected.any { it.id == id } })
            require(value.operation == "STAGE" || value.operation_id == null)
            if (value.operation == "STAGE") require(value.selected.any { it.id == value.operation_id })
            require(value.root_id.isEmpty() || value.root_id != value.previous_root_id)
            if (value.reboot_boot != null) require(value.created.boot != null && value.reboot_boot >= value.created.boot)
            if (value.operation in setOf("STAGE", "INSTALL_APK", "REBOOT", "HEALTHY", "ROLLBACK")) require(value.root_id.isNotEmpty())
            if (value.phase == "TRIAL") {
                require(value.trial_id != null && value.trial_started != null && value.attempts in 1..value.max_attempts)
                if (value.selected.isNotEmpty()) require(value.reboot_boot != null && value.trial_started.boot != null && value.trial_started.boot > value.reboot_boot)
            }
        }
    }
}
