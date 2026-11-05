package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class UpdateNoticeTest {
    private val now = UpdateInstallTime(1000, 1, 1_000_000)
    private val result = UpdateInstallResult("a".repeat(32), "updated", 1005, "0.1.5", now, 86_400_000,
        mapOf("zh-Hans" to "中文说明", "en" to "English notes"), needs_recovery_clear = false)
    private fun snapshot(value: UpdateInstallResult?) = UpdateInstallStore.Snapshot(null, UpdateInstallHistory(1, emptyList(), value))
    @Test fun anInstallResultIsRequiredAndChineseFallbackIsStable() {
        assertNull(UpdateNotice.read(snapshot(null), 1005, now, Locale.CHINA))
        assertEquals("中文说明", UpdateNotice.read(snapshot(result), 1005, now, Locale.CHINA)!!.notes)
        assertEquals("English notes", UpdateNotice.read(snapshot(result), 1005, now, Locale.FRENCH)!!.notes)
    }
    @Test fun oneDayUsesMonotonicTimeDespiteWallJumpsAndExpiresAtTheBoundary() {
        assertNotNull(UpdateNotice.read(snapshot(result), 1005, now.copy(elapsed = 86_400_999, wall = Long.MAX_VALUE), Locale.CHINA))
        assertNull(UpdateNotice.read(snapshot(result), 1005, now.copy(elapsed = 86_401_000), Locale.CHINA))
        assertNull(UpdateNotice.read(snapshot(result), 1005, now.copy(boot = 2, wall = null), Locale.CHINA))
        assertNotNull(UpdateNotice.read(snapshot(result), 1005, now.copy(boot = 2, elapsed = 0, wall = 1_001_000), Locale.CHINA))
    }
    @Test fun anotherInstalledVersionCannotReuseAHealthyResultButRollbackNamesTheFailedVersion() {
        assertNull(UpdateNotice.read(snapshot(result), 1006, now, Locale.CHINA))
        val rollback = result.copy(kind = "rolled_back", reason = "crash_loop")
        assertEquals("rolled_back", UpdateNotice.read(snapshot(rollback), 1004, now, Locale.CHINA)!!.kind)
        assertEquals("0.1.5", UpdateNotice.read(snapshot(rollback), 1004, now, Locale.CHINA)!!.version)
    }
}
