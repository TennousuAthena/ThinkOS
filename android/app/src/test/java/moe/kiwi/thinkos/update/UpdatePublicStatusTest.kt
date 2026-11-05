package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class UpdatePublicStatusTest {
    private val fixture = UpdateFixture()
    private val catalog = fixture.verified()
    private val policy = UpdatePolicy(true, "stable", listOf("https://mirror.example/ota/stable.json"), 600, 3600, 24000, 2000, 60, 3600, 5000, 2024, 1000, 1000)
    private val env = UpdateSession.Environment(Attention.ENGAGED, false, false)
    private fun available() = UpdateController.Status(UpdateStateDocument(1, "stable", "AVAILABLE", 0, catalog.document.release.size,
        seq = catalog.document.seq, catalog_digest = catalog.digest), configured = true,
        offer = UpdateSession.Offer(catalog.digest, catalog.document.release.copy(notes = mapOf("zh-Hans" to "中文更新", "en" to "English changes")), null))
    private fun read(state: UpdateController.Status, environment: UpdateSession.Environment = env, locale: Locale = Locale.SIMPLIFIED_CHINESE) =
        UpdatePublicStatus.read(state, environment, policy, "0.1.1", 1001, locale)

    @Test fun missingDeploymentKeysOfferNeitherNetworkActionsNorInstallation() {
        val status = read(available().copy(configured = false, offer = null, error = "keys"))
        assertEquals("unavailable", status.phase); assertEquals("keys", status.error)
        assertFalse(status.can_check); assertFalse(status.can_download); assertFalse(status.can_install)
    }
    @Test fun idleWithoutAuthenticatedMetadataDoesNotClaimUpToDate() {
        val status = read(available().copy(state = UpdateStateDocument(1, "stable", "IDLE", 0, 0), offer = null))
        assertEquals("idle", status.phase); assertNull(status.version); assertNull(status.notes)
    }
    @Test fun eligibleMetadataCarriesChineseNotesAndManualDownloadDespiteAttention() {
        val status = read(available())
        assertEquals(catalog.document.release.version, status.version); assertEquals("中文更新", status.notes)
        assertTrue(status.can_check); assertTrue(status.can_download); assertFalse(status.can_install)
        assertEquals("English changes", read(available(), locale = Locale.FRENCH).notes)
    }
    @Test fun videoCastAndForeignForegroundCannotBeBypassedByManualCapability() {
        for ((environment, reason) in listOf(env.copy(video = true) to "video", env.copy(casting = true) to "casting", env.copy(foreign = true) to "foreign")) {
            val status = read(available(), environment)
            assertFalse(status.can_download); assertEquals("paused", status.phase); assertEquals(reason, status.blocked)
        }
    }
    @Test fun staleOfferDigestCannotExposeVersionNotesOrClaimReady() {
        val stale = available().copy(state = available().state.copy(phase = "READY", catalog_digest = "f".repeat(64)))
        val status = read(stale)
        assertEquals("unavailable", status.phase); assertNull(status.version); assertNull(status.notes); assertFalse(status.can_download)
    }
    @Test fun onlyAuthenticatedVersionIneligibilityMeansUpToDate() {
        val state = available()
        assertEquals("up_to_date", read(state.copy(offer = state.offer!!.copy(ineligible = UpdateCatalog.Ineligible.VERSION))).phase)
        val incompatible = read(state.copy(offer = state.offer!!.copy(ineligible = UpdateCatalog.Ineligible.SDK)))
        assertEquals("unavailable", incompatible.phase); assertEquals("incompatible", incompatible.error)
    }
    @Test fun readModelRoundTripsClosedSchemaWithoutPathsUrlsOrKeys() {
        val state = available().copy(checking = true, busy = true, manual = true)
        val value = read(state)
        val json = UpdateModels.writeUpdateStatusDocument(value)
        assertEquals(value, UpdateModels.readUpdateStatusDocument(UpdateJson.read(json.toString().toByteArray(), 16384)))
        assertEquals("checking", value.phase); assertFalse(value.can_check); assertFalse(value.can_download)
        assertFalse(json.toString().contains("https://")); assertFalse(json.has("path")); assertFalse(json.has("signer"))
    }
    @Test fun moduleRequirementsDoNotMasqueradeAsInstalledComponentsOrDownloadCapability() {
        val module = fixture.verified(fixture.catalog().apply { getJSONObject("release").getJSONArray("modules").put(fixture.module()) })
        val state = available().copy(state = available().state.copy(catalog_digest = module.digest), offer = UpdateSession.Offer(module.digest, module.document.release, null))
        val projected = read(state)
        assertEquals("requires_updater", projected.components.single().state)
        assertFalse(projected.can_download); assertFalse(projected.can_install)
    }
    @Test fun automaticWorkLeavesManualCheckAvailableButManualWorkDoesNot() {
        val automatic = available().copy(checking = true, busy = true, manual = false)
        assertTrue(read(automatic).can_check)
        assertTrue(read(automatic).can_download)
        assertFalse(read(automatic.copy(manual = true)).can_check)
        assertFalse(read(automatic.copy(manual = true)).can_download)
    }

    @Test fun nativeInstallerNeedsReadyCapabilityAndNoAudioVideoCastOrForeignSession() {
        val ready = available().copy(state = available().state.copy(phase = "READY"), installable = true)
        assertTrue(read(ready).can_install)
        assertFalse(read(ready.copy(installable = false)).can_install)
        assertFalse(read(ready.copy(busy = true)).can_install)
        for (blocked in listOf(env.copy(media = true), env.copy(video = true), env.copy(casting = true), env.copy(foreign = true)))
            assertFalse(read(ready, blocked).can_install)
        val audio = read(ready, env.copy(media = true))
        assertEquals("media", audio.blocked)
        assertFalse(read(ready.copy(state = ready.state.copy(phase = "INSTALLING"))).can_check)
    }

    @Test fun crashLoopBlacklistIsNotPresentedAsUpToDateOrACompatibleDownload() {
        val value = read(available().let { it.copy(offer = it.offer!!.copy(ineligible = UpdateCatalog.Ineligible.BLACKLIST)) })
        assertEquals("unavailable", value.phase); assertEquals("blacklisted", value.error)
        assertFalse(value.can_download); assertFalse(value.can_install)
    }

    @Test fun componentMetadataIsBoundToAuthenticatedTargetAndNeverInventsBytesOrResults() {
        val catalog = fixture.verified(fixture.catalog().apply { getJSONObject("release").getJSONArray("modules").put(fixture.module()) })
        val module = catalog.document.release.modules.single()
        val transferred = module.size / 2
        val state = available().copy(state = available().state.copy(catalog_digest = catalog.digest),
            offer = UpdateSession.Offer(catalog.digest, catalog.document.release, null))
        fun read(view: ModuleUpdateRuntime.View) = UpdatePublicStatus.read(state, env, policy, "0.1.1", 1001,
            Locale.SIMPLIFIED_CHINESE, view).components.single()
        val view = ModuleUpdateRuntime.View(available = true, metadataDigest = catalog.digest,
            installedCodes = mapOf(module.id to 1), downloaded = mapOf(module.id to transferred), sizes = mapOf(module.id to module.size),
            states = mapOf(module.id to "downloading"), results = mapOf(module.id to "rolled_back"))
        val current = read(view)
        assertEquals(1, current.installed_code)
        assertEquals(transferred, current.downloaded)
        assertEquals(module.size, current.size)
        assertEquals("rolled_back", current.result)
        val stale = read(view.copy(metadataDigest = "f".repeat(64)))
        assertEquals(1, stale.installed_code) // Current root installation is not target-scoped.
        assertNull(stale.downloaded); assertNull(stale.result)
        assertNull(read(view.copy(downloaded = mapOf(module.id to module.size + 1))).downloaded)
        assertNull(read(view.copy(sizes = mapOf(module.id to module.size + 1))).downloaded)
        assertNull(read(view.copy(results = mapOf(module.id to "not-confirmed"))).result)
        val document = UpdatePublicStatus.read(state, env, policy, "0.1.1", 1001, Locale.SIMPLIFIED_CHINESE, view)
        assertEquals(document, UpdateModels.readUpdateStatusDocument(UpdateModels.writeUpdateStatusDocument(document)))
    }

    @Test fun keylessReadableRootInventorySurvivesAbsentChannelKeysSourcesAndAuthenticatedOffer() {
        val missing = available().copy(configured = false, offer = null, error = "keys",
            state = UpdateStateDocument(1, "stable", "IDLE", 0, 0))
        val source = ModuleUpdateRuntime.View(inventoryReadable = true, inventoryManaged = false, rootKeys = 0,
            installedCodes = mapOf("timesync" to 1, "thinkos-certs" to 20261020, "thinkos-cast" to 0,
                "thinkos-shell" to -1, "untrusted-module" to 999),
            states = mapOf("timesync" to "ready"), results = mapOf("timesync" to "updated"),
            downloaded = mapOf("timesync" to 100), sizes = mapOf("timesync" to 100))
        val status = UpdatePublicStatus.read(missing, env, policy.copy(sources = emptyList()), "0.1.8", 1008,
            Locale.SIMPLIFIED_CHINESE, source)
        val inventory = requireNotNull(status.inventory)
        assertEquals("read_only", inventory.updater)
        assertEquals(7, inventory.modules.size)
        assertEquals(1, inventory.modules.single { it.id == "timesync" }.installed_code)
        assertEquals(20261020, inventory.modules.single { it.id == "thinkos-certs" }.installed_code)
        assertEquals(0, inventory.modules.single { it.id == "thinkos-cast" }.installed_code)
        assertEquals(-1, inventory.modules.single { it.id == "thinkos-shell" }.installed_code)
        assertNull(inventory.modules.single { it.id == "thinkos-updater" }.installed_code)
        assertFalse(inventory.modules.any { it.id == "untrusted-module" })
        assertTrue(status.components.isEmpty())
        assertNull(status.version); assertNull(status.code); assertNull(status.result)
        assertFalse(status.can_check); assertFalse(status.can_download); assertFalse(status.can_install)
        val encoded = UpdateModels.writeUpdateStatusDocument(status)
        val entries = encoded.getJSONObject("inventory").getJSONArray("modules")
        for (index in 0 until entries.length()) {
            val entry = entries.getJSONObject(index)
            assertTrue(entry.keys().asSequence().all { it in setOf("id", "installed_code") })
        }
        assertEquals(status, UpdateModels.readUpdateStatusDocument(encoded))
    }

    @Test fun unreadableInventoryDiscardsStaleCodesAndNeverPretendsModulesAreAbsent() {
        val source = ModuleUpdateRuntime.View(inventoryReadable = false, inventoryManaged = true,
            installedCodes = mapOf("timesync" to 999))
        val status = UpdatePublicStatus.read(available().copy(offer = null), env, policy, "0.1.8", 1008,
            Locale.SIMPLIFIED_CHINESE, source)
        assertEquals("unavailable", status.inventory!!.updater)
        assertTrue(status.inventory!!.modules.isEmpty())
        assertTrue(status.components.isEmpty())
        val old = UpdateModels.writeUpdateStatusDocument(status).apply { remove("inventory") }
        assertNull(UpdateModels.readUpdateStatusDocument(old).inventory)
    }

    @Test fun inventoryCapabilityNeverGrantsActionAdmissionOrConstructsTargets() {
        val hint = available().copy(state = available().state.copy(phase = "READY"), offer = null, installable = true)
        for (managed in listOf(false, true)) {
            val source = ModuleUpdateRuntime.View(inventoryReadable = true, inventoryManaged = managed,
                installedCodes = mapOf("timesync" to 1))
            val status = UpdatePublicStatus.read(hint, env, policy, "0.1.8", 1008, Locale.SIMPLIFIED_CHINESE, source)
            assertEquals(if (managed) "ready" else "read_only", status.inventory!!.updater)
            assertTrue(status.components.isEmpty())
            assertFalse(status.can_download); assertFalse(status.can_install)
            assertNull(status.version); assertNull(status.code)
        }
        val ordinary = available().copy(installable = true)
        val plain = UpdatePublicStatus.read(ordinary, env, policy, "0.1.8", 1008, Locale.SIMPLIFIED_CHINESE, ModuleUpdateRuntime.View())
        val inventory = UpdatePublicStatus.read(ordinary, env, policy, "0.1.8", 1008, Locale.SIMPLIFIED_CHINESE,
            ModuleUpdateRuntime.View(inventoryReadable = true, inventoryManaged = true, installedCodes = mapOf("timesync" to 1)))
        assertEquals(plain.can_check, inventory.can_check)
        assertEquals(plain.can_download, inventory.can_download)
        assertEquals(plain.can_install, inventory.can_install)
    }
}
