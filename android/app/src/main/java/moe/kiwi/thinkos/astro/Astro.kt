package moe.kiwi.thinkos.astro

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Where the observer is. Longitude is positive east, [elevation] is metres above sea level. */
data class Place(val latitude: Double, val longitude: Double, val elevation: Double = 0.0) {
    init {
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0) { "coordinates out of range" }
        require(elevation.isFinite() && elevation in -500.0..10_000.0) { "elevation out of range" }
    }
}

/** Direction in the sky, in degrees. [azimuth] runs clockwise from north. */
data class Horizontal(val azimuth: Double, val altitude: Double)

/** Shared constants and small helpers. Angles are degrees unless a name says otherwise. */
internal object Astro {
    const val RAD = PI / 180.0
    const val DEG = 180.0 / PI
    const val DAY_MILLIS = 86_400_000L
    private const val UNIX_EPOCH_JD = 2_440_587.5
    const val J2000 = 2_451_545.0
    const val AU_KM = 149_597_870.7
    const val EARTH_RADIUS_KM = 6_378.14

    /** TT − UT. About 69 s through the 2020s; the ephemeris is evaluated at TT. */
    const val DELTA_T_SECONDS = 69.2

    /** Standard refraction at the horizon (34′). */
    const val REFRACTION = 34.0 / 60.0

    fun julianDay(epochMillis: Long): Double = epochMillis / DAY_MILLIS.toDouble() + UNIX_EPOCH_JD
    fun epochMillis(julianDay: Double): Long = Math.round((julianDay - UNIX_EPOCH_JD) * DAY_MILLIS)
    fun centuries(julianDay: Double): Double = (julianDay - J2000) / 36_525.0

    fun norm360(degrees: Double): Double {
        val v = degrees % 360.0
        return if (v < 0) v + 360.0 else v
    }

    fun norm180(degrees: Double): Double {
        val v = norm360(degrees)
        return if (v > 180.0) v - 360.0 else v
    }

    /** Greenwich mean sidereal time (Meeus 12.4), from a UT Julian day. */
    fun gmst(julianDayUt: Double): Double {
        val t = centuries(julianDayUt)
        return norm360(280.46061837 + 360.98564736629 * (julianDayUt - J2000) + 0.000387933 * t * t - t * t * t / 38_710_000.0)
    }

    fun meanObliquity(t: Double): Double =
        23.0 + 26.0 / 60 + 21.448 / 3600 - (46.8150 * t + 0.00059 * t * t - 0.001813 * t * t * t) / 3600

    /** Ecliptic (longitude, latitude, any distance unit) to equatorial (right ascension, declination), degrees. */
    fun equatorial(lon: Double, lat: Double, obliquity: Double): DoubleArray {
        val l = lon * RAD
        val b = lat * RAD
        val e = obliquity * RAD
        val x = cos(b) * cos(l)
        val y = cos(b) * sin(l) * cos(e) - sin(b) * sin(e)
        val z = cos(b) * sin(l) * sin(e) + sin(b) * cos(e)
        return doubleArrayOf(norm360(atan2(y, x) * DEG), atan2(z, sqrt(x * x + y * y)) * DEG)
    }

    /** Azimuth and (geometric) altitude of a point with the given right ascension and declination. */
    fun horizontal(rightAscension: Double, declination: Double, julianDayUt: Double, place: Place): Horizontal {
        val h = (gmst(julianDayUt) + place.longitude - rightAscension) * RAD
        val phi = place.latitude * RAD
        val d = declination * RAD
        val altitude = asin(sin(phi) * sin(d) + cos(phi) * cos(d) * cos(h))
        val azimuth = atan2(sin(h), cos(h) * sin(phi) - tan(d) * cos(phi)) * DEG + 180.0
        return Horizontal(norm360(azimuth), altitude * DEG)
    }

    /** Hour angle in degrees, −180..180: negative before the body crosses the meridian. */
    fun hourAngle(rightAscension: Double, julianDayUt: Double, place: Place): Double =
        norm180(gmst(julianDayUt) + place.longitude - rightAscension)

    /** Dip of the horizon for an observer [elevation] metres up, in degrees (positive lowers the horizon). */
    fun horizonDip(elevation: Double): Double = if (elevation <= 0) 0.0 else 1.76 / 60.0 * sqrt(elevation)
}
