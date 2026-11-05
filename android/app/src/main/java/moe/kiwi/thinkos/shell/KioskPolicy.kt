package moe.kiwi.thinkos.shell

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.provider.Settings
import android.util.Log
import moe.kiwi.thinkos.MainActivity
import moe.kiwi.thinkos.admin.ThinkOSAdminReceiver

/**
 * Hard kiosk on top of the immersive flags. Needs Device Owner (set by a person, docs/07-build-deploy.md §4.3);
 * without it every call is a no-op and the immersive mode plus watchdog remain the only protection.
 * On API 27 lock-task mode blocks the status bar pull-down, Home, Recents and the navigation gestures as a whole.
 */
class KioskPolicy(private val context: Context) {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, ThinkOSAdminReceiver::class.java)
    private var homePinned = false

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /** A package replacement must not send HOME back to the factory launcher. */
    private fun pinHome() {
        if (homePinned) return
        val filter = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME); addCategory(Intent.CATEGORY_DEFAULT)
        }
        // API27 adds another rule on each call. Replace only our own rules so
        // activity recreation and repeated package replacements cannot grow it.
        dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
        dpm.addPersistentPreferredActivity(admin, filter, ComponentName(context, MainActivity::class.java))
        homePinned = true
        Log.i("ThinkOS", "Persistent HOME registered")
    }

    /**
     * The shared music/video folders need READ_EXTERNAL_STORAGE, and Android 8.1 only tells an app the name of the Wi-Fi network
     * it is on if it may see the location. A wall display has nobody to answer the system dialog, so a Device Owner grants both
     * silently; otherwise the dialog is shown on first use (storage) or the name is left out (network).
     */
    fun grantStorageRead() {
        if (!isDeviceOwner) return
        for (permission in listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            runCatching {
                if (context.checkSelfPermission(permission) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    dpm.setPermissionGrantState(admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
                }
            }.onFailure { Log.w("ThinkOS", "Unable to grant $permission as Device Owner", it) }
        }
    }

    /**
     * Switch the policy on or off (the `kiosk.lock` setting); call on the main thread. While the control sheet is on
     * ([panel]) Android's own notification shade stays blocked even when nothing else is locked: the pull-down is ThinkOS's.
     */
    fun apply(activity: Activity, locked: Boolean, panel: Boolean) {
        if (ManagementExit.isReleased(context)) return
        if (!isDeviceOwner) {
            if ((locked || panel) && !warned) { warned = true; Log.w("ThinkOS", "kiosk.lock or panel.enabled is on but ThinkOS is not Device Owner; Android's notification shade is not blocked") }
            return
        }
        runCatching { pinHome() }.onFailure { Log.e("ThinkOS", "Unable to keep ThinkOS as HOME", it) }
        runCatching {
            // Read first: dropping the package from the whitelist makes the system end lock-task by itself.
            val active = activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            dpm.setLockTaskPackages(admin, if (locked) arrayOf(context.packageName) else emptyArray())
            dpm.setStatusBarDisabled(admin, locked || panel)
            val plugged = BatteryManager.BATTERY_PLUGGED_AC or BatteryManager.BATTERY_PLUGGED_USB or BatteryManager.BATTERY_PLUGGED_WIRELESS
            if (Settings.Global.getInt(context.contentResolver, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, 0) != plugged) {
                dpm.setGlobalSetting(admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN, plugged.toString())
            }
            dpm.setKeyguardDisabled(admin, true)   // a wall display has no lock screen, whatever kiosk.lock says
            // Settings can change while an intentional Android app has the
            // screen. Its hidden ThinkOS Activity cannot enter Lock Task;
            // window-focus relock handles the real return instead.
            if (locked && !active && KioskState.mayRelock() && activity.hasWindowFocus()) activity.startLockTask()
            else if (!locked && active) { activity.stopLockTask(); bringToFront(activity) }
        }.onFailure { Log.e("ThinkOS", "Unable to apply kiosk policy", it) }
    }

    /**
     * An Android app is about to come to the front because the user chose it (ADR-035). Lock-task would refuse to start it, and
     * on API 27 it blocks Home, so an app that ignores Back could not be left. Only the lock ends; the notification shade stays
     * blocked and the control sheet stays the way to reach the device's controls.
     */
    fun leaveLock(activity: Activity) {
        if (!isDeviceOwner) return
        runCatching {
            if (activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) activity.stopLockTask()
        }.onFailure { Log.e("ThinkOS", "Unable to leave lock-task", it) }
    }

    private val retries = Handler(Looper.getMainLooper())

    /**
     * Lock-task refuses to start while the task is not yet in front ("Invalid task, not in foreground"), and on the way back from
     * another app's screen ThinkOS is resumed, and even gets the window focus, a moment before it is: then the lock simply never
     * came back. So it is tried again from the window focus, a few times, until Android takes it. Does nothing when the lock is
     * already on, or when the lock is off on purpose (an Android screen was opened, [KioskState.mayRelock]).
     */
    fun relock(activity: Activity, locked: Boolean) {
        retries.removeCallbacksAndMessages(null)
        if (ManagementExit.isReleased(context)) return
        relockAttempt(activity, locked, 0)
    }

    private fun relockAttempt(activity: Activity, locked: Boolean, attempt: Int) {
        if (!isDeviceOwner || !locked || !KioskState.mayRelock() || activity.isFinishing || !activity.hasWindowFocus()) return
        try {
            if (activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) return
            activity.startLockTask()
            Log.i("ThinkOS", "Lock-task was off with ThinkOS in front; locked again (attempt ${attempt + 1})")
        } catch (refused: IllegalArgumentException) {
            if (attempt + 1 < RELOCK_ATTEMPTS) retries.postDelayed({ relockAttempt(activity, locked, attempt + 1) }, RELOCK_DELAY_MILLIS)
            else Log.w("ThinkOS", "Unable to lock again after ${attempt + 1} attempts: ${refused.message}")
        } catch (error: Exception) {
            Log.w("ThinkOS", "Unable to lock again", error)
        }
    }

    /** Let Android's own settings be used (maintenance menu): lock-task would refuse to open them. */
    fun release(activity: Activity) {
        if (!isDeviceOwner) return
        runCatching {
            dpm.setStatusBarDisabled(admin, false)
            if (activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) activity.stopLockTask()
        }.onFailure { Log.e("ThinkOS", "Unable to release kiosk policy", it) }
    }

    /** Called only by the native physical-touch confirmation. API27 still
     * requires owner identity when clearing persistent HOME, so do that before
     * clearDeviceOwnerApp. See docs/installation-guide.md, implementation notes. */
    @Suppress("DEPRECATION")
    internal fun surrender(activity: Activity, locked: Boolean, panel: Boolean): ManagementExitTransaction.Outcome {
        val previousMaintenance = KioskState.maintenanceUntil
        var previousPackages = emptyArray<String>()
        var wasLocked = false
        val outcome = ManagementExitTransaction.run(object : ManagementExitTransaction.Ports {
            override fun prepare() {
                ManagementExit.prepare(context)
                if (isDeviceOwner) previousPackages = dpm.getLockTaskPackages(admin)
                wasLocked = activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
                retries.removeCallbacksAndMessages(null)
                KioskState.maintenanceUntil = Long.MAX_VALUE
            }
            override fun isOwner() = isDeviceOwner
            override fun releasePolicies() {
                // Stop the task before withdrawing its whitelist; API27 may
                // otherwise finish a whitelisted locked task automatically.
                if (wasLocked) activity.stopLockTask()
                dpm.setLockTaskPackages(admin, emptyArray())
                check(dpm.setStatusBarDisabled(admin, false)) { "Status bar release refused" }
                check(dpm.setKeyguardDisabled(admin, false)) { "Keyguard release refused" }
                dpm.clearPackagePersistentPreferredActivities(admin, context.packageName)
                homePinned = false
            }
            override fun clearOwner() = dpm.clearDeviceOwnerApp(context.packageName)
            override fun isSelfAdminActive() = dpm.isAdminActive(admin)
            override fun removeSelfAdmin() {
                check(!isDeviceOwner) { "Device Owner must be absent before removing its admin" }
                // Fixed own receiver only. The API is asynchronous; the
                // transaction's second isAdminActive read decides completion.
                dpm.removeActiveAdmin(admin)
            }
            override fun restorePolicies() {
                KioskState.maintenanceUntil = previousMaintenance
                homePinned = false
                pinHome()
                dpm.setLockTaskPackages(admin, previousPackages)
                check(dpm.setStatusBarDisabled(admin, locked || panel)) { "Status bar restore refused" }
                check(dpm.setKeyguardDisabled(admin, true)) { "Keyguard restore refused" }
                if (wasLocked && activity.hasWindowFocus()) activity.startLockTask()
                // Otherwise the normal focus callback retries after the error dialog closes.
            }
            override fun abort() { ManagementExit.abort(context) }
            override fun commit() = ManagementExit.commit(context)
            override fun finish() = ManagementExit.finish(context)
        })
        if (!outcome.released) KioskState.maintenanceUntil = previousMaintenance
        outcome.failure?.let { Log.e("ThinkOS", "Management exit incomplete; released=${outcome.released}", it) }
        return outcome
    }

    /** Leaving lock-task makes the system raise the stock launcher; take the screen back (singleTask: no second instance). */
    private fun bringToFront(activity: Activity) {
        Handler(Looper.getMainLooper()).postDelayed({
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }, 300)
    }

    private companion object {
        var warned = false
        const val RELOCK_ATTEMPTS = 10
        const val RELOCK_DELAY_MILLIS = 300L
    }
}
