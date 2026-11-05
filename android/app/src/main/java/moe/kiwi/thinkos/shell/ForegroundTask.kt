package moe.kiwi.thinkos.shell

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.kiwi.thinkos.MainActivity

/** Return to the last resumed own task, preserving the single NavStack. */
internal object ForegroundTask {
    fun restore(context: Context, reason: String, clearAbove: Boolean = false): Boolean {
        if (ManagementExit.isReleased(context)) return false
        val taskId = KioskState.foregroundTaskId
        val moved = if (clearAbove || taskId < 0) false else runCatching {
            val manager = context.getSystemService(ActivityManager::class.java)
            if (manager.appTasks.none { it.taskInfo.id == taskId }) false
            else { manager.moveTaskToFront(taskId, 0); true }
        }.onFailure { Log.w("ThinkOS", "Unable to return to task $taskId", it) }.getOrDefault(false)
        val restored = moved || runCatching {
            val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // Explicit Home also removes a permission Activity above MainActivity.
            // singleTask reuses the same MainActivity and its one NavStack.
            if (clearAbove) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(intent)
            true
        }.onFailure { Log.w("ThinkOS", "Unable to recover ThinkOS for $reason", it) }.getOrDefault(false)
        Log.i("ThinkOS", "$reason task=$taskId moved=$moved")
        return restored
    }
}
