package moe.kiwi.thinkos.device

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** The foreground Activity may hold a short lease, independently of its UI and audio state. */
internal object SleepBacklight {
    private val worker by lazy { Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "ThinkOS-Backlight").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    } }
    private val client = PowerClient()
    private var owner: Any? = null // main thread only
    private var keep: ScheduledFuture<*>? = null // worker only

    fun set(window: Any, enable: Boolean) {
        if (enable && owner === window || !enable && owner !== window) return
        if (enable && owner != null) release()
        owner = if (enable) window else null
        if (!enable) release()
        else worker.execute {
            if (client.sleepBacklight("DARK_BEGIN")) {
                keep = worker.scheduleWithFixedDelay({
                    if (!client.sleepBacklight("DARK_KEEP")) { keep?.cancel(false); keep = null }
                }, 5, 5, TimeUnit.SECONDS)
            }
        }
    }

    private fun release() = worker.execute {
        keep?.cancel(false)
        keep = null
        client.sleepBacklight("DARK_END")
    }
}
