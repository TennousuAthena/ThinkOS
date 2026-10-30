package moe.kiwi.thinkos.astro

import moe.kiwi.thinkos.astro.Astro.DEG
import moe.kiwi.thinkos.astro.Astro.RAD
import moe.kiwi.thinkos.tz.ZoneClock
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Language-neutral names; a plugin translates these ids through its string table. */
enum class MoonPhaseName(val id: String) {
    NEW("new"), WAXING_CRESCENT("waxing_crescent"), FIRST_QUARTER("first_quarter"),
    WAXING_GIBBOUS("waxing_gibbous"), FULL("full"), WANING_GIBBOUS("waning_gibbous"),
    LAST_QUARTER("last_quarter"), WANING_CRESCENT("waning_crescent"),
}

data class MoonPhase(
    /** Moon minus Sun ecliptic longitude, 0..360 degrees. */
    val angle: Double,
    /** Illuminated fraction, 0..1 (not a percentage). */
    val illumination: Double,
    val waxing: Boolean,
    val name: MoonPhaseName,
    /** Elapsed days since the actual preceding new moon, not a mean synodic month. */
    val ageDays: Double,
    val previousNewMoon: Long,
    val nextNewMoon: Long,
    val nextFirstQuarter: Long,
    val nextFullMoon: Long,
    val nextLastQuarter: Long,
)

data class MoonDay(
    val start: Long,
    val end: Long,
    /** All crossings in the civil day, including the rare two-rise day around a clock change. */
    val rises: List<Long>,
    val sets: List<Long>,
    val transits: List<Long>,
    val alwaysUp: Boolean,
    val alwaysDown: Boolean,
    val phase: MoonPhase,
    val position: Horizontal,
) {
    val rise: Long? get() = rises.firstOrNull()
    val set: Long? get() = sets.firstOrNull()
    val transit: Long? get() = transits.firstOrNull()
}

/**
 * Schlyter's orbital elements and lunar perturbations (all 12 longitude, 5 latitude and 2 distance
 * terms), https://stjarnhimlen.se/comp/ppcomp.html sections 4, 6–9. Coordinates are of date.
 * TT = UT + 69.2 s in this 2020s implementation; no platform ephemeris or network is required.
 * Observer parallax uses the oblate-Earth construction of Meeus ch. 40; refraction is applied only
 * to the rise/set threshold, never to [position].
 */
object Moon {
    internal data class Apparent(
        val longitude: Double,
        val latitude: Double,
        val distanceKm: Double,
        val rightAscension: Double,
        val declination: Double,
    )

    internal fun apparent(epochMillis: Long): Apparent {
        val jd = Astro.julianDay(epochMillis) + Astro.DELTA_T_SECONDS / 86_400.0
        val d = jd - 2_451_543.5
        val node = Astro.norm360(125.1228 - 0.0529538083 * d) * RAD
        val inclination = 5.1454 * RAD
        val perigee = Astro.norm360(318.0634 + 0.1643573223 * d) * RAD
        val anomaly = Astro.norm360(115.3654 + 13.0649929509 * d) * RAD
        val eccentricity = 0.0549
        var eccentricAnomaly = anomaly + eccentricity * sin(anomaly) * (1 + eccentricity * cos(anomaly))
        repeat(4) {
            eccentricAnomaly -= (eccentricAnomaly - eccentricity * sin(eccentricAnomaly) - anomaly) /
                (1 - eccentricity * cos(eccentricAnomaly))
        }
        val x = 60.2666 * (cos(eccentricAnomaly) - eccentricity)
        val y = 60.2666 * sqrt(1 - eccentricity * eccentricity) * sin(eccentricAnomaly)
        var distance = sqrt(x * x + y * y)
        val argument = atan2(y, x) + perigee
        val xe = cos(node) * cos(argument) - sin(node) * sin(argument) * cos(inclination)
        val ye = sin(node) * cos(argument) + cos(node) * sin(argument) * cos(inclination)
        val ze = sin(argument) * sin(inclination)
        var longitude = atan2(ye, xe) * DEG
        var latitude = atan2(ze, sqrt(xe * xe + ye * ye)) * DEG
        val solarAnomaly = Astro.norm360(356.0470 + 0.9856002585 * d) * RAD
        val solarPerigee = Astro.norm360(282.9404 + 4.70935e-5 * d) * RAD
        val elongation = anomaly + perigee + node - solarAnomaly - solarPerigee
        val argumentLatitude = anomaly + perigee
        longitude += -1.274 * sin(anomaly - 2 * elongation) + 0.658 * sin(2 * elongation) -
            0.186 * sin(solarAnomaly) - 0.059 * sin(2 * anomaly - 2 * elongation) -
            0.057 * sin(anomaly - 2 * elongation + solarAnomaly) + 0.053 * sin(anomaly + 2 * elongation) +
            0.046 * sin(2 * elongation - solarAnomaly) + 0.041 * sin(anomaly - solarAnomaly) -
            0.035 * sin(elongation) - 0.031 * sin(anomaly + solarAnomaly) -
            0.015 * sin(2 * argumentLatitude - 2 * elongation) + 0.011 * sin(anomaly - 4 * elongation)
        latitude += -0.173 * sin(argumentLatitude - 2 * elongation) -
            0.055 * sin(anomaly - argumentLatitude - 2 * elongation) -
            0.046 * sin(anomaly + argumentLatitude - 2 * elongation) +
            0.033 * sin(argumentLatitude + 2 * elongation) + 0.017 * sin(2 * anomaly + argumentLatitude)
        distance += -0.58 * cos(anomaly - 2 * elongation) - 0.46 * cos(2 * elongation)
        val omega = (125.04 - 1934.136 * Astro.centuries(jd)) * RAD
        longitude = Astro.norm360(longitude - 0.00478 * sin(omega))
        val obliquity = Astro.meanObliquity(Astro.centuries(jd)) + 0.00256 * cos(omega)
        val equatorial = Astro.equatorial(longitude, latitude, obliquity)
        return Apparent(longitude, latitude, distance * Astro.EARTH_RADIUS_KM, equatorial[0], equatorial[1])
    }

