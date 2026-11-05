package moe.kiwi.thinkos

/** Fixed experiment bounds, shared only by JVM tests and the opt-in test APK. */
internal object CallH264ProbePolicy {
    const val WIDTH = 1280
    const val HEIGHT = 720
    const val FRAME_TIMESTAMP_NS = 1_000_000_000L
    const val MAX_OUTPUT_BYTES = 512 * 1024
    private val baseline = mapOf("level-asymmetry-allowed" to "1", "profile-level-id" to "42e01f", "packetization-mode" to "1")

    fun requirePinnedStandard(variant: String, version: String, aar: String, library: String) {
        require(variant == "android" && version == "150.7871.04" &&
            aar == "1872dda4e3bf7d6ea6f7c6e11861332081c57081a1e62fc82fab72415c69c84c" &&
            library == "5415e7a02ce83e983329575e7c2e46b8ced6c75baea3c41aa52b5699ccb54eda")
    }

    fun baselineIndex(values: List<Pair<String, Map<String, String>>>): Int {
        require(values.size in 1..64)
        values.forEach { (name, params) ->
            require(name.length in 1..64 && name.none(Char::isISOControl) && params.size <= 16)
            require(params.all { (key, value) -> key.length in 1..128 && value.length <= 256 &&
                key.none(Char::isISOControl) && value.none(Char::isISOControl) })
        }
        val matches = values.indices.filter { values[it].first == "H264" && values[it].second == baseline }
        require(matches.size == 1) { "One exact supported baseline required" }
        return matches.single()
    }

    fun requireHardware(type: String, implementation: String, platformName: String, encoder: Boolean) {
        // API27 has no isHardwareAccelerated(). Do not use VideoEncoder's
        // default isHardwareEncoder=true as proof. Bind the real SDK class to
        // the actual MediaCodec instance and its known QCOM implementation.
        require(type == "org.webrtc.HardwareVideoEncoder" && implementation == "OMX.qcom.video.encoder.avc" &&
            platformName == implementation && encoder)
    }

    fun requireOutput(width: Int, height: Int, bytes: Int, timestamp: Long, frame: String) {
        require(width == WIDTH && height == HEIGHT && bytes in 1..MAX_OUTPUT_BYTES &&
            timestamp == FRAME_TIMESTAMP_NS && frame == "VideoFrameKey")
    }
}
