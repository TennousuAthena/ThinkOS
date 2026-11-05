package moe.kiwi.thinkos

/** Shared by the test APK inventory and JVM tests; no platform calls or product policy. */
internal object CallCapabilityFacts {
    // API27 has no MediaCodecInfo.isHardwareAccelerated/isSoftwareOnly. Never
    // turn an unfamiliar vendor name, or a substring such as "sw", into proof.
    fun codecOrigin(name: String): String = when {
        name.startsWith("OMX.google.") -> "software_by_name"
        name.startsWith("OMX.qcom.") -> "hardware_by_name"
        else -> "unknown"
    }

    /** Single-stream Camera2 metadata only. Zero duration means unpublished,
     * not infinite speed. A negative answer outranks missing metadata. */
    fun camera720p30(sizeSupported: Boolean, minimumFrameNanos: Long?, aeRanges: List<IntRange>?): Boolean? {
        if (!sizeSupported) return false
        if (aeRanges != null && aeRanges.none { 30 in it }) return false
        if (minimumFrameNanos != null && minimumFrameNanos > 0 && minimumFrameNanos > 1_000_000_000L / 30) return false
        if (minimumFrameNanos == null || minimumFrameNanos <= 0 || aeRanges == null) return null
        return true
    }
}
