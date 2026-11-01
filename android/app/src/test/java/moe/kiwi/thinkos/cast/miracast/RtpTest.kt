package moe.kiwi.thinkos.cast.miracast

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class RtpTest {
    private fun ts(fill: Int, packets: Int = 7) = ByteArray(188 * packets) { if (it % 188 == 0) 0x47 else fill.toByte() }

    private fun datagram(sequence: Int, payload: ByteArray, type: Int = 33, version: Int = 2, extension: Boolean = false, padding: Int = 0): ByteArray {
        val header = ByteArray(12 + if (extension) 8 else 0)
        header[0] = ((version shl 6) or (if (extension) 0x10 else 0) or (if (padding > 0) 0x20 else 0)).toByte()
        header[1] = type.toByte()
        header[2] = (sequence shr 8).toByte(); header[3] = sequence.toByte()
        if (extension) { header[14] = 0; header[15] = 1 }          // one 32-bit word of extension data
        val tail = if (padding > 0) ByteArray(padding).also { it[padding - 1] = padding.toByte() } else ByteArray(0)
        return header + payload + tail
    }

    private fun parse(bytes: ByteArray) = RtpPacket.parse(bytes, bytes.size)

    @Test fun aPlainPacketYieldsItsTransportStreamPackets() {
        val p = parse(datagram(513, ts(1)))!!
        assertEquals(513, p.sequence); assertEquals(1316, p.payload.size)
    }

    @Test fun extensionsAndPaddingAreSkipped() {
        assertEquals(1316, parse(datagram(1, ts(2), extension = true))!!.payload.size)
        assertEquals(1316, parse(datagram(1, ts(2), padding = 4))!!.payload.size)
    }

    @Test fun anythingElseIsRefused() {
        assertNull(parse(datagram(1, ts(2), type = 96)))                         // not MP2T
        assertNull(parse(datagram(1, ts(2), version = 1)))
        assertNull(parse(ByteArray(8)))
        assertNull(parse(datagram(1, ts(2, 7).copyOf(1316 - 1))))                 // not a whole number of TS packets
        assertNull(parse(datagram(1, ByteArray(188) { 5 })))                     // no sync byte
        assertNull(parse(datagram(1, ByteArray(0))))
    }

    private fun feed(r: RtpReorder, vararg seq: Int, at: Long = 0): List<Int> {
        val got = ArrayList<Int>()
        for (s in seq) r.push(s, byteArrayOf(s.toByte()), at) { got += it[0].toInt() and 0xff }
        return got
    }

    @Test fun inOrderPacketsPassStraightThrough() {
        assertEquals(listOf(10, 11, 12), feed(RtpReorder(), 10, 11, 12))
    }

    @Test fun aLatePacketIsPutBackInPlace() {
        val r = RtpReorder()
        assertEquals(listOf(1, 2, 3, 4, 5), feed(r, 1, 2, 4, 5, 3))
        assertEquals(0, r.lost)
    }

    @Test fun duplicatesAreDropped() {
        val r = RtpReorder()
        assertEquals(listOf(1, 2, 3), feed(r, 1, 2, 2, 1, 3))
        assertEquals(2, r.late)
    }

    @Test fun aGapIsGivenUpOnAfterAShortWaitAndCounted() {
        val r = RtpReorder(maxWaitMillis = 25)
        val got = ArrayList<Int>()
        val out: (ByteArray) -> Unit = { got += it[0].toInt() and 0xff }
        r.push(1, byteArrayOf(1), 0, out); r.push(2, byteArrayOf(2), 0, out)
        r.push(5, byteArrayOf(5), 100, out); r.push(6, byteArrayOf(6), 101, out)       // 3 and 4 never come
        assertEquals(listOf(1, 2), got)
        r.tick(110, out); assertEquals(listOf(1, 2), got)                              // not yet
        r.tick(130, out); assertEquals(listOf(1, 2, 5, 6), got)
        assertEquals(2, r.lost)
    }

    @Test fun tooManyHeldPacketsForceTheGapToBeSkipped() {
        val r = RtpReorder(maxHeld = 4, maxWaitMillis = 10_000)
        val got = feed(r, 1, 3, 4, 5, 6, 7)
        assertEquals(listOf(1, 3, 4, 5, 6, 7), got)
        assertEquals(1, r.lost)
    }

    @Test fun sequenceNumbersWrapAround() {
        val r = RtpReorder()
        val got = ArrayList<Int>()
        for (s in listOf(65534, 65535, 1, 0, 2)) r.push(s, byteArrayOf((s and 0xff).toByte(), (s shr 8).toByte()), 0) { got += (it[1].toInt() and 0xff) * 256 + (it[0].toInt() and 0xff) }
        assertEquals(listOf(65534, 65535, 0, 1, 2), got)
        assertEquals(0, r.lost)
    }

    @Test fun aSenderThatRestartsElsewhereIsFollowed() {
        val r = RtpReorder()
        feed(r, 100, 101, 102)
        assertEquals(listOf(40000 and 0xff, 40001 and 0xff), feed(r, 40000, 40001))        // a jump of thousands: resynchronised, not stuck waiting
        assertEquals(listOf(7, 8), feed(r, 7, 8).also { })                // and a backwards jump too
    }

    @Test fun theReceiverDeliversInOrderOnlyFromTheSourceAndCountsLoss() {
        val payloads = ArrayList<ByteArray>()
        var lossCalls = 0
        val receiver = RtpReceiver(0, InetAddress.getByName("127.0.0.1"), InetAddress.getByName("127.0.0.1"), { synchronized(payloads) { payloads += it } }, { lossCalls++ })
        receiver.start()
        try {
            val send = DatagramSocket()
            fun out(seq: Int, fill: Int) { val d = datagram(seq, ts(fill)); send.send(DatagramPacket(d, d.size, InetAddress.getByName("127.0.0.1"), receiver.boundPort)) }
            out(1, 1); out(2, 2); out(4, 4); out(3, 3); out(5, 5)
            out(7, 7)                                                     // 6 is lost
            Thread.sleep(150)
            val junk = byteArrayOf(1, 2, 3); send.send(DatagramPacket(junk, junk.size, InetAddress.getByName("127.0.0.1"), receiver.boundPort))
            Thread.sleep(100)
            val fills = synchronized(payloads) { payloads.map { it[1].toInt() } }
            assertEquals(listOf(1, 2, 3, 4, 5, 7), fills)
            assertEquals(1, receiver.lost)
            assertTrue(lossCalls >= 1)
            assertEquals(1L, receiver.rejected)
            send.close()
        } finally { receiver.stop() }
    }

    @Test fun aStrangerOnTheNetworkIsIgnored() {
        val payloads = ArrayList<ByteArray>()
        // The source is declared to be some other host; the loopback sender is a stranger.
        val receiver = RtpReceiver(0, InetAddress.getByName("192.168.49.2"), InetAddress.getByName("127.0.0.1"), { payloads += it }, {})
        receiver.start()
        try {
            val send = DatagramSocket()
            val d = datagram(1, ts(1)); send.send(DatagramPacket(d, d.size, InetAddress.getByName("127.0.0.1"), receiver.boundPort))
            Thread.sleep(150)
            assertTrue(payloads.isEmpty()); assertEquals(1L, receiver.rejected)
            send.close()
        } finally { receiver.stop() }
    }
}
