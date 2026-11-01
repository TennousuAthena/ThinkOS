package moe.kiwi.thinkos.cast.miracast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.cast.EngineCastPlayer
import moe.kiwi.thinkos.media.MediaEngine
import moe.kiwi.thinkos.media.MirrorSource
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The Wi-Fi Direct link a phone or PC shares its screen over, as this device sees it. */
internal data class P2pLink(val interfaceName: String, val address: Inet4Address, val prefixLength: Int)

/** Which addresses on the Wi-Fi Direct subnet might hold the Source, best guess first. Pure, so it is tested without a network. */
internal object P2pPeers {
    class ArpEntry(val ip: String, val flags: Int, val device: String)

    fun parseArp(text: String): List<ArpEntry> = text.lineSequence().drop(1).mapNotNull { line ->
        val f = line.trim().split(Regex("\\s+"))
        if (f.size < 6) null else f[2].removePrefix("0x").toIntOrNull(16)?.let { ArpEntry(f[0], it, f[5]) }
    }.toList()

    /** Neighbours the kernel already knows (complete entries), then the usual group owner address. */
    fun likely(link: P2pLink, arp: List<ArpEntry>): List<String> {
        val own = link.address.hostAddress.orEmpty()
        val known = arp.filter { it.device == link.interfaceName && it.flags and 0x2 != 0 && it.ip != own }.map { it.ip }
        val owner = link.address.address.let { b -> "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}.1" }.takeIf { it != own }
        return (known + listOfNotNull(owner)).distinct()
    }

    /** The rest of a /24, for when nothing likelier answers. A Wi-Fi Direct group never has more than a few members. */
    fun everyoneElse(link: P2pLink, tried: Set<String>): List<String> {
        if (link.prefixLength < 24) return emptyList()
        val b = link.address.address
        val prefix = "${b[0].toInt() and 0xff}.${b[1].toInt() and 0xff}.${b[2].toInt() and 0xff}."
        val own = link.address.hostAddress.orEmpty()
        return (2..254).map { prefix + it }.filter { it != own && it !in tried }
    }
}

/**
 * Receives screens shared over Miracast (Wi-Fi Display) from Android phones and Windows PCs (ADR-031, docs/28-casting.md).
 *
 * Three parts cooperate:
 *  - the ThinkOS Cast system module (root daemon) makes this device discoverable as a wireless display ([WfdControl]);
 *  - Android's own Wi-Fi Direct stack does the pairing and forms the group (the system asks before it accepts a connection);
 *  - this class then finds the Source in the group, dials its RTSP port, runs the Sink side of the session ([WfdSink]),
 *    receives the RTP/MPEG-TS stream ([RtpReceiver]) and gives the bytes to the media engine as a [MirrorSource].
 *
 * Without the module nothing happens: no error for the viewer, one line in the log. The device never depends on it (ADR-015).
 */
