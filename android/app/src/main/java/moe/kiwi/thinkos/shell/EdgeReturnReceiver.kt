package moe.kiwi.thinkos.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.kiwi.thinkos.ThinkApplication

/** Explicit, non-exported bottom-to-Home signal from system_server (ADR-046). */
class EdgeReturnReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (ManagementExit.isReleased(context)) return
        if (!(context.applicationContext as ThinkApplication).updateGuard.allowsRuntime) return
        val host = (context.applicationContext as ThinkApplication).runtime
        if (!host.system.navUpEnabled && !host.foreignPanel.globalNavigation) return
        // This explicit user Home also leaves maintenance/settings. A cold
        // catalog starts at its home role when it arrives (DeckRouter.setPages).
        host.systemHomeGesture()
    }
}
