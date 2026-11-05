package moe.kiwi.thinkos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import moe.kiwi.thinkos.plugin.PluginSchema

class GlyphsTest {
    @Test fun everyIconFromIconfontIsAKnownIconName() {
        // A plugin's `icon.name` is only accepted if the OS vocabulary lists it.
        val missing = Glyphs.names - IconView.names
        assertTrue("not in the icon vocabulary: $missing", missing.isEmpty())
    }

    @Test fun theNamesAreTheDocumentedOnes() {
        val panel = setOf("wifi", "wifi-off", "volume", "volume-off", "brightness", "tv", "radio", "cast", "apps", "settings")
        val weather = IconView.weatherNames
        val categories = setOf("monitor", "map-pin", "house", "sliders-horizontal", "puzzle", "moon-star", "sunset", "bluetooth")
        assertEquals(panel + weather + setOf("sun", "moon", "unknown") + categories, Glyphs.names)
    }

    @Test fun formIconsUseExactlyTheRegisteredVectorVocabulary() {
        assertEquals(Glyphs.names, PluginSchema.formIconNames())
    }

    @Test fun everyWeatherIconIsAVectorSoNoneFallsBackToTheOldBitmaps() {
        val missing = IconView.weatherNames - Glyphs.names
        assertTrue("weather icons without a vector: $missing", missing.isEmpty())
    }

    @Test fun aNameIsEitherDrawnInCodeOrFromIconfontNeverBoth() {
        assertEquals(emptySet<String>(), IconView.drawnNames.intersect(Glyphs.names))
    }

    @Test fun otherNamesAreNotGlyphs() {
        assertFalse(Glyphs.has("clock"))
        assertFalse(Glyphs.has(""))
        assertFalse(Glyphs.has("WIFI"))
        assertFalse(Glyphs.has("moon-phase-w-50"))
    }
}
