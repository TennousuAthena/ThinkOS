package moe.kiwi.thinkos.tz

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where the bundled IANA database lives. Paths are relative, e.g. `zoneinfo/Asia/Tokyo`, `zones.tsv`. */
interface TzSource {
    fun read(path: String): ByteArray?
}

/** A directory on disk; used by unit tests against `shared/tzdata`. */
class FileTzSource(private val root: File) : TzSource {
    override fun read(path: String): ByteArray? = File(root, path).takeIf { it.isFile }?.readBytes()
}

/** A canonical zone from the IANA `zone.tab`. */
data class ZoneInfo(val id: String, val country: String, val latitude: Double, val longitude: Double, val comment: String)

/**
 * ThinkOS's own copy of the IANA time zone database (shared/tzdata, see tools/vendor_tzdata.py).
 * The ROM's tzdata is frozen at build time, so rules are evaluated from this copy instead.
 */
class TzDatabase(private val source: TzSource) {
    val version: String = String(source.read("VERSION") ?: error("tzdata: VERSION missing"), Charsets.UTF_8).trim()
    val zones: List<ZoneInfo>
    private val byId: Map<String, ZoneInfo>
    private val aliases: Map<String, String>
    private val countries: Map<String, String>
    private val cache = ConcurrentHashMap<String, ZoneRules>()

    init {
        zones = rows("zones.tsv").map { r ->
            ZoneInfo(r[0], r.getOrElse(1) { "" }, r[2].toDouble(), r[3].toDouble(), r.getOrElse(4) { "" })
        }.sortedBy { it.id }
        byId = zones.associateBy { it.id }
        aliases = rows("links.tsv").associate { it[0] to it[1] }
        countries = rows("countries.tsv").associate { row -> row[0] to row.getOrElse(1) { row[0] } }
        require(zones.isNotEmpty()) { "tzdata: no zones" }
    }

    private fun rows(path: String): List<List<String>> =
        String(source.read(path) ?: error("tzdata: $path missing"), Charsets.UTF_8).lineSequence()
            .filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split('\t') }.toList()

    /** The canonical id for [id] or for an alias such as `Asia/Calcutta`; null if unknown. */
    fun canonical(id: String): String? = when {
        id in byId -> id
        else -> aliases[id]?.takeIf { it in byId }
    }

    fun info(id: String): ZoneInfo? = canonical(id)?.let(byId::get)

    fun countryName(code: String): String? = countries[code]

    /** Parsed rules; null for an unknown zone. Parsed once and cached. */
    fun rules(id: String): ZoneRules? {
        val canonical = canonical(id) ?: return null
        cache[canonical]?.let { return it }
        val bytes = source.read("zoneinfo/$canonical") ?: return null
        return TzIf.parse(bytes).also { cache[canonical] = it }
    }
}
