package moe.kiwi.thinkos.shell

/** The optional shell module must be ready; overlay permission alone never enables the foreign edge. */
internal object ForeignPanelPolicy {
    const val PROPERTY = "sys.thinkos.shell"
    fun globalNavigation(protocol: String, permission: Boolean, policy: String?): Boolean =
        protocol == "2" && permission && matchesPolicy(policy, "immersive.full")
    fun available(foreign: Boolean, resumed: Boolean, enabled: Boolean, hasPanel: Boolean, safeMode: Boolean,
                  permission: Boolean, protocol: String, policy: String?): Boolean =
        (foreign || protocol == "2") && !resumed && enabled && hasPanel && !safeMode && permission && protocol in setOf("1", "2") &&
            matchesPolicy(policy, if (protocol == "2") "immersive.full" else "immersive.status")
    private fun matchesPolicy(policy: String?, key: String) = policy.orEmpty().split(':').any { section ->
        section.substringBefore('=') == key && section.substringAfter('=', "").split(',').let { filters -> "*" in filters && filters.none { it.startsWith('-') } }
    }
}
