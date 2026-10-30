package moe.kiwi.thinkos.core

import org.junit.Assert.assertEquals
import org.junit.Test

class PoliciesTest {
    @Test fun attentionDoesNotSleepWhilePresenceIsHeld() {
        val policy=AttentionPolicy(0)
        policy.presence(true,1_000)
        assertEquals(Attention.AMBIENT,policy.level(200_000))
        policy.touch(201_000)
        assertEquals(Attention.ENGAGED,policy.level(210_000))
        assertEquals(Attention.AWAKE,policy.level(217_000))
        policy.presence(false,220_000)
        assertEquals(Attention.ASLEEP,policy.level(400_000))
    }
    @Test fun firstApproachWakesWithoutRequiringATouch() {
        val policy = AttentionPolicy(0)
        assertEquals(Attention.ASLEEP, policy.level(200_000))
        policy.presence(true, 201_000)
        assertEquals(Attention.AWAKE, policy.level(201_001))
        policy.presence(true, 300_000)
        assertEquals(Attention.AMBIENT, policy.level(321_000))
    }
    @Test fun settingsInteractionKeepsTheDisplayEngagedWithoutChangingNormalIdleHistory() {
        val policy = AttentionPolicy(0)
        policy.touch(1_000)
        assertEquals(Attention.ENGAGED, policy.level(20_000, interactionActive = true))
        assertEquals(Attention.ENGAGED, policy.level(300_000, interactionActive = true))
        assertEquals(Attention.ASLEEP, policy.level(300_001))
        policy.touch(300_002)
        assertEquals(Attention.ENGAGED, policy.level(300_003))
    }
    @Test fun disablingNightModeAppliesImmediatelyWhileEditingSettings() {
        for (mode in listOf("auto", "light", "dark")) {
            var limits = ThemeThresholds(mode = mode)
            val policy = ThemePolicy(Theme.NIGHT) { limits }
            assertEquals(Theme.NIGHT, policy.sample(.1f, 0, engaged = true))
            limits = limits.copy(nightEnabled = false)
            assertEquals(if (mode == "light") Theme.LIGHT else Theme.DARK,
                policy.sample(.1f, 1, engaged = true))
        }
    }
    @Test fun transientLightAndEngagementCannotSwitchTheme() {
        val policy=ThemePolicy(Theme.LIGHT)
        policy.sample(20f,0,false)
        assertEquals(Theme.LIGHT,policy.sample(100f,29_000,false))
        policy.sample(20f,30_000,false)
        assertEquals(Theme.LIGHT,policy.sample(20f,61_000,true))
        assertEquals(Theme.DARK,policy.sample(20f,62_000,false))
        policy.sample(200f,63_000,false)
        assertEquals(Theme.DARK,policy.sample(200f,100_000,false))
        assertEquals(Theme.LIGHT,policy.sample(200f,183_000,false))
    }
    @Test fun sunModeFollowsSunriseAndSunsetWhateverTheRoomLight() {
        var day: Boolean? = true
        val policy = ThemePolicy(Theme.DARK, { day }) { ThemeThresholds(mode = "sun") }
        // Sun is up: light at once (no dwell), even with a hand on the screen and a dim room.
        assertEquals(Theme.LIGHT, policy.sample(30f, 0, engaged = true))
        assertEquals(Theme.LIGHT, policy.sample(20f, 1_000, engaged = false))
        assertEquals(Theme.LIGHT, policy.scheduled())
        // Sun is down: dark at once, even in a bright room.
        day = false
        assertEquals(Theme.DARK, policy.sample(300f, 2_000, engaged = true))
        day = true
        assertEquals(Theme.LIGHT, policy.sample(300f, 3_000, engaged = false))
    }
    @Test fun sunModeKeepsRedNightModeByLuxAndLeavesItBySun() {
        val policy = ThemePolicy(Theme.LIGHT, { true }) { ThemeThresholds(mode = "sun") }
        policy.sample(2f, 0, false)
        assertEquals(Theme.NIGHT, policy.sample(2f, 10_000, false))
        policy.sample(100f, 30_000, false)
        assertEquals(Theme.NIGHT, policy.sample(100f, 39_999, false))
        assertEquals(Theme.LIGHT, policy.sample(100f, 40_000, false))
    }
    @Test fun sunModeFallsBackToAmbientLightWhileTheSunIsUnknown() {
        val policy = ThemePolicy(Theme.LIGHT, { null }) { ThemeThresholds(mode = "sun") }
        assertEquals(null, policy.scheduled())
        policy.sample(20f, 0, false)
        assertEquals(Theme.LIGHT, policy.sample(20f, 29_000, false))
        assertEquals(Theme.DARK, policy.sample(20f, 30_000, false))
    }
    @Test fun nightHasSeparateDwellAndExitHysteresis() {
        val policy=ThemePolicy(Theme.DARK)
        policy.sample(2f,0,false)
        assertEquals(Theme.DARK,policy.sample(2f,9_999,false))
        assertEquals(Theme.NIGHT,policy.sample(2f,10_000,false))
        assertEquals(Theme.NIGHT,policy.sample(12f,80_000,false))
        policy.sample(20f,81_000,false)
        assertEquals(Theme.DARK,policy.sample(20f,91_000,false))
    }
}
