package moe.kiwi.thinkos.cast.miracast

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.TreeMap

/**
 * Puts RTP packets back in order. UDP over a Wi-Fi Direct link reorders and loses a little; a mirror cannot wait for what is
 * missing, so a gap is waited for only a few milliseconds and then given up on (and counted, so the caller can ask the Source for a
 * fresh IDR frame).
 *
 * Pure logic with an explicit clock, so it is tested without sockets.
 */
internal class RtpReorder(private val maxHeld: Int = 32, private val maxWaitMillis: Long = 25) {
    private var next = -1L                       // extended sequence number expected next
    private val held = TreeMap<Long, ByteArray>()
    private var oldestHeldAt = 0L

    var lost = 0L
        private set
    var late = 0L
        private set

    /** Hands [payload] in; whatever is now deliverable in order goes to [out]. */
    fun push(sequence: Int, payload: ByteArray, nowMillis: Long, out: (ByteArray) -> Unit) {
        if (next < 0) next = sequence.toLong()
        val ahead = ((sequence - (next and 0xffff).toInt() + 0x8000) and 0xffff) - 0x8000       // signed distance on the 16-bit circle
        when {
            ahead == 0 -> { out(payload); next++; drain(nowMillis, out) }
            ahead < -MAX_JUMP -> { held.clear(); next = sequence.toLong(); out(payload); next++ }        // the sender restarted at some other number
            ahead < 0 -> late++                                                                        // a duplicate, or too late to matter
            ahead > MAX_JUMP -> { lost += ahead; held.clear(); next += ahead; out(payload); next++ }      // we were cut off for a long time: resynchronise
            else -> {
                if (held.isEmpty()) oldestHeldAt = nowMillis
                held[next + ahead] = payload
                if (held.size > maxHeld) skipGap(nowMillis, out)
            }
        }
    }

    /** Call regularly: gives up on a gap that has been open for too long. */
    fun tick(nowMillis: Long, out: (ByteArray) -> Unit) {
        if (held.isNotEmpty() && nowMillis - oldestHeldAt >= maxWaitMillis) skipGap(nowMillis, out)
    }

    private fun skipGap(nowMillis: Long, out: (ByteArray) -> Unit) {
        val first = held.firstKey()
        lost += first - next
        next = first
        drain(nowMillis, out)
    }

    private fun drain(nowMillis: Long, out: (ByteArray) -> Unit) {
        while (true) {
            val payload = held.remove(next) ?: break
            out(payload)
            next++
        }
        if (held.isNotEmpty()) oldestHeldAt = nowMillis
    }

    private companion object { const val MAX_JUMP = 1024 }
}

/** What is inside one RTP packet of a Wi-Fi Display stream. */
internal object RtpPacket {
    class Parsed(val sequence: Int, val payload: ByteArray)

    private const val TS_PACKET = 188

    /** Null for anything that is not an RTP packet carrying whole MPEG-TS packets (payload type 33). */
    fun parse(data: ByteArray, length: Int): Parsed? {
        if (length < 12 || (data[0].toInt() shr 6) and 3 != 2) return null
        val csrc = data[0].toInt() and 0x0f
        var offset = 12 + csrc * 4
        if ((data[0].toInt() shr 4) and 1 == 1) {
            if (length < offset + 4) return null
            offset += 4 + (((data[offset + 2].toInt() and 0xff) shl 8) or (data[offset + 3].toInt() and 0xff)) * 4
        }
        var end = length
        if ((data[0].toInt() shr 5) and 1 == 1 && end > offset) end -= data[length - 1].toInt() and 0xff      // padding
        if (data[1].toInt() and 0x7f != 33) return null
        val size = end - offset
        if (size < TS_PACKET || size % TS_PACKET != 0) return null
        for (i in offset until end step TS_PACKET) if (data[i] != 0x47.toByte()) return null
        return Parsed(((data[2].toInt() and 0xff) shl 8) or (data[3].toInt() and 0xff), data.copyOfRange(offset, end))
    }
}

/**
 * Receives the Source's RTP stream on a UDP port and delivers its MPEG-TS payload in order. Only packets from the Source's
 * own address are accepted: the port is open to the whole Wi-Fi Direct group.
 */
internal class RtpReceiver(
    private val port: Int,
    private val source: InetAddress,
    private val bind: InetAddress?,
    private val onPayload: (ByteArray) -> Unit,
    /** Packets went missing: the picture will be damaged until the next IDR frame. */
    private val onLoss: () -> Unit,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    @Volatile private var running = false
    private var socket: DatagramSocket? = null
    private var thread: Thread? = null
    private val reorder = RtpReorder()

    @Volatile var packets = 0L
        private set
    @Volatile var bytes = 0L
        private set
    @Volatile var rejected = 0L
        private set
    val lost: Long get() = reorder.lost

    /** The port actually bound, which differs from the requested one only when that was 0. */
    @Volatile var boundPort = 0
        private set

    @Throws(IOException::class)
    fun start() {
        check(!running) { "already running" }
        val udp = DatagramSocket(null)
        udp.reuseAddress = true
        // Seven TS packets per datagram at several Mbit/s: a default buffer of tens of KB overflows on a scheduling hiccup.
        runCatching { udp.receiveBufferSize = 1024 * 1024 }
        udp.soTimeout = POLL_MILLIS
        udp.bind(java.net.InetSocketAddress(bind, port))
        socket = udp
        boundPort = udp.localPort
        running = true
        thread = Thread({ loop(udp) }, "ThinkOS-Rtp").apply { isDaemon = true; priority = Thread.NORM_PRIORITY + 1; start() }
    }

    private fun loop(udp: DatagramSocket) {
        val buffer = ByteArray(2048)
        val packet = DatagramPacket(buffer, buffer.size)
        var reportedLoss = 0L
        val deliver: (ByteArray) -> Unit = { bytes += it.size; onPayload(it) }
        while (running) {
            try {
                packet.length = buffer.size
                udp.receive(packet)
                if (packet.address != source) { rejected++; continue }
                val parsed = RtpPacket.parse(buffer, packet.length)
                if (parsed == null) { rejected++; continue }
                packets++
                reorder.push(parsed.sequence, parsed.payload, clock(), deliver)
            } catch (error: SocketTimeoutException) {
                reorder.tick(clock(), deliver)
            } catch (error: IOException) {
                if (running) running = false
            }
            if (reorder.lost != reportedLoss) { reportedLoss = reorder.lost; onLoss() }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.let { if (it !== Thread.currentThread()) it.join(500) }
        socket = null; thread = null
    }

    private companion object { const val POLL_MILLIS = 10 }
}
