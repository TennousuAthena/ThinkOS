package moe.kiwi.thinkos.core

/**
 * The time the OS shows. The device has no RTC and an app cannot set the system clock on API 27, so
 * [offsetMillis] (from `time/TimeSync`) corrects what the system reports. It is 0 when the system clock is trusted as is.
 */
object WallClock {
    @Volatile var offsetMillis = 0L
    fun now(): Long = System.currentTimeMillis() + offsetMillis
}
