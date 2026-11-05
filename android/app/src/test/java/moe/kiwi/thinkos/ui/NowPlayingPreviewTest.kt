package moe.kiwi.thinkos.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Not a check: a way to look at the music player's arrangement without a device. With THINKOS_LAYOUT_DUMP set to a file path
 * it writes where every part goes for the page sizes the device uses (dp, density 1), and `tools/layout_preview.py` draws it:
 *
 *     THINKOS_LAYOUT_DUMP=/tmp/layouts.json ./gradlew --offline testDebugUnitTest --tests '*NowPlayingPreviewTest'
 */
class NowPlayingPreviewTest {
    private class Preset(val name: String, val style: String, val variant: String, val w: Int, val h: Int, val rtl: Boolean = false,
        val active: Boolean = true, val notice: Boolean = false)

    private val presets = listOf(
        Preset("Now playing  landscape", "full", "cover", 748, 452),
        Preset("Now playing  landscape  error notice", "full", "cover", 748, 452, notice = true),
        Preset("Now playing  portrait", "full", "cover", 452, 748),
        Preset("Now playing  landscape  right to left", "full", "cover", 748, 452, rtl = true),
        Preset("Queue  landscape", "full", "queue", 748, 452),
        Preset("Queue  portrait", "full", "queue", 452, 748),
        Preset("Nothing playing  landscape", "full", "cover", 748, 452, active = false),
        Preset("Home card  tall (landscape page)", "card", "cover", 318, 404),
        Preset("Home card  wide (portrait page)", "card", "cover", 404, 318),
        Preset("Home card  nothing playing", "card", "cover", 318, 404, active = false),
        Preset("Library footer  playing", "bar", "cover", 748, 56),
        Preset("Library footer  idle", "bar", "cover", 748, 56, active = false),
    )

    @Test fun dumpTheLayouts() {
        val target = System.getenv("THINKOS_LAYOUT_DUMP")
        assumeTrue("set THINKOS_LAYOUT_DUMP to write the layouts", !target.isNullOrBlank())
        val out = JSONArray()
        for (p in presets) {
            val layout = NowPlayingGeometry.layout(p.style, p.variant, p.w, p.h, 1f, p.rtl, p.notice, p.active)
            val parts = JSONObject()
            fun put(name: String, box: RenderGeometry.Box?) {
                if (box != null) parts.put(name, JSONArray(listOf(box.left, box.top, box.right, box.bottom)))
            }
            put("cover", layout.cover); put("title", layout.title); put("artist", layout.artist); put("format", layout.format)
            put("notice", layout.notice); put("idle", layout.idle); put("seek", layout.seek); put("line", layout.line)
            put("controls", layout.controls); put("volume", layout.volume); put("queue", layout.queue)
            val buttons = JSONArray()
            layout.controls?.let { NowPlayingGeometry.buttons(layout.buttons, it, 1f).forEach { b -> buttons.put(JSONArray(listOf(b.left, b.top, b.right, b.bottom))) } }
            out.put(JSONObject().put("name", p.name).put("w", p.w).put("h", p.h).put("rtl", p.rtl).put("parts", parts).put("buttons", buttons)
                .put("buttonSet", layout.buttons.name).put("coverFill", String.format("#%06x", CoverTone.cover(CoverTone.hue("Song 2|Artist 2")) and 0xffffff)))
        }
        File(target!!).writeText(out.toString(2))
    }
}
