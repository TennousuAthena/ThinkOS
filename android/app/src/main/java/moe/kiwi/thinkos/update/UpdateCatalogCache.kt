package moe.kiwi.thinkos.update

import java.io.File
import java.io.IOException
import java.util.Base64

/** Raw-byte/signature pair is one atomic record. Metadata is reverified on every read.
 * Sequence authority stays in UpdateSequenceStore, independently of this disposable cache. */
internal class UpdateCatalogCache(directory: File, private val keys: UpdateKeys) {
    class Invalid(cause: Exception) : IOException("Invalid authenticated update cache", cause)
    data class Entry(val catalog: UpdateCatalog, val source: String, val etag: String?)
    private val files = UpdateFiles(directory, "catalog-cache.lock")

    fun read(channel: String): Entry? = files.locked { root ->
        val name = name(channel)
        val raw = try { UpdateFiles.read(root, name, MAX_BYTES) } catch (error: UpdateFiles.Oversized) { throw Invalid(error) }
            ?: return@locked null
        try {
            val record = UpdateModels.readUpdateCachedCatalogDocument(UpdateJson.read(raw, MAX_BYTES))
            require(record.channel == channel) { "Cache channel mismatch" }
            UpdateCatalog.validateUrl(record.source)
            val catalog = UpdateCatalog.read(decode(record.body), decode(record.signature), keys, channel)
            Entry(catalog, record.source, record.etag)
        } catch (error: Exception) { throw Invalid(error) }
    }

    fun write(catalog: UpdateCatalog, source: String, etag: String?, sequences: UpdateSequenceStore) = files.locked { root ->
        UpdateCatalog.validateUrl(source)
        sequences.requireCurrent(catalog)
        val record = UpdateCachedCatalogDocument(1, catalog.document.channel,
            Base64.getEncoder().encodeToString(catalog.bytes()), Base64.getEncoder().encodeToString(catalog.signature()), source, etag)
        val raw = UpdateModels.writeUpdateCachedCatalogDocument(record).toString().toByteArray(Charsets.UTF_8)
        UpdateFiles.write(root, name(catalog.document.channel), raw, MAX_BYTES)
    }

    private fun name(channel: String): String {
        require(channel in setOf("stable", "beta"))
        return "catalog-$channel.json"
    }

    private fun decode(encoded: String): ByteArray = Base64.getDecoder().decode(encoded).also {
        require(Base64.getEncoder().encodeToString(it) == encoded) { "Noncanonical cached Base64" }
    }

    private companion object { const val MAX_BYTES = 2 * 1024 * 1024 }
}
