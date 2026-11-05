package moe.kiwi.thinkos.device

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerProtocolTest {
    @Test fun onlyTheThreeFixedLinesCanBeSent() {
        assertArrayEquals("POWEROFF\n".toByteArray(), PowerProtocol.request("POWEROFF"))
        assertArrayEquals("HELLO\n".toByteArray(), PowerProtocol.request("HELLO"))
        assertArrayEquals("STATUS\n".toByteArray(), PowerProtocol.request("STATUS"))
        for (line in listOf("", "poweroff", "POWEROFF now", "POWEROFF\n", "REBOOT", "HELLO\nPOWEROFF", "SET_TIMEZONE UTC")) {
            assertThrows(line, IllegalArgumentException::class.java) { PowerProtocol.request(line) }
        }
    }

    @Test fun helloMeansTheGuardAnsweredOurFixedGreeting() {
        assertTrue(PowerProtocol.hello("OK thinkos-power 2"))
        assertFalse(PowerProtocol.hello(null))
        assertFalse(PowerProtocol.hello(""))
        assertFalse(PowerProtocol.hello("OK"))
        assertFalse(PowerProtocol.hello("ERR denied"))
        assertFalse(PowerProtocol.hello("OK thinkos-updater 1"))
        assertFalse(PowerProtocol.hello("OK thinkos-power " + "9".repeat(300)))
    }

    @Test fun theAddressAndBoundsAreTheOnesTheDaemonUses() {
        assertEquals("thinkos-power", PowerProtocol.ADDRESS)
        assertEquals(2000, PowerProtocol.TIMEOUT_MS)
    }
}
