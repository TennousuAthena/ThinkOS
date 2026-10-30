package moe.kiwi.thinkos.tz

import android.content.res.AssetManager
import java.io.FileNotFoundException

/** Reads the bundled database from `assets/tzdata/` (copied from shared/tzdata at build time). */
class AssetTzSource(private val assets: AssetManager) : TzSource {
    override fun read(path: String): ByteArray? = try {
        assets.open("tzdata/$path").use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }
}
