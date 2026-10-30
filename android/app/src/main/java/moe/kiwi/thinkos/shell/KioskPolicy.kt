package moe.kiwi.thinkos.shell

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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

    val isDeviceOwner: Boolean get() = dpm.isDeviceOwnerApp(context.packageName)

    /** Switch the policy on or off (the `kiosk.lock` setting); call on the main thread. */
    fun apply(activity: Activity, locked: Boolean) {
        if (!isDeviceOwner) {
            if (locked && !warned) { warned = true; Log.w("ThinkOS", "kiosk.lock is on but ThinkOS is not Device Owner; system UI is not locked") }
            return
        }
        runCatching {
            // Read first: dropping the package from the whitelist makes the system end lock-task by itself.
            val active = activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
            dpm.setLockTaskPackages(admin, if (locked) arrayOf(context.packageName) else emptyArray())
            dpm.setStatusBarDisabled(admin, locked)
            dpm.setKeyguardDisabled(admin, true)   // a wall display has no lock screen, whatever kiosk.lock says
            if (locked && !active) activity.startLockTask()
            else if (!locked && active) { activity.stopLockTask(); bringToFront(activity) }
        }.onFailure { Log.e("ThinkOS", "Unable to apply kiosk policy", it) }
    }

    /** Let Android's own settings be used (maintenance menu): lock-task would refuse to open them. */
    fun release(activity: Activity) {
        if (!isDeviceOwner) return
        runCatching {
            dpm.setStatusBarDisabled(admin, false)
            if (activity.getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE) activity.stopLockTask()
        }.onFailure { Log.e("ThinkOS", "Unable to release kiosk policy", it) }
    }

    /** Leaving lock-task makes the system raise the stock launcher; take the screen back (singleTask: no second instance). */
    private fun bringToFront(activity: Activity) {
        Handler(Looper.getMainLooper()).postDelayed({
            activity.startActivity(Intent(activity, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        }, 300)
    }

    private companion object { var warned = false }
}
