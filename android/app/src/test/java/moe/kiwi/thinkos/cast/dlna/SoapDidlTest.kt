package moe.kiwi.thinkos.cast.dlna

import moe.kiwi.thinkos.media.MediaSubtitle
import org.junit.Assert.*
import org.junit.Test

class SoapDidlTest {
    private fun envelope(action: String, namespace: String, vararg args: Pair<String, String>) =
        """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>""" +
            """<u:$action xmlns:u="$namespace">""" + args.joinToString("") { "<${it.first}>${Soap.escape(it.second)}</${it.first}>" } + "</u:$action></s:Body></s:Envelope>"

    @Test fun aRequestNamesItsServiceActionAndArguments() {
        val type = Services.AV_TRANSPORT.type
        val call = Soap.parse(envelope("SetAVTransportURI", type, "InstanceID" to "0", "CurrentURI" to "http://tv.test/a.mp4?x=1&y=2"), "\"$type#SetAVTransportURI\"")
        assertEquals(type, call.serviceType)
        assertEquals("SetAVTransportURI", call.action)
        assertEquals("http://tv.test/a.mp4?x=1&y=2", call.arguments["CurrentURI"])
    }

    @Test fun withoutTheHeaderTheNamespaceTellsTheService() {
        val type = Services.RENDERING_CONTROL.type
        val call = Soap.parse(envelope("GetVolume", type, "InstanceID" to "0", "Channel" to "Master"), null)
        assertEquals(type, call.serviceType)
        assertEquals("GetVolume", call.action)
    }

    @Test fun aHeaderThatDisagreesWithTheBodyIsAFault() {
        val type = Services.AV_TRANSPORT.type
        val fault = assertThrows(SoapFault::class.java) { Soap.parse(envelope("Play", type), "\"$type#Stop\"") }
        assertEquals(401, fault.code)
    }

    @Test fun whatIsNotASoapRequestIsAFaultNotACrash() {
        for (body in listOf("", "plain text", "<a/>", "<s:Envelope xmlns:s=\"x\"/>", "<!DOCTYPE x><s:Envelope xmlns:s=\"x\"/>")) {
            val fault = assertThrows(SoapFault::class.java) { Soap.parse(body, null) }
            assertTrue(fault.code in setOf(401, 402))
        }
    }

    @Test fun responsesEscapeTheirValues() {
        val xml = Soap.response("urn:x", "GetThing", listOf("Out" to "<DIDL a=\"1\">&</DIDL>"))
        val root = moe.kiwi.thinkos.cast.MiniXml.parse(xml)
        assertEquals("<DIDL a=\"1\">&</DIDL>", root.descendants("Out").single().text)
        assertTrue(xml.contains("<u:GetThingResponse xmlns:u=\"urn:x\">"))
        val fault = moe.kiwi.thinkos.cast.MiniXml.parse(Soap.fault(701, "No <media>"))
        assertEquals("701", fault.descendants("errorCode").single().text)
        assertEquals("No <media>", fault.descendants("errorDescription").single().text)
    }

    private val film = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/" xmlns:sec="http://www.sec.co.kr/">
        <item id="1" parentID="0" restricted="1"><dc:title>Big Buck Bunny</dc:title><upnp:class>object.item.videoItem.movie</upnp:class>
        <res protocolInfo="http-get:*:video/mp4:*">http://tv.test/bbb.mp4</res>
        <res protocolInfo="http-get:*:text/srt:*">http://tv.test/bbb.srt</res>
        <sec:CaptionInfoEx sec:type="srt">http://tv.test/bbb.en.srt</sec:CaptionInfoEx></item></DIDL-Lite>"""

    @Test fun aFilmGivesTitleKindMimeAndSubtitles() {
        val info = Didl.parse(film, "http://tv.test/bbb.mp4")
        assertEquals("Big Buck Bunny", info.title)
        assertEquals("video", info.kind)
        assertEquals("video/mp4", info.mime)
        assertEquals(listOf("http://tv.test/bbb.srt", "http://tv.test/bbb.en.srt"), info.subtitles.map { it.url })
        assertEquals(MediaSubtitle.SRT, info.subtitles.first().mimeType)
    }

    @Test fun musicGivesArtistAlbumAndCover() {
        val xml = """<DIDL-Lite xmlns:dc="d" xmlns:upnp="u"><item><dc:title>Song</dc:title><upnp:artist>Band</upnp:artist><upnp:album>Record</upnp:album>
            <upnp:albumArtURI>http://tv.test/cover.jpg</upnp:albumArtURI><upnp:class>object.item.audioItem.musicTrack</upnp:class>
            <res protocolInfo="http-get:*:audio/flac:*">http://tv.test/s.flac</res></item></DIDL-Lite>"""
        val info = Didl.parse(xml, "http://tv.test/s.flac")
        assertEquals(listOf("Song", "Band", "Record", "audio", "audio/flac"), listOf(info.title, info.artist, info.album, info.kind, info.mime))
        assertEquals("http://tv.test/cover.jpg", info.art)
    }

    @Test fun metadataThatCannotBeReadNeverBlocksACast() {
        assertEquals("video", Didl.parse("", "http://tv.test/a").kind)
        assertEquals("audio", Didl.parse("NOT_IMPLEMENTED", "http://tv.test/a.mp3").kind)
        assertEquals("video", Didl.parse("<DIDL-Lite><item><broken></DIDL-Lite>", "http://tv.test/a.mp4").kind)
        assertEquals("", Didl.parse("<!DOCTYPE x><DIDL-Lite/>", "http://tv.test/a").title)
        assertEquals("audio", Didl.parse("<DIDL-Lite/>", "http://tv.test/a.M4A").kind)
    }
}
