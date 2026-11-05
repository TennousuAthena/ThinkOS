package moe.kiwi.thinkos.plugin

/** Immutable OS-owned prompt, built only from an authenticated registry offer. */
data class RegistryConsent(
    val id: String,
    val name: String,
    val repo: String,
    val commit: String,
    val tier: String,
    val size: Long,
    val permissions: List<Pair<String, String>>,
)
