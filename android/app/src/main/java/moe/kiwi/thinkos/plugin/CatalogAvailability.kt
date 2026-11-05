package moe.kiwi.thinkos.plugin

/**
 * Content can keep its last frame after a failure; settings must still be able to handle edits. The control sheet is different: a
 * sheet whose plugin is not running would show buttons that do nothing, so it is withdrawn, and a pull-down just does not open
 * (the recovery menu and the long press do not depend on it).
 */
internal fun pagesForCatalog(
    pages: List<PluginPage>,
    hasScript: Boolean,
    scriptRunning: Boolean,
    disabled: Boolean,
): List<PluginPage> = if (!disabled && (!hasScript || scriptRunning)) pages
    else pages.filterNot { "settings" in it.roles || "panel" in it.roles }
