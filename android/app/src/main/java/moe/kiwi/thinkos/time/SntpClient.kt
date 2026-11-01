package moe.kiwi.thinkos.time

import android.os.SystemClock
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * One SNTP exchange (RFC 4330). [offsetMillis] is server time minus system time; [rttMillis] is the network round trip.
 * [anchorWallMillis] is true time at the middle of the exchange, which was [anchorElapsedMillis] on the monotonic
 * clock: that pair is what the OS keeps time from, so the system clock's own error never enters it.
 */
data class SntpSample(val server: String, val offsetMillis: Long, val rttMillis: Long, val anchorWallMillis: Long = 0, val anchorElapsedMillis: Long = 0)

object SntpClient {
    private const val PORT = 123
    private const val EPOCH_DELTA_SECONDS = 2_208_988_800L   // 1900 → 1970

    fun query(address: InetAddress, timeoutMillis: Int): SntpSample? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMillis
            val request = ByteArray(48).also { it[0] = (0 shl 6 or 3 shl 3 or 3).toByte() }   // LI 0, version 3, client
            val sentWall = System.currentTimeMillis()
            val sentTick = SystemClock.elapsedRealtime()
            writeTimestamp(request, 40, sentWall)
            socket.send(DatagramPacket(request, request.size, address, PORT))
            val reply = ByteArray(48)
            socket.receive(DatagramPacket(reply, reply.size))
            val rtt = SystemClock.elapsedRealtime() - sentTick
            parse(address.hostAddress.orEmpty(), request, reply, sentWall, sentWall + rtt)?.let { anchored(it, sentWall, sentTick, rtt) }
        }
    }.getOrNull()

    /**
     * Pins [sample] to the monotonic clock: the exchange was sent at system time [sentWall] / monotonic [sentTick] and took
     * [roundTrip] by the monotonic clock. The server's time applies to the middle of it.
     */
    fun anchored(sample: SntpSample, sentWall: Long, sentTick: Long, roundTrip: Long) = sample.copy(
        anchorWallMillis = sentWall + roundTrip / 2 + sample.offsetMillis,
        anchorElapsedMillis = sentTick + roundTrip / 2,
    )

    /** [t0]/[t3]: system time when the request left and the reply arrived. Null for anything that is not a sane answer to [request]. */
    fun parse(server: String, request: ByteArray, reply: ByteArray, t0: Long, t3: Long): SntpSample? {
        if (reply.size < 48) return null
        val mode = reply[0].toInt() and 7
        val leap = (reply[0].toInt() shr 6) and 3
        val stratum = reply[1].toInt() and 0xFF
        if (mode != 4 || leap == 3 || stratum !in 1..15) return null
        for (i in 0 until 8) if (reply[24 + i] != request[40 + i]) return null     // not an answer to our request
        val t1 = readTimestamp(reply, 32) ?: return null
        val t2 = readTimestamp(reply, 40) ?: return null
        val offset = ((t1 - t0) + (t2 - t3)) / 2
        val rtt = (t3 - t0) - (t2 - t1)
        return SntpSample(server, offset, rtt.coerceAtLeast(0))
    }

    private fun writeTimestamp(buffer: ByteArray, at: Int, millis: Long) {
        val seconds = millis / 1000 + EPOCH_DELTA_SECONDS
        val fraction = (millis % 1000) * 0x100000000L / 1000
        for (i in 0 until 4) { buffer[at + i] = (seconds shr (24 - 8 * i)).toByte(); buffer[at + 4 + i] = (fraction shr (24 - 8 * i)).toByte() }
    }

    private fun readTimestamp(buffer: ByteArray, at: Int): Long? {
        var seconds = 0L
        var fraction = 0L
        for (i in 0 until 4) { seconds = seconds shl 8 or (buffer[at + i].toLong() and 0xFF); fraction = fraction shl 8 or (buffer[at + 4 + i].toLong() and 0xFF) }
        if (seconds == 0L && fraction == 0L) return null
        // Era 0 ends in 2036; seconds with the top bit clear are era 1.
        val unix = if (seconds and 0x80000000L != 0L) seconds - EPOCH_DELTA_SECONDS else seconds + 0x100000000L - EPOCH_DELTA_SECONDS
        return unix * 1000 + fraction * 1000 / 0x100000000L
    }
}
