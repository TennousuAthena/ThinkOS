package moe.kiwi.thinkos.cast.dlna

import moe.kiwi.thinkos.cast.FakeCastPlayer
import moe.kiwi.thinkos.cast.CastState
import moe.kiwi.thinkos.cast.CastStatus
import moe.kiwi.thinkos.media.MediaEntry
import org.junit.Assert.*
import org.junit.Test
import java.net.URI

class DlnaRendererTest {
    private val player = FakeCastPlayer()
    private val published = mutableListOf<Pair<ServiceDef, String>>()
    private val renderer = DlnaRenderer(player, { uri -> require(uri.host != "127.0.0.1") { "no" } }) { service, doc -> published += service to doc }

    private fun call(service: ServiceDef, action: String, vararg args: Pair<String, String>): Map<String, String> {
        val xml = renderer.handle(SoapCall(service.type, action, mapOf("InstanceID" to "0") + args))
        val root = moe.kiwi.thinkos.cast.MiniXml.parse(xml)
        return root.descendants(action + "Response").single().children.associate { it.local to it.text }
    }
    private fun av(action: String, vararg args: Pair<String, String>) = call(Services.AV_TRANSPORT, action, *args)
    private fun rcs(action: String, vararg args: Pair<String, String>) = call(Services.RENDERING_CONTROL, action, *args)
    private fun fault(block: () -> Unit) = assertThrows(SoapFault::class.java) { block() }.code

    private val url = "http://tv.test/film.mp4"
    private fun setUri(uri: String = url, metadata: String = "") = av("SetAVTransportURI", "CurrentURI" to uri, "CurrentURIMetaData" to metadata)

    @Test fun anAddressIsLoadedPausedAndPlayStartsIt() {
        setUri(metadata = """<DIDL-Lite xmlns:dc="d" xmlns:upnp="u"><item><dc:title>Film</dc:title><upnp:class>object.item.videoItem</upnp:class></item></DIDL-Lite>""")
        assertEquals(listOf("load autoplay=false"), player.calls)
        assertEquals(listOf("Film", "video"), listOf(player.loaded!!.title, player.loaded!!.kind))
        assertEquals("STOPPED", av("GetTransportInfo")["CurrentTransportState"])      // loaded, not started: STOPPED, not PAUSED
        av("Play", "Speed" to "1")
        assertEquals("play", player.calls.last())
        assertEquals("PLAYING", av("GetTransportInfo")["CurrentTransportState"])
        av("Pause")
        assertEquals("PAUSED_PLAYBACK", av("GetTransportInfo")["CurrentTransportState"])
    }

    @Test fun positionAndDurationAreReportedInUpnpTime() {
        setUri(); av("Play", "Speed" to "1")
        player.state = player.state.copy(positionMillis = 3_723_000, durationMillis = 7_200_000)
        val info = av("GetPositionInfo")
        assertEquals(listOf("1:02:03", "2:00:00", url), listOf(info["RelTime"], info["TrackDuration"], info["TrackURI"]))
        assertEquals("2:00:00", av("GetMediaInfo")["MediaDuration"])
    }

    @Test fun seekAcceptsRelativeAndAbsoluteTimesAndRefusesTheRest() {
        setUri(); av("Play", "Speed" to "1")
        av("Seek", "Unit" to "REL_TIME", "Target" to "0:01:30")
        assertEquals("seek 90000", player.calls.last())
        av("Seek", "Unit" to "ABS_TIME", "Target" to "00:00:05.500")
        assertEquals("seek 5500", player.calls.last())
        assertEquals(711, fault { av("Seek", "Unit" to "REL_TIME", "Target" to "soon") })
        assertEquals(711, fault { av("Seek", "Unit" to "REL_TIME", "Target" to "5:00:00") })     // past the 2 h duration
        assertEquals(710, fault { av("Seek", "Unit" to "REL_FRAME", "Target" to "5") })
    }

    @Test fun stopGivesTheScreenBackAndPlayStartsTheSameAddressAgain() {
        setUri(); av("Play", "Speed" to "1"); av("Stop")
        assertEquals("stop", player.calls.last())
        assertEquals("STOPPED", av("GetTransportInfo")["CurrentTransportState"])
        assertEquals(url, av("GetMediaInfo")["CurrentURI"])
        av("Play", "Speed" to "1")
        assertEquals("load autoplay=true", player.calls.last())
    }

    @Test fun whenSomethingElseTakesTheSessionThisCastReportsStopped() {
        setUri(); av("Play", "Speed" to "1")
        player.state = CastState(owned = false, status = CastStatus.PLAYING)
        assertEquals("STOPPED", av("GetTransportInfo")["CurrentTransportState"])
    }

    @Test fun anErrorIsReportedAsErrorOccurred() {
        setUri(); av("Play", "Speed" to "1")
        player.state = player.state.copy(status = CastStatus.ERROR)
        val info = av("GetTransportInfo")
        assertEquals(listOf("STOPPED", "ERROR_OCCURRED"), listOf(info["CurrentTransportState"], info["CurrentTransportStatus"]))
    }

    @Test fun nothingLoadedMeansNoMediaAndPlayHasNothingToStart() {
        assertEquals("NO_MEDIA_PRESENT", av("GetTransportInfo")["CurrentTransportState"])
        assertEquals(701, fault { av("Play", "Speed" to "1") })
        assertEquals(701, fault { av("Pause") })
        assertEquals(701, fault { av("Next") })
    }

