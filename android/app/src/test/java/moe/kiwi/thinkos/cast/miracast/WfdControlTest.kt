package moe.kiwi.thinkos.cast.miracast

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class WfdControlTest {
    private class Fake(var reply: String = "OK") : WfdTransport {
        val sent = ArrayList<String>()
        var fail: Exception? = null
        override fun exchange(line: String): String { sent += line; fail?.let { throw it }; return reply }
    }

    @Test fun helloAndOffAreOneWordsToTheModule() {
        val t = Fake("OK thinkos-wfdd 1"); val c = WfdControl(t)
        assertEquals(WfdReply.Ok("thinkos-wfdd 1"), c.hello()); assertEquals(WfdReply.Ok("thinkos-wfdd 1"), c.sinkOff())
        assertEquals(listOf("HELLO", "SINK_OFF"), t.sent)
    }

    @Test fun sinkOnCarriesThePortAndACleanedName() {
        val t = Fake(); val c = WfdControl(t)
        assertTrue(c.sinkOn(7236, "Living room").ok)
        assertTrue(c.sinkOn(7236, "  evil\"name`\$x\n ").ok)
        assertEquals(listOf("SINK_ON 7236 Living room", "SINK_ON 7236 evil-name--x-"), t.sent)
        assertThrows(IllegalArgumentException::class.java) { c.sinkOn(80, "x") }
    }

    @Test fun errorsAndAMissingModuleAreTold() {
        val t = Fake("ERR wpa-down"); val c = WfdControl(t)
        assertEquals(WfdReply.Failed("wpa-down"), c.sinkOn(7236, "x"))
        t.reply = "what"; assertEquals(WfdReply.Failed("garbled"), c.hello())
        t.fail = IOException("connection refused"); assertEquals(WfdReply.Unavailable, c.hello())
        t.fail = SecurityException("denied"); assertEquals(WfdReply.Unavailable, c.sinkOn(7236, "x"))
        assertNull(c.status())
    }

    @Test fun statusIsParsed() {
        val c = WfdControl(Fake("OK sink=on port=7236 wpa=up wfd=000600111c440032 name=Living room"))
        assertEquals(WfdStatus(true, 7236, true, "000600111c440032", "Living room"), c.status())
        val off = WfdControl(Fake("OK sink=off port=0 wpa=down wfd=- name=")).status()!!
        assertFalse(off.sinkOn); assertFalse(off.wpaUp); assertEquals("", off.name)
    }

    @Test fun theNameFitsThirtyTwoBytesAndNeverBreaksACharacter() {
        assertEquals("ThinkOS", WfdControl.p2pName(""))
        assertEquals("ThinkOS", WfdControl.p2pName("   "))
        assertEquals("a-b", WfdControl.p2pName("a\u0001b"))
        val long = WfdControl.p2pName("客厅的大屏幕设备名字很长很长很长很长很长")
        assertTrue(long.toByteArray().size <= 32); assertTrue(long.startsWith("客厅的"))
        assertEquals(10, WfdControl.p2pName("客厅的大屏幕设备名字很长很长很长很长很长").length)     // ten three-byte characters = 30 bytes
        assertEquals("x".repeat(32), WfdControl.p2pName("x".repeat(50)))
    }
}
