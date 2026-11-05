package moe.kiwi.thinkos.update

import android.content.Context
import moe.kiwi.thinkos.BuildConfig
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Only APK-owned assets may populate this keyring. A downloaded document cannot replace it. */
internal class UpdateKeys private constructor(private val channel: List<PublicKey>, private val registry: List<PublicKey>) {
    enum class Role { CHANNEL, REGISTRY }

    fun <T> read(role: Role, kind: SignedDocument.Kind, bytes: ByteArray, signature: ByteArray, decode: (ByteArray) -> T): T =
        SignedDocument.read(kind, bytes, signature, if (role == Role.CHANNEL) channel else registry, decode)

    companion object {
        /** Missing/malformed deployment keys remain an explicit failure, never TOFU or a placeholder key. */
        suspend fun load(context: Context): Result<UpdateKeys> = UpdateIo.run {
            runCatching { loadAssets({ path -> context.assets.open(path) }, BuildConfig.DEBUG) }
        }

        internal fun loadAssets(open: (String) -> InputStream, debug: Boolean): UpdateKeys {
            val production = open("update/keys.json").use { limited(it, 64 * 1024) }
            val test = if (debug) try {
                open("update/debug-keys.json").use { limited(it, 4096) }
            } catch (_: FileNotFoundException) { null } else null
            return parse(production, test)
        }

        internal fun parse(production: ByteArray, debugOnly: ByteArray? = null): UpdateKeys {
            val document = UpdateModels.readUpdateKeyDocument(UpdateJson.read(production, 64 * 1024))
            val entries = listOf(document.channel.active, document.channel.backup, document.registry.active, document.registry.backup) +
                listOfNotNull(debugOnly?.let { UpdateModels.readUpdateDebugKeyDocument(UpdateJson.read(it, 4096)).test })
            require(entries.map { it.id }.toSet().size == entries.size) { "Duplicate signing-key ID" }
            val keys = entries.map { entry ->
                val encoded = Base64.getDecoder().decode(entry.spki)
                require(Base64.getEncoder().encodeToString(encoded) == entry.spki) { "Non-canonical signing-key SPKI" }
                val key = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(encoded))
                require(key.encoded.contentEquals(encoded)) { "Non-canonical signing-key DER" }
                SignedDocument.requireP256(key)
            }
            require(keys.map { Base64.getEncoder().encodeToString(it.encoded) }.toSet().size == keys.size) { "Signing roles must use distinct keys" }
            val test = if (keys.size == 5) listOf(keys[4]) else emptyList()
            return UpdateKeys(keys.take(2) + test, keys.drop(2).take(2) + test)
        }

        private fun limited(input: InputStream, limit: Int): ByteArray = ByteArrayOutputStream().use { output ->
            val buffer = ByteArray(4096)
            while (true) {
                val count = input.read(buffer, 0, minOf(buffer.size, limit - output.size() + 1))
                if (count < 0) break
                require(count <= limit - output.size()) { "Signing-key asset size exceeded" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }
}
