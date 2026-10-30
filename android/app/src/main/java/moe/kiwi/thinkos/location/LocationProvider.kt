package moe.kiwi.thinkos.location

import moe.kiwi.thinkos.astro.Place
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.tz.ZoneInfo
import moe.kiwi.thinkos.tz.ZoneNames
import moe.kiwi.thinkos.tz.Zones
import java.util.Locale

/** A configured observing location. `timezone` is an IANA id, never inferred from the longitude. */
data class DeviceLocation(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val elevation: Double,
    val timezone: String,
    /** `timezone` is an approximate representative city; `manual` is explicit user input. */
    val source: String,
) {
    val place: Place get() = Place(latitude, longitude, elevation)

    fun toMap(): Map<String, Any> = linkedMapOf(
        "name" to name, "latitude" to latitude, "longitude" to longitude,
        "elevation" to elevation, "timezone" to timezone, "source" to source,
    )
}

/**
 * Resolves only local settings and bundled zone metadata. No GPS, network, Server or device location
 * permission is needed. Time-zone-city mode is approximate, so the settings screen calls that out.
 * Injected zone functions keep fallback and coordinate behaviour independently testable.
 */
class LocationProvider(
    private val settings: SettingsStore,
    private val homeZone: () -> String = { Zones.home().id },
    private val zoneInfo: (String) -> ZoneInfo? = { Zones.database?.info(it) },
    private val cityName: (String, Locale) -> String = ZoneNames::city,
) {
    fun get(locale: Locale = Locale.getDefault()): DeviceLocation {
        val values = settings.snapshot(SettingsStore.SYSTEM)
        val home = homeZone()
        val automatic = values["location.mode"] == "auto"
        if (automatic) {
            zoneInfo(home)?.let {
                return DeviceLocation(cityName(it.id, locale), it.latitude, it.longitude, 0.0, it.id, "timezone")
            }
        }
        fun number(key: String) = (values[key] as Number).toDouble()
        val latitude = number("location.latitude")
        val longitude = number("location.longitude")
        val name = (values["location.name"] as String).ifBlank {
            String.format(Locale.ROOT, "%.4f°, %.4f°", latitude, longitude)
        }
        val timezone = (values["location.timezone"] as String).ifBlank { home }
        return DeviceLocation(name, latitude, longitude, number("location.elevation"), timezone,
            if (automatic) "manual-fallback" else "manual")
    }
}
