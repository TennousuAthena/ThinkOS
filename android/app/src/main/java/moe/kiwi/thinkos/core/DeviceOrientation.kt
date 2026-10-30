package moe.kiwi.thinkos.core

import android.content.pm.ActivityInfo

/** ThinkSmart View's built-in panel is mounted 180° from Android's default landscape. */
object DeviceOrientation {
    const val LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE

    /** Keep older development commands useful while enforcing the device's physical upright side. */
    fun forced(request: Int): Int = if (request == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) LANDSCAPE else request
}
