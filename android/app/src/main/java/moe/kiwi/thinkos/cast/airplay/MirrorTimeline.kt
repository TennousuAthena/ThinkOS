package moe.kiwi.thinkos.cast.airplay

/**
 * A sender with nothing to show sends nothing: a still screen is no pictures at all, and a silent phone no sound. The player's clock stops
 * with the last sample it has, but the next picture is stamped with the real time that went by, so the player would hold the old picture for
 * that whole time before the new one is due (a still screen of 25 seconds became 25 seconds of "frozen" after the next touch).
 *
 * So whenever *neither* stream has delivered anything for [maxGapUs], the silence is cut out of the timestamps and the sample that ends it
 * is placed [stepUs] after the last one. Anything that is not silence on both streams is left alone: while sound goes on, a picture that
 * comes late is still due when the sound says so. Samples keep their order and, apart from the cut, their spacing. One writer at a time.
 */
internal class MirrorTimeline(
    private val maxGapUs: Long = 150_000,
    private val stepUs: Long = 40_000,
    /** Told how long a silence was (microseconds) when one of at least a second is cut out. */
    private val onQuiet: (Long) -> Unit = {},
) {
    private var skew = 0L
    private val own = longArrayOf(NONE, NONE)
    private var newest = NONE

    /** [track] is [VIDEO] or [AUDIO]; [ptsUs] the sample's time as the sender gave it. Returns the time to write into the transport stream. */
    fun map(track: Int, ptsUs: Long): Long {
        var out = ptsUs - skew
        val before = own[track]
        // Only a track that has already spoken can have been silent, and only a silence on both tracks stops the player's clock.
        if (before != NONE && out - before > maxGapUs && out - newest > maxGapUs) {
            val cut = out - newest - stepUs
            skew += cut
            out -= cut
            if (cut >= 1_000_000) onQuiet(cut)
        }
        if (out > own[track]) own[track] = out
        if (out > newest) newest = out
        return out
    }

    /** A new transport stream starts: nothing said before counts. */
    fun reset() { skew = 0; own[VIDEO] = NONE; own[AUDIO] = NONE; newest = NONE }

    companion object {
        const val VIDEO = 0
        const val AUDIO = 1
        private const val NONE = Long.MIN_VALUE
    }
}