    @Test fun onlyWebAddressesAreAcceptedAndTheHostCanRefuseMore() {
        for (bad in listOf("file:///data/data/moe.kiwi.thinkos/files/settings/local.json", "content://media/external/video/1", "ftp://tv.test/a.mp4",
            "rtsp://tv.test/a", "javascript:alert(1)", "http://user:secret@tv.test/a.mp4", "not a url", "http://127.0.0.1:8080/a.mp4")) {
            assertEquals(bad, 402, fault { setUri(bad) })
        }
        assertTrue(player.calls.isEmpty())
    }

    @Test fun anEngineThatRefusesIsAnActionFailureNotACrash() {
        player.refuse = true
        assertEquals(501, fault { setUri() })
        assertEquals("NO_MEDIA_PRESENT", av("GetTransportInfo")["CurrentTransportState"])   // the old address was not replaced
    }

    @Test fun anEmptyAddressClearsTheCast() {
        setUri(); av("Play", "Speed" to "1")
        setUri("")
        assertEquals("stop", player.calls.last())
        assertEquals("NO_MEDIA_PRESENT", av("GetTransportInfo")["CurrentTransportState"])
    }

    @Test fun theQueuedAddressTakesOverWhenTheCurrentOneEnds() {
        setUri(); av("Play", "Speed" to "1")
        av("SetNextAVTransportURI", "NextURI" to "http://tv.test/second.mp4", "NextURIMetaData" to "")
        assertEquals("http://tv.test/second.mp4", av("GetMediaInfo")["NextURI"])
        renderer.playerChanged(player.state.copy(status = CastStatus.ENDED))
        assertEquals("load autoplay=true", player.calls.last())
        assertEquals("http://tv.test/second.mp4", player.loaded!!.url)
        assertEquals("", av("GetMediaInfo")["NextURI"])
    }

    @Test fun nextMovesToTheQueuedAddressOnRequest() {
        setUri(); av("SetNextAVTransportURI", "NextURI" to "http://tv.test/second.mp4", "NextURIMetaData" to "")
        av("Next")
        assertEquals("http://tv.test/second.mp4", player.loaded!!.url)
    }

    @Test fun volumeAndMuteUseThePercentScale() {
        rcs("SetVolume", "Channel" to "Master", "DesiredVolume" to "40")
        assertEquals("volume 0.4", player.calls.last())
        assertEquals("40", rcs("GetVolume", "Channel" to "Master")["CurrentVolume"])
        rcs("SetMute", "Channel" to "Master", "DesiredMute" to "1")
        assertEquals("volume 0.0", player.calls.last())
        assertEquals("1", rcs("GetMute", "Channel" to "Master")["CurrentMute"])
        rcs("SetMute", "Channel" to "Master", "DesiredMute" to "0")
        assertEquals("volume 0.4", player.calls.last())
        assertEquals(402, fault { rcs("SetVolume", "Channel" to "Master", "DesiredVolume" to "101") })
        assertEquals(402, fault { rcs("SetVolume", "Channel" to "Master", "DesiredVolume" to "loud") })
        assertEquals(402, fault { rcs("GetVolume", "Channel" to "LF") })
    }

    @Test fun theConnectionManagerListsWhatCanBePlayed() {
        val sink = call(Services.CONNECTION_MANAGER, "GetProtocolInfo")["Sink"]!!
        assertTrue(sink.contains("http-get:*:video/mp4:*") && sink.contains("http-get:*:application/vnd.apple.mpegurl:*"))
        assertEquals("0", call(Services.CONNECTION_MANAGER, "GetCurrentConnectionIDs")["ConnectionIDs"])
        assertEquals("Input", call(Services.CONNECTION_MANAGER, "GetCurrentConnectionInfo", "ConnectionID" to "0")["Direction"])
        assertEquals(706, fault { call(Services.CONNECTION_MANAGER, "GetCurrentConnectionInfo", "ConnectionID" to "9") })
    }

    @Test fun unknownThingsAreFaultsNotSilence() {
        assertEquals(401, fault { av("Launch") })
        assertEquals(401, fault { renderer.handle(SoapCall("urn:schemas-upnp-org:service:Other:1", "Play", emptyMap())) })
        assertEquals(718, fault { renderer.handle(SoapCall(Services.AV_TRANSPORT.type, "Stop", mapOf("InstanceID" to "3"))) })
    }

    @Test fun subscribersHearOnlyAboutChanges() {
        setUri()
        renderer.playerChanged(player.state)
        val before = published.size
        renderer.playerChanged(player.state)                      // nothing changed
        assertEquals(before, published.size)
        av("Play", "Speed" to "1")
        renderer.playerChanged(player.state)
        val last = published.last { it.first == Services.AV_TRANSPORT }.second
        assertTrue(last, last.contains("TransportState val=\"PLAYING\""))
        renderer.playerChanged(player.state.copy(volume = 0.25f))
        assertTrue(published.last().second.contains("Volume channel=\"Master\" val=\"25\""))
    }

    @Test fun upnpTimeRoundTripsAndRejectsGarbage() {
        assertEquals("0:00:00", UpnpTime.format(-5))
        assertEquals("10:00:01", UpnpTime.format(36_001_999))
        assertEquals(3_723_500L, UpnpTime.parse("1:02:03.5"))
        assertEquals(1_000L, UpnpTime.parse("+00:00:01"))
        for (bad in listOf("", "1:02", "a:b:c", "0:99:00", "0:00:75", "NOT_IMPLEMENTED")) assertNull(bad, UpnpTime.parse(bad))
    }
}
