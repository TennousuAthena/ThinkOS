package moe.kiwi.thinkos.shell

import android.os.SystemClock

/** Main-thread state shared by Activity and foreground watchdog. */
object KioskState {
    private val foreground = kotlinx.coroutines.flow.MutableStateFlow(false)
    internal val foregroundState: kotlinx.coroutines.flow.StateFlow<Boolean> = foreground
    var resumed = false; private set
    var foregroundTaskId = -1; private set
    fun noteResumed(taskId: Int) { foregroundTaskId = taskId; resumed = true; foreground.value = true }
    fun notePaused(taskId: Int) { if (foregroundTaskId == taskId) { resumed = false; foreground.value = false } }
    fun ownsForegroundTask(taskId: Int): Boolean = resumed && taskId >= 0 && foregroundTaskId == taskId
    var maintenanceUntil = 0L
    /**
     * While an Android app is in front because the user opened it (ADR-035), the watchdog leaves it alone until this moment
     * (elapsed realtime). 0 means no such app is out; [Long.MAX_VALUE] means it may stay as long as it likes.
     */
    var foreignUntil = 0L; private set
    val foreign: Boolean get() = foreignUntil != 0L
    fun allowSystemSettings() { maintenanceUntil = SystemClock.elapsedRealtime() + 300_000 }
    /** [returnAfterMillis] of 0 or less means ThinkOS never takes the screen back by itself. */
    fun enterForeign(returnAfterMillis: Long, now: Long = SystemClock.elapsedRealtime()) { foreignUntil = if (returnAfterMillis <= 0) Long.MAX_VALUE else now + returnAfterMillis }
    /** The app is still doing something the user wants (it is playing sound): the time starts again. */
    fun extendForeign(returnAfterMillis: Long, now: Long = SystemClock.elapsedRealtime()) { if (foreign && foreignUntil != Long.MAX_VALUE && returnAfterMillis > 0) foreignUntil = now + returnAfterMillis }
    /** An explicit user return wakes before the stage observes ASLEEP; watchdog expiry keeps normal idle policy. */
    fun returningBeforeTimeout(now: Long = SystemClock.elapsedRealtime()): Boolean = foreign && now < foreignUntil
    fun leaveForeign() { foreignUntil = 0L }
    /**
     * Whether ThinkOS may put the lock back on when its window has the focus. Not while an Android app or screen was opened on
     * purpose (the lock is off for it) or the recovery menu has just opened Android's settings.
     */
    fun mayRelock(): Boolean = !foreign && SystemClock.elapsedRealtime() >= maintenanceUntil
    fun shouldRecover(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return !resumed && now >= maintenanceUntil && now >= foreignUntil
    }
}
