package moe.kiwi.thinkos.i18n

import java.util.Locale

/** What the system says about how times and dates should look (ADR-020). */
interface FormatEnv {
    val locale: Locale
    /** The user's 12/24-hour choice in Android's own settings. */
    val hour24: Boolean
    /** The locale's own best pattern for a CLDR skeleton such as `yMMMMEEEEd`. */
    fun bestPattern(skeleton: String): String
}

/**
 * Named formats. Plugins and views say what they want to show (`@time`, `@dateFull`) and the OS
 * decides how, from the system locale and the system 12/24-hour setting; raw SimpleDateFormat patterns
 * still work for the rare case that needs one.
 */
object Formats {
    @Volatile var env: FormatEnv = EnglishFormatEnv

    /** name -> skeleton. `@time*` are chosen per the system hour cycle. */
    private val NAMED = mapOf(
        "date" to "yMd", "dateLong" to "yMMMMd", "dateFull" to "yMMMMEEEEd", "weekday" to "EEEE", "weekdayShort" to "EEE",
        "monthYear" to "yMMMM", "monthDay" to "MMMMd", "monthDayWeekday" to "MMMMEEEEd", "day" to "d", "month" to "MMMM", "year" to "y",
    )
    val names: Set<String> get() = NAMED.keys + setOf("time", "timeSeconds")

    private data class Key(val skeleton: String, val tag: String, val hour24: Boolean)
    private val cache = object : LinkedHashMap<Key, String>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, String>) = size > 64
    }

    /** `@name` -> a SimpleDateFormat pattern for the current system settings; other text is returned unchanged. */
    fun pattern(spec: String): String {
        if (!spec.startsWith("@")) return spec
        val name = spec.substring(1)
        val e = env
        val skeleton = when (name) {
            "time" -> if (e.hour24) "Hm" else "hm"
            "timeSeconds" -> if (e.hour24) "Hms" else "hms"
            else -> NAMED[name] ?: return spec.substring(1)       // unknown name: show it as text rather than crash
        }
        return resolve(e, skeleton)
    }

    @Synchronized private fun resolve(e: FormatEnv, skeleton: String): String =
        cache.getOrPut(Key(skeleton, e.locale.toLanguageTag(), e.hour24)) { e.bestPattern(skeleton) }

    @Synchronized fun invalidate() = cache.clear()
}

/**
 * The pieces of a time-of-day pattern, so a big digital clock can place the hour, separator, minute and
 * AM/PM marker itself. `h:mm a` gives hour=`h`, separator `:`, minute=`mm`, period after;
 * Chinese `ah:mm` has the period first.
 */
data class TimeLayout(val hour: String, val separator: String, val minute: String, val second: String?, val period: String?, val periodFirst: Boolean) {
    companion object {
        private val HOUR_LETTERS = "hHkK"

        fun parse(pattern: String): TimeLayout {
            var i = 0
            var hour = ""
            var minute = ""
            var second: String? = null
            var period: String? = null
            var periodIndex = -1
            var hourIndex = -1
            var separator = ""
            var seenHour = false
            var sepStart = -1
            val text = StringBuilder()
            var tokenIndex = 0
            while (i < pattern.length) {
                val c = pattern[i]
                when {
                    c == '\'' -> {                                      // quoted literal
                        val end = pattern.indexOf('\'', i + 1).let { if (it < 0) pattern.length else it }
                        if (end == i + 1) text.append('\'') else text.append(pattern, i + 1, end)
                        i = end + 1
                    }
                    c.isLetter() -> {
                        var j = i
                        while (j < pattern.length && pattern[j] == c) j++
                        val run = pattern.substring(i, j)
                        when {
                            c in HOUR_LETTERS && hour.isEmpty() -> { hour = run; hourIndex = tokenIndex; seenHour = true; text.setLength(0) }
                            c == 'm' && minute.isEmpty() -> { minute = run; if (seenHour) { separator = text.toString(); text.setLength(0) } }
                            c == 's' && second == null -> second = run
                            c == 'a' || c == 'b' || c == 'B' -> { period = run; periodIndex = tokenIndex }
                        }
                        tokenIndex++
                        i = j
                    }
                    else -> { text.append(c); i++ }
                }
            }
            if (hour.isEmpty()) hour = "HH"
            if (minute.isEmpty()) minute = "mm"
            return TimeLayout(hour, separator.ifEmpty { ":" }, minute, second, period, period != null && periodIndex in 0 until hourIndex)
        }
    }
}

/** English patterns for unit tests and for the moment before Android's own are available. */
object EnglishFormatEnv : FormatEnv {
    override val locale: Locale = Locale.ENGLISH
    override val hour24: Boolean = false
    override fun bestPattern(skeleton: String): String = when (skeleton) {
        "Hm" -> "HH:mm"; "hm" -> "h:mm a"; "Hms" -> "HH:mm:ss"; "hms" -> "h:mm:ss a"
        "yMd" -> "M/d/y"; "yMMMMd" -> "MMMM d, y"; "yMMMMEEEEd" -> "EEEE, MMMM d, y"
        "EEEE" -> "EEEE"; "EEE" -> "EEE"; "yMMMM" -> "MMMM y"; "MMMMd" -> "MMMM d"; "MMMMEEEEd" -> "EEEE, MMMM d"
        "d" -> "d"; "MMMM" -> "MMMM"; "y" -> "y"
        else -> skeleton
    }
}
