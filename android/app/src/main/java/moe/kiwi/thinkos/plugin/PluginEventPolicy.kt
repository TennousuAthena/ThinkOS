package moe.kiwi.thinkos.plugin

/** Capability events use the same granted authority as their read APIs. */
internal object PluginEventPolicy {
    val restricted = setOf("presence", "gesture", "location", "device", "cast", "apps", "update", "practice", "plugins")

    fun allows(name: String, api: Int, granted: Set<String>): Boolean = when (name) {
        "presence", "gesture" -> "presence" in granted
        "location" -> "location" in granted
        "device" -> "system.device" in granted
        "cast" -> api >= 9 && "media" in granted
        "apps" -> api >= 9 && "system.apps" in granted
        "update" -> api >= 10 && "system.update" in granted
        "practice" -> api >= 10 && "system.settings" in granted
        "plugins" -> api >= 10 && "system.plugins" in granted
        else -> true
    }
}
