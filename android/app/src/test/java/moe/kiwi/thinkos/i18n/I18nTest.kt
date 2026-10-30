package moe.kiwi.thinkos.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class I18nTest {
    private fun locale(tag: String) = Locale.forLanguageTag(tag)

    @Test
    fun chineseScriptFollowsRegionWhenUnstated() {
        assertEquals(listOf("zh-Hans-CN", "zh-Hans", "zh-CN", "zh", "en"), LocaleChain.candidates(locale("zh-CN")))
        assertEquals(listOf("zh-Hant-HK", "zh-Hant", "zh-HK", "zh", "en"), LocaleChain.candidates(locale("zh-HK")))
        assertEquals("Hant", LocaleChain.script(locale("zh-TW")))
        assertEquals("Hans", LocaleChain.script(locale("zh")))
        assertEquals("Hant", LocaleChain.script(locale("zh-Hant-CN")))
    }

    @Test
    fun otherLanguagesFallBackToLanguageThenEnglish() {
        assertEquals(listOf("pt-BR", "pt", "en"), LocaleChain.candidates(locale("pt-BR")))
        assertEquals(listOf("ar", "en"), LocaleChain.candidates(locale("ar")))
        assertEquals(listOf("en"), LocaleChain.candidates(locale("en")))
        assertEquals(listOf("en-GB", "en"), LocaleChain.candidates(locale("en-GB")))
    }

    @Test
    fun rightToLeftLanguages() {
        for (tag in listOf("ar", "ar-EG", "ur", "ur-PK")) assertTrue(tag, LocaleChain.isRtl(locale(tag)))
        for (tag in listOf("en", "zh-CN", "hi", "bn", "ru")) assertFalse(tag, LocaleChain.isRtl(locale(tag)))
    }

    @Test
    fun everyShippedLanguageHasAFallbackPath() {
        for (name in LocaleChain.SHIPPED) assertEquals("en", LocaleChain.candidates(locale(name.substringBefore('-'))).last())
        assertEquals(11, LocaleChain.SHIPPED.size)
    }

    private val table = StringTable(mapOf(
        "en" to mapOf("hello" to "Hello", "only_en" to "English only", "items.one" to "{count} item", "items.other" to "{count} items", "temp" to "{city}: {value}°"),
        "zh-Hans" to mapOf("hello" to "你好", "items.other" to "{count} 项"),
        "zh-Hant" to mapOf("hello" to "你好（繁）"),
        "ru" to mapOf("items.one" to "{count} элемент", "items.few" to "{count} элемента", "items.many" to "{count} элементов", "items.other" to "{count} элемента"),
        "ar" to mapOf("items.zero" to "لا عناصر", "items.one" to "عنصر واحد", "items.two" to "عنصران", "items.few" to "{count} عناصر", "items.many" to "{count} عنصرًا", "items.other" to "{count} عنصر"),
    ))

    @Test
    fun lookupFallsBackThroughTheChainToEnglish() {
        assertEquals("你好", table.format("hello", locale("zh-CN")))
        assertEquals("你好（繁）", table.format("hello", locale("zh-HK")))
        assertEquals("English only", table.format("only_en", locale("zh-CN")))
        assertEquals("Hello", table.format("hello", locale("de")))
        assertNull(table.format("nothing", locale("en")))
    }

    @Test
    fun placeholdersAreFilledAndUnknownOnesStayVisible() {
        assertEquals("Tokyo: 21.5°", table.format("temp", Locale.ENGLISH, mapOf("city" to "Tokyo", "value" to 21.5)))
        assertEquals("{city}: 3°", table.format("temp", Locale.ENGLISH, mapOf("value" to 3)))
    }

    @Test
    fun englishPlurals() {
        assertEquals("1 item", table.format("items", Locale.ENGLISH, mapOf("count" to 1)))
        assertEquals("2 items", table.format("items", Locale.ENGLISH, mapOf("count" to 2)))
        assertEquals("0 items", table.format("items", Locale.ENGLISH, mapOf("count" to 0)))
        assertEquals("1,000 items", table.format("items", Locale.US, mapOf("count" to 1000)))
    }

    @Test
    fun russianPluralCategories() {
        val ru = locale("ru")
        for ((n, expected) in listOf(1 to "one", 2 to "few", 4 to "few", 5 to "many", 11 to "many", 12 to "many", 14 to "many", 21 to "one", 22 to "few", 25 to "many", 111 to "many", 101 to "one")) {
            assertEquals("$n", expected, Plurals.category(ru, n.toDouble()))
        }
        assertEquals("21 элемент", table.format("items", ru, mapOf("count" to 21)))
        assertEquals("1,5 элемента", table.format("items", Locale("ru", "RU"), mapOf("count" to 1.5)))     // fractions are "other"; the comma is the locale's
    }

    @Test
    fun arabicPluralCategories() {
        val ar = locale("ar")
        for ((n, expected) in listOf(0 to "zero", 1 to "one", 2 to "two", 3 to "few", 10 to "few", 11 to "many", 99 to "many", 100 to "other", 102 to "other", 103 to "few")) {
            assertEquals("$n", expected, Plurals.category(ar, n.toDouble()))
        }
        assertEquals("عنصران", table.format("items", ar, mapOf("count" to 2)))
    }

    @Test
    fun otherLanguagePlurals() {
        assertEquals("other", Plurals.category(locale("zh-CN"), 1.0))
        assertEquals("one", Plurals.category(locale("fr"), 0.0))
        assertEquals("one", Plurals.category(locale("fr"), 1.5))
        assertEquals("other", Plurals.category(locale("fr"), 2.0))
        assertEquals("one", Plurals.category(locale("pt"), 0.0))
        assertEquals("one", Plurals.category(locale("hi"), 0.0))
        assertEquals("other", Plurals.category(locale("bn"), 2.0))
        assertEquals("one", Plurals.category(locale("es"), 1.0))
        assertEquals("other", Plurals.category(locale("ur"), 0.0))
        assertEquals("one", Plurals.category(locale("ur"), 1.0))
    }

    @Test
    fun labelReferences() {
        val labels = Labels({ key -> if (key == "setting.theme.mode") "Theme" else null }, { plugin, key, _, _ -> if (plugin == "weather" && key == "city") "City" else null })
        assertEquals("Theme", labels.resolve("system", "@setting.theme.mode"))
        assertEquals("City", labels.resolve("weather", "@city"))
        assertEquals("Plain text", labels.resolve("weather", "Plain text"))
        assertEquals("@literal", labels.resolve("weather", "@@literal"))
        assertEquals("missing.key", labels.resolve("weather", "@missing.key"))     // a hole shows its key, never crashes
        assertTrue(Labels.isReference("@x"))
        assertFalse(Labels.isReference("@@x"))
        assertFalse(Labels.isReference("x"))
    }

    @Test
    fun loadsTablesFromAPluginDirectoryAndSkipsDamagedFiles() {
        val directory = createTempDir()
        try {
            val i18n = java.io.File(directory, "i18n").apply { mkdirs() }
            java.io.File(i18n, "en.json").writeText("""{"hello":"Hello"}""")
            java.io.File(i18n, "zh-Hans.json").writeText("""{"hello":"你好"}""")
            java.io.File(i18n, "ru.json").writeText("{broken")
            java.io.File(i18n, "ar.json").writeText("""{"hello":42}""")      // values must be strings
            val loaded = StringTable.load(directory)
            assertEquals(setOf("en", "zh-Hans"), loaded.languages)
            assertEquals("你好", loaded.format("hello", locale("zh-CN")))
            assertEquals("Hello", loaded.format("hello", locale("ru")))
        } finally {
            directory.deleteRecursively()
        }
        assertTrue(StringTable.load(java.io.File("/nonexistent-thinkos")).languages.isEmpty())
    }
}
