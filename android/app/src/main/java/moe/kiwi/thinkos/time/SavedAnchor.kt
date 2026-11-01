package moe.kiwi.thinkos.time

/**
 * The reading [moe.kiwi.thinkos.core.WallClock] is built on, kept so a restarted process is exact at once
 * instead of waiting for the network. The monotonic clock restarts at every boot, so a reading only means
 * something on the boot it was taken in.
 */
class SavedAnchor(val boot: Int, val wall: Long, val elapsed: Long) {
    fun encode() = "$boot,$wall,$elapsed"

    fun usable(currentBoot: Int, nowElapsed: Long) =
        boot >= 0 && boot == currentBoot && elapsed in 0..nowElapsed && wall >= EPOCH_2020

    companion object {
        private const val EPOCH_2020 = 1_577_836_800_000L

        fun decode(text: String): SavedAnchor? {
            val parts = text.trim().split(',')
            if (parts.size != 3) return null
            return SavedAnchor(parts[0].toIntOrNull() ?: return null, parts[1].toLongOrNull() ?: return null, parts[2].toLongOrNull() ?: return null)
        }
    }
}
