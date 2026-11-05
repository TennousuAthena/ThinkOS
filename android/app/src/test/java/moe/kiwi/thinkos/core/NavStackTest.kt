package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.core.NavStack.Change
import moe.kiwi.thinkos.core.NavStack.Frame
import moe.kiwi.thinkos.core.NavStack.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavStackTest {
    private fun frame(page: String, variant: String = "v") = Frame(page, variant)
    private val home = frame("home.main", "digital")
    private val library = frame("player.library", "music")
    private val video = frame("player.video")
    private val settings = frame("settings.main")

    @Test fun anEmptyStackHasNothingToGoBackTo() {
        val stack = NavStack()
        assertTrue(stack.isEmpty)
        assertNull(stack.below)
        assertNull(stack.pop())
    }

    @Test fun backReturnsToThePageAndVariantTheAppWasOpenedFrom() {
        val stack = NavStack()
        assertTrue(stack.push(home, Kind.APP, library))
        assertEquals(Kind.APP, stack.top?.kind)
        assertEquals(home, stack.below)
        assertEquals(home, stack.pop())
        assertTrue(stack.isEmpty)
    }

    @Test fun layersComeOffOneAtATimeInTheOrderTheyWentOn() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.push(library, Kind.TAKEOVER, video)
        stack.push(video, Kind.SETTINGS, settings)
        assertEquals(3, stack.depth)
        assertEquals(video, stack.pop())
        assertEquals(library, stack.pop())
        assertEquals(home, stack.pop())
        assertNull(stack.pop())
    }

    @Test fun theVariantTheUserLeftAnAppWithIsTheOneItComesBackTo() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.variantChanged("video")
        stack.push(frame("player.library", "video"), Kind.TAKEOVER, video)
        assertEquals(frame("player.library", "video"), stack.pop())
    }

    @Test fun theStackStopsAtItsDepthAndRefusesMore() {
        val stack = NavStack(maxLayers = 2)
        assertTrue(stack.push(home, Kind.APP, frame("a.one")))
        assertTrue(stack.push(frame("a.one"), Kind.APP, frame("a.two")))
        assertFalse(stack.push(frame("a.two"), Kind.APP, frame("a.three")))
        assertEquals(2, stack.depth)
        assertEquals(frame("a.two"), stack.top?.frame)
    }

    @Test fun aTakeoverReplacesTheTakeoverBeforeItButBackStillLeadsToWhereTheFirstBegan() {
        val stack = NavStack()
        stack.push(library, Kind.TAKEOVER, video)
        assertTrue(stack.replaceTop(frame("player.audio")))
        assertEquals(1, stack.depth)
        assertEquals(library, stack.pop())
    }

    @Test fun closingAnAppFromInsideItShowsWhatIsBelowWhereverItSits() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.push(library, Kind.TAKEOVER, video)
        // The library is closed by its plugin while a film from it is on screen: the film stays.
        assertEquals(Change.None, stack.remove("player.library"))
        assertEquals(Change.Show(home), stack.remove("player.video"))
    }

    @Test fun removingTheTopLayerShowsTheOneUnderIt() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.push(library, Kind.TAKEOVER, video)
        assertEquals(Change.Show(library), stack.remove("player.video"))
        assertEquals(1, stack.depth)
    }

    @Test fun removingAPageThatIsNotInTheStackChangesNothing() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        assertEquals(Change.None, stack.remove("iptv.live"))
        assertEquals(1, stack.depth)
    }

    @Test fun unwindingToAPageThatIsAlreadyOpenPeelsWhatIsAboveItInsteadOfStackingItTwice() {
        val stack = NavStack()
        stack.push(home, Kind.APP, frame("iptv.channels"))
        stack.push(frame("iptv.channels"), Kind.TAKEOVER, frame("iptv.live"))
        assertEquals(Change.Show(frame("iptv.channels")), stack.unwindTo("iptv.channels"))
        assertEquals(1, stack.depth)
        // Already on top, or not there at all: nothing to do.
        assertEquals(Change.None, stack.unwindTo("iptv.channels"))
        assertEquals(Change.None, stack.unwindTo("player.library"))
    }

    @Test fun whenAPluginVanishesItsLayersGoAndTheNextOneDownIsShown() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.push(library, Kind.TAKEOVER, video)
        assertEquals(Change.Show(home), stack.prune { it == "home.main" })
        assertTrue(stack.isEmpty)
    }

    @Test fun aVanishedPluginInTheMiddleDoesNotMoveTheScreen() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.push(library, Kind.TAKEOVER, video)
        assertEquals(Change.None, stack.prune { it != "player.library" })
        assertEquals(1, stack.depth)
        assertEquals(video, stack.top?.frame)
    }

    @Test fun whenEveryPageIsGoneIncludingTheOneUnderneathTheAnswerIsHome() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        assertEquals(Change.Home, stack.prune { false })
        assertTrue(stack.isEmpty)
    }

    @Test fun pruningAStackThatIsFineChangesNothing() {
        val stack = NavStack()
        assertEquals(Change.None, stack.prune { true })
        stack.push(home, Kind.APP, library)
        assertEquals(Change.None, stack.prune { true })
        assertEquals(1, stack.depth)
    }

    @Test fun theSettingsPageCanBeClosedEvenIfItsPluginIsGone() {
        val stack = NavStack()
        stack.push(home, Kind.SETTINGS, settings)
        assertTrue(stack.has(Kind.SETTINGS))
        assertEquals(Change.Show(home), stack.removeKind(Kind.SETTINGS))
        assertFalse(stack.has(Kind.SETTINGS))
        assertEquals(Change.None, stack.removeKind(Kind.SETTINGS))
    }

    @Test fun clearingAnswersThePageTheLayersWereBuiltOver() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.push(library, Kind.TAKEOVER, video)
        assertEquals(home, stack.clear())
        assertTrue(stack.isEmpty)
        assertNull(stack.clear())
    }

    @Test fun aNewStackRemembersAFreshBase() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.pop()
        stack.push(frame("clock.face"), Kind.APP, library)
        assertEquals(frame("clock.face"), stack.pop())
    }

    @Test fun navigatingInsideSettingsDoesNotAddAnotherShellLayer() {
        val stack = NavStack()
        stack.push(home, Kind.APP, library)
        stack.push(library, Kind.SETTINGS, settings)
        // A scope change is content inside this frame. The shell still remembers the app underneath.
        stack.variantChanged("form")
        assertEquals(2, stack.depth)
        assertEquals(library, stack.below)
        assertEquals(library, stack.pop())
        assertEquals(home, stack.pop())
    }
}
