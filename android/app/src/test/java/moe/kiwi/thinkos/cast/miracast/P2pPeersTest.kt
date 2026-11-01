package moe.kiwi.thinkos.cast.miracast

import org.junit.Assert.*
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class P2pPeersTest {
    private val arp = """IP address       HW type     Flags       HW address            Mask     Device
192.168.49.2     0x1         0x2         aa:bb:cc:dd:ee:02     *        p2p0
192.168.49.9     0x1         0x0         00:00:00:00:00:00     *        p2p0
192.168.3.1      0x1         0x2         aa:bb:cc:dd:ee:03     *        wlan0
192.168.49.7     0x1         0x2         aa:bb:cc:dd:ee:07     *        p2p0
"""

    private fun link(ip: String, prefix: Int = 24) = P2pLink("p2p0", InetAddress.getByName(ip) as Inet4Address, prefix)

    @Test fun theArpTableIsRead() {
        val entries = P2pPeers.parseArp(arp)
        assertEquals(4, entries.size); assertEquals(0x2, entries[0].flags); assertEquals("p2p0", entries[0].device)
        assertTrue(P2pPeers.parseArp("").isEmpty()); assertTrue(P2pPeers.parseArp("garbage\nmore garbage here\n").isEmpty())
    }

    @Test fun onlyCompleteEntriesOnTheGroupInterfaceAreLikely() {
        assertEquals(listOf("192.168.49.2", "192.168.49.7"), P2pPeers.likely(link("192.168.49.1"), P2pPeers.parseArp(arp)))
    }

    @Test fun aClientAlsoTriesTheGroupOwner() {
        assertEquals(listOf("192.168.49.2", "192.168.49.7", "192.168.49.1"), P2pPeers.likely(link("192.168.49.5"), P2pPeers.parseArp(arp)))
        assertEquals(listOf("192.168.49.1"), P2pPeers.likely(link("192.168.49.5"), emptyList()))
    }

    @Test fun theGroupOwnerNeverTriesItself() {
        assertEquals(emptyList<String>(), P2pPeers.likely(link("192.168.49.1"), emptyList()))
        assertFalse("192.168.49.1" in P2pPeers.everyoneElse(link("192.168.49.1"), emptySet()))
    }

    @Test fun theScanSkipsWhatWasTriedAndStaysInsideTheSubnet() {
        val rest = P2pPeers.everyoneElse(link("192.168.49.1"), setOf("192.168.49.2"))
        assertEquals(252, rest.size); assertFalse("192.168.49.2" in rest); assertTrue(rest.all { it.startsWith("192.168.49.") })
        assertTrue(P2pPeers.everyoneElse(link("10.0.0.1", 16), emptySet()).isEmpty())
    }

    @Test fun theVideoFormatsStringHasTheThirteenFieldsTheStandardDefines() {
        val fields = WfdSink.Video().formats.split(' ')
        assertEquals(13, fields.size)
        assertEquals("30", fields[0]); assertEquals("00000061", fields[4])
        assertEquals(0x61L, 1L or (1L shl 5) or (1L shl 6))               // 640x480p60, 1280x720p30, 1280x720p60
    }
}
