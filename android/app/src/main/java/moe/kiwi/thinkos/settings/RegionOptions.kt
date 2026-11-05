package moe.kiwi.thinkos.settings

import moe.kiwi.thinkos.tz.Zones
import java.util.Locale

/** The same offline region inventory used by setup and ordinary settings. */
internal object RegionOptions {
    private val CODE = Regex("[A-Z]{2}")

    fun codes(): List<String> = Zones.database?.zones.orEmpty().map { it.country }
        .filter { CODE.matches(it) }.distinct()

    fun normalize(value: String): String? = value.uppercase(Locale.ROOT).takeIf { code ->
        CODE.matches(code) && code in codes()
    }
}
