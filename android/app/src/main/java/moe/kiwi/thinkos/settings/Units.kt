package moe.kiwi.thinkos.settings

/**
 * The measurement units a person chose in system settings (`units.*`). Plugins read the resolved result through
 * `tos.units`; they never see the raw choice and never convert on their own.
 */
data class Units(val system: String, val windChoice: String) {
    /** `c` or `f`. */
    val temperature: String get() = if (system == IMPERIAL) "f" else "c"

    /** `kmh`, `ms`, `mph` or `kn`; `auto` follows the system. */
    val wind: String get() = if (windChoice == AUTO) (if (system == IMPERIAL) "mph" else "kmh") else windChoice

    /** Convert degrees Celsius to the chosen temperature unit. */
    fun temperature(celsius: Double): Double = if (temperature == "f") celsius * 1.8 + 32 else celsius

    /** Convert kilometres per hour to the chosen wind unit. */
    fun wind(kmh: Double): Double = when (wind) {
        "ms" -> kmh / 3.6
        "mph" -> kmh * 0.621371192
        "kn" -> kmh * 0.539956803
        else -> kmh
    }

    fun toMap(): Map<String, Any?> = mapOf("system" to system, "temperature" to temperature, "wind" to wind)

    companion object {
        const val METRIC = "metric"
        const val IMPERIAL = "imperial"
        const val AUTO = "auto"
    }
}
