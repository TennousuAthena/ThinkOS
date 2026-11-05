package moe.kiwi.thinkos.shell

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.kiwi.thinkos.ThinkApplication

/** Root's fixed transaction-complete notification. Non-exported; no input or
 * navigation request. Read the verified policy again after its last property write. */
class ShellChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "moe.kiwi.thinkos.SHELL_CHANGED") return
        if (!(context.applicationContext as ThinkApplication).updateGuard.allowsRuntime) return
        (context.applicationContext as ThinkApplication).runtime.foreignPanel.sync()
    }
}
