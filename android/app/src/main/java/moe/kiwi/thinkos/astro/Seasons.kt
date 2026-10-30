package moe.kiwi.thinkos.astro

import kotlin.math.cos

/** The four astronomical season boundaries, as UTC Unix milliseconds. Names are hemisphere neutral. */
data class SeasonYear(
    val year: Int,
    val marchEquinox: Long,
    val juneSolstice: Long,
    val septemberEquinox: Long,
    val decemberSolstice: Long,
)

/**
 * Equinoxes and solstices using Meeus, Astronomical Algorithms, 2nd ed., chapter 27:
 * the mean-date polynomials (Table 27.B) and all 24 periodic terms (Table 27.C).
 *
 * These are instants, independent of the observer's location or time zone. Convert them with the
 * bundled time-zone engine when displaying them. The calculation uses the shared fixed TT − UT
 * estimate of 69.2 s, appropriate to the 2020s; it does not predict future changes in Earth's rotation.
 * The supported range is deliberately 1950–2050. Across that range the independent PyEphem 4.2.1
 * vectors differ by at most 71 s (44 s during 2020–2029); this is a minute-scale display calculation.
 *
 * Coefficients cross-checked against the open-source chapter 27 implementation at
 * https://github.com/soniakeys/meeus/blob/master/v3/solstice/solstice.go .
 */
object Seasons {
    const val MIN_YEAR = 1950
    const val MAX_YEAR = 2050

    fun forYear(year: Int): SeasonYear {
        require(year in MIN_YEAR..MAX_YEAR) { "seasons year must be in $MIN_YEAR..$MAX_YEAR" }
        val y = (year - 2000) / 1000.0
        return SeasonYear(
            year,
            boundary(y, meanPolynomials[0]),
            boundary(y, meanPolynomials[1]),
            boundary(y, meanPolynomials[2]),
            boundary(y, meanPolynomials[3]),
        )
    }

    private fun boundary(y: Double, coefficients: DoubleArray): Long {
        var meanJde = coefficients.last()
        for (i in coefficients.lastIndex - 1 downTo 0) meanJde = meanJde * y + coefficients[i]
        val t = Astro.centuries(meanJde)
        val w = (35_999.373 * t - 2.47) * Astro.RAD
        val longitudeRate = 1 + 0.0334 * cos(w) + 0.0007 * cos(2 * w)
        var correction = 0.0
        for (term in periodicTerms) correction += term[0] * cos((term[1] + term[2] * t) * Astro.RAD)
        val jde = meanJde + 0.00001 * correction / longitudeRate
        return Astro.epochMillis(jde - Astro.DELTA_T_SECONDS / 86_400.0)
    }

    private val meanPolynomials = arrayOf(
        doubleArrayOf(2_451_623.80984, 365_242.37404, 0.05169, -0.00411, -0.00057),
        doubleArrayOf(2_451_716.56767, 365_241.62603, 0.00325, 0.00888, -0.00030),
        doubleArrayOf(2_451_810.21715, 365_242.01767, -0.11575, 0.00337, 0.00078),
        doubleArrayOf(2_451_900.05952, 365_242.74049, -0.06223, -0.00823, 0.00032),
    )

    // [amplitude, phase (degrees), angular frequency (degrees per Julian century)]
    private val periodicTerms = arrayOf(
        doubleArrayOf(485.0, 324.96, 1934.136),
        doubleArrayOf(203.0, 337.23, 32964.467),
        doubleArrayOf(199.0, 342.08, 20.186),
        doubleArrayOf(182.0, 27.85, 445267.112),
        doubleArrayOf(156.0, 73.14, 45036.886),
        doubleArrayOf(136.0, 171.52, 22518.443),
        doubleArrayOf(77.0, 222.54, 65928.934),
        doubleArrayOf(74.0, 296.72, 3034.906),
        doubleArrayOf(70.0, 243.58, 9037.513),
        doubleArrayOf(58.0, 119.81, 33718.147),
        doubleArrayOf(52.0, 297.17, 150.678),
        doubleArrayOf(50.0, 21.02, 2281.226),
        doubleArrayOf(45.0, 247.54, 29929.562),
        doubleArrayOf(44.0, 325.15, 31555.956),
        doubleArrayOf(29.0, 60.93, 4443.417),
        doubleArrayOf(18.0, 155.12, 67555.328),
        doubleArrayOf(17.0, 288.79, 4562.452),
        doubleArrayOf(16.0, 198.04, 62894.029),
        doubleArrayOf(14.0, 199.76, 31436.921),
        doubleArrayOf(12.0, 95.39, 14577.848),
        doubleArrayOf(12.0, 287.11, 31931.756),
        doubleArrayOf(12.0, 320.81, 34777.259),
        doubleArrayOf(9.0, 227.73, 1222.114),
        doubleArrayOf(8.0, 15.45, 16859.074),
    )
}
