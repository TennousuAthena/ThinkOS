package moe.kiwi.thinkos.astro

import moe.kiwi.thinkos.astro.Astro.RAD
import moe.kiwi.thinkos.astro.Astro.DEG
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** VSOP87D with FK5, nutation, aberration and observer parallax (Meeus chs. 22, 25, 32, 40). */
internal object Sun {
    // Every twilight threshold reuses the same ten-minute sample instants. Keep the expensive
    // VSOP evaluations bounded and local to the computing thread; the ephemeris remains exact.
    private val apparentCache = object : ThreadLocal<LinkedHashMap<Long, Apparent>>() {
        override fun initialValue() = object : LinkedHashMap<Long, Apparent>(1024, .75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Apparent>) = size > 1024
        }
    }
    class Apparent(
        val longitude: Double,
        val rightAscension: Double,
        val declination: Double,
        /** Distance in astronomical units. */
        val distance: Double,
    )

    fun apparent(epochMillis: Long): Apparent {
        val cache = apparentCache.get()!!
        return cache.getOrPut(epochMillis) { calculateApparent(epochMillis) }
    }

    private fun calculateApparent(epochMillis: Long): Apparent {
        val jde = Astro.julianDay(epochMillis) + Astro.DELTA_T_SECONDS / 86_400.0
        val t = Astro.centuries(jde)
        val earth = EarthVsop.at(t / 10)
        val trueLongitude = Astro.norm360(earth[0] * DEG + 180)
        val distance = earth[2]
        val omega = (125.04 - 1934.136 * t) * RAD
        val solarMeanLongitude = (280.4665 + 36_000.7698 * t) * RAD
        val lunarMeanLongitude = (218.3165 + 481_267.8813 * t) * RAD
        val nutation = (-17.20 * sin(omega) - 1.32 * sin(2 * solarMeanLongitude) -
            0.23 * sin(2 * lunarMeanLongitude) + 0.21 * sin(2 * omega)) / 3600
        val longitude = Astro.norm360(trueLongitude - 0.09033 / 3600 + nutation - 20.4898 / (3600 * distance))
        val obliquity = Astro.meanObliquity(t) + (9.20 * cos(omega) + 0.57 * cos(2 * solarMeanLongitude) +
            0.10 * cos(2 * lunarMeanLongitude) - 0.09 * cos(2 * omega)) / 3600
        val fk5Longitude = (trueLongitude - 1.397 * t - 0.00031 * t * t) * RAD
        val latitude = -earth[1] * DEG + 0.03916 / 3600 * (cos(fk5Longitude) - sin(fk5Longitude))
        val equatorial = Astro.equatorial(longitude, latitude, obliquity)
        return Apparent(longitude, equatorial[0], equatorial[1], distance)
    }

    /** Where the Sun's centre is. Geometric altitude: no refraction. */
    fun position(epochMillis: Long, place: Place): Horizontal {
        val sun = apparent(epochMillis)
        val phi = place.latitude * RAD
        val u = atan(0.99664719 * tan(phi))
        val elevation = place.elevation / (Astro.EARTH_RADIUS_KM * 1000)
        val rhoSin = 0.99664719 * sin(u) + elevation * sin(phi)
        val rhoCos = cos(u) + elevation * cos(phi)
        val h = Astro.hourAngle(sun.rightAscension, Astro.julianDay(epochMillis), place) * RAD
        val dec = sun.declination * RAD
        val sinParallax = Astro.EARTH_RADIUS_KM / (sun.distance * Astro.AU_KM)
        val denominator = cos(dec) - rhoCos * sinParallax * cos(h)
        val deltaRa = atan2(-rhoCos * sinParallax * sin(h), denominator)
        val topDec = atan2((sin(dec) - rhoSin * sinParallax) * cos(deltaRa), denominator) * DEG
        return Astro.horizontal(sun.rightAscension + deltaRa * DEG, topDec, Astro.julianDay(epochMillis), place)
    }

    fun altitude(epochMillis: Long, place: Place): Double = position(epochMillis, place).altitude

    /** Hour angle of the Sun's centre, degrees; it crosses zero from negative at solar noon. */
    fun hourAngle(epochMillis: Long, place: Place): Double =
        Astro.hourAngle(apparent(epochMillis).rightAscension, Astro.julianDay(epochMillis), place)

    /**
     * Centre altitude at which the upper limb touches the horizon: standard refraction (34′) plus the
     * Sun's semi-diameter (16′ at 1 AU) plus the dip for an elevated observer.
     */
    fun horizonAltitude(epochMillis: Long, place: Place): Double =
        -(Astro.REFRACTION + 0.266_6 / apparent(epochMillis).distance + Astro.horizonDip(place.elevation))
}
