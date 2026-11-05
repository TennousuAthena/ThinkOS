package moe.kiwi.thinkos.update

/** Native read-only fixed-socket probe preserves errno; API27 LocalSocket erases it into IOException text. */
internal object UpdateRepairPresence {
    enum class State { ABSENT, PRESENT, UNKNOWN }
    private val loaded = runCatching { System.loadLibrary("tos") }.isSuccess
    private external fun nativeProbe(): Int
    fun read(): State = if (!loaded) State.UNKNOWN else runCatching {
        when (nativeProbe()) { 1 -> State.ABSENT; 2 -> State.PRESENT; else -> State.UNKNOWN }
    }.getOrDefault(State.UNKNOWN)
}
