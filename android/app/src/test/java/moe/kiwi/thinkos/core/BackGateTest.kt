package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.core.BackGate.Verdict.DELIVER
import moe.kiwi.thinkos.core.BackGate.Verdict.LEAVE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackGateTest {
    private val gate = BackGate(graceMillis = 2_000)

    @Test fun anAppThatDeclaredNothingIsLeftAtOnce() {
        assertEquals(LEAVE, gate.back("player", 0))
        assertEquals(LEAVE, gate.back(null, 0))
    }

    @Test fun anAppThatInterceptsIsToldInsteadOfLeft() {
        gate.intercept("iptv", true)
        assertTrue(gate.intercepts("iptv"))
        assertEquals(DELIVER, gate.back("iptv", 0))
    }

    @Test fun anAppThatAnsweredKeepsHearingBackForAsLongAsItInterceptsNoMatterHowLongItTakes() {
        gate.intercept("iptv", true)
        var now = 0L
        repeat(5) {
            assertEquals(DELIVER, gate.back("iptv", now))
            gate.answered("iptv")
            now += 10_000
        }
    }

    @Test fun anAppThatStaysSilentForTheGracePeriodForfeitsTheInterception() {
        gate.intercept("iptv", true)
        assertEquals(DELIVER, gate.back("iptv", 0))
        assertEquals(DELIVER, gate.back("iptv", 1_999))
        assertEquals(LEAVE, gate.back("iptv", 2_000))
        assertFalse(gate.intercepts("iptv"))
    }

    @Test fun pressingAgainAndAgainCannotPostponeTheWayOut() {
        gate.intercept("iptv", true)
        assertEquals(DELIVER, gate.back("iptv", 0))
        for (t in listOf(500L, 1_000L, 1_500L, 1_900L)) assertEquals(DELIVER, gate.back("iptv", t))
        assertEquals(LEAVE, gate.back("iptv", 2_100))
    }

    @Test fun anAnswerStartsTheCountAgain() {
        gate.intercept("iptv", true)
        assertEquals(DELIVER, gate.back("iptv", 0))
        gate.answered("iptv")
        assertEquals(DELIVER, gate.back("iptv", 5_000))
        assertEquals(DELIVER, gate.back("iptv", 6_000))
        assertEquals(LEAVE, gate.back("iptv", 7_000))
    }

    @Test fun givingTheInterceptionBackIsAnAnswerAndMeansTheNextBackLeaves() {
        gate.intercept("iptv", true)
        assertEquals(DELIVER, gate.back("iptv", 0))
        gate.intercept("iptv", false)
        assertEquals(LEAVE, gate.back("iptv", 100))
    }

    @Test fun whatAnAppDeclaredEndsWhenItsPageLeaves() {
        gate.intercept("iptv", true)
        gate.forget("iptv")
        assertFalse(gate.intercepts("iptv"))
        assertEquals(LEAVE, gate.back("iptv", 0))
    }

    @Test fun oneAppsSilenceDoesNotCountAgainstAnother() {
        gate.intercept("iptv", true)
        gate.intercept("player", true)
        assertEquals(DELIVER, gate.back("iptv", 0))
        assertEquals(DELIVER, gate.back("player", 5_000))
        assertTrue(gate.intercepts("iptv"))
    }

    @Test fun settingsUsesTheSameTimeoutAndCanRedeclareAfterLeaving() {
        gate.intercept("settings", true)
        assertEquals(DELIVER, gate.back("settings", 0))
        assertEquals(LEAVE, gate.back("settings", 2_000))
        gate.intercept("settings", true)
        assertEquals(DELIVER, gate.back("settings", 3_000))
        gate.answered("settings")
        gate.forget("settings")
        assertEquals(LEAVE, gate.back("settings", 6_000))
    }
}
