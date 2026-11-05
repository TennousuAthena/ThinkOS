package moe.kiwi.thinkos.ui

import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.update.UpdateComponentStatus
import moe.kiwi.thinkos.update.UpdateInstalledComponent
import moe.kiwi.thinkos.update.UpdateSystemInventory
import org.junit.Assert.*
import org.junit.Test

class UpdateComponentUiTest {
    private fun item(state: String = "downloading", downloaded: Long? = null, size: Long? = null) =
        UpdateComponentStatus("timesync", "v2", 2, state, downloaded = downloaded, size = size)

    @Test fun onlyRealBoundedByteCountsProducePercentages() {
        assertEquals(.25, UpdateComponentUi.progress(item(downloaded = 25, size = 100)).fraction!!, 0.0)
        assertEquals(0.0, UpdateComponentUi.progress(item(downloaded = 0, size = 100)).fraction!!, 0.0)
        assertEquals(1.0, UpdateComponentUi.progress(item(downloaded = 100, size = 100)).fraction!!, 0.0)
        for ((bytes, size) in listOf(null to 100L, 1L to null, -1L to 100L, 101L to 100L, 0L to 0L))
            assertNull(UpdateComponentUi.progress(item(downloaded = bytes, size = size)).fraction)
    }

    @Test fun pendingDownloadIsIndeterminateButStageNeverFabricatesCompletion() {
        val loading = UpdateComponentUi.progress(item())
        assertTrue(loading.visible); assertNull(loading.fraction)
        for (state in listOf("ready", "staged", "current", "failed", "skipped")) {
            val progress = UpdateComponentUi.progress(item(state))
            assertFalse(progress.visible); assertNull(progress.fraction)
        }
    }

    @Test fun missingAndUnknownInstalledBuildsRemainDistinct() {
        assertEquals(UpdateComponentUi.Installed.UNKNOWN, UpdateComponentUi.installed(null))
        assertEquals(UpdateComponentUi.Installed.UNKNOWN, UpdateComponentUi.installed(-1))
        assertEquals(UpdateComponentUi.Installed.ABSENT, UpdateComponentUi.installed(0))
        assertEquals(UpdateComponentUi.Installed.BUILD, UpdateComponentUi.installed(1))
    }

    @Test fun allSevenComponentsHaveNativeNamesAndAllConfirmedResultsHaveShortLabels() {
        val ids = listOf("thinkos-updater", "thinkos-cast", "thinkos-immersive", "thinkos-shell", "thinkos-certs", "thinkos-power", "timesync")
        assertEquals(7, ids.map(UpdateComponentUi::name).distinct().size)
        assertFalse(ids.any { UpdateComponentUi.name(it) == R.string.update_components })
        for (result in listOf("updated", "rolled_back", "install_failed", "rollback_failed", "cancelled"))
            assertNotNull(UpdateComponentUi.result(result))
        assertNull(UpdateComponentUi.result(null))
        assertNull(UpdateComponentUi.result("staged"))
        assertNull(UpdateComponentUi.result("unknown"))
    }

    @Test fun installedOnlyInventoryNeverCreatesTargetProgressOrSuccessRows() {
        val inventory = UpdateSystemInventory("read_only", listOf(UpdateInstalledComponent("timesync", 1),
            UpdateInstalledComponent("thinkos-cast", 0), UpdateInstalledComponent("thinkos-certs", -1)))
        val rows = UpdateComponentUi.rows(emptyList(), inventory)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.target == null })
        assertEquals(listOf(1, 0, -1), rows.map { it.installedCode })
    }

    @Test fun signedTargetsJoinCurrentInventoryWithoutBorrowingStaleInstalledOrTargetData() {
        val inventory = UpdateSystemInventory("ready", listOf(UpdateInstalledComponent("timesync", 7), UpdateInstalledComponent("thinkos-cast", 0)))
        val target = item("ready").copy(installed_code = 999, downloaded = 100, size = 100, result = "updated")
        val rows = UpdateComponentUi.rows(listOf(target), inventory)
        assertEquals(2, rows.size)
        assertEquals(7, rows.first { it.id == "timesync" }.installedCode)
        assertEquals(target, rows.first { it.id == "timesync" }.target)
        assertNull(rows.first { it.id == "thinkos-cast" }.target)
        val failed = UpdateComponentUi.rows(listOf(target), UpdateSystemInventory("unavailable", inventory.modules))
        assertEquals(1, failed.size)
        assertNull(failed.single().installedCode)
        val legacy = UpdateComponentUi.rows(listOf(target), null)
        assertEquals(999, legacy.single().installedCode)
    }
}
