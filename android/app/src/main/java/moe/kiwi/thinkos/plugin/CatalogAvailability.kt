package moe.kiwi.thinkos.plugin

/** Content can keep its last frame after a failure; settings must still be able to handle edits. */
internal fun pagesForCatalog(
    pages: List<PluginPage>,
    hasScript: Boolean,
    scriptRunning: Boolean,
    disabled: Boolean,
): List<PluginPage> = if (!disabled && (!hasScript || scriptRunning)) pages
    else pages.filterNot { "settings" in it.roles }
