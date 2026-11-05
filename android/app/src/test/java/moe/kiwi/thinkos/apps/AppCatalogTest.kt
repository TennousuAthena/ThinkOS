package moe.kiwi.thinkos.apps

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class AppCatalogTest {
    private val pages = listOf(AppEntry("player.library", "page", "Music", "music"), AppEntry("cast.home", "page", "Cast", "cast"))
    private val installed = listOf("moe.kiwi.thinkos" to "ThinkOS", "com.example.reader" to "Reader",
        "com.example.hidden" to "Hidden", "com.example.reader" to "Duplicate activity", "bad/package" to "Invalid")

    @Test fun launcherPackagesAreDeduplicatedFilteredAndGroupedAfterThinkOsApps() {
        val entries = AppCatalog.list(pages, installed, "moe.kiwi.thinkos", true, setOf("com.example.hidden"), Locale.US)
        assertEquals(listOf("cast.home", "player.library", "android:com.example.reader"), entries.map { it.id })
        assertEquals("app:com.example.reader", entries.last().image)
        assertEquals("Reader", entries.last().title)
        assertTrue(entries.take(2).all { it.kind == "page" })
    }

    @Test fun androidVisibilityDoesNotHideThinkOsAppsOrRequireAnInstalledApp() {
        assertEquals(pages.sortedBy { it.title }, AppCatalog.list(pages, installed, "moe.kiwi.thinkos", false, emptySet(), Locale.US))
        assertEquals(pages.sortedBy { it.title }, AppCatalog.list(pages, emptyList(), "moe.kiwi.thinkos", true, emptySet(), Locale.US))
    }

    @Test fun identifiersCannotCarryIntentsUrlsOrTraversalAndLabelsStayBounded() {
        for (bad in listOf("com.example/Activity", "com.example?x=1", "com.example:export", "com..example", "../x", "https://example.com", "com.a\n"))
            assertFalse(bad, AppCatalog.packageName(bad))
        assertEquals("Readable", AppCatalog.label("\nReadable\u0000"))
        assertEquals(80, AppCatalog.label("a".repeat(200)).length)
    }

    @Test fun unexpectedlyLargeCatalogsFailExplicitlyInsteadOfSilentlyDroppingApps() {
        assertThrows(IllegalArgumentException::class.java) {
            AppCatalog.list(emptyList(), (1..513).map { "com.example.p$it" to "App $it" }, "moe.kiwi.thinkos", true, emptySet(), Locale.US)
        }
    }
}
