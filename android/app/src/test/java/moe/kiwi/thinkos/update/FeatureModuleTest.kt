package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Test

class FeatureModuleTest {
    private fun policy() = UpdatePolicy(false, "stable", listOf("https://example.invalid/ota/stable.json"),
        600, 3600, 24000, 2000, 60, 3600, 5000, 2024, 1000, 1000)
    private fun target(id: String = FeatureScope.MODULE, code: Int = 2) =
        FeatureTargetBinding(id, "v$code", code, "a".repeat(64), 10, 0, 0)
    private fun binding() = FeaturePlanBinding("1".repeat(32), "2".repeat(64), "stable",
        UpdateApkIdentity("0.1.0", 1000, "3".repeat(64), 100, "4".repeat(64)), "5".repeat(32), listOf(target()))
    private fun pending(feature: String? = FeatureScope.FEATURE, mode: String = "manual",
                        selected: List<ModuleUpdateTarget> = listOf(ModuleUpdateTarget(FeatureScope.MODULE, 2))) =
        ModuleUpdatePending(1, "a".repeat(32), "", "", "b".repeat(64), "stable",
            UpdateApkIdentity("0.1.0", 1000, "c".repeat(64), 100, "d".repeat(64)),
            UpdateApkIdentity("0.1.0", 1000, "c".repeat(64), 100, "d".repeat(64)),
            mode, "PREPARED", "", selected = selected, staged = emptyList(),
            created = UpdateInstallTime(1000, 7, null), attempts = 0, max_attempts = 3,
            health_ms = 600000, poll_ms = 5000, feature = feature)

    @Test fun nativePlanCanBeConsumedOnceAndRevocationNeverCreatesDurableConsent() {
        val scope = FeatureScope("window", policy(), "a".repeat(32))
        assertTrue(scope.valid); assertFalse(scope.durable)
        assertTrue(scope.consume()); assertFalse(scope.consume())
        assertFalse(scope.durable) // Consuming alone is not ModuleUpdateStore.prepare.
        scope.prepared(); assertTrue(scope.durable)
        scope.revoke(); assertFalse(scope.valid)
        assertTrue(scope.durable) // Closing cannot erase an already committed transaction.
        val cancelled = FeatureScope("other", policy(), "b".repeat(32))
        cancelled.revoke(); assertFalse(cancelled.consume()); assertFalse(cancelled.durable)
    }

    @Test fun fingerprintBindsCatalogApkInventoryNonceAndEveryActualSelectedModule() {
        val base = binding()
        val original = base.fingerprint()
        val variants = listOf(base.copy(nonce = "6".repeat(32)), base.copy(catalogDigest = "7".repeat(64)),
            base.copy(channel = "beta"), base.copy(previousRoot = "8".repeat(32)),
            base.copy(apk = base.apk.copy(sha256 = "9".repeat(64))),
            base.copy(apk = base.apk.copy(size = 101)), base.copy(apk = base.apk.copy(signer = "f".repeat(64))),
            base.copy(selected = listOf(target(code = 3))),
            base.copy(selected = listOf(target().copy(floor = 1))),
            base.copy(selected = listOf(target().copy(installedCode = 1))),
            base.copy(selected = listOf(target(), target("timesync"))))
        for (variant in variants) assertNotEquals(original, variant.fingerprint())
        val both = base.copy(selected = listOf(target(), target("timesync")))
        assertEquals(both.fingerprint(), both.copy(selected = both.selected.reversed()).fingerprint())
    }

    @Test fun onlyFinalManualCastTransactionMayPersistFeatureMarker() {
        ModuleUpdateStore.validate(pending())
        ModuleUpdateStore.validate(pending(feature = null, mode = "automatic"))
        for (bad in listOf(pending(mode = "automatic"), pending(selected = emptyList()), pending(feature = "anything"))) {
            try { ModuleUpdateStore.validate(bad); fail("Invalid feature consent accepted") }
            catch (_: IllegalArgumentException) { }
        }
        val roundTrip = UpdateModels.readModuleUpdatePending(UpdateModels.writeModuleUpdatePending(pending()))
        assertEquals(FeatureScope.FEATURE, roundTrip.feature)
        assertFalse(UpdateModels.writeModuleUpdatePending(pending(feature = null)).has("feature"))
    }
}
