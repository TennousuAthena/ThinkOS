package moe.kiwi.thinkos.shell

import android.os.SystemClock

/** Main-thread state shared by Activity and foreground watchdog. */
object KioskState {
    var resumed = false
    var maintenanceUntil = 0L
    fun allowSystemSettings() { maintenanceUntil = SystemClock.elapsedRealtime() + 300_000 }
    fun shouldRecover(): Boolean = !resumed && SystemClock.elapsedRealtime() >= maintenanceUntil
}
