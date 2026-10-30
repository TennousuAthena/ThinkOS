package moe.kiwi.thinkos.tz

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One zone at one moment: offset, DST flag, abbreviation and wall-clock fields. */
class ZoneClock internal constructor(
    val id: String,
    private val rules: ZoneRules?,
    private val fallback: TimeZone?,
) {
    /** True when the zone came from the bundled IANA database rather than the ROM's. */
    val bundled: Boolean get() = rules != null

    fun typeAt(epochMillis: Long): LocalType {
        rules?.let { return it.typeAt(Math.floorDiv(epochMillis, 1000L)) }
        val zone = fallback ?: TimeZone.getDefault()
        val dst = zone.inDaylightTime(Date(epochMillis))
        return LocalType(zone.getOffset(epochMillis) / 1000, dst, zone.getDisplayName(dst, TimeZone.SHORT, Locale.ENGLISH))
    }

    fun offsetMillis(epochMillis: Long): Long = typeAt(epochMillis).offset * 1000L

    fun fields(epochMillis: Long): LocalFields = LocalFields(epochMillis + offsetMillis(epochMillis))

    /** Whole local days this zone is ahead of [home] at the same instant: -1 yesterday, 0 same day, +1 tomorrow. */
    fun dayDelta(epochMillis: Long, home: ZoneClock): Int = (fields(epochMillis).days - home.fields(epochMillis).days).toInt()
}

/**
 * Entry point for everything that needs a wall-clock time. Once [install]ed, zones are evaluated
 * from the bundled IANA database; before that (or for ids it lacks) the platform's TimeZone is used.
 */
object Zones {
    @Volatile var database: TzDatabase? = null
        private set

    fun install(db: TzDatabase?) { database = db }

    /** Always returns a usable clock; unknown ids resolve to the device's own zone. */
    fun clock(id: String?): ZoneClock {
        val db = database
        if (!id.isNullOrBlank()) {
            val canonical = db?.canonical(id)
            val rules = canonical?.let(db::rules)
            if (canonical != null && rules != null) return ZoneClock(canonical, rules, null)
            if (id in TimeZone.getAvailableIDs()) return ZoneClock(id, null, TimeZone.getTimeZone(id))
        }
        return home()
    }

    /** The device's own zone (what the ROM's settings say), evaluated with the bundled rules when possible. */
    fun home(): ZoneClock {
        val system = TimeZone.getDefault()
        val db = database
        val canonical = db?.canonical(system.id)
        val rules = canonical?.let(db::rules)
        return if (canonical != null && rules != null) ZoneClock(canonical, rules, null) else ZoneClock(system.id, null, system)
    }

    /** Null when [id] is unknown to the bundled database (aliases are accepted and normalised). */
    fun canonical(id: String): String? = database?.canonical(id)
}

/** Formats an instant as wall-clock text in a given zone, with the same patterns as SimpleDateFormat. */
object ZoneFormat {
    private val formatters = object : ThreadLocal<LinkedHashMap<String, SimpleDateFormat>>() {
        override fun initialValue() = object : LinkedHashMap<String, SimpleDateFormat>(16, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SimpleDateFormat>) = size > 32
        }
    }

    /** Zone-name pattern letters (`z`, `Z`, `X`) would print UTC here; clock formats do not use them. */
    fun format(pattern: String, epochMillis: Long, zone: ZoneClock, locale: Locale = Locale.getDefault()): String {
        val cache = formatters.get()!!
        val resolved = moe.kiwi.thinkos.i18n.Formats.pattern(pattern)
        val formatter = cache.getOrPut("${locale.toLanguageTag()}|$resolved") {
            SimpleDateFormat(resolved, locale).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        return formatter.format(Date(epochMillis + zone.offsetMillis(epochMillis)))
    }

    /** `UTC+8`, `UTC+5:30`, `UTC-3:30`, `UTC`. */
    fun offsetLabel(offsetSeconds: Int): String {
        if (offsetSeconds == 0) return "UTC"
        val sign = if (offsetSeconds < 0) '-' else '+'
        val total = Math.abs(offsetSeconds)
        val hours = total / 3600
        val minutes = total / 60 % 60
        return "UTC$sign$hours" + if (minutes != 0) ":%02d".format(minutes) else ""
    }
}
