package moe.kiwi.thinkos.vision

import android.animation.ValueAnimator
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import moe.kiwi.thinkos.core.*
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.anim.Motion
import moe.kiwi.thinkos.settings.SystemValues
import org.json.JSONArray
import java.util.Calendar

class SensorController(
    context: Context,
    private val store: Store,
    private val system: SystemValues,
    private val onBrightness: (Float) -> Unit,
    private val onPluginEvent: (String, JSONArray) -> Unit = { _, _ -> },
    private val settingsOpen: () -> Boolean = { false },
    /** Day or night by the Sun at the configured place, for the sunrise-and-sunset theme mode. */
    daylight: () -> Boolean? = { null },
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val attention = AttentionPolicy(SystemClock.elapsedRealtime()) { system.attentionTimings }
    private val theme = ThemePolicy(store.current.theme, daylight) { system.themeThresholds }
    private var light: Float? = null
    private var injectedLux: Float? = null
    private var heldTheme: Theme? = null
    private var heldAttention: Attention? = null
    private var brightnessAnimator: ValueAnimator? = null
    private var displayedBrightness = 0.85f
    private var active = false
    private var brightness = -1f
    private val ticker = object : Runnable {
        override fun run() { if (active) { evaluate(); handler.postDelayed(this, 1_000) } }
    }
    fun start() {
        if (active) return
        active = true
        listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY).forEach { type ->
            manager.getDefaultSensor(type)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        }
        handler.post(ticker)
    }
    fun stop() { active = false; manager.unregisterListener(this); handler.removeCallbacks(ticker); brightnessAnimator?.cancel() }
    private var mediaHold = false
    /** While a video plays the display stays at least AWAKE; a touch still raises it to ENGAGED. */
    fun mediaPlaying(value: Boolean) {
        if (mediaHold == value) return
        mediaHold = value
        evaluate()
    }
    fun debugHoldAttention(value: Attention?) {
        if (!BuildConfig.DEBUG) return
        heldAttention = value
        evaluate()
    }
    fun debugHoldTheme(value: Theme?) { if (BuildConfig.DEBUG) { heldTheme = value; evaluate() } }
    fun debugInjectLux(value: Float?) { injectedLux = value; evaluate() }
    fun debugPresence(value: Boolean) { attention.presence(value, SystemClock.elapsedRealtime()); onPluginEvent("presence", JSONArray().put(value)); evaluate() }
    var debugTouchCount = 0L; private set
    fun touch() { if (BuildConfig.DEBUG) debugTouchCount++; attention.touch(SystemClock.elapsedRealtime()); evaluate() }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> light = event.values[0]
            Sensor.TYPE_PROXIMITY -> {
                val near = event.values[0] < event.sensor.maximumRange
                attention.presence(near, SystemClock.elapsedRealtime())
                onPluginEvent("presence", JSONArray().put(near))
            }
        }
        evaluate()
    }
    /** The brightness cap changed (a drag on a film): follow it now, not at the next once-a-second look. */
    fun refreshBrightness() = evaluate(immediate = true)
    private fun evaluate(immediate: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        val level = heldAttention ?: attention.level(now, settingsOpen()).let { if (mediaHold && it < Attention.AWAKE) Attention.AWAKE else it }
        val nextTheme = heldTheme ?: (injectedLux ?: light)?.let { theme.sample(it, now, level == Attention.ENGAGED) } ?: run {
            // No usable light sensor: a pinned or sun-scheduled mode still applies, otherwise fall back to the clock.
            theme.scheduled() ?: if (Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 7..18) Theme.LIGHT else Theme.DARK
        }
        val old = store.current
        store.update { it.copy(attention = level, theme = nextTheme) }
        if (old.attention != level) { Log.i("ThinkOS", "attention=$level"); onPluginEvent("attention", JSONArray().put(level.name)) }
        if (old.theme != nextTheme) { Log.i("ThinkOS", "theme=$nextTheme lux=$light"); onPluginEvent("theme", JSONArray().put(nextTheme.name)) }
        // The user's brightness cap applies while someone is looking; AMBIENT is a bit under half of it.
        val cap = system.brightnessMax
        val target = if (nextTheme == Theme.NIGHT) 0.03f else when (level) {
            Attention.ASLEEP -> 0.03f
            Attention.AMBIENT -> (cap * 0.47f).coerceAtLeast(0.03f)
            else -> cap
        }
        if (brightness != target) {
            brightnessAnimator?.cancel()
            if (brightness < 0f || immediate) { displayedBrightness = target; onBrightness(target) }
            else {
                brightnessAnimator = ValueAnimator.ofFloat(displayedBrightness, target).apply {
                    duration = if (nextTheme == Theme.NIGHT) Motion.NIGHT else Motion.THEME
                    addUpdateListener { displayedBrightness = it.animatedValue as Float; onBrightness(displayedBrightness) }
                    start()
                }
            }
            brightness = target
        }
    }
}
