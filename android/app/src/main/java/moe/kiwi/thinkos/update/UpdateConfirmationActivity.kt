package moe.kiwi.thinkos.update

import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Bundle
import kotlinx.coroutines.*
import moe.kiwi.thinkos.MainActivity
import moe.kiwi.thinkos.shell.KioskState

/** Only an explicit manual install may open Android's own confirmation UI.
 * Its intent comes from the private installer receipt, never a plugin or URL. */
class UpdateConfirmationActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var opened = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        opened = savedInstanceState?.getBoolean("opened") == true
        if (opened) return
        scope.launch {
            try {
                val pending = UpdateIo.run { UpdateInstallContext.store(this@UpdateConfirmationActivity).snapshot().pending }
                require(pending?.phase == "INSTALLING" && pending.mode == "manual" && pending.transaction_id == intent.getStringExtra("transaction") &&
                    pending.session_id == intent.getIntExtra("session", -1))
                @Suppress("DEPRECATION")
                val confirmation = requireNotNull(intent.getParcelableExtra<Intent>("confirmation"))
                val resolved = requireNotNull(confirmation.resolveActivity(packageManager))
                require(packageManager.getApplicationInfo(resolved.packageName, 0).flags and
                    (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0)
                KioskState.allowSystemSettings()
                KioskState.enterForeign(0)
                opened = true
                @Suppress("DEPRECATION")
                startActivityForResult(confirmation, 1)
            } catch (error: Exception) {
                android.util.Log.e("ThinkOS", "Unable to open manual update confirmation", error)
                finish()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putBoolean("opened", opened); super.onSaveInstanceState(outState) }
    @Deprecated("API27 platform confirmation result")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        KioskState.leaveForeign()
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
