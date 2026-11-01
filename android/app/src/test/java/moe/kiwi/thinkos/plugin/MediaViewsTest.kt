package moe.kiwi.thinkos.plugin

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaViewsTest {
    @Test fun mediaComponentsAreFoundAnywhereInAView() {
        assertTrue(MediaViews.used(JSONObject("""{"layout":"stage","slots":{"stage":{"type":"video"}}}""")))
        assertTrue(MediaViews.used(JSONObject("""{"layout":"focus","slots":{"hero":{"children":[{"type":"text","value":"x"},{"type":"seekbar"}]}}}""")))
        assertTrue(MediaViews.used(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"controls","mode":"seek"}}}""")))
        assertFalse(MediaViews.used(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"text","value":"video"}}}""")))
        assertFalse(MediaViews.used(JSONObject("""{"template":"tile","faces":[{"label":"controls","value":"{{video}}"}]}""")))
    }
}
