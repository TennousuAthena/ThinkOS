package moe.kiwi.thinkos.update

import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** Fixed APK-install filenames only. Every call belongs inside the existing install.lock. */
internal object UpdateRepairFiles {
    const val MARKER = "install-repair.json"
    private const val META_MAX = 16 * 1024
    // Existing APK artifact envelope; damaged records cannot cause an unbounded hash/read.
    private const val EVIDENCE_MAX = 64L * 1024 * 1024

    data class Token(val pending: UpdateRepairFile?, val previous: UpdateRepairFile?,
                     val history: UpdateRepairFile?, val marker: UpdateRepairFile?)
    data class Snapshot(val token: Token, val corruptPending: Boolean, val corruptHistory: Boolean,
                        val record: UpdateRepairRecord?) {
        val needed get() = corruptPending || corruptHistory || record?.state == "PREPARED"
    }
    data class Evidence(val current: UpdateApkIdentity, val rootState: String, val recorded: UpdateInstallTime)
    class Changed : IOException("Install recovery evidence changed")

    fun marker(root: File): UpdateRepairRecord? = UpdateFiles.read(root, MARKER, META_MAX)?.let {
        UpdateModels.readUpdateRepairRecord(UpdateJson.read(it, META_MAX)).also { value ->
            require(value.state == "ISOLATED" || value.bound_transaction == null)
        }
    }

    fun snapshot(root: File, corruptPending: Boolean, corruptHistory: Boolean): Snapshot {
        val token = token(root)
        return Snapshot(token, corruptPending, corruptHistory, marker(root))
    }

    private fun token(root: File) = Token(stamp(root, "pending.json"), stamp(root, "previous.apk"),
        stamp(root, "install-history.json"), stamp(root, MARKER))

    /** Hash both before and after native/package checks, so a stale UI cannot remove a new transaction. */
    fun isolate(root: File, expected: Snapshot, evidence: () -> Evidence,
                checkActive: () -> Unit = {}, id: () -> String = UpdateInstaller::newId): UpdateRepairRecord {
        if (expected.corruptHistory || !expected.needed || token(root) != expected.token) throw Changed()
        val prior = marker(root)
        val existing = prior?.takeIf { it.state == "PREPARED" }
        val proof = evidence()
        check(proof.rootState in setOf("absent", "idle"))
        checkActive()
        if (token(root) != expected.token) throw Changed()
        // Resume the exact prior isolation intent after a power/process interruption.
        // No bytes from a corrupt pending document are parsed for this decision.
        val record = existing ?: UpdateRepairRecord(schema = 1, id = id(), state = "PREPARED", current = proof.current,
            pending = requireNotNull(expected.token.pending), root_state = proof.rootState, recorded = proof.recorded,
            previous = expected.token.previous, history = expected.token.history, prior_marker = expected.token.marker)
        if (record.current != proof.current) throw Changed()
        if (record.history != expected.token.history) throw Changed()
        val archive = archive(root, record.id, create = existing == null)
        if (existing == null) {
            if (record.prior_marker != null) UpdateFiles.write(archive, "prior-repair.json",
                requireNotNull(UpdateFiles.read(root, MARKER, META_MAX)), META_MAX)
            UpdateFiles.write(archive, "proof.json", encode(record), META_MAX)
            UpdateFiles.write(root, MARKER, encode(record), META_MAX)
        } else {
            val raw = requireNotNull(UpdateFiles.read(archive, "proof.json", META_MAX))
            val retained = UpdateModels.readUpdateRepairRecord(UpdateJson.read(raw, META_MAX))
            require(retained == record || retained.state == "ISOLATED" && retained.copy(state = "PREPARED") == record) {
                "Isolation receipt changed"
            }
        }
        // Backup first, pending last. A crash in between leaves pending blocking
        // new installs; the prepared marker prevents prepare overwriting evidence.
        checkActive()
        move(root, archive, "previous.apk", record.previous)
        checkActive()
        move(root, archive, "pending.json", record.pending)
        val isolated = record.copy(state = "ISOLATED")
        UpdateFiles.write(archive, "proof.json", encode(isolated), META_MAX)
        UpdateFiles.write(root, MARKER, encode(isolated), META_MAX)
        return isolated
    }

