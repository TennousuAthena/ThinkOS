package moe.kiwi.thinkos.core

import org.junit.Assert.*
import org.junit.Test

class OnboardingPolicyTest {
    @Test fun migrationAdoptsOnlyAnOldDeviceWithoutAnExplicitCompletionValue() {
        assertTrue(OnboardingPolicy.adoptExisting(true, false, false))
        assertFalse(OnboardingPolicy.adoptExisting(false, false, false))
        assertFalse(OnboardingPolicy.adoptExisting(true, true, false))
    }
    @Test fun aNewDeviceInterruptedAfterWritingLocalSettingsIsStillIncompleteOnRestart() {
        assertFalse(OnboardingPolicy.adoptExisting(false, false, false))
        // The first launch persists initialized=true together with any setup edits. done=0
        // is absent because it is the schema default; the new local file is not legacy data.
        assertFalse(OnboardingPolicy.adoptExisting(true, false, true))
        assertTrue(OnboardingPolicy.prompt(0, 1, 3, false))
    }
    @Test fun aLegacyDeviceMigratesOnceAndExplicitResetZeroDoesNotAdoptItAgain() {
        assertTrue(OnboardingPolicy.adoptExisting(true, false, false))
        assertFalse(OnboardingPolicy.adoptExisting(true, true, true))
        // Resetting completion to its default removes that override, but keeps initialized.
        assertFalse(OnboardingPolicy.adoptExisting(true, false, true))
        assertTrue(OnboardingPolicy.prompt(0, 0, 3, false))
    }
    @Test fun completionSafeModeAndThePromptLimitEachStopAutomaticReentry() {
        assertTrue(OnboardingPolicy.prompt(0, 0, 3, false))
        assertTrue(OnboardingPolicy.prompt(0, 2, 3, false))
        assertFalse(OnboardingPolicy.prompt(0, 3, 3, false))
        assertFalse(OnboardingPolicy.prompt(1, 0, 3, false))
        assertFalse(OnboardingPolicy.prompt(0, 0, 3, true))
        assertFalse(OnboardingPolicy.prompt(0, 0, 0, false))
    }
    @Test fun changingOnboardingStepsKeepsOneLayerAndPruningAnInvalidPluginExits() {
        val nav = NavStack()
        val home = NavStack.Frame("home.main", "default")
        nav.push(home, NavStack.Kind.ONBOARDING, NavStack.Frame("welcome.hello", "default"))
        nav.replaceTop(NavStack.Frame("welcome.setup", "default"))
        assertEquals(1, nav.depth)
        assertTrue(nav.has(NavStack.Kind.ONBOARDING))
        assertEquals(home, nav.below)
        assertEquals(NavStack.Change.Show(home), nav.prune { it == home.page })
        assertTrue(nav.isEmpty)
    }

    @Test fun anExplicitColdHomeSuppressesOnlyThisProcessInitialPrompt() {
        val coldHome = OnboardingEntryPolicy()
        coldHome.homeRequested()
        assertFalse(coldHome.claimInitialCatalog())
        assertFalse(coldHome.claimInitialCatalog())
        // No persisted completion or prompt count changed. A later normal
        // process can still continue the unfinished setup.
        assertTrue(OnboardingPolicy.prompt(0, 1, 3, false))
        assertTrue(OnboardingEntryPolicy().claimInitialCatalog())
    }

    @Test fun catalogRefreshesCannotSpendAnotherAutomaticPrompt() {
        val entry = OnboardingEntryPolicy()
        assertTrue(entry.claimInitialCatalog())
        assertFalse(entry.claimInitialCatalog())
        entry.homeRequested()
        assertFalse(entry.claimInitialCatalog())
    }

    @Test fun globalHomeCompletesOnlyTheVisibleOwnersArmedHomeLesson() {
        assertTrue(OnboardingPolicy.practiceMatches(true, true, "welcome", "welcome", "home", "home"))
        assertFalse(OnboardingPolicy.practiceMatches(false, true, "welcome", "welcome", "home", "home"))
        assertFalse(OnboardingPolicy.practiceMatches(true, false, "welcome", "welcome", "home", "home"))
        assertFalse(OnboardingPolicy.practiceMatches(true, true, "settings", "welcome", "home", "home"))
        assertFalse(OnboardingPolicy.practiceMatches(true, true, "welcome", "welcome", "back", "home"))
        // Completion clears the active practice before sending its event;
        // a subsequent system/local callback cannot report it a second time.
        assertFalse(OnboardingPolicy.practiceMatches(true, true, "welcome", null, null, "home"))
        assertFalse(OnboardingPolicy.practiceMatches(true, true, null, null, "home", "home"))
    }
}
