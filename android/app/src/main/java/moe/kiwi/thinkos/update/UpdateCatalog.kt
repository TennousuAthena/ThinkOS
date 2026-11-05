package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.net.LocalNetworkPolicy
import java.net.URI
import java.security.MessageDigest

/** A catalog can only be constructed by authenticating the original bytes with channel keys. */
internal class UpdateCatalog private constructor(val document: UpdateCatalogDocument, val digest: String,
                                                 private val raw: ByteArray, private val signed: ByteArray) {
    data class Device(val name: String, val sdk: Int, val versionCode: Int, val signer: String) {
        init { require(name.isNotBlank() && sdk > 0 && versionCode >= 0 && signer.matches(Regex("[a-f0-9]{64}"))) }
    }
    enum class Ineligible { DEVICE, SDK, VERSION, SIGNER, BLACKLIST }

    fun ineligible(device: Device): Ineligible? = when {
        device.name !in document.release.device -> Ineligible.DEVICE
        device.sdk != document.release.sdk -> Ineligible.SDK
        document.release.code <= device.versionCode -> Ineligible.VERSION
        device.signer != document.release.signer -> Ineligible.SIGNER
        else -> null
    }

    /** A missing/untrusted clock disables only this advisory. Expiry never rejects authenticated metadata. */
    fun stale(trustedNowMillis: Long?): Boolean = trustedNowMillis != null && trustedNowMillis >= document.expires
    fun bytes(): ByteArray = raw.copyOf()
    fun signature(): ByteArray = signed.copyOf()
    fun apk(): Fetcher.Artifact = Fetcher.Artifact(Fetcher.ArtifactKind.APK, document.release.size, document.release.sha256)

    /** Authenticated APK projection when the optional module updater is absent.
     * Original signed bytes/digest remain unchanged; no reserialized document is
     * ever treated as signed. Module installation cannot use this projection. */
    fun apkOnly(): UpdateCatalog = if (document.release.modules.isEmpty()) this else UpdateCatalog(
        document.copy(release = document.release.copy(modules = emptyList())), digest, raw, signed)

    companion object {
        fun read(bytes: ByteArray, signature: ByteArray, keys: UpdateKeys, channel: String): UpdateCatalog {
            require(channel in setOf("stable", "beta"))
            val signed = signature.copyOf()
            return keys.read(UpdateKeys.Role.CHANNEL, SignedDocument.Kind.CATALOG, bytes, signed) { verified ->
                val document = UpdateModels.readUpdateCatalogDocument(UpdateJson.read(verified, SignedDocument.Kind.CATALOG.maxBytes))
                require(document.channel == channel) { "Unexpected update channel" }
                require(document.expires >= document.published) { "Invalid update expiry ordering" }
                val release = document.release
                require(versionCode(release.version) == release.code) { "Release version/code mismatch" }
                require(release.modules.map { it.id }.toSet().size == release.modules.size) { "Duplicate update module" }
                (release.urls + release.source + release.modules.flatMap { it.urls + it.manifest_urls }).forEach(::validateUrl)
                document.mirrors.orEmpty().forEach { mirror ->
                    validateUrl(mirror)
                    require(URI(mirror).path.endsWith("/${document.channel}.json")) { "A mirror must be this channel's catalog" }
                }
                release.modules.forEach { module ->
                    require((module.id == "thinkos-immersive") == (module.kind == "vector")) { "Invalid update module kind" }
                }
                UpdateCatalog(document, sha256(verified), verified, signed)
            }
        }

        internal fun validateUrl(url: String) {
            val uri = URI(url)
            LocalNetworkPolicy.validate(uri)
            require(uri.fragment == null) { "Update URL fragments are forbidden" }
        }

        internal fun versionCode(version: String): Int {
            val parts = version.split('.')
            require(parts.size == 3 && parts.all { it.matches(Regex("0|[1-9][0-9]*")) }) { "Invalid release version" }
            val values = parts.map { it.toLongOrNull() ?: throw IllegalArgumentException("Release version overflow") }
            require(values[0] <= 2147 && values[1] in 0..999 && values[2] in 0..999) { "Release version component overflow" }
            val code = values[0] * 1_000_000 + values[1] * 1000 + values[2]
            require(code in 1..Int.MAX_VALUE.toLong()) { "Release version code overflow" }
            return code.toInt()
        }

        internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
