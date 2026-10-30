package moe.kiwi.thinkos.i18n

import java.util.Locale

/**
 * CLDR plural categories for the shipped languages, written out so behaviour is identical on the
 * device and in unit tests. Only the categories that language actually uses are returned.
 */
object Plurals {
    fun category(locale: Locale, count: Double): String {
        if (!count.isFinite()) return "other"
        val integer = count == Math.rint(count)
        val n = Math.abs(count)
        val i = Math.floor(n).toLong()
        return when (locale.language) {
            "zh", "ja", "ko", "vi", "th", "id" -> "other"
            "fr", "pt" -> if (i == 0L || i == 1L) "one" else "other"                  // 0 and 1 (and 1.5) are singular
            "hi", "bn" -> if (i == 0L || n == 1.0) "one" else "other"
            "ru" -> {
                if (!integer) "other" else {
                    val m10 = i % 10
                    val m100 = i % 100
                    when {
                        m10 == 1L && m100 != 11L -> "one"
                        m10 in 2..4 && m100 !in 12..14 -> "few"
                        else -> "many"
                    }
                }
            }
            "ar" -> {
                if (!integer) "other" else {
                    val m100 = i % 100
                    when {
                        i == 0L -> "zero"
                        i == 1L -> "one"
                        i == 2L -> "two"
                        m100 in 3..10 -> "few"
                        m100 in 11..99 -> "many"
                        else -> "other"
                    }
                }
            }
            else -> if (n == 1.0) "one" else "other"                                  // en, es (core), ur, de, …
        }
    }
}
