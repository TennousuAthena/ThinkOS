package moe.kiwi.thinkos.cast.dlna

import java.io.IOException
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit

/** The text of SSDP: who answers which search, and the exact messages. No sockets here, so tests can read them. */
internal object Ssdp {
    const val GROUP = "239.255.255.250"
    const val PORT = 1900
    const val MAX_AGE = 1800
    const val SERVER = "Linux/3.18 UPnP/1.0 ThinkOS/1"

    class Search(val target: String, val maxWaitSeconds: Int)

    /** Null unless [packet] is an `M-SEARCH * HTTP/1.1` with `MAN: "ssdp:discover"`. */
    fun parseSearch(packet: String): Search? {
        val lines = packet.split("\r\n", "\n")
        if (lines.firstOrNull()?.trim()?.uppercase(Locale.ROOT)?.startsWith("M-SEARCH * HTTP/1.") != true) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().uppercase(Locale.ROOT) to line.substring(colon + 1).trim()
        }.toMap()
        if (headers["MAN"]?.trim('"') != "ssdp:discover") return null
        val target = headers["ST"]?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: return null
        return Search(target, (headers["MX"]?.toIntOrNull() ?: 1).coerceIn(1, 5))
    }

    /** Every (NT/ST, USN) pair the device is known by, root device first. */
    fun targets(uuid: String): List<Pair<String, String>> {
        val device = "uuid:$uuid"
        return listOf("upnp:rootdevice" to "$device::upnp:rootdevice", device to device,
            DeviceDescription.DEVICE_TYPE to "$device::${DeviceDescription.DEVICE_TYPE}") +
            Services.ALL.map { it.type to "$device::${it.type}" }
    }

    fun answers(uuid: String, search: String): List<Pair<String, String>> =
        if (search == "ssdp:all") targets(uuid) else targets(uuid).filter { it.first == search }.map { it.first to it.second }

    fun response(location: String, target: String, usn: String) = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=$MAX_AGE\r\nEXT:\r\n" +
        "LOCATION: $location\r\nSERVER: $SERVER\r\nST: $target\r\nUSN: $usn\r\n\r\n"

    fun notify(location: String, target: String, usn: String, alive: Boolean) =
        "NOTIFY * HTTP/1.1\r\nHOST: $GROUP:$PORT\r\n" + (if (alive) "CACHE-CONTROL: max-age=$MAX_AGE\r\nLOCATION: $location\r\n" else "") +
            "NT: $target\r\nNTS: ${if (alive) "ssdp:alive" else "ssdp:byebye"}\r\nSERVER: $SERVER\r\nUSN: $usn\r\n\r\n"
}

/**
 * Listens on the SSDP group and answers searches for this renderer, and announces it. Only peers [accept] lets in get an
 * answer, and each peer gets a handful of answers per interval: a search with a forged sender must not turn this device
 * into an amplifier.
 */
internal class SsdpService(
    private val uuid: String,
    /** The description URL, or null while there is no network. */
    private val location: () -> String?,
    private val accept: (InetAddress) -> Boolean,
    private val log: (String) -> Unit = {},
) {
    private var socket: MulticastSocket? = null
    private var worker: ScheduledExecutorService? = null
    @Volatile private var running = false
    private val seen = HashMap<InetAddress, ArrayDeque<Long>>()

    fun start(network: NetworkInterface?) {
        check(!running) { "already running" }
        val s = MulticastSocket(null)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(Ssdp.PORT))
            s.timeToLive = 4
            if (network != null) {
                s.networkInterface = network
                s.joinGroup(InetSocketAddress(InetAddress.getByName(Ssdp.GROUP), Ssdp.PORT), network)
            } else s.joinGroup(InetAddress.getByName(Ssdp.GROUP))
            s.soTimeout = 1000
        } catch (error: IOException) { runCatching { s.close() }; throw error }
        socket = s
        running = true
        val pool = Executors.newScheduledThreadPool(1) { Thread(it, "ThinkOS-Cast-Ssdp-Send").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
        worker = pool
        Thread({ receive(s, pool) }, "ThinkOS-Cast-Ssdp").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1; start() }
        // Three announcements close together (UDP is lossy), then a refresh at half the lifetime.
        for (delay in listOf(0L, 400L, 1200L)) pool.schedule({ announce(true) }, delay, TimeUnit.MILLISECONDS)
        pool.scheduleWithFixedDelay({ announce(true) }, Ssdp.MAX_AGE / 2L, Ssdp.MAX_AGE / 2L, TimeUnit.SECONDS)
    }

    fun stop() {
        if (!running) return
        announce(false)
        running = false
        worker?.shutdownNow()
        runCatching { socket?.close() }
        socket = null; worker = null
    }

    private fun announce(alive: Boolean) {
        val where = location() ?: return
        for ((target, usn) in Ssdp.targets(uuid)) send(Ssdp.notify(where, target, usn, alive), InetAddress.getByName(Ssdp.GROUP), Ssdp.PORT)
    }

    private fun send(text: String, to: InetAddress, port: Int) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        try { socket?.send(DatagramPacket(bytes, bytes.size, to, port)) } catch (error: IOException) { log("ssdp send failed: ${error.message}") }
    }

    private fun receive(s: MulticastSocket, pool: ScheduledExecutorService) {
        val buffer = ByteArray(2048)
        while (running) {
            val packet = DatagramPacket(buffer, buffer.size)
            try { s.receive(packet) } catch (timeout: java.net.SocketTimeoutException) { continue } catch (error: IOException) { if (running) log("ssdp receive failed: ${error.message}"); return }
            val peer = packet.address ?: continue
            if (!accept(peer)) continue
            val search = Ssdp.parseSearch(String(packet.data, 0, packet.length, Charsets.UTF_8)) ?: continue
            val where = location() ?: continue
            val answers = Ssdp.answers(uuid, search.target)
            if (answers.isEmpty() || !allowed(peer)) continue
            val port = packet.port
            // Answer within MX seconds, spread out, as the protocol asks.
            val window = (minOf(search.maxWaitSeconds, 2) * 1000L).coerceAtLeast(1)
            for ((target, usn) in answers) {
                pool.schedule({ send(Ssdp.response(where, target, usn), peer, port) }, ThreadLocalRandom.current().nextLong(window), TimeUnit.MILLISECONDS)
            }
        }
    }

    /** At most [MAX_SEARCHES] answered searches per peer in [WINDOW_MILLIS]. */
    private fun allowed(peer: InetAddress): Boolean {
        val now = System.nanoTime() / 1_000_000
        if (seen.size > 256) seen.clear()
        val times = seen.getOrPut(peer) { ArrayDeque() }
        while (times.isNotEmpty() && now - times.first() > WINDOW_MILLIS) times.removeFirst()
        if (times.size >= MAX_SEARCHES) return false
        times.addLast(now)
        return true
    }

    private companion object {
        const val MAX_SEARCHES = 8
        const val WINDOW_MILLIS = 10_000L
    }
}
