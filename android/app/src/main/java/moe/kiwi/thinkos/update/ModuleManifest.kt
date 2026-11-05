package moe.kiwi.thinkos.update

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.math.BigInteger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Collections
import java.util.Locale

/** The root daemon rechecks these exact authenticated bytes independently before any mutation. */
internal class ModuleManifest private constructor(
    val module: UpdateModule, private val raw: ByteArray, private val signed: ByteArray,
) {
    fun bytes(): ByteArray = raw.copyOf()
    fun signature(): ByteArray = signed.copyOf()

    /** Only the APK's independent system roots can construct this verifier. */
    class Keys private constructor(private val roots: List<PublicKey>) {
        internal fun <T> read(bytes: ByteArray, signature: ByteArray, decode: (ByteArray) -> T): T {
            canonicalSignature(signature, SignedDocument.requireP256(roots.first()).params.order)
            return SignedDocument.read(SignedDocument.Kind.MANIFEST, bytes, signature, roots, decode)
        }

        companion object {
            internal fun parse(bytes: ByteArray): Keys {
                val document = UpdateModels.readUpdateSystemKeyDocument(UpdateJson.read(bytes, KEY_LIMIT))
                val entries = listOf(document.active, document.backup)
                require(entries.map { it.id }.distinct().size == 2) { "Duplicate system signing-key ID" }
                val roots = entries.map { entry ->
                    val encoded = Base64.getDecoder().decode(entry.spki)
                    require(Base64.getEncoder().encodeToString(encoded) == entry.spki) { "Non-canonical system-key SPKI" }
                    KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded)).also { key ->
                        require(key.encoded.contentEquals(encoded)) { "Non-canonical system-key DER" }
                        SignedDocument.requireP256(key)
                    }
                }
                require(!roots[0].encoded.contentEquals(roots[1].encoded)) { "System active and backup keys must differ" }
                return Keys(roots)
            }
        }
    }

    companion object {
        private const val KEY_LIMIT = 64 * 1024
        private const val PACKAGE_LIMIT = 16L * 1024 * 1024
        private val IDS = setOf("thinkos-updater", "thinkos-cast", "thinkos-immersive", "thinkos-shell", "thinkos-certs", "thinkos-power", "timesync")

        suspend fun loadKeys(context: Context): Result<Keys> = UpdateIo.run {
            runCatching { loadKeyAsset { context.assets.open(it) } }
        }

        internal fun parseKeys(bytes: ByteArray): Keys = Keys.parse(bytes)

        /** This path deliberately never opens update/debug-keys.json or any downloaded file. */
        internal fun loadKeyAsset(open: (String) -> InputStream): Keys = open("update/system-keys.json").use { input ->
            val bytes = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (true) {
                val n = input.read(buffer, 0, minOf(buffer.size, KEY_LIMIT - bytes.size() + 1))
                if (n < 0) break
                require(n <= KEY_LIMIT - bytes.size()) { "System-key asset exceeds protocol bounds" }
                bytes.write(buffer, 0, n)
            }
            parseKeys(bytes.toByteArray())
        }

        fun read(bytes: ByteArray, signature: ByteArray, keys: Keys, expected: UpdateModule): ModuleManifest {
            require(bytes.size in 1..SignedDocument.Kind.MANIFEST.maxBytes && signature.size in 8..72) { "Module proof exceeds protocol bounds" }
            val signed = signature.copyOf()
            return keys.read(bytes, signed) { verified ->
                // Authentication always precedes inspecting the untrusted manifest text.
                require(verified.all { it == 10.toByte() || it.toInt() in 32..126 }) { "Module manifest must be ASCII and LF" }
                val lines = String(verified, Charsets.US_ASCII).split('\n')
                require(lines.size == 8 && lines.last().isEmpty() && lines[0] == "thinkos-module 1") { "Invalid module manifest header or line count" }
                fun field(index: Int, prefix: String): String {
                    val line = lines[index]
                    require(line.startsWith(prefix) && line.length > prefix.length) { "Missing or reordered module field" }
                    return line.substring(prefix.length)
                }
                val id = field(1, "id=")
                val kind = field(2, "kind=")
                val code = decimal(field(3, "code="), Int.MAX_VALUE.toLong())
                val version = field(4, "version=")
                val digest = field(5, "sha256=")
                val size = decimal(field(6, "size="), PACKAGE_LIMIT)
                require(id in IDS && expected.id == id) { "Unexpected module ID" }
                require(kind == if (id == "thinkos-immersive") "vector-apk" else "magisk") { "Invalid signed module kind" }
                require(expected.kind == if (id == "thinkos-immersive") "vector" else "magisk") { "Catalog module kind mismatch" }
                require(version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+\\-]{0,47}"))) { "Invalid module version" }
                require(digest.matches(Regex("[0-9A-Fa-f]{64}")) && expected.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid module digest" }
                require(code == expected.code.toLong() && version == expected.version && size == expected.size &&
                    digest.lowercase(Locale.ROOT) == expected.sha256) { "Signed module differs from authenticated catalog" }
                require(expected.policy in setOf("always", "installed", "feature")) { "Invalid module policy" }
                ModuleManifest(expected.copy(urls = Collections.unmodifiableList(expected.urls.toList()),
                    manifest_urls = Collections.unmodifiableList(expected.manifest_urls.toList())), verified, signed)
            }
        }

        private fun decimal(value: String, maximum: Long): Long {
            require(value.matches(Regex("[1-9][0-9]*"))) { "Module numeric field must be canonical positive decimal" }
            return value.toLongOrNull()?.takeIf { it in 1..maximum } ?: throw IllegalArgumentException("Module numeric field exceeds protocol bounds")
        }

        /** Match the daemon's strict short-form P-256 DER rather than accepting provider-specific BER. */
        private fun canonicalSignature(signature: ByteArray, order: BigInteger) {
            require(signature.size in 8..72 && signature[0] == 0x30.toByte() &&
                (signature[1].toInt() and 255) == signature.size - 2) { "Invalid module signature DER" }
            var position = 2
            repeat(2) {
                require(position + 2 <= signature.size && signature[position++] == 2.toByte()) { "Missing signature scalar" }
                val length = signature[position++].toInt() and 255
                require(length in 1..33 && position + length <= signature.size) { "Invalid signature scalar length" }
                val first = signature[position].toInt() and 255
                require(first < 128 && (length == 1 || first != 0 || (signature[position + 1].toInt() and 128) != 0)) { "Non-canonical signature scalar" }
                require(length != 33 || first == 0) { "Oversized signature scalar" }
                val scalar = BigInteger(1, signature.copyOfRange(position, position + length))
                require(scalar.signum() > 0 && scalar < order) { "Signature scalar outside P-256 order" }
                position += length
            }
            require(position == signature.size) { "Trailing signature bytes" }
        }
    }
}
