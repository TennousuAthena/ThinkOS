package moe.kiwi.thinkos.cast.airplay

import moe.kiwi.thinkos.cast.CastState
import moe.kiwi.thinkos.cast.CastStatus
import moe.kiwi.thinkos.cast.FakeCastPlayer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class EngineAirPlayControlTest {
    private val player = FakeCastPlayer()
    private val events = java.util.Collections.synchronizedList(ArrayList<String>())
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private var refuseAddress: String? = null
    private val control = EngineAirPlayControl(player, { uri -> refuseAddress?.let { require(uri.host != it) { "refused $it" } } }, { events += it }, worker, {})

    @After fun stop() { worker.shutdownNow() }

    private fun play(url: String, fraction: Double = 0.0, seconds: Double? = null) = control.play(AirPlayPlay(url, fraction, seconds, null, InetAddress.getByName("192.168.3.9")))

    @Test fun anHttpAddressIsLoadedAsAVideoAndTheSenderToldItIsLoading() {
        play("http://192.168.3.9:8080/film.m3u8?token=1")
        assertEquals("http://192.168.3.9:8080/film.m3u8?token=1", player.loaded!!.url); assertEquals("video", player.loaded!!.kind)
        assertEquals(listOf("load autoplay=true"), player.calls); assertEquals(listOf("loading"), events)
    }

    @Test fun whatIsNotHttpIsRefusedBeforeTheEngineHearsOfIt() {
        for (url in listOf("mlhls://localhost/master.m3u8", "file:///sdcard/a.mp4", "rtsp://192.168.3.9/live", "content://media/1", "ftp://x/y", "not a url", "http://user:pw@tv.test/a.mp4")) {
            assertThrows(url, IllegalArgumentException::class.java) { play(url) }
        }
        assertTrue(player.calls.isEmpty())
    }

    @Test fun theMediaPolicyCanRefuseAnAddressToo() {
        refuseAddress = "10.9.9.9"
        assertThrows(IllegalArgumentException::class.java) { play("http://10.9.9.9/a.mp4") }
        assertTrue(player.calls.isEmpty())
    }

    @Test fun rateZeroPausesAndRateOnePlays() {
        play("http://192.168.3.9/a.mp4")
        control.rate(0f); control.rate(1f)
        assertEquals(listOf("load autoplay=true", "pause", "play"), player.calls)
    }

    @Test fun scrubSeeksInMilliseconds() {
        play("http://192.168.3.9/a.mp4")
        control.scrub(12.5); control.scrub(-3.0)
        assertEquals(listOf("load autoplay=true", "seek 12500"), player.calls)
    }

    @Test fun aStartPositionInSecondsSeeksOnceTheFilmIsLoaded() {
        play("http://192.168.3.9/a.mp4", seconds = 90.0)
        awaitCall("seek 90000")
    }

    @Test fun aStartFractionWaitsForTheDuration() {
        play("http://192.168.3.9/a.mp4", fraction = 0.5)
        awaitCall("seek 60000")                                      // the fake film is 120 s long
    }

    private fun awaitCall(call: String) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < end && call !in player.calls) Thread.sleep(20)
        assertTrue("${player.calls}", call in player.calls)
    }

    @Test fun playbackIsReadFromTheEngineInSeconds() {
        assertNull(control.playback())
        play("http://192.168.3.9/a.mp4")
        player.seek(61_500)
        val p = control.playback()!!
        assertEquals(120.0, p.durationSeconds, 0.001); assertEquals(61.5, p.positionSeconds, 0.001); assertEquals(1f, p.rate, 0f); assertTrue(p.ready); assertFalse(p.buffering)
        control.rate(0f)
        assertEquals(0f, control.playback()!!.rate, 0f)
    }

    @Test fun theSenderHearsEachChangeOnce() {
        val owned = { status: CastStatus -> CastState(true, status, 0, 1000) }
        control.onState(owned(CastStatus.BUFFERING)); control.onState(owned(CastStatus.BUFFERING))
        control.onState(owned(CastStatus.PLAYING)); control.onState(owned(CastStatus.PLAYING))
        control.onState(owned(CastStatus.PAUSED)); control.onState(owned(CastStatus.PLAYING))
        assertEquals(listOf("loading", "playing", "paused", "playing"), events)
    }

    @Test fun anEndedFilmIsReportedAndGivesTheScreenBack() {
        play("http://192.168.3.9/a.mp4")
        events.clear()
        control.onState(CastState(true, CastStatus.ENDED, 120_000, 120_000))
        assertEquals(listOf("stopped"), events)
        awaitCall("stop")
    }

    @Test fun someoneElsesSessionIsNotReportedAsOurs() {
        control.onState(CastState(false, CastStatus.PLAYING, 0, 0))
        assertTrue(events.isEmpty())
    }

    @Test fun stopEndsItAndTellsTheSender() {
        play("http://192.168.3.9/a.mp4"); events.clear()
        control.stop()
        assertEquals("stop", player.calls.last()); assertEquals(listOf("stopped"), events)
    }

    @Test fun theRecordNamesTheDeviceAndItsCapabilities() {
        val device = AirPlayDevice("AA:BB:CC:DD:EE:FF", { "ThinkOS" })
        val txt = AirPlayRecord.attributes(device)
        assertEquals("AA:BB:CC:DD:EE:FF", txt["deviceid"]); assertEquals("0x19", txt["features"]); assertEquals("0x4", txt["flags"]); assertEquals("AppleTV3,2", txt["model"])
        assertFalse("no key means no pairing promise", txt.containsKey("pk"))
    }

    @Test fun theDeviceIdIsStableAndLooksLikeALocalMacAddress() {
        val a = AirPlayRecord.deviceId("seed-1"); val b = AirPlayRecord.deviceId("seed-1")
        assertEquals(a, b); assertNotEquals(a, AirPlayRecord.deviceId("seed-2"))
        assertTrue(a.matches(Regex("([0-9A-F]{2}:){5}[0-9A-F]{2}")))
        val first = a.substring(0, 2).toInt(16)
        assertEquals(2, first and 3)                         // locally administered, unicast
    }
}
