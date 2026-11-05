package moe.kiwi.thinkos.device

/** Main-thread audio fence. Read every original index before changing aliased Android streams. */
internal class SleepAudioGate(private val volumes: Volumes, private val persistence: Persistence) {
    interface Volumes {
        fun read(stream: Int): Int?
        fun maximum(stream: Int): Int?
        fun write(stream: Int, index: Int): Boolean
    }
    interface Persistence {
        fun read(): Map<Int, Int>?
        fun write(values: Map<Int, Int>)
        fun clear()
    }

    var sleeping = false
        private set
    private var saved: Map<Int, Int>? = null
    private var loaded = false

    fun update(asleep: Boolean) {
        val first = !loaded
        if (!loaded) {
            saved = persistence.read()?.also(::validate)
            loaded = true
        }
        if (asleep) {
            val previous = saved
            if (previous == null) {
                val original = currentVolumes()
                // Never zero a stream before the recovery record is atomically durable.
                persistence.write(original)
                saved = original
            } else if (!first && !sleeping) {
                // A prior wake may have left a partial recovery record. New nonzero DAY values
                // belong to this new sleep; still-zero streams retain their unrecovered target.
                val current = currentVolumes()
                val original = previous.mapValues { (stream, index) -> current.getValue(stream).takeIf { it != 0 } ?: index }
                persistence.write(original)
                saved = original
            }
            sleeping = true
            enforce()
        } else {
            val original = saved ?: return
            // Restoring may synchronously produce volume notifications. Release the fence first.
            sleeping = false
            var restored = true
            original.forEach { (stream, index) ->
                val current = volumes.read(stream)
                // A new nonzero value belongs to the user, including after an APK/process restart.
                if (current == null) restored = false
                else if (current == 0 && index != 0) {
                    if (!volumes.write(stream, index) || volumes.read(stream) != index) restored = false
                }
            }
            if (restored) { persistence.clear(); saved = null }
        }
    }

    private fun currentVolumes(): Map<Int, Int> = STREAMS.associateWith { stream ->
        checkNotNull(volumes.read(stream)) { "sleep volume unavailable" }
    }.also(::validate)

    private fun validate(values: Map<Int, Int>) {
        require(values.keys == STREAMS.toSet()) { "invalid sleep volume streams" }
        values.forEach { (stream, index) ->
            val maximum = checkNotNull(volumes.maximum(stream)) { "sleep volume limit unavailable" }
            require(index in 0..maximum) { "invalid sleep volume index" }
        }
    }

    fun enforce() {
        if (!sleeping) return
        STREAMS.forEach { stream ->
            val index = volumes.read(stream)
            if (index != null && index != 0) volumes.write(stream, 0)
        }
    }

    companion object {
        // SYSTEM, RING, MUSIC, ALARM, NOTIFICATION on API 27. Never change ringer/DND policy.
        private val STREAMS = 1..5
    }
}
