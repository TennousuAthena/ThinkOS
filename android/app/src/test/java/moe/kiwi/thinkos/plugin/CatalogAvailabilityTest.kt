package moe.kiwi.thinkos.plugin

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogAvailabilityTest {
    private val content = PluginPage("sample", "content", "Content", JSONObject())
    private val settings = PluginPage("sample", "settings", "Settings", JSONObject(), roles = listOf("settings"))
    private val mixed = PluginPage("sample", "mixed", "Mixed", JSONObject(), roles = listOf("home", "settings"))
    private val panel = PluginPage("sample", "panel", "Panel", JSONObject(), roles = listOf("panel"))
    private val pages = listOf(content, settings, mixed)

    @Test fun aHealthyScriptPublishesInteractiveSettingsAndContent() {
        assertEquals(pages, pagesForCatalog(pages, hasScript = true, scriptRunning = true, disabled = false))
    }

    @Test fun restartBackoffHidesUnusableSettingsButKeepsTheLastContentFrame() {
        assertEquals(listOf(content), pagesForCatalog(pages, hasScript = true, scriptRunning = false, disabled = false))
    }

    @Test fun quarantineCannotLeaveASilentSettingsPageInTheCatalog() {
        assertEquals(listOf(content), pagesForCatalog(pages, hasScript = true, scriptRunning = false, disabled = true))
    }

    @Test fun aHealthyDeclarationOnlyPluginDoesNotRequireLua() {
        assertEquals(pages, pagesForCatalog(pages, hasScript = false, scriptRunning = false, disabled = false))
    }

    @Test fun aDisabledDeclarationDoesNotKeepInteractiveSettings() {
        assertEquals(listOf(content), pagesForCatalog(pages, hasScript = false, scriptRunning = false, disabled = true))
    }

    @Test fun theControlSheetIsOnlyOfferedWhileItsScriptRuns() {
        val withSheet = pages + panel
        assertEquals(withSheet, pagesForCatalog(withSheet, hasScript = true, scriptRunning = true, disabled = false))
        // A sheet whose plugin is down would show buttons that do nothing: the pull-down just does not open.
        assertEquals(listOf(content), pagesForCatalog(withSheet, hasScript = true, scriptRunning = false, disabled = false))
        assertEquals(listOf(content), pagesForCatalog(withSheet, hasScript = true, scriptRunning = false, disabled = true))
    }
}
