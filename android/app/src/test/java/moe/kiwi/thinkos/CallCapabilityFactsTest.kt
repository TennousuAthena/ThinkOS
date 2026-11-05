package moe.kiwi.thinkos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallCapabilityFactsTest {
    @Test fun api27_names_are_only_labeled_heuristics_and_unknown_vendors_stay_unknown() {
        val cases = mapOf(
            "OMX.google.h264.decoder" to "software_by_name",
            "OMX.qcom.video.encoder.avc" to "hardware_by_name",
            "OMX.qcom.video.decoder.avc" to "hardware_by_name",
            "OMX.vendor.sw.avc.decoder" to "unknown",
            "c2.android.avc.decoder" to "unknown",
            "OMX.qcomish.avc.encoder" to "unknown",
            "omx.QCOM.video.encoder.avc" to "unknown"
        )
        cases.forEach { (name, expected) -> assertEquals(name, expected, CallCapabilityFacts.codecOrigin(name)) }
    }

    @Test fun unpublished_camera_duration_never_means_unlimited_fps() {
        for (duration in listOf(null, 0L, -1L)) {
            assertNull(CallCapabilityFacts.camera720p30(true, duration, listOf(15..30)))
        }
        assertNull(CallCapabilityFacts.camera720p30(true, 33_333_333L, null))
    }

    @Test fun camera_missing_or_insufficient_metadata_cannot_be_upgraded_by_other_fields() {
        assertFalse(CallCapabilityFacts.camera720p30(false, null, null)!!)
        assertFalse(CallCapabilityFacts.camera720p30(true, 0L, listOf(15..24))!!)
        assertFalse(CallCapabilityFacts.camera720p30(true, 66_666_666L, null)!!)
        assertFalse(CallCapabilityFacts.camera720p30(true, 16_666_666L, emptyList())!!)
    }

    @Test fun camera_30fps_uses_a_range_that_includes_30_and_the_reported_single_stream_duration() {
        assertTrue(CallCapabilityFacts.camera720p30(true, 33_333_333L, listOf(15..30))!!)
        assertTrue(CallCapabilityFacts.camera720p30(true, 16_666_666L, listOf(30..60))!!)
        assertFalse(CallCapabilityFacts.camera720p30(true, 33_333_334L, listOf(30..30))!!)
    }
}