    /** Called only by authenticated prepare after native verification of current and target APKs. */
    fun requireInstallable(root: File, installed: UpdateApkIdentity) {
        val record = marker(root) ?: return
        require(record.state == "ISOLATED" && record.current == installed) { "Recovery package identity changed" }
    }

    fun bind(root: File, transaction: String, installed: UpdateApkIdentity) {
        val record = marker(root) ?: return
        require(record.state == "ISOLATED" && record.current == installed) { "Recovery package identity changed" }
        UpdateFiles.write(root, MARKER, encode(record.copy(bound_transaction = transaction)), META_MAX)
    }

    fun allowsClear(root: File, transaction: String): Boolean = marker(root)?.let {
        it.state == "ISOLATED" && it.bound_transaction == transaction
    } ?: true

    fun acknowledged(root: File, transaction: String) {
        if (marker(root)?.bound_transaction == transaction) UpdateFiles.delete(root, MARKER)
    }

    private fun encode(value: UpdateRepairRecord) = UpdateModels.writeUpdateRepairRecord(value).toString().toByteArray(Charsets.UTF_8)

    private fun archive(root: File, id: String, create: Boolean): File {
        require(id.matches(Regex("[a-f0-9]{32}")))
        val directory = File(root, "install-quarantine")
        require(!Files.isSymbolicLink(directory.toPath()) && directory.canonicalFile == directory.absoluteFile)
        if (!directory.exists()) {
            if (!create || !directory.mkdir()) throw IOException("Cannot create isolation directory")
            sync(root)
        }
        require(directory.isDirectory)
        val target = File(directory, id)
        require(!Files.isSymbolicLink(target.toPath()) && target.canonicalFile == target.absoluteFile)
        if (create) {
            if (target.exists() || !target.mkdir()) throw IOException("Isolation identifier already exists")
            sync(directory)
        } else require(target.isDirectory)
        return target
    }

    private fun move(root: File, archive: File, name: String, expected: UpdateRepairFile?) {
        val original = stamp(root, name)
        val retained = stamp(archive, name)
        if (expected == null) { if (original != null || retained != null) throw Changed(); return }
        if (original == null) {
            // rename preserves inode/key; partial prior work is accepted only by exact fingerprint.
            if (retained != expected) throw Changed()
            return
        }
        if (original != expected || retained != null) throw Changed()
        Files.move(UpdateFiles.safe(root, name).toPath(), UpdateFiles.safe(archive, name).toPath(), StandardCopyOption.ATOMIC_MOVE)
        sync(archive); sync(root)
        if (stamp(archive, name) != expected) throw Changed()
    }

    internal fun stamp(root: File, name: String): UpdateRepairFile? {
        val file = UpdateFiles.safe(root, name)
        if (!Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) return null
        fun attributes() = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)
        val before = attributes()
        require(before.isRegularFile && !before.isSymbolicLink && before.size() in 0..EVIDENCE_MAX) { "Invalid recovery evidence file" }
        val key = requireNotNull(before.fileKey()).toString()
        val hash = MessageDigest.getInstance("SHA-256")
        var count = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val size = input.read(buffer); if (size < 0) break
                count += size; require(count <= before.size() && count <= EVIDENCE_MAX)
                hash.update(buffer, 0, size)
            }
        }
        val after = attributes()
        if (count != before.size() || before.fileKey() != after.fileKey() || before.size() != after.size() ||
            before.lastModifiedTime() != after.lastModifiedTime()) throw Changed()
        return UpdateRepairFile(count, hash.digest().joinToString("") { "%02x".format(it.toInt() and 255) }, key, before.lastModifiedTime().toString())
    }

    private fun sync(directory: File) = FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
}
