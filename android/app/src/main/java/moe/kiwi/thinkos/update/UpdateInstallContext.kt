package moe.kiwi.thinkos.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import android.provider.Settings
import moe.kiwi.thinkos.core.WallClock
import java.io.File

/** Bootstrap reads need no RuntimeHost, plugin, network, or native library. */
internal object UpdateInstallContext {
    fun directory(context: Context) = File(context.filesDir, "update")
    fun store(context: Context) = UpdateInstallStore(directory(context))
    fun now(context: Context) = UpdateInstallTime(SystemClock.elapsedRealtime(),
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }.getOrDefault(-1)
            .takeIf { it >= 0 }?.toLong(),
        // Before the runtime restores its trusted clock, omit wall time rather
        // than infer an age from this device's unreliable hardware clock.
        WallClock.now().takeIf { WallClock.synced })

    @Suppress("DEPRECATION")
    fun running(context: Context): UpdateInstallStore.Running {
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        val signer = info.signatures?.singleOrNull() ?: error("Installed APK must have one signer")
        return UpdateInstallStore.Running(requireNotNull(info.versionName), info.versionCode, UpdateCatalog.sha256(signer.toByteArray()))
    }
}
