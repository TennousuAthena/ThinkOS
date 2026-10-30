package moe.kiwi.thinkos.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.kiwi.thinkos.MainActivity
import moe.kiwi.thinkos.service.KioskService

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startForegroundService(Intent(context, KioskService::class.java))
        if (KioskState.shouldRecover()) {
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
