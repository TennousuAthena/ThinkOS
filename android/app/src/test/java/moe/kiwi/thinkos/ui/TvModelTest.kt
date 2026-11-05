package moe.kiwi.thinkos.ui

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class TvModelTest {
    private fun channel(json: String) = TvChannel.parse(JSONObject(json))

    @Test fun aWholeChannelIsReadFieldByField() {
        val c = channel("""{"number":12,"name":"News 24","group":"News","line":2,"lines":3,"favorite":true,"notice":"Switched to source 2",
            "program":{"title":"Evening news","start":1000000,"end":4600000},"next":{"title":"Weather","start":4600000}}""")
        assertEquals(12, c.number); assertEquals("News 24", c.name); assertEquals("News", c.group)
        assertEquals(2, c.line); assertEquals(3, c.lines); assertTrue(c.favorite); assertTrue(c.hasLines)
        assertEquals("Switched to source 2", c.notice)
        assertEquals(TvChannel.Programme("Evening news", 1_000_000, 4_600_000), c.now)
        assertEquals(TvChannel.Programme("Weather", 4_600_000, 0), c.next)
    }

    @Test fun noChannelIsTheEmptyOne() {
        assertSame(TvChannel.EMPTY, TvChannel.parse(null))
        assertEquals(TvChannel.EMPTY, channel("{}"))
        assertFalse(TvChannel.EMPTY.hasLines)
    }

    @Test fun fieldsOfTheWrongKindCountAsMissing() {
        val c = channel("""{"number":"7","name":5,"group":null,"line":-3,"lines":"x","favorite":"yes","program":"now","next":{"title":"No time"}}""")
        assertEquals(TvChannel.EMPTY, c)
        // A programme needs a title and a start; an end that is not after the start means "until the next one".
        assertNull(channel("""{"program":{"title":"","start":5}}""").now)
        assertNull(channel("""{"program":{"title":"A","start":0}}""").now)
        assertEquals(0L, channel("""{"program":{"title":"A","start":50,"end":40}}""").now?.end)
    }

    @Test fun textAndNumbersAreBounded() {
        val c = channel("""{"number":99999999,"name":"${"n".repeat(500)}","notice":"${"x".repeat(900)}","lines":1}""")
        assertEquals(100_000, c.number); assertEquals(120, c.name.length); assertEquals(200, c.notice.length); assertFalse(c.hasLines)
    }

    @Test fun theProgressOfAProgrammeIsHowFarItsTimeHasGone() {
        val p = TvChannel.Programme("A", 1000, 3000)
        assertEquals(0f, TvChannel.progress(p, 1000)!!, 0f)
        assertEquals(.5f, TvChannel.progress(p, 2000)!!, 1e-6f)
        assertEquals(1f, TvChannel.progress(p, 9000)!!, 0f)                     // over, and the plugin has not caught up yet
        assertNull(TvChannel.progress(p, 999))                                     // not on air yet
        assertNull(TvChannel.progress(TvChannel.Programme("B", 1000, 0), 2000))    // no known end
    }
}
