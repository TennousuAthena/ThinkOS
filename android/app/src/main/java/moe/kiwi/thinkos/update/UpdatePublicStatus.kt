package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.i18n.LocaleChain
import java.util.Locale

/** Bounded read model shared by Lua and native recovery. No paths, URLs, keys,
 * signatures, device identifiers or mutable catalog objects cross this boundary. */
internal object UpdatePublicStatus {
    fun read(status: UpdateController.Status, environment: UpdateSession.Environment, policy: UpdatePolicy,
             currentVersion: String, currentCode: Int, locale: Locale, modules: ModuleUpdateRuntime.View? = null): UpdateStatusDocument {
        val state = status.state
        val offer = status.offer?.takeIf { it.digest == state.catalog_digest }
        val eligible = offer?.ineligible == null && offer != null
        val blocked = when {
            environment.casting -> "casting"
            environment.video -> "video"
            environment.foreign -> "foreign"
            environment.media && state.phase in setOf("READY", "INSTALLING") -> "media"
            else -> null
        }
        val sources = policy.sources.isNotEmpty()
        val busyManual = status.busy && status.manual
        val error = when {
            modules?.error != null -> modules.error
            !status.configured -> status.error ?: "unavailable"
            !sources -> "sources"
            offer?.ineligible == UpdateCatalog.Ineligible.BLACKLIST -> "blacklisted"
            offer?.ineligible != null && offer.ineligible != UpdateCatalog.Ineligible.VERSION -> "incompatible"
            else -> state.error ?: status.error
        }
        val phase = when {
            modules?.error != null -> "failed"
            modules?.active == true -> "installing"
            modules?.completedDigest != null && modules.completedDigest == state.catalog_digest -> "up_to_date"
            !status.configured -> "unavailable"
            status.checking -> "checking"
            offer?.ineligible == UpdateCatalog.Ineligible.VERSION -> "up_to_date"
            offer?.ineligible != null -> "unavailable"
            state.phase in setOf("READY", "INSTALLING") && !eligible -> "unavailable"
            state.phase == "AVAILABLE" && blocked != null -> "paused"
            !sources && offer == null -> "unavailable"
            else -> state.phase.lowercase(Locale.ROOT)
        }
        val notes = if (eligible) LocaleChain.candidates(locale).firstNotNullOfOrNull { offer!!.release.notes[it] } else null
        val moduleAction = modules?.active != true && (modules?.completedDigest == null || modules.completedDigest != state.catalog_digest)
        val metadata = modules?.takeIf { offer != null && it.metadataDigest == offer.digest }
        return UpdateStatusDocument(1, phase, state.channel, currentVersion, currentCode, status.configured,
            status.configured && sources && !busyManual && modules?.active != true && phase != "installing",
            status.configured && eligible && moduleAction && (offer!!.release.modules.isEmpty() || modules != null) && !busyManual && environment.allows(true) && state.size > 0 &&
                state.phase in setOf("AVAILABLE", "FAILED"),
            status.configured && eligible && moduleAction && status.installable && !status.busy && state.phase == "READY" &&
                (offer!!.release.modules.isEmpty() || modules != null) && environment.allowsInstall(), state.downloaded, state.size,
            offer?.release?.modules.orEmpty().map { module ->
                val installed = modules?.installedCodes?.get(module.id)?.takeIf { it >= -1 }
                val componentState = when {
                    modules?.active != true && installed != null && installed >= module.code -> "current"
                    metadata?.states?.get(module.id) in COMPONENT_STATES -> metadata!!.states.getValue(module.id)
                    modules?.available == true -> "available"
                    else -> "requires_updater"
                }
                val downloaded = metadata?.downloaded?.get(module.id)?.takeIf {
                    metadata?.sizes?.get(module.id) == module.size && it in 0..module.size
                }
                val result = metadata?.results?.get(module.id)?.takeIf { it in COMPONENT_RESULTS }
                UpdateComponentStatus(module.id, module.version, module.code, componentState,
                    installed_code = installed, downloaded = downloaded, size = module.size, result = result)
            },
            version = offer?.release?.version?.takeIf { eligible }, code = offer?.release?.code?.takeIf { eligible },
            notes = notes, error = error, blocked = blocked, inventory = inventory(modules))
    }
    private fun inventory(source: ModuleUpdateRuntime.View?): UpdateSystemInventory {
        if (source?.inventoryReadable != true) return UpdateSystemInventory("unavailable", emptyList())
        return UpdateSystemInventory(if (source.inventoryManaged) "ready" else "read_only", SYSTEM_COMPONENTS.map { id ->
            UpdateInstalledComponent(id, installed_code = source.installedCodes[id]?.takeIf { it >= -1 })
        })
    }
    private val SYSTEM_COMPONENTS = listOf("thinkos-updater", "thinkos-cast", "thinkos-immersive", "thinkos-shell", "thinkos-certs", "thinkos-power", "timesync")
    private val COMPONENT_STATES = setOf("requires_updater", "current", "skipped", "available", "downloading", "ready", "staged", "failed")
    private val COMPONENT_RESULTS = setOf("updated", "rolled_back", "install_failed", "rollback_failed", "cancelled")
}
