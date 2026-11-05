package moe.kiwi.thinkos.media

import android.net.Uri
import androidx.media3.exoplayer.dash.manifest.DashManifest
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.dash.manifest.Period
import androidx.media3.exoplayer.dash.manifest.ProgramInformation
import androidx.media3.exoplayer.dash.manifest.ServiceDescriptionElement
import androidx.media3.exoplayer.dash.manifest.UtcTimingElement
import moe.kiwi.thinkos.core.WallClock
import java.time.Instant

/** Media3 1.9.2 otherwise uses SntpClient outside the supplied DataSource for
 * dynamic DASH with missing/NTP UTC timing. Strict public HTTPS playback uses
 * ThinkOS's already-corrected wall clock instead; ordinary DASH is unchanged.
 * Override the parser's construction hook and forward every other field to
 * its implementation. DashMediaSource still applies its own stream-key filter.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class PublicDashManifestParser : DashManifestParser() {
    override fun buildMediaPresentationDescription(
        availabilityStartTime: Long, duration: Long, minBufferTime: Long, dynamic: Boolean,
        minUpdatePeriod: Long, timeShiftBufferDepth: Long, suggestedPresentationDelay: Long, publishTime: Long,
        programInformation: ProgramInformation?, utcTiming: UtcTimingElement?, serviceDescription: ServiceDescriptionElement?,
        location: Uri?, periods: MutableList<Period>,
    ): DashManifest = super.buildMediaPresentationDescription(
        availabilityStartTime, duration, minBufferTime, dynamic, minUpdatePeriod, timeShiftBufferDepth,
        suggestedPresentationDelay, publishTime, programInformation,
        PublicDashTiming.replace(dynamic, utcTiming, WallClock.now()), serviceDescription, location, periods)
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object PublicDashTiming {
    fun replace(dynamic: Boolean, original: UtcTimingElement?, wallMillis: Long): UtcTimingElement? =
        if (!dynamic) original else UtcTimingElement("urn:mpeg:dash:utc:direct:2014", Instant.ofEpochMilli(wallMillis).toString())
}
