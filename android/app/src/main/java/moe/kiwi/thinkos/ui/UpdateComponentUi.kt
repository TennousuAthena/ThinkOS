package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.update.UpdateComponentStatus
import moe.kiwi.thinkos.update.UpdateSystemInventory

/** Presentation decisions only. No IO, privileged actions or inferred progress. */
internal object UpdateComponentUi {
    enum class Installed { UNKNOWN, ABSENT, BUILD }
    data class Progress(val visible: Boolean, val fraction: Double?)
    data class Row(val id: String, val installedCode: Int?, val target: UpdateComponentStatus?)
    /** Inventory can create an installed-only row, never an update target.
     * Legacy snapshots without inventory retain their old installed-code hint. */
    fun rows(targets: List<UpdateComponentStatus>, inventory: UpdateSystemInventory?): List<Row> {
        val installed = if (inventory?.updater in setOf("ready", "read_only")) inventory!!.modules.associateBy { it.id } else emptyMap()
        val offers = targets.associateBy { it.id }
        return (installed.keys + offers.keys).distinct().take(7).map { id ->
            Row(id, if (inventory == null) offers[id]?.installed_code else installed[id]?.installed_code, offers[id])
        }
    }
    fun installed(code: Int?) = when {
        code == null || code < 0 -> Installed.UNKNOWN
        code == 0 -> Installed.ABSENT
        else -> Installed.BUILD
    }
    fun progress(value: UpdateComponentStatus): Progress {
        val bytes = value.downloaded
        val size = value.size
        val fraction = if (bytes != null && size != null && size > 0 && bytes in 0..size)
            bytes.toDouble() / size else null
        return Progress(fraction != null || value.state == "downloading", fraction)
    }
    fun name(id: String): Int = when (id) {
        "thinkos-updater" -> R.string.update_component_updater
        "thinkos-cast" -> R.string.update_component_cast
        "thinkos-immersive" -> R.string.update_component_immersive
        "thinkos-shell" -> R.string.update_component_shell
        "thinkos-certs" -> R.string.update_component_certs
        "thinkos-power" -> R.string.update_component_power
        "timesync" -> R.string.update_component_timesync
        else -> R.string.update_components
    }
    fun state(value: String): Int = when (value) {
        "requires_updater" -> R.string.update_component_requires_updater
        "current" -> R.string.update_component_current
        "skipped" -> R.string.update_component_skipped
        "available" -> R.string.update_component_available
        "downloading" -> R.string.update_component_downloading
        "ready" -> R.string.update_component_ready
        "staged" -> R.string.update_component_staged
        "failed" -> R.string.update_component_failed
        else -> R.string.update_component_unknown
    }
    fun result(value: String?): Int? = when (value) {
        "updated" -> R.string.update_component_updated
        "rolled_back" -> R.string.update_component_rolled_back
        "install_failed" -> R.string.update_component_install_failed
        "rollback_failed" -> R.string.update_component_rollback_failed
        "cancelled" -> R.string.update_component_cancelled
        else -> null
    }
}