    /** Topocentric, geometric direction in degrees; atmospheric refraction is deliberately absent. */
    fun position(epochMillis: Long, place: Place): Horizontal {
        val moon = apparent(epochMillis)
        val phi = place.latitude * RAD
        val u = atan(0.99664719 * kotlin.math.tan(phi))
        val elevation = place.elevation / (Astro.EARTH_RADIUS_KM * 1000)
        val rhoSin = 0.99664719 * sin(u) + elevation * sin(phi)
        val rhoCos = cos(u) + elevation * cos(phi)
        val hourAngle = Astro.hourAngle(moon.rightAscension, Astro.julianDay(epochMillis), place) * RAD
        val dec = moon.declination * RAD
        val sinParallax = Astro.EARTH_RADIUS_KM / moon.distanceKm
        val denominator = cos(dec) - rhoCos * sinParallax * cos(hourAngle)
        val deltaRa = atan2(-rhoCos * sinParallax * sin(hourAngle), denominator)
        val topDec = atan2((sin(dec) - rhoSin * sinParallax) * cos(deltaRa), denominator) * DEG
        return Astro.horizontal(moon.rightAscension + deltaRa * DEG, topDec, Astro.julianDay(epochMillis), place)
    }

    /** Upper limb crossing a level horizon, standard 34′ refraction, plus observer horizon dip. */
    internal fun horizonAltitude(epochMillis: Long, place: Place): Double =
        -(Astro.REFRACTION + asin(1737.4 / apparent(epochMillis).distanceKm) * DEG + Astro.horizonDip(place.elevation))

    fun day(epochMillis: Long, zone: ZoneClock, place: Place): MoonDay {
        val window = AstroEvents.dayWindow(epochMillis, zone)
        val crossings = AstroEvents.crossings(window.start, window.end) { t ->
            position(t, place).altitude - horizonAltitude(t, place)
        }
        // Hour angle jumps from +180 to -180 at lower transit: only an increasing zero is upper transit.
        val transits = AstroEvents.crossings(window.start, window.end) { t ->
            Astro.hourAngle(apparent(t).rightAscension, Astro.julianDay(t), place)
        }.filter { it.rising }.map { it.time }
        val above = position(epochMillis, place).altitude > horizonAltitude(epochMillis, place)
        return MoonDay(window.start, window.end, crossings.filter { it.rising }.map { it.time },
            crossings.filterNot { it.rising }.map { it.time }, transits,
            crossings.isEmpty() && above, crossings.isEmpty() && !above, phase(epochMillis), position(epochMillis, place))
    }

    private fun elongation(epochMillis: Long): Double =
        Astro.norm360(apparent(epochMillis).longitude - Sun.apparent(epochMillis).longitude)

    fun phase(epochMillis: Long): MoonPhase {
        val moon = apparent(epochMillis)
        val sun = Sun.apparent(epochMillis)
        val angle = Astro.norm360(moon.longitude - sun.longitude)
        val separation = acos((cos(moon.latitude * RAD) * cos(angle * RAD)).coerceIn(-1.0, 1.0))
        val solarDistanceKm = sun.distance * Astro.AU_KM
        val phaseAngle = atan2(solarDistanceKm * sin(separation), moon.distanceKm - solarDistanceKm * cos(separation))
        val illumination = ((1 + cos(phaseAngle)) / 2).coerceIn(0.0, 1.0)
        val previous = phaseEvent(epochMillis, 0, false)
        return MoonPhase(angle, illumination, angle < 180, MoonPhaseName.entries[((angle + 22.5) / 45).toInt() % 8],
            (epochMillis - previous) / Astro.DAY_MILLIS.toDouble(), previous,
            nextPhase(epochMillis, 0), nextPhase(epochMillis, 1), nextPhase(epochMillis, 2), nextPhase(epochMillis, 3))
    }

    /** First actual longitude crossing at or after [epochMillis]; 0=new, 1=first, 2=full, 3=last. */
    fun nextPhase(epochMillis: Long, quarter: Int): Long {
        require(quarter in 0..3) { "quarter must be 0..3" }
        return phaseEvent(epochMillis, quarter, true)
    }

    private fun phaseEvent(epochMillis: Long, quarter: Int, forward: Boolean): Long {
        val target = quarter * 90.0
        fun value(t: Long) = Astro.norm180(elongation(t) - target)
        val step = 6 * 3_600_000L * if (forward) 1 else -1
        var a = epochMillis
        var fa = value(a)
        if (kotlin.math.abs(fa) < 1e-8) return a
        repeat(140) { // >35 days, longer than the longest synodic month.
            val b = a + step
            val fb = value(b)
            if ((forward && fa < 0 && fb >= 0) || (!forward && fa >= 0 && fb < 0)) {
                var lo = minOf(a, b)
                var hi = maxOf(a, b)
                while (hi - lo > 1L) {
                    val mid = lo + (hi - lo) / 2
                    if (value(mid) < 0) lo = mid else hi = mid
                }
                return hi
            }
            a = b
            fa = fb
        }
        error("lunar phase crossing not bracketed")
    }
}
