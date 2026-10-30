package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingFieldTest {
    private fun parse(key: String, json: String, system: Boolean = false) = SettingField.parse(key, JSONObject(json), system)

    @Test
    fun fourTypesParseWithTheirDefaults() {
        assertEquals(true, parse("hour24", """{"type":"bool","label":"24h","default":true}""").default)
        assertEquals("c", parse("unit", """{"type":"choice","label":"Unit","default":"c","choices":[{"value":"c","label":"C"},{"value":"f","label":"F"}]}""").default)
        assertEquals(22.3, parse("lat", """{"type":"number","label":"Lat","default":22.3,"min":-90,"max":90,"step":0.1}""").default)
        assertEquals("Hong Kong", parse("city", """{"type":"text","label":"City","default":"Hong Kong"}""").default)
    }

    @Test
    fun wholeNumbersStayIntegersSoTemplatesPrintCleanly() {
        val field = parse("interval", """{"type":"number","label":"s","default":20,"min":5,"max":120}""")
        assertEquals(20L, field.default)
        assertEquals(25L, field.validate(25.0))
        assertEquals(22.5, field.validate(22.5))
    }

    @Test
    fun valuesOutsideTheDeclarationAreRefusedWithAStructuredReason() {
        val number = parse("n", """{"type":"number","label":"n","default":5,"min":1,"max":10}""")
        assertEquals("min", assertThrows(SettingError::class.java) { number.validate(0) }.code)
        assertEquals("max", assertThrows(SettingError::class.java) { number.validate(11) }.code)
        assertEquals("type", assertThrows(SettingError::class.java) { number.validate("5") }.code)
        assertEquals("type", assertThrows(SettingError::class.java) { number.validate(Double.NaN) }.code)
        val text = parse("t", """{"type":"text","label":"t","default":"","maxLength":4}""")
        assertEquals("length", assertThrows(SettingError::class.java) { text.validate("12345") }.code)
        assertEquals("chars", assertThrows(SettingError::class.java) { text.validate("a\u0000") }.code)
        val choice = parse("c", """{"type":"choice","label":"c","default":"a","choices":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""")
        assertEquals("option", assertThrows(SettingError::class.java) { choice.validate("z") }.code)
        assertEquals("type", assertThrows(SettingError::class.java) { parse("b", """{"type":"bool","label":"b","default":false}""").validate("true") }.code)
    }

    @Test
    fun malformedDeclarationsFailClosed() {
        val bad = listOf(
            """{"type":"bool","label":"x"}""",                                                  // no default
            """{"type":"bool","label":"x","default":"yes"}""",                                  // wrong default type
            """{"type":"bool","label":"x","default":true,"min":1}""",                           // property of another type
            """{"type":"slider","label":"x","default":1}""",                                    // unknown type
            """{"type":"number","label":"x","default":5}""",                                    // no range
            """{"type":"number","label":"x","default":50,"min":1,"max":10}""",                  // default out of range
            """{"type":"number","label":"x","default":5,"min":10,"max":1}""",                   // inverted range
            """{"type":"choice","label":"x","default":"a"}""",                                  // neither choices nor source
            """{"type":"choice","label":"x","default":"a","choices":[{"value":"a","label":"A"}]}""",   // only one option
            """{"type":"choice","label":"x","default":"a","choices":[{"value":"a","label":"A"},{"value":"a","label":"B"}]}""",
            """{"type":"choice","label":"x","default":"c","choices":[{"value":"a","label":"A"},{"value":"b","label":"B"}]}""",
            """{"type":"choice","label":"x","default":"a","source":"planets"}""",
            """{"type":"text","label":"x","default":"","maxLength":0}""",
            """{"type":"text","label":"","default":""}""",
        )
        for (json in bad) assertThrows(json, IllegalArgumentException::class.java) { parse("k", json) }
    }

    @Test
    fun keysFollowTheirScope() {
        val ok = """{"type":"bool","label":"x","default":true}"""
        parse("good_key", ok)
        assertThrows(IllegalArgumentException::class.java) { parse("Bad", ok) }
        assertThrows(IllegalArgumentException::class.java) { parse("theme.mode", ok) }          // dots are for the system scope
        parse("theme.mode", ok, system = true)
        assertThrows(IllegalArgumentException::class.java) { parse("plain", ok, system = true) }
    }

    @Test
    fun dynamicSourcesAreNormalisedByTheOs() {
        val aliases = object : OptionSource {
            override fun normalize(source: String, value: String) = if (value == "Asia/Calcutta") "Asia/Kolkata" else value.takeIf { it == "Asia/Kolkata" }
        }
        val field = SettingField.parse("zone", JSONObject("""{"type":"choice","label":"z","default":"Asia/Kolkata","source":"timezone","allowEmpty":true}"""), false, aliases)
        assertEquals("Asia/Kolkata", field.validate("Asia/Calcutta", aliases))
        assertEquals("", field.validate("", aliases))
        assertTrue(assertThrows(SettingError::class.java) { field.validate("Mars/Base", aliases) }.code == "option")
    }

    @Test
    fun roundTripsThroughJson() {
        val json = """{"type":"number","label":"Lat","default":22,"min":-90,"max":90,"step":0.5,"unit":"°"}"""
        val field = parse("lat", json)
        val again = SettingField.parse("lat", field.toJson(), false)
        assertEquals(field.default, again.default)
        assertEquals(field.unit, again.unit)
        assertEquals(field.step, again.step)
    }

    @Test
    fun hiddenFieldsAreStillValidatedAndSurviveRoundTrips() {
        val field = parse("tune", """{"type":"number","label":"t","default":50,"min":10,"max":200,"hidden":true}""")
        assertTrue(field.hidden)
        assertEquals(60L, field.validate(60.0))
        assertThrows(SettingError::class.java) { field.validate(500.0) }
        assertTrue(SettingField.parse("tune", field.toJson(), false).hidden)
        assertTrue(!parse("shown", """{"type":"bool","label":"s","default":true}""").hidden)
        assertThrows(IllegalArgumentException::class.java) { parse("bad", """{"type":"bool","label":"x","default":true,"hidden":"yes"}""") }
    }
}
