package moe.kiwi.thinkos.i18n

import moe.kiwi.thinkos.tz.ZoneFormat
import moe.kiwi.thinkos.tz.Zones
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class FormatsTest {
    private class Env(override val locale: Locale, override val hour24: Boolean, val table: Map<String, String>) : FormatEnv {
        override fun bestPattern(skeleton: String) = table.getValue(skeleton)
    }

    @After fun restore() { Formats.env = EnglishFormatEnv; Formats.invalidate() }

    @Test
    fun timeFollowsTheSystemHourCycle() {
        val table = mapOf("Hm" to "HH:mm", "hm" to "h:mm a", "Hms" to "HH:mm:ss", "hms" to "h:mm:ss a")
        Formats.env = Env(Locale.US, true, table); Formats.invalidate()
        assertEquals("HH:mm", Formats.pattern("@time"))
        assertEquals("HH:mm:ss", Formats.pattern("@timeSeconds"))
        Formats.env = Env(Locale.US, false, table); Formats.invalidate()
        assertEquals("h:mm a", Formats.pattern("@time"))
    }

    @Test
    fun rawPatternsPassThroughAndUnknownNamesBecomeText() {
        assertEquals("yyyy-MM-dd", Formats.pattern("yyyy-MM-dd"))
        assertEquals("nonsense", Formats.pattern("@nonsense"))
    }

    @Test
    fun namedDatesUseTheLocalesOwnOrder() {
        Formats.env = Env(Locale.JAPAN, true, mapOf("yMMMMEEEEd" to "y年M月d日EEEE")); Formats.invalidate()
        assertEquals("y年M月d日EEEE", Formats.pattern("@dateFull"))
        Formats.env = Env(Locale.GERMANY, true, mapOf("yMMMMEEEEd" to "EEEE, d. MMMM y")); Formats.invalidate()
        assertEquals("EEEE, d. MMMM y", Formats.pattern("@dateFull"))
    }

    @Test
    fun timeLayoutSplitsAPattern() {
        assertEquals(TimeLayout("HH", ":", "mm", null, null, false), TimeLayout.parse("HH:mm"))
        assertEquals(TimeLayout("h", ":", "mm", null, "a", false), TimeLayout.parse("h:mm a"))
        assertEquals(TimeLayout("h", ":", "mm", null, "a", true), TimeLayout.parse("ah:mm"))          // Chinese: period first
        assertEquals(TimeLayout("H", ".", "mm", null, null, false), TimeLayout.parse("H.mm"))          // Finnish
        assertEquals(TimeLayout("HH", ":", "mm", "ss", null, false), TimeLayout.parse("HH:mm:ss"))
        assertEquals(TimeLayout("HH", " h ", "mm", null, null, false), TimeLayout.parse("HH 'h' mm"))  // quoted literal between them
        assertEquals(TimeLayout("h", ":", "mm", null, "a", false), TimeLayout.parse("h:mm a"))
    }

    @Test
    fun formattingInAZoneUsesTheNamedPattern() {
        Zones.install(moe.kiwi.thinkos.tz.TzTestSupport.database)
        try {
            val instant = moe.kiwi.thinkos.tz.TzTestSupport.utc(2026, 7, 1, 20, 30) * 1000
            val tokyo = Zones.clock("Asia/Tokyo")
            assertEquals("5:30 AM", ZoneFormat.format("@time", instant, tokyo, Locale.US))
            assertEquals("Thursday, July 2, 2026", ZoneFormat.format("@dateFull", instant, tokyo, Locale.US))
            assertNull(null)
        } finally {
            Zones.install(null)
        }
    }
}
