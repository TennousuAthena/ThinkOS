package moe.kiwi.thinkos.media

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class MediaPolicyTest {
    @Test fun budgetsStayInsideWhatTheDeviceCanHold() {
        MediaPolicy()
        MediaPolicy(bufferMillis = 5_000, bufferBytes = 4 * 1024 * 1024)
        MediaPolicy(bufferMillis = 120_000, bufferBytes = 96 * 1024 * 1024)
        for ((millis, bytes) in listOf(4_999 to 8 * 1024 * 1024, 120_001 to 8 * 1024 * 1024, 30_000 to 1024, 30_000 to 97 * 1024 * 1024)) {
            assertThrows(IllegalArgumentException::class.java) { MediaPolicy(bufferMillis = millis, bufferBytes = bytes) }
        }
        assertFalse("plain HTTP from the internet is off unless the user turns it on", MediaPolicy().publicCleartext)
    }

    @Test fun rtspSocketsAreOnlyOpenedToPrivateAddressesUnlessThePolicyAllowsMore() {
        val closed = PrivateSocketFactory { false }
        val publicAddress = InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))
        assertThrows(IOException::class.java) { closed.createSocket(publicAddress, 554) }
        assertThrows(IOException::class.java) { closed.createSocket("8.8.8.8", 554) }
        // Mixed answers are refused as a whole: one public address is enough to refuse the host.
        val mixed = arrayOf(InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, 5)), publicAddress)
        assertThrows(IOException::class.java) { closed.createSocket(mixed[1], 554, mixed[0], 0) }
    }
}
