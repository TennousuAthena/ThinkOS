package moe.kiwi.thinkos.plugin

import moe.kiwi.thinkos.astro.AstroInterval
import moe.kiwi.thinkos.astro.Moon
import moe.kiwi.thinkos.astro.Place
import moe.kiwi.thinkos.astro.Seasons
import moe.kiwi.thinkos.astro.Solar
import moe.kiwi.thinkos.astro.SunDay
import moe.kiwi.thinkos.astro.Twilight
import moe.kiwi.thinkos.tz.Zones

/** Typed tos.astro adapter. Sun/Moon run on the astronomy executor; seasons are cheap worker calls. */
internal class AstroHost(private val location: () -> Map<String, Any>) {
    private data class SunKey(val day: Long, val zone: String, val place: Place, val tzVersion: String?)
    private val sunDays = object : LinkedHashMap<SunKey, SunDay>(8, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<SunKey, SunDay>) = size > 8
    }
    fun call(method: String, args: Array<Any?>): Any? {
        if (method == "astro.seasons") {
            val raw = args.getOrNull(0) as? Number ?: error("year is required")
            val year = raw.toDouble()
            require(year.isFinite() && year == year.toInt().toDouble()) { "year must be an integer" }
            val events = Seasons.forYear(year.toInt())
            return mapOf("year" to events.year, "marchEquinox" to events.marchEquinox,
                "juneSolstice" to events.juneSolstice, "septemberEquinox" to events.septemberEquinox,
                "decemberSolstice" to events.decemberSolstice)
        }
        // A device without an RTC must not invent today's events before time is synchronised.
        val raw = args.getOrNull(0) as? Number ?: return null
        val ms = raw.toDouble()
        require(ms.isFinite() && ms >= 0 && ms < 2_556_144_000_000.0) { "astronomy date must be before 2051" }
        if (ms < 1_577_836_800_000.0) return null // 2020-01-01: same untrusted-time rule as the UI.
        val epochMillis = ms.toLong()
        val explicit = args.getOrNull(1)
        require(explicit == null || explicit is Map<*, *>) { "place must be a table" }
        val values = (explicit as? Map<*, *>) ?: location()
        fun number(key: String, fallback: Double? = null): Double =
            (values[key] as? Number)?.toDouble() ?: fallback ?: error("place.$key is required")
        val place = Place(number("latitude"), number("longitude"), number("elevation", 0.0))
        val zoneId = values["timezone"] as? String
        require(zoneId == null || zoneId.isBlank() || zoneId == Zones.home().id || Zones.canonical(zoneId) != null) { "unknown place timezone" }
        val zone = Zones.clock(zoneId?.takeIf { it.isNotBlank() })
        fun twilight(value: Twilight) = mapOf("dawn" to value.dawn, "dusk" to value.dusk)
        fun intervals(values: List<AstroInterval>) = values.map { mapOf("start" to it.start, "end" to it.end) }
        return when (method) {
            "astro.sun" -> {
                val key = SunKey(zone.fields(epochMillis).days, zone.id, place, Zones.database?.version)
                val day = sunDays.getOrPut(key) { Solar.day(epochMillis, zone, place) }
                val position = Solar.position(epochMillis, place)
                mapOf("start" to day.dateStart, "end" to day.dateEnd, "noon" to day.noon,
                    "sunrise" to day.sunrise, "sunset" to day.sunset, "dayLength" to day.dayLengthMillis,
                    "dayLengthChange" to day.dayLengthChangeMillis, "maximumAltitude" to day.maximumAltitude,
                    "polar" to day.polar.name.lowercase(java.util.Locale.ROOT), "civil" to twilight(day.civil),
                    "nautical" to twilight(day.nautical), "astronomical" to twilight(day.astronomical),
                    "golden" to intervals(day.golden), "blue" to intervals(day.blue),
                    "azimuth" to position.azimuth, "altitude" to position.altitude, "timezone" to zone.id)
            }
            "astro.moon" -> {
                val day = Moon.day(epochMillis, zone, place)
                val phase = day.phase
                mapOf("start" to day.start, "end" to day.end, "rise" to day.rise, "set" to day.set,
                    "transit" to day.transit, "rises" to day.rises, "sets" to day.sets,
                    "transits" to day.transits, "alwaysUp" to day.alwaysUp, "alwaysDown" to day.alwaysDown,
                    "azimuth" to day.position.azimuth, "altitude" to day.position.altitude, "timezone" to zone.id,
                    "phase" to mapOf("name" to phase.name.id, "angle" to phase.angle,
                        "illumination" to phase.illumination, "waxing" to phase.waxing, "ageDays" to phase.ageDays,
                        "previousNewMoon" to phase.previousNewMoon, "nextNewMoon" to phase.nextNewMoon,
                        "nextFirstQuarter" to phase.nextFirstQuarter, "nextFullMoon" to phase.nextFullMoon,
                        "nextLastQuarter" to phase.nextLastQuarter))
            }
            else -> error("unknown astronomy method")
        }
    }
}
