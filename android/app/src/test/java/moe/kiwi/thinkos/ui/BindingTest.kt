package moe.kiwi.thinkos.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class BindingTest {
    @org.junit.Test
    fun listRowBindingsSupportExplicitItemAndGlobalSettingsWithoutCopyingState() {
        val state = org.json.JSONObject("""{"title":"Global","config":{"unit":"°C"}}""")
        val item = org.json.JSONObject("""{"title":"Tuesday","temperature":22}""")
        val template = Binding.compile("{{.title}} / {{title}}: {{.temperature}} {{config.unit}}")
        org.junit.Assert.assertEquals("Tuesday / Tuesday: 22 °C", template.render(state, "", { null }, item))
        org.junit.Assert.assertEquals("", Binding.compile("{{.title}}").render(state, "", { null }))
    }
    @Test fun nestedValuesFormatWithoutExecutingExpressions() {
        val state=JSONObject().put("now",JSONObject().put("temperature",21.6).put("title","hello"))
        assertEquals("22° · HELLO",Binding.render("{{now.temperature | round}}° · {{now.title | upper}}",state,"digital"))
        assertEquals("",Binding.render("{{now.temperature + 1}}",state,"digital"))
    }
    @Test fun missingValuesUseDefaultAndVariantIsRuntimeOwned() {
        assertEquals("analog / —",Binding.render("{{variant}} / {{missing | default \"—\"}}",JSONObject().put("variant","forged"),"analog"))
    }
    @Test fun nullAndNonFiniteValuesDoNotCrashFormatting() {
        val state=JSONObject().put("empty",JSONObject.NULL).put("value","NaN")
        assertEquals("none",Binding.render("{{empty | default \"none\"}}",state,"digital"))
        assertEquals("",Binding.render("{{value | round}}",state,"digital"))
    }
}
