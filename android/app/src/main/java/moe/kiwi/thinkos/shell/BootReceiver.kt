package moe.kiwi.thinkos.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.kiwi.thinkos.MainActivity
import moe.kiwi.thinkos.ThinkApplication
import moe.kiwi.thinkos.service.KioskService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (ManagementExit.isReleased(context)) return
        android.util.Log.i("ThinkOS", "Startup received ${intent.action}")
        if (!(context.applicationContext as ThinkApplication).updateGuard.allowsRuntime) return
        context.startForegroundService(Intent(context, KioskService::class.java))
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
