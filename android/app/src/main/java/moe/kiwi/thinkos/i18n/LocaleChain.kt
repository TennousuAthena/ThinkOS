package moe.kiwi.thinkos.i18n

import java.util.Locale

/**
 * Which string tables to try, best first, for a device locale.
 * Table names are `en`, `zh-Hans`, `zh-Hant`, `pt`, `ar`, … optionally refined as `pt-PT`, `zh-Hant-HK`.
 */
object LocaleChain {
    /** Languages shipped with ThinkOS: the ten most spoken, plus Traditional Chinese for Hong Kong / Taiwan. */
    val SHIPPED = listOf("en", "zh-Hans", "zh-Hant", "hi", "es", "fr", "ar", "bn", "pt", "ru", "ur")
    private val RTL = setOf("ar", "ur", "he", "fa", "ps", "sd", "yi", "dv", "ku", "ug")
    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")

    fun script(locale: Locale): String {
        if (locale.script.isNotEmpty()) return locale.script
        return if (locale.language == "zh") (if (locale.country in TRADITIONAL_REGIONS) "Hant" else "Hans") else ""
    }

    fun candidates(locale: Locale): List<String> {
        val language = locale.language.ifEmpty { "en" }
        val script = script(locale)
        val region = locale.country
        return buildList {
            if (script.isNotEmpty() && region.isNotEmpty()) add("$language-$script-$region")
            if (script.isNotEmpty()) add("$language-$script")
            if (region.isNotEmpty()) add("$language-$region")
            add(language)
            add("en")
        }.distinct()
    }

    fun isRtl(locale: Locale): Boolean = locale.language in RTL
}
