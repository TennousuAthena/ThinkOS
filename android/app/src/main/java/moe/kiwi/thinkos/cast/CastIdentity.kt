package moe.kiwi.thinkos.cast

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.SecureRandom
import java.util.Locale
import java.util.UUID

/** OS-owned discovery identity. Independent of onboarding, system identifiers
 * and the AirPlay pairing key. Provisioning must omit this file from clones. */
internal class CastIdentity private constructor(seed: ByteArray) {
    val uuid: String = UUID.nameUUIDFromBytes("thinkos-cast:".toByteArray(Charsets.US_ASCII) + seed).toString()
    /** Internal seed for AirPlayRecord's locally administered device ID. Never published. */
    val airplaySeed: String = seed.joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 0xff) }

    companion object {
        private const val SEED_BYTES = 32

        /** Reads exactly one seed; a missing/truncated/oversized file gets a new
         * owner-only seed. I/O failures propagate rather than sharing a fallback ID. */
        @Synchronized fun load(file: File): CastIdentity {
            if (file.exists() && !file.isFile) throw IOException("Cast identity is not a regular file")
            if (file.isFile) {
                ownerOnly(file)
                val existing = file.inputStream().use { input ->
                    val bytes = ByteArray(SEED_BYTES)
                    var offset = 0
                    while (offset < bytes.size) {
                        val count = input.read(bytes, offset, bytes.size - offset)
                        if (count < 0) return@use null
                        offset += count
                    }
                    bytes.takeIf { input.read() == -1 }
                }
                if (existing != null) return CastIdentity(existing)
            }
            val parent = file.absoluteFile.parentFile ?: throw IOException("Missing cast identity directory")
            if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Unable to create cast identity directory")
            val seed = ByteArray(SEED_BYTES).also { SecureRandom().nextBytes(it) }
            val temporary = File.createTempFile(".cast-identity-", ".tmp", parent)
            try {
                ownerOnly(temporary)
                FileOutputStream(temporary).use { output -> output.write(seed); output.fd.sync() }
                if (!temporary.renameTo(file)) throw IOException("Unable to publish cast identity")
                return CastIdentity(seed)
            } finally { temporary.delete() }
        }

        private fun ownerOnly(file: File) {
            if (!file.setReadable(false, false) || !file.setWritable(false, false) || !file.setExecutable(false, false) ||
                !file.setReadable(true, true) || !file.setWritable(true, true)) throw IOException("Unable to protect cast identity")
        }
    }
}
