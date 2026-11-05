package moe.kiwi.thinkos.plugin

/** System capabilities belong to the verified APK copy, including native image requests. */
internal object PluginAuthority {
    fun rolesAllowed(roles: List<String>, granted: Set<String>): Boolean = roles.all {
        when (it) { "panel" -> "system.device" in granted; "launcher" -> "system.apps" in granted;
            "onboarding", "settings" -> "system.settings" in granted; else -> true }
    }
    fun granted(declared: Set<String>, factory: Boolean): Set<String> =
        if (factory) declared else declared.filterNot { it.startsWith("system.") }.toSet()

    fun requireSystem(permission: String, declared: Set<String>, factory: Boolean) {
        require(permission in declared) { "$permission permission required" }
        require(factory) { "$permission is only granted to built-in plugins" }
    }
}
