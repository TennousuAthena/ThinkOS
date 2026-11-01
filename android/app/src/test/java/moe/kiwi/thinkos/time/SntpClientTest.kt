package moe.kiwi.thinkos.time

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SntpClientTest {
    private val unixOf1900 = 2_208_988_800L
    private fun put(buffer: ByteArray, at: Int, millis: Long) {
        val seconds = millis / 1000 + unixOf1900
        val fraction = (millis % 1000) * 0x100000000L / 1000
        for (i in 0 until 4) { buffer[at + i] = (seconds shr (24 - 8 * i)).toByte(); buffer[at + 4 + i] = (fraction shr (24 - 8 * i)).toByte() }
    }
    private fun request(t0: Long) = ByteArray(48).also { put(it, 40, t0) }
    private fun reply(request: ByteArray, receive: Long, transmit: Long, header: Int = 0x24, stratum: Int = 2) = ByteArray(48).also {
        it[0] = header.toByte(); it[1] = stratum.toByte()
        System.arraycopy(request, 40, it, 24, 8)
        put(it, 32, receive); put(it, 40, transmit)
    }
    private val t0 = 1_793_000_000_000L

    @Test fun offsetAndRoundTripFollowRfc4330() {
        // Server is 5 s ahead; 20 ms each way, 2 ms of processing.
        val req = request(t0)
        val sample = SntpClient.parse("s", req, reply(req, t0 + 5_020, t0 + 5_022), t0, t0 + 42)!!
        assertTrue(Math.abs(sample.offsetMillis - 5_000) <= 1)
        assertTrue(Math.abs(sample.rttMillis - 40) <= 1)
    }

    @Test fun rejectsAnswersThatAreNotForThisRequest() {
        val req = request(t0)
        assertNull(SntpClient.parse("s", request(t0 + 1), reply(req, t0, t0), t0, t0 + 10))
    }

    @Test fun rejectsKissOfDeathUnsynchronisedAndWrongMode() {
        val req = request(t0)
        assertNull(SntpClient.parse("s", req, reply(req, t0, t0, stratum = 0), t0, t0 + 10))
        assertNull(SntpClient.parse("s", req, reply(req, t0, t0, header = 0xE4), t0, t0 + 10))
        assertNull(SntpClient.parse("s", req, reply(req, t0, t0, header = 0x23), t0, t0 + 10))
        assertNotNull(SntpClient.parse("s", req, reply(req, t0, t0), t0, t0 + 10))
    }

    @Test fun theAnchorIsTheMiddleOfTheExchangeOnTheServersClock() {
        // The system clock is 350 ms slow. The exchange left at system time t0 / monotonic 9_000, took 60 ms.
        val req = request(t0)
        val sample = SntpClient.parse("s", req, reply(req, t0 + 350 + 30, t0 + 350 + 31), t0, t0 + 60)!!
        val anchored = SntpClient.anchored(sample, t0, 9_000, 60)
        assertEquals(9_030L, anchored.anchorElapsedMillis)
        // At the middle of the exchange the server read t0 + 380; the system clock t0 + 30.
        assertTrue(Math.abs(anchored.anchorWallMillis - (t0 + 380)) <= 1)
    }
}
