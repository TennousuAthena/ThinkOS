package moe.kiwi.thinkos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CallH264ProbePolicyTest {
    private val baseline = mapOf("level-asymmetry-allowed" to "1", "profile-level-id" to "42e01f", "packetization-mode" to "1")

    @Test fun selectsActualAdvertisedBaselineAndNeverInventsSoftwareSupport() {
        assertEquals(1, CallH264ProbePolicy.baselineIndex(listOf("VP8" to emptyMap(), "H264" to baseline)))
        assertThrows(IllegalArgumentException::class.java) {
            CallH264ProbePolicy.baselineIndex(listOf("H264" to (baseline + ("profile-level-id" to "640c1f"))))
        }
    }

    @Test fun ambiguousOrUnboundedFactoryListsRefuseCreation() {
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.baselineIndex(List(2) { "H264" to baseline }) }
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.baselineIndex(List(65) { "VP8" to emptyMap() }) }
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.baselineIndex(listOf("H264" to (baseline + ("x" to "bad\nvalue")))) }
    }

    @Test fun fixedStandardArtifactCannotBeReplacedByAnotherNamespaceOrHash() {
        val aar = "1872dda4e3bf7d6ea6f7c6e11861332081c57081a1e62fc82fab72415c69c84c"
        val library = "5415e7a02ce83e983329575e7c2e46b8ced6c75baea3c41aa52b5699ccb54eda"
        CallH264ProbePolicy.requirePinnedStandard("android", "150.7871.04", aar, library)
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requirePinnedStandard("android-prefixed", "150.7871.04", aar, library) }
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requirePinnedStandard("android", "150.7871.04", "0".repeat(64), library) }
    }

    @Test fun factoryLabelOrDefaultHardwareFlagIsNotActualCodecIdentity() {
        val hardware = "org.webrtc.HardwareVideoEncoder"
        val qcom = "OMX.qcom.video.encoder.avc"
        CallH264ProbePolicy.requireHardware(hardware, qcom, qcom, true)
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requireHardware("org.webrtc.VideoEncoderFallback", qcom, qcom, true) }
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requireHardware(hardware, qcom, "OMX.google.h264.encoder", true) }
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requireHardware(hardware, qcom, qcom, false) }
    }

    @Test fun outputMustBeTheOneBoundedRequestedFrameNotAnotherTimestampOrResolution() {
        val stamp = CallH264ProbePolicy.FRAME_TIMESTAMP_NS
        CallH264ProbePolicy.requireOutput(1280, 720, 1000, stamp, "VideoFrameKey")
        for (size in listOf(0, CallH264ProbePolicy.MAX_OUTPUT_BYTES + 1)) {
            assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requireOutput(1280, 720, size, stamp, "VideoFrameKey") }
        }
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requireOutput(640, 480, 1000, stamp, "VideoFrameKey") }
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requireOutput(1280, 720, 1000, stamp + 1, "VideoFrameKey") }
        assertThrows(IllegalArgumentException::class.java) { CallH264ProbePolicy.requireOutput(1280, 720, 1000, stamp, "VideoFrameDelta") }
    }
}
