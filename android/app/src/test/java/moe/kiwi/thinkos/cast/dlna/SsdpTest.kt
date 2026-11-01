package moe.kiwi.thinkos.cast.dlna

import org.junit.Assert.*
import org.junit.Test

class SsdpTest {
    private fun search(target: String, extra: String = "MX: 2\r\n") =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n${extra}ST: $target\r\n\r\n"

    @Test fun aSearchNamesWhatItIsLookingFor() {
        val found = Ssdp.parseSearch(search("urn:schemas-upnp-org:device:MediaRenderer:1"))!!
        assertEquals("urn:schemas-upnp-org:device:MediaRenderer:1", found.target)
        assertEquals(2, found.maxWaitSeconds)
        assertEquals(1, Ssdp.parseSearch(search("ssdp:all", extra = ""))!!.maxWaitSeconds)
        assertEquals(5, Ssdp.parseSearch(search("ssdp:all", "MX: 120\r\n"))!!.maxWaitSeconds)
        assertEquals("ssdp:all", Ssdp.parseSearch(search("ssdp:all").replace("\r\n", "\n"))!!.target)
    }

    @Test fun whatIsNotASearchIsIgnored() {
        assertNull(Ssdp.parseSearch("NOTIFY * HTTP/1.1\r\nNT: upnp:rootdevice\r\nNTS: ssdp:alive\r\n\r\n"))
        assertNull(Ssdp.parseSearch("M-SEARCH * HTTP/1.1\r\nMAN: \"ssdp:other\"\r\nST: ssdp:all\r\n\r\n"))
        assertNull(Ssdp.parseSearch("M-SEARCH * HTTP/1.1\r\nMAN: \"ssdp:discover\"\r\n\r\n"))
        assertNull(Ssdp.parseSearch("GET / HTTP/1.1\r\n\r\n"))
        assertNull(Ssdp.parseSearch(""))
        assertNull(Ssdp.parseSearch(search("x".repeat(300))))
    }

    @Test fun theDeviceAnswersForItselfItsTypeAndEachService() {
        val uuid = "aaaa"
        assertEquals(6, Ssdp.answers(uuid, "ssdp:all").size)
        assertEquals(listOf("upnp:rootdevice" to "uuid:aaaa::upnp:rootdevice"), Ssdp.answers(uuid, "upnp:rootdevice"))
        assertEquals(listOf("uuid:aaaa" to "uuid:aaaa"), Ssdp.answers(uuid, "uuid:aaaa"))
        assertEquals(1, Ssdp.answers(uuid, "urn:schemas-upnp-org:device:MediaRenderer:1").size)
        assertEquals(1, Ssdp.answers(uuid, "urn:schemas-upnp-org:service:AVTransport:1").size)
        assertTrue(Ssdp.answers(uuid, "urn:schemas-upnp-org:device:MediaServer:1").isEmpty())
        assertTrue(Ssdp.answers(uuid, "uuid:other").isEmpty())
    }

    @Test fun theAnswerCarriesTheLocationAndTheTarget() {
        val text = Ssdp.response("http://192.168.3.167:49152/dlna/description.xml", "upnp:rootdevice", "uuid:aaaa::upnp:rootdevice")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n") && text.endsWith("\r\n\r\n"))
        for (line in listOf("LOCATION: http://192.168.3.167:49152/dlna/description.xml", "ST: upnp:rootdevice", "USN: uuid:aaaa::upnp:rootdevice", "CACHE-CONTROL: max-age=1800", "EXT:")) {
            assertTrue(line, text.contains("\r\n$line\r\n") || text.contains(line + "\r\n"))
        }
    }

    @Test fun announcementsComeInAliveAndByebyeForms() {
        val alive = Ssdp.notify("http://h/d.xml", "upnp:rootdevice", "uuid:a::upnp:rootdevice", alive = true)
        assertTrue(alive.startsWith("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n") && alive.contains("NTS: ssdp:alive") && alive.contains("LOCATION: http://h/d.xml"))
        val bye = Ssdp.notify("http://h/d.xml", "upnp:rootdevice", "uuid:a::upnp:rootdevice", alive = false)
        assertTrue(bye.contains("NTS: ssdp:byebye") && !bye.contains("LOCATION"))
    }
}
