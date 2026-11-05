package moe.kiwi.thinkos.location

import moe.kiwi.thinkos.net.PluginHttp
import moe.kiwi.thinkos.tz.ZoneInfo
import moe.kiwi.thinkos.tz.ZoneNames
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

/** A location result with its province/city/district hierarchy, ready for a picker row. */
data class PlaceResult(val location: DeviceLocation, val detail: String) {
    fun formValue(): Map<String, Any> = location.toMap() + ("mode" to "manual")
}

/**
 * Open-Meteo-compatible geocoding. [search] is blocking and must run on an IO worker. Requests use
 * the same bounded response, redirect and local-cleartext policy as every plugin HTTP call.
 * Searching is optional; [LocationProvider] and manual coordinate entry never call this service.
 */
object PlaceSearch {
    fun search(endpoint: String, query: String, locale: Locale): List<PlaceResult> {
        val text = query.trim().take(160)
        if (text.length < 2) return emptyList()
        require(URI(endpoint).rawFragment == null) { "search address cannot contain a fragment" }
        val language = locale.language.ifBlank { "en" }.lowercase(Locale.ROOT)
        return searchVariants(text) { variant ->
            val response = PluginHttp.request("GET", urlFor(endpoint, variant, language), null, null)
            check((response["status"] as Number).toInt() == 200) { "place search returned HTTP ${response["status"]}" }
            parse(response["body"] as String)
        }
    }

    private fun urlFor(endpoint: String, text: String, language: String): String {
        val separator = if (URI(endpoint).rawQuery == null) "?" else "&"
        return endpoint + separator + "name=" + URLEncoder.encode(text, "UTF-8") +
            "&count=20&language=" + URLEncoder.encode(language, "UTF-8") + "&format=json"
    }

    /**
     * The service matches Chinese names whole: "阜新" finds nothing, "阜新市" finds the city. What was typed goes first, and a short
     * Chinese name then also tries the usual endings, so nobody has to know that. A failure of the first request is the caller's
     * (it falls back to the offline list); a failure of a later one only leaves its results out.
     */
    internal fun searchVariants(text: String, fetch: (String) -> List<PlaceResult>): List<PlaceResult> {
        val found = LinkedHashMap<String, PlaceResult>()
        for ((index, variant) in queryVariants(text).withIndex()) {
            val rows = if (index == 0) fetch(variant) else runCatching { fetch(variant) }.getOrDefault(emptyList())
            for (row in rows) found.putIfAbsent("${row.location.name}|${row.location.latitude}|${row.location.longitude}", row)
            if (found.size >= 20) break
        }
        return found.values.take(20)
    }

    /** Only a name written wholly in Chinese characters that does not already end like a place gets the endings tried; anything else goes as typed. */
    internal fun queryVariants(text: String): List<String> {
        val ideograph = { c: Char -> c.code in 0x4E00..0x9FFF }
        if (text.isEmpty() || !text.all(ideograph) || text.last() in ENDINGS) return listOf(text)
        return listOf(text) + listOf("市", "县", "区").map { text + it }
    }

    private const val ENDINGS = "市县区旗州盟乡镇省"

    /**
     * The offline fallback: the time zone cities bundled with the device, matched on their localised city
     * and country names. Coordinates are the city's own (zone.tab); its elevation is unknown, so sea level.
     */
    fun offline(
        query: String,
        locale: Locale,
        zones: List<ZoneInfo> = Zones.database?.zones.orEmpty(),
        city: (String, Locale) -> String = ZoneNames::city,
        country: (String, Locale) -> String = ZoneNames::country,
    ): List<PlaceResult> {
        val text = query.trim().take(160).lowercase(locale)
        if (text.length < 2) return emptyList()
        return zones.asSequence()
            .filter { !it.id.startsWith("Etc/") }
            .map { zone -> Triple(zone, city(zone.id, locale), country(zone.country, locale)) }
            .filter { (zone, name, land) -> "$name $land ${zone.id.replace('_', ' ')}".lowercase(locale).contains(text) }
            .sortedWith(compareBy({ !it.second.lowercase(locale).startsWith(text) }, { it.second }))
            .take(20)
            .map { (zone, name, land) -> PlaceResult(DeviceLocation(name, zone.latitude, zone.longitude, 0.0, zone.id, "manual"), land) }
            .toList()
    }

    /** External data is untrusted: skip incomplete or invalid entries, retain at most 20. */
    fun parse(body: String): List<PlaceResult> {
        val root = JSONObject(body)
        check(!root.optBoolean("error")) { "place search rejected the request" }
        val rows = root.optJSONArray("results") ?: return emptyList()
        return (0 until minOf(rows.length(), 20)).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val latitude = row.optDouble("latitude", Double.NaN)
            val longitude = row.optDouble("longitude", Double.NaN)
            val elevation = row.optDouble("elevation", 0.0)
            val name = clean(row.optString("name"))
            val timezone = clean(row.optString("timezone"))
            if (name.isEmpty() || latitude !in -90.0..90.0 || longitude !in -180.0..180.0 ||
                elevation !in -500.0..10_000.0 || timezone.isEmpty()) return@mapNotNull null
            val detail = listOf("admin1", "admin2", "admin3", "country")
                .map { clean(row.optString(it)) }.filter { it.isNotEmpty() && it != name }.distinct().joinToString(", ")
            PlaceResult(DeviceLocation(name, latitude, longitude, elevation, timezone, "manual"), detail)
        }
    }

    private fun clean(text: String): String = text.filter { it >= ' ' }.trim().take(160)
}
