package moe.kiwi.thinkos.ui

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageBackdropTest {
    @Test fun contentPagesCoverTheAmbientFieldWhileClockAndHomeKeepIt() {
        assertTrue(PageBackdrop.quiet(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"list","items":"rows"}}}""")))
        assertTrue(PageBackdrop.quiet(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"grid","items":"rows"}}}""")))
        assertTrue(PageBackdrop.quiet(JSONObject("""{"layout":"form","slots":{"body":{"type":"form","path":"fields"}}}""")))
        assertTrue(PageBackdrop.quiet(JSONObject("""{"slots":{"hero":{"children":[{"type":"tabs"}]}}}""")))
        assertFalse(PageBackdrop.quiet(JSONObject("""{"layout":"focus","slots":{"hero":{"type":"clock"}}}""")))
        assertFalse(PageBackdrop.quiet(JSONObject("""{"layout":"stacks","slots":{"left":[{"widget":"clock.now"}],"right":[{"widget":"weather.now"}]}}""")))
    }
}
