package moe.kiwi.thinkos.plugin

/** Public mode reads do not grant device control. Only a factory system.device UI may mutate it. */
internal object DeviceModePolicy {
    fun requireRead(api: Int) { require(api >= 10) { "device mode requires API 10" } }
    fun requireWrite(api: Int, permissions: Set<String>, factory: Boolean, value: String, tap: Long?, now: Long) {
        requireRead(api)
        PluginAuthority.requireSystem("system.device", permissions, factory)
        require(value in setOf("day", "redshift", "sleep")) { "unknown device mode" }
        require(PluginUpdatePolicy.fresh(tap, now, 5_000)) { "device mode needs a recent touch" }
    }
}