internal class MiracastService(
    private val context: Context,
    private val control: WfdControl,
    private val media: MediaEngine,
    private val main: Handler,
    private val player: EngineCastPlayer,
    private val deviceName: () -> String,
    private val log: (String) -> Unit,
) {
    private class Active(val sink: WfdSink, val socket: Socket, val source: MirrorSource, val rtp: RtpReceiver) {
        @Volatile var shown = false
    }

    private val executor = Executors.newSingleThreadScheduledExecutor { Thread(it, "ThinkOS-Miracast").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private val probes = Executors.newCachedThreadPool { Thread(it, "ThinkOS-Miracast-Dial").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 } }
    private var enabled = false
    private var maintenance: ScheduledFuture<*>? = null
    private var receiver: BroadcastReceiver? = null
    private var available: Boolean? = null
    @Volatile private var active: Active? = null
    @Volatile private var dialing = false
    @Volatile private var generation = 0

    /** Called from any thread. */
    fun reconfigure(wanted: Boolean) { executor.execute { if (wanted) enable() else disable() } }

    fun shutdown() { executor.execute { disable() } }

    /** The engine published new numbers (main thread). A cast that someone else took the session from, or that the viewer stopped, is over. */
    fun onMediaState(state: Map<String, Any?>) {
        val current = active ?: return
        if (current.shown && state["owner"] != OWNER) executor.execute { end("replaced") }
    }

    // ---- switching on and off (executor thread) -----------------------------------------------------------------------------

    private fun enable() {
        if (!enabled) {
            enabled = true
            register()
            maintenance = executor.scheduleWithFixedDelay({ runCatching { maintain() } }, 0, MAINTAIN_SECONDS, TimeUnit.SECONDS)
        } else {
            runCatching { maintain() }            // the name may have changed
        }
        onGroupChanged()
    }

    private fun disable() {
        if (!enabled) return
        enabled = false
        maintenance?.cancel(false); maintenance = null
        unregister()
        end("disabled")
        control.sinkOff()
        available = null
        log("Miracast is off")
    }

    /** Keeps the system module's sink on and named as asked, and notices when the module appears or goes away. */
    private fun maintain() {
        if (!enabled) return
        val status = control.status()
        if (status == null) {
            if (available != false) log("Miracast: the ThinkOS Cast system module is not running (see system/README.md)")
            available = false
            return
        }
        val name = WfdControl.p2pName(deviceName())
        if (!status.sinkOn || status.name != name || !status.wpaUp || status.element.isEmpty() || status.port != WfdControl.CONTROL_PORT) {
            val reply = control.sinkOn(WfdControl.CONTROL_PORT, name)
            if (reply.ok) log("Miracast: visible as \"$name\"") else if (available != false || reply is WfdReply.Failed) log("Miracast: the system module answered $reply")
            available = reply.ok
        } else available = true
    }

    // ---- the Wi-Fi Direct group -----------------------------------------------------------------------------------------------

    private fun register() {
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
        }
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { executor.execute { runCatching { onGroupChanged() } } }
        }
        receiver = r
        context.registerReceiver(r, filter)
    }

    private fun unregister() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
    }

    private fun link(): P2pLink? = try {
        NetworkInterface.getNetworkInterfaces().toList().firstNotNullOfOrNull { nif ->
            if (!nif.isUp || !nif.name.startsWith("p2p")) null
            else nif.interfaceAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }
                ?.let { P2pLink(nif.name, it.address as Inet4Address, it.networkPrefixLength.toInt()) }
        }
    } catch (error: Exception) { null }

    /** A group came or went. While there is one and no session yet, look for the Source in it. */
    private fun onGroupChanged() {
        if (!enabled) return
        val group = link()
        if (group == null) {
            generation++
            if (active != null) end("group gone")
            return
        }
        if (active == null && !dialing) {
            dialing = true
            val mine = ++generation
            log("Miracast: a Wi-Fi Direct group is up on ${group.interfaceName} (${group.address.hostAddress}); looking for the sender")
            Thread({
                try { dialLoop(group, mine) } finally {
                    dialing = false
                    // A group that is still up (or came back while this loop was winding down) gets looked at again.
                    executor.execute { runCatching { onGroupChanged() } }
                }
            }, "ThinkOS-Miracast-Session").apply { isDaemon = true; start() }
        }
    }

    // ---- finding the Source and running the session (own thread) ----------------------------------------------------------

    private fun dialLoop(group: P2pLink, mine: Int) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DIAL_SECONDS)
        var round = 0
        val tried = HashSet<String>()
        while (enabled && generation == mine && System.nanoTime() < deadline) {
            val likely = P2pPeers.likely(group, P2pPeers.parseArp(readArp()))
            tried += likely
            var socket = probe(group, likely)
            if (socket == null && round >= 1) socket = probe(group, P2pPeers.everyoneElse(group, tried))
            if (socket != null) {
                runSession(socket, group.address)
                return
            }
            round++
            try { Thread.sleep(ROUND_PAUSE_MILLIS) } catch (error: InterruptedException) { return }
        }
        log("Miracast: no sender answered on port ${WfdControl.CONTROL_PORT}")
    }

    private fun readArp(): String = try { File("/proc/net/arp").readText() } catch (error: Exception) { "" }

    /** Connects to the first address that has the control port open. That connection *is* the session: a Source accepts exactly one. */
    private fun probe(group: P2pLink?, hosts: List<String>, port: Int = WfdControl.CONTROL_PORT): Socket? {
        if (hosts.isEmpty()) return null
        val done: CompletionService<Socket?> = ExecutorCompletionService(probes)
        var running = 0
        var found: Socket? = null
        val iterator = hosts.iterator()
        fun submit(host: String) {
            running++
            done.submit {
                val socket = Socket()
                try {
                    group?.let { socket.bind(InetSocketAddress(it.address, 0)) }
                    socket.connect(InetSocketAddress(host, port), CONNECT_MILLIS)
                    socket
                } catch (error: Exception) { runCatching { socket.close() }; null }
            }
        }
        while (iterator.hasNext() && running < PARALLEL_PROBES) submit(iterator.next())
        while (running > 0) {
            val socket = done.take().get()
            running--
            if (socket != null && found == null) found = socket else socket?.let { runCatching { it.close() } }
            if (found == null && iterator.hasNext()) submit(iterator.next())
            if (found != null && !iterator.hasNext() && running == 0) break
        }
        return found
    }

    private fun runSession(socket: Socket, local: InetAddress?) {
        val peer = socket.inetAddress
        log("Miracast: connected to the sender at ${peer.hostAddress}")
        val source = MirrorSource("miracast")
        var rtp: RtpReceiver? = null
        var current: Active? = null
        var lastIdr = 0L
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = SESSION_TIMEOUT_MILLIS
            val receiver = RtpReceiver(0, peer, local, { source.write(it) }, {
                val now = System.nanoTime() / 1_000_000
                if (now - lastIdr > IDR_INTERVAL_MILLIS) { lastIdr = now; current?.sink?.requestIdr() }
            })
            receiver.start()
            rtp = receiver
            val listener = object : WfdSink.Listener {
                override fun onSetup() {
                    // A Source that keeps a longer session timeout than ours would be dropped between its keep-alives.
                    socket.soTimeout = maxOf(SESSION_TIMEOUT_MILLIS, ((current?.sink?.sessionTimeoutSeconds ?: 0) * 2 + 10) * 1000)
                    // Get the player ready now: the Source starts sending the moment it has answered PLAY, and the first frame is an IDR.
                    val entry = current ?: return
                    try { player.loadMirror(source, "Miracast"); entry.shown = true } catch (error: Exception) {
                        log("Miracast: the player refused the stream: ${error.message}")
                        executor.execute { end("the player refused the stream") }
                    }
                }
                override fun onPlaying() { log("Miracast: playing") }
                override fun onEnded(reason: String) { log("Miracast: the session ended ($reason)") }
            }
            val sink = WfdSink(socket.getInputStream(), socket.getOutputStream(), peer.hostAddress.orEmpty(), receiver.boundPort, listener, log)
            current = Active(sink, socket, source, receiver)
            active = current
            sink.run()
        } catch (error: Exception) {
            log("Miracast: the session failed: ${error.javaClass.simpleName}")
        } finally {
            val wasShown = current?.shown == true
            active = null
            rtp?.stop()
            source.close()
            rtp?.let { log("Miracast: ${it.packets} packets received, ${it.lost} lost, ${it.rejected} refused, ${source.droppedBytes / 1024} KiB dropped for being late") }
            runCatching { socket.close() }
            if (wasShown) runCatching { player.stop(); player.releaseScreen() }
        }
    }

    /** Ends the running session from outside: tells the Source, then drops the connection, which lets [WfdSink.run] return. */
    private fun end(reason: String) {
        val current = active ?: return
        log("Miracast: ending the session ($reason)")
        current.sink.teardown()
        runCatching { current.socket.close() }
    }

    // ---- debugging --------------------------------------------------------------------------------------------------------

    /** Debug builds only: dial a Source at [host]:[port] over the ordinary network, which is how the test tools stand in for a phone. */
    fun dialForTest(host: String, port: Int) {
        if (!BuildConfig.DEBUG) return
        executor.execute {
            if (active != null || dialing) { log("Miracast: a session is already running"); return@execute }
            dialing = true
            Thread({
                try {
                    val socket = probe(null, listOf(host), port)
                    if (socket == null) log("Miracast: nothing is listening at $host:$port") else runSession(socket, null)
                } finally { dialing = false }
            }, "ThinkOS-Miracast-Session").apply { isDaemon = true; start() }
        }
    }

    companion object {
        const val OWNER = "cast:miracast"
        private const val MAINTAIN_SECONDS = 15L
        private const val DIAL_SECONDS = 90L
        private const val ROUND_PAUSE_MILLIS = 1500L
        private const val CONNECT_MILLIS = 500
        private const val PARALLEL_PROBES = 16
        private const val SESSION_TIMEOUT_MILLIS = 45_000
        private const val IDR_INTERVAL_MILLIS = 500L
    }
}
