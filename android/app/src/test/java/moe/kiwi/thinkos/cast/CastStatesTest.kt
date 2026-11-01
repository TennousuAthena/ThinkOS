package moe.kiwi.thinkos.cast

import org.junit.Assert.*
import org.junit.Test

class CastStatesTest {
    private fun snapshot(status: String, owner: String = "cast:dlna") = mapOf<String, Any?>("status" to status, "owner" to owner, "position" to 4200L,
        "duration" to 90_000L, "volume" to 0.5, "seekable" to true)

    @Test fun theEngineStatusesMapOntoTheProtocolOnes() {
        val expected = mapOf("playing" to CastStatus.PLAYING, "buffering" to CastStatus.BUFFERING, "reconnecting" to CastStatus.BUFFERING,
            "paused" to CastStatus.PAUSED, "ended" to CastStatus.ENDED, "error" to CastStatus.ERROR, "stopped" to CastStatus.IDLE)
        for ((engine, cast) in expected) assertEquals(engine, cast, CastStates.from(snapshot(engine), "cast:dlna").status)
    }

    @Test fun numbersComeThroughAndAPreciseTimeWins() {
        val state = CastStates.from(snapshot("playing"), "cast:dlna")
        assertEquals(listOf(4200L, 90_000L, 0.5f, true), listOf(state.positionMillis, state.durationMillis, state.volume, state.seekable))
        assertEquals(4321L, CastStates.from(snapshot("playing"), "cast:dlna", position = 4321).positionMillis)
    }

    @Test fun somebodyElsesSessionIsNotOurs() {
        val state = CastStates.from(snapshot("playing", owner = "player:3"), "cast:dlna")
        assertFalse(state.owned)
        assertEquals(CastStatus.IDLE, state.status)
    }
}
