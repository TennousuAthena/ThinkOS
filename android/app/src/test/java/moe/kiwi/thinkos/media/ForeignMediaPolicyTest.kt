package moe.kiwi.thinkos.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ForeignMediaPolicyTest {
    @Test fun ordinaryAudioKeepsPlayingAndDelegatesToAudioFocus() {
        for (status in listOf("playing", "buffering", "reconnecting", "paused", "stopped"))
            assertEquals(ForeignMediaPolicy.Action.KEEP, ForeignMediaPolicy.action("audio", false, status))
    }
    @Test fun activeVideoPausesWithoutDiscardingTheQueue() {
        for (status in listOf("playing", "buffering", "reconnecting"))
            assertEquals(ForeignMediaPolicy.Action.PAUSE, ForeignMediaPolicy.action("video", false, status))
        for (status in listOf("paused", "error", "ended", "stopped"))
            assertEquals(ForeignMediaPolicy.Action.KEEP, ForeignMediaPolicy.action("video", false, status))
    }
    @Test fun mirrorsEndEvenWhenTheyWerePaused() {
        for (status in listOf("playing", "buffering", "paused", "error"))
            assertEquals(ForeignMediaPolicy.Action.STOP, ForeignMediaPolicy.action("video", true, status))
    }
}
