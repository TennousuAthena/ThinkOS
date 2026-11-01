package moe.kiwi.thinkos.cast.dlna

import moe.kiwi.thinkos.cast.FakeCastPlayer
import moe.kiwi.thinkos.cast.MiniXml
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/** The device over real sockets on the loopback interface: what a controller would see. */
class UpnpDeviceTest {
    private val player = FakeCastPlayer()
    private lateinit var device: UpnpDevice
    private var port = 0
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Before fun start() {
        device = UpnpDevice("11111111-2222-3333-4444-555555555555", { "Living room" }, player, { }, { true })
        port = device.start(51000 + (Math.random() * 3000).toInt(), loopback)
    }

    @After fun stop() = device.stop()

    private class Reply(val status: Int, val headers: Map<String, String>, val body: String)

    private fun send(request: String, body: String = "", extra: List<String> = emptyList()): Reply {
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 5000
            val bytes = body.toByteArray()
            val head = request + "\r\nHost: test\r\n" + (if (bytes.isNotEmpty() || "POST" in request) "Content-Length: ${bytes.size}\r\n" else "") + extra.joinToString("") { "$it\r\n" } + "\r\n"
            socket.getOutputStream().write(head.toByteArray() + bytes)
            socket.getOutputStream().flush()
            val text = socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            val headEnd = text.indexOf("\r\n\r\n")
            val lines = text.substring(0, headEnd).split("\r\n")
            return Reply(lines[0].split(' ')[1].toInt(), lines.drop(1).associate { it.substringBefore(':').lowercase() to it.substringAfter(':').trim() }, text.substring(headEnd + 4))
        }
    }

    private fun soap(service: ServiceDef, action: String, args: String = "<InstanceID>0</InstanceID>") =
        send("POST ${service.controlPath} HTTP/1.1", """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:$action xmlns:u="${service.type}">$args</u:$action></s:Body></s:Envelope>""",
            listOf("SOAPACTION: \"${service.type}#$action\"", "Content-Type: text/xml; charset=\"utf-8\""))

    @Test fun theDescriptionNamesTheDeviceAndItsThreeServices() {
        val reply = send("GET ${DeviceDescription.PATH} HTTP/1.1")
        assertEquals(200, reply.status)
        val root = MiniXml.parse(reply.body)
        assertEquals("uuid:11111111-2222-3333-4444-555555555555", root.descendants("UDN").single().text)
        assertEquals("Living room", root.descendants("friendlyName").single().text)
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", root.descendants("deviceType").single().text)
        assertEquals(Services.ALL.map { it.type }, root.descendants("serviceType").map { it.text })
        assertEquals(Services.ALL.map { it.controlPath }, root.descendants("controlURL").map { it.text })
    }

    @Test fun everyServiceDocumentIsConsistentWithItsActionTable() {
        for (service in Services.ALL) {
            val reply = send("GET ${service.scpdPath} HTTP/1.1")
            assertEquals(200, reply.status)
            val root = MiniXml.parse(reply.body)
            val variables = root.descendants("stateVariable").map { it.child("name")!!.text }.toSet()
            assertEquals(service.actions.map { it.name }, root.descendants("action").map { it.child("name")!!.text })
            for (related in root.descendants("relatedStateVariable")) assertTrue("${service.short}: ${related.text}", related.text in variables)
        }
        val avt = MiniXml.parse(send("GET ${Services.AV_TRANSPORT.scpdPath} HTTP/1.1").body)
        assertEquals(listOf("LastChange"), avt.descendants("stateVariable").filter { it.attributes["sendEvents"] == "yes" }.map { it.child("name")!!.text })
    }

    @Test fun aControllerCanPushAndPlayOverHttp() {
        val set = soap(Services.AV_TRANSPORT, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>http://tv.test/a.mp4?x=1&amp;y=2</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        assertEquals(200, set.status)
        assertTrue(set.body.contains("SetAVTransportURIResponse"))
        assertEquals("http://tv.test/a.mp4?x=1&y=2", player.loaded!!.url)
        assertEquals(200, soap(Services.AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>").status)
        val info = soap(Services.AV_TRANSPORT, "GetTransportInfo")
        assertEquals("PLAYING", MiniXml.parse(info.body).descendants("CurrentTransportState").single().text)
        assertEquals("text/xml; charset=\"utf-8\"", info.headers["content-type"])
    }

    @Test fun aFailedActionIsAnHttp500WithAnUpnpError() {
        val reply = soap(Services.AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        assertEquals(500, reply.status)
        assertEquals("701", MiniXml.parse(reply.body).descendants("errorCode").single().text)
        assertEquals(500, send("POST ${Services.AV_TRANSPORT.controlPath} HTTP/1.1", "garbage").status)
    }

    @Test fun routesAndMethodsAreStrict() {
        assertEquals(404, send("GET /dlna/nothing.xml HTTP/1.1").status)
        assertEquals(404, send("GET /../../etc/passwd HTTP/1.1").status)
        assertEquals(405, send("PUT ${Services.AV_TRANSPORT.controlPath} HTTP/1.1").status)
        assertEquals(405, send("POST ${DeviceDescription.PATH} HTTP/1.1", "x").status)
        assertEquals(400, send("NOT-HTTP\r\n").status)
    }

    @Test fun aChunkedRequestBodyIsReadToo() {
        val xml = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetTransportInfo xmlns:u="${Services.AV_TRANSPORT.type}"><InstanceID>0</InstanceID></u:GetTransportInfo></s:Body></s:Envelope>"""
        val chunked = Integer.toHexString(xml.length) + "\r\n" + xml + "\r\n0\r\n\r\n"
        Socket(loopback, port).use { socket ->
            socket.soTimeout = 5000
            socket.getOutputStream().write(("POST ${Services.AV_TRANSPORT.controlPath} HTTP/1.1\r\nHost: t\r\nTransfer-Encoding: chunked\r\nSOAPACTION: \"${Services.AV_TRANSPORT.type}#GetTransportInfo\"\r\n\r\n$chunked").toByteArray())
            assertTrue(socket.getInputStream().readBytes().toString(Charsets.UTF_8).startsWith("HTTP/1.1 200"))
        }
    }

    @Test fun anOversizedBodyIsRefusedBeforeItIsRead() {
        assertEquals(413, send("POST ${Services.AV_TRANSPORT.controlPath} HTTP/1.1", "", listOf("Content-Length: 99999999")).status)
    }

    @Test fun subscribersGetTheStateFirstAndThenEveryChange() {
        val callback = ServerSocket(0, 5, loopback)
        val received = java.util.concurrent.LinkedBlockingQueue<String>()
        Thread {
            repeat(2) {
                callback.accept().use { s ->
                    s.soTimeout = 3000
                    val text = StringBuilder(); val input = s.getInputStream()
                    var length = 0
                    while (!text.endsWith("\r\n\r\n")) text.append(input.read().toChar())
                    Regex("(?i)content-length: (\\d+)").find(text)?.let { length = it.groupValues[1].toInt() }
                    val body = ByteArray(length); var read = 0
                    while (read < length) read += input.read(body, read, length - read)
                    s.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray())
                    received.put(text.toString() + String(body))
                }
            }
        }.apply { isDaemon = true; start() }
        val granted = send("SUBSCRIBE ${Services.AV_TRANSPORT.eventPath} HTTP/1.1", extra = listOf("CALLBACK: <http://127.0.0.1:${callback.localPort}/cb>", "NT: upnp:event", "TIMEOUT: Second-120"))
        assertEquals(200, granted.status)
        val sid = granted.headers.getValue("sid")
        assertTrue(sid.startsWith("uuid:"))
        assertEquals("Second-120", granted.headers["timeout"])
        val first = received.poll(5, TimeUnit.SECONDS)!!
        assertTrue(first, first.contains("SID: $sid") && first.contains("SEQ: 0") && first.contains("NTS: upnp:propchange"))
        assertTrue(first, first.contains("LastChange"))
        soap(Services.AV_TRANSPORT, "SetAVTransportURI", "<InstanceID>0</InstanceID><CurrentURI>http://tv.test/a.mp4</CurrentURI><CurrentURIMetaData></CurrentURIMetaData>")
        soap(Services.AV_TRANSPORT, "Play", "<InstanceID>0</InstanceID><Speed>1</Speed>")
        device.playerChanged(player.state)
        val second = received.poll(5, TimeUnit.SECONDS)!!
        assertTrue(second, second.contains("SEQ: 1") && second.contains("PLAYING"))
        assertEquals(1, device.subscribers())
        assertEquals(200, send("SUBSCRIBE ${Services.AV_TRANSPORT.eventPath} HTTP/1.1", extra = listOf("SID: $sid", "TIMEOUT: Second-300")).status)
        assertEquals(200, send("UNSUBSCRIBE ${Services.AV_TRANSPORT.eventPath} HTTP/1.1", extra = listOf("SID: $sid")).status)
        assertEquals(0, device.subscribers())
        assertEquals(412, send("UNSUBSCRIBE ${Services.AV_TRANSPORT.eventPath} HTTP/1.1", extra = listOf("SID: $sid")).status)
        callback.close()
    }

    @Test fun aSubscriptionCannotAimTheDeviceAtAnotherMachine() {
        for (callback in listOf("<http://192.168.1.77:80/cb>", "<http://evil.test/cb>", "<ftp://127.0.0.1/cb>", "http://127.0.0.1/cb", "")) {
            val reply = send("SUBSCRIBE ${Services.AV_TRANSPORT.eventPath} HTTP/1.1", extra = listOf("CALLBACK: $callback", "NT: upnp:event"))
            assertEquals(callback, 412, reply.status)
        }
        assertEquals(0, device.subscribers())
        assertEquals(412, send("SUBSCRIBE ${Services.AV_TRANSPORT.eventPath} HTTP/1.1", extra = listOf("CALLBACK: <http://127.0.0.1:9/cb>")).status)   // no NT
    }

    @Test fun peersThatAreNotLetInGetNoAnswer() {
        val closed = UpnpDevice("u", { "x" }, player, { }, { false })
        val other = closed.start(54000 + (Math.random() * 1000).toInt(), loopback)
        Socket(loopback, other).use { socket ->
            socket.soTimeout = 3000
            socket.getOutputStream().write("GET ${DeviceDescription.PATH} HTTP/1.1\r\n\r\n".toByteArray())
            val answer = try { socket.getInputStream().readBytes() } catch (error: java.net.SocketException) { ByteArray(0) }
            assertEquals(0, answer.size)
        }
        closed.stop()
    }
}
