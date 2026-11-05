package moe.kiwi.thinkos.media

import androidx.media3.exoplayer.dash.manifest.UtcTimingElement
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class PublicDashTimingTest {
    @Test fun strictDynamicDashUsesOsTimeForMissingNtpAndHttpTimingWithoutAnotherNetworkClient() {
        val now = 1_700_000_000_123L
        for (original in listOf(null,
            UtcTimingElement("urn:mpeg:dash:utc:ntp:2014", "camera.local"),
            UtcTimingElement("urn:mpeg:dash:utc:http-iso:2014", "http://192.168.1.1/time"),
            UtcTimingElement("urn:mpeg:dash:utc:http-xsdate:2014", "https://origin.test/time"))) {
            val timing = PublicDashTiming.replace(true, original, now)!!
            assertEquals("urn:mpeg:dash:utc:direct:2014", timing.schemeIdUri)
            assertEquals(now, Instant.parse(timing.value).toEpochMilli())
        }
    }

    @Test fun staticManifestTimingIsPreservedAndItsFactoryCannotCollideWithStrictHls() {
        val original = UtcTimingElement("urn:mpeg:dash:utc:direct:2014", "2023-01-01T00:00:00Z")
        assertSame(original, PublicDashTiming.replace(false, original, 0))
        assertNull(PublicDashTiming.replace(false, null, 0))
        val hls = MediaEngine.SourceKey(false, false, "https://origin.test:443", emptyMap(), true)
        assertNotEquals(hls, hls.copy(strictDash = true))
    }
}
