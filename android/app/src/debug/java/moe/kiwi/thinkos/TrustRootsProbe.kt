package moe.kiwi.thinkos

import org.json.JSONObject
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.X509Certificate

/** Shell-protected, read-only, debug-only view of the platform trust store. */
internal object TrustRootsProbe {
    fun read(): JSONObject {
        val store = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
        val aliases = store.aliases().toList()
        val system = aliases.filter { it.startsWith("system:") }
        val fingerprints = system.map { alias ->
            val cert = store.getCertificate(alias) as X509Certificate
            hex(MessageDigest.getInstance("SHA-256").digest(cert.encoded))
        }.sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(
            fingerprints.joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII)
        )
        return JSONObject().put("provider", store.provider.name).put("system_count", system.size)
            .put("unique_system_count", fingerprints.distinct().size)
            .put("user_count", aliases.count { it.startsWith("user:") })
            .put("system_fingerprints_sha256", hex(digest))
    }

    private fun hex(raw: ByteArray): String = raw.joinToString("") { "%02x".format(it.toInt() and 255) }
}
