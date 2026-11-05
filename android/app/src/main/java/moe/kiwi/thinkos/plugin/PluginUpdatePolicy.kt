package moe.kiwi.thinkos.plugin

/** The timestamp belongs to a VM generation and is supplied only by OS UI events.
 * Lua arguments and plugin timer/command/event handlers cannot mint a tap. */
internal object PluginUpdatePolicy {
    // Native link/toggle activation emits a boolean form.change. Text editing,
    // async place results, sliders, layout and navigation do not mint a tap.
    fun userEvent(name: String, args: Array<Any?>) = name == "tap" ||
        name == "form.change" && args.size == 2 && args[1] is Boolean
    fun requireRead(api: Int, granted: Set<String>, factory: Boolean) {
        require(api >= 10) { "update requires API 10" }
        PluginAuthority.requireSystem("system.update", granted, factory)
    }
    fun fresh(tap: Long?, now: Long, graceMillis: Long): Boolean =
        graceMillis in 1..5000 && tap != null && tap >= 0 && now >= tap && now - tap <= graceMillis
}
