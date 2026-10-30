package moe.kiwi.thinkos.tz

import java.util.Locale

/**
 * Human-readable names for zones. City names come from the platform's CLDR data (ICU), which is
 * localised and maintained by the OS; ids it does not know fall back to the last part of the id.
 */
object ZoneNames {
    /** Overridable so unit tests need not load android.icu. */
    var cityLookup: (String, Locale) -> String? = { id, locale ->
        try {
            android.icu.text.TimeZoneNames.getInstance(locale).getExemplarLocationName(id)
        } catch (_: Throwable) {
            null
        }
    }

    /** Display order of the picker's groups; the label of each is an Android string `tz_area_<id lower-case>`. */
    val AREA_ORDER = listOf("Asia", "Europe", "America", "Africa", "Australia", "Pacific", "Atlantic", "Indian", "Antarctica", "Arctic", "Etc")

    fun city(id: String, locale: Locale = Locale.getDefault()): String {
        if (id == "Etc/UTC") return "UTC"
        val name = cityLookup(id, locale)?.takeIf { it.isNotBlank() }
        return name ?: id.substringAfterLast('/').replace('_', ' ')
    }

    /** The group an id belongs to: its first path segment (`Asia`, `Europe`, …, `Etc` for UTC). */
    fun area(id: String): String = id.substringBefore('/')

    fun country(code: String, locale: Locale = Locale.getDefault()): String =
        if (code.isEmpty()) "" else Locale("", code).getDisplayCountry(locale).ifBlank { code }
}
