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
import java.util.Locale

class SensorController(
    context: Context,
    private val store: Store,
    private val system: SystemValues,
    private val onBrightness: (Float) -> Unit,
    private val onPluginEvent: (String, JSONArray) -> Unit = { _, _ -> },
    private val settingsOpen: () -> Boolean = { false },
    /** Day or night by the Sun at the configured place, for the sunrise-and-sunset theme mode. */
    daylight: () -> Boolean? = { null },
    private val modeRuntime: ModeRuntime? = null,
    private val onModeChanged: (Mode) -> Unit = {},
) : SensorEventListener {
    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private val attention = AttentionPolicy(SystemClock.elapsedRealtime()) { system.attentionTimings }
    private val touchUpdates = kotlinx.coroutines.flow.MutableStateFlow(SystemClock.elapsedRealtime())
    internal val touches: kotlinx.coroutines.flow.StateFlow<Long> = touchUpdates
    private val theme = ThemePolicy(store.current.theme,
        { if (modeRuntime != null) modeRuntime.daylight(WallClock.now()) else daylight() },
        { modeRuntime?.dayTimings ?: ThemeTimings() }) { system.themeThresholds }
    private var light: Float? = null
    private var injectedLux: Float? = null
    private var heldTheme: Theme? = null
    private var heldAttention: Attention? = null
    private var heldPresence: Boolean? = null
    private var brightnessAnimator: ValueAnimator? = null
    private var displayedBrightness = 0.85f
    private var active = false
    private var brightness = -1f
    private var previewCap: Float? = null
    private val ticker = object : Runnable {
        override fun run() { if (active) { evaluate(); handler.postDelayed(this, 1_000) } }
    }
    private val clockChanged: () -> Unit = { handler.post { if (active) timeChanged() } }
    fun start() {
        if (Looper.myLooper() != handler.looper) { handler.post { start() }; return }
        if (active) return
        active = true
        WallClock.addListener(clockChanged)
        listOf(Sensor.TYPE_LIGHT, Sensor.TYPE_PROXIMITY).forEach { type ->
            manager.getDefaultSensor(type)?.let { manager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL, handler) }
        }
        handler.post(ticker)
    }
    fun stop() {
        if (Looper.myLooper() != handler.looper) { handler.post { stop() }; return }
        active = false; previewCap = null; WallClock.removeListener(clockChanged)
        manager.unregisterListener(this); handler.removeCallbacks(ticker); brightnessAnimator?.cancel()
    }

    /** Native controls call these through RuntimeHost's main-thread bridge after its tap gate. */
    fun setMode(value: Mode): Boolean {
        check(Looper.myLooper() == handler.looper) { "Mode controls require the main thread" }
        val runtime = modeRuntime ?: return false
        runtime.setManual(value)
        evaluate()
        return store.current.mode == value
    }
    fun clearMode(): Boolean {
        check(Looper.myLooper() == handler.looper) { "Mode controls require the main thread" }
        val runtime = modeRuntime ?: return false
        runtime.clearManual()
        evaluate()
        return true
    }
    fun timeChanged() {
        if (Looper.myLooper() != handler.looper) { handler.post { timeChanged() }; return }
        modeRuntime?.invalidateTime()
        evaluate()
    }
    private var mediaHold = false
    /** While a video plays the display stays at least AWAKE; a touch still raises it to ENGAGED. */
    fun mediaPlaying(value: Boolean) {
        if (Looper.myLooper() != handler.looper) { handler.post { mediaPlaying(value) }; return }
        if (mediaHold == value) return
        mediaHold = value
        evaluate()
    }
    fun debugHoldAttention(value: Attention?) {
        if (Looper.myLooper() != handler.looper) { handler.post { debugHoldAttention(value) }; return }
        if (!BuildConfig.DEBUG) return
        heldAttention = value
        evaluate()
    }
    fun debugHoldTheme(value: Theme?) {
        if (Looper.myLooper() != handler.looper) { handler.post { debugHoldTheme(value) }; return }
        if (BuildConfig.DEBUG) { heldTheme = value; evaluate() }
    }
    /** Read-only checkpoint for the shell-protected screenshot walkthrough. */
    fun debugOverrides(): org.json.JSONObject = org.json.JSONObject()
        .put("attention", heldAttention?.name ?: org.json.JSONObject.NULL)
        .put("presence", heldPresence ?: org.json.JSONObject.NULL)
        .put("theme", heldTheme?.name ?: org.json.JSONObject.NULL)
        .put("lux", injectedLux ?: org.json.JSONObject.NULL)
    fun debugInjectLux(value: Float?) {
        if (Looper.myLooper() != handler.looper) { handler.post { debugInjectLux(value) }; return }
        injectedLux = value; evaluate()
    }
    fun debugPresence(value: Boolean) {
        if (Looper.myLooper() != handler.looper) { handler.post { debugPresence(value) }; return }
        attention.presence(value, SystemClock.elapsedRealtime()); onPluginEvent("presence", JSONArray().put(value)); evaluate()
    }
    /** Keep real proximity changes from contaminating a time-driven unattended test. Process-local, debug builds only. */
    fun debugHoldPresence(value: Boolean?) {
        if (Looper.myLooper() != handler.looper) { handler.post { debugHoldPresence(value) }; return }
        if (!BuildConfig.DEBUG) return
        heldPresence = value
        if (value != null) debugPresence(value)
    }
    var debugTouchCount = 0L; private set
    fun touch() {
        if (Looper.myLooper() != handler.looper) { handler.post { touch() }; return }
        if (BuildConfig.DEBUG) debugTouchCount++
        val now = SystemClock.elapsedRealtime()
        attention.touch(now); touchUpdates.value = now; evaluate(touched = true)
    }
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> light = event.values[0]
            Sensor.TYPE_PROXIMITY -> {
                val near = heldPresence ?: (event.values[0] < event.sensor.maximumRange)
                attention.presence(near, SystemClock.elapsedRealtime())
                onPluginEvent("presence", JSONArray().put(near))
            }
        }
        evaluate()
    }
    /** The brightness cap changed (a drag on a film): follow it now, not at the next once-a-second look. */
    fun refreshBrightness() = evaluate(immediate = true)
    /** Native temporary preview; it never writes SettingsStore. */
    fun previewBrightness(value: Float?) {
        if (Looper.myLooper() != handler.looper) { handler.post { previewBrightness(value) }; return }
        previewCap = value; evaluate(immediate = true)
    }
    private fun evaluate(immediate: Boolean = false, touched: Boolean = false) {
        if (Looper.myLooper() != handler.looper) { handler.post { evaluate(immediate, touched) }; return }
        val now = SystemClock.elapsedRealtime()
        val wall = WallClock.now()
        val level = heldAttention ?: attention.level(now, settingsOpen()).let { if (mediaHold && it < Attention.AWAKE) Attention.AWAKE else it }
        val lux = (injectedLux ?: light)?.takeIf { it.isFinite() && it >= 0 }
        val decision = modeRuntime?.evaluate(now, wall, lux, level == Attention.ENGAGED, touched)
        val nextMode = decision?.mode ?: Mode.DAY
        val leavingSleep = decision?.exitSleepViaRedshift == true
        val dayTheme = lux?.let { theme.sample(it, now, level == Attention.ENGAGED) } ?: run {
            // No usable light sensor: a pinned or sun-scheduled mode still applies, otherwise fall back to the clock.
            theme.scheduled() ?: modeRuntime?.fallbackTheme(wall) ?: Theme.DARK
        }
        // MODE-0 is still a gate: SLEEP is a logical state with the safe existing
        // redshift rendering, never a 0-brightness or system-screen-off request.
        val nextTheme = heldTheme ?: if (nextMode == Mode.DAY) dayTheme else Theme.NIGHT
        val old = store.current
        store.update { it.copy(attention = level, theme = nextTheme, mode = nextMode) }
        if (old.mode != nextMode) {
            // Gates read the new state before effects pause media/end casting.
            onModeChanged(nextMode)
            onPluginEvent("mode", JSONArray().put(nextMode.name.lowercase(Locale.ROOT)))
        }
        if (old.attention != level) { Log.i("ThinkOS", "attention=$level"); onPluginEvent("attention", JSONArray().put(level.name)) }
        if (old.theme != nextTheme) { Log.i("ThinkOS", "theme=$nextTheme lux=$light"); onPluginEvent("theme", JSONArray().put(nextTheme.name)) }
        // The user's brightness cap applies while someone is looking; AMBIENT is a bit under half of it.
        val cap = previewCap ?: system.brightnessMax
        val target = if (nextTheme == Theme.NIGHT) 0.03f else when (level) {
            Attention.ASLEEP -> 0.03f
            Attention.AMBIENT -> (cap * 0.47f).coerceAtLeast(0.03f)
            else -> cap
        }
        if (brightness != target) {
            brightnessAnimator?.cancel()
            if (brightness < 0f || immediate && !leavingSleep) { displayedBrightness = target; onBrightness(target) }
            else {
                brightnessAnimator = ValueAnimator.ofFloat(displayedBrightness, target).apply {
                    duration = if (nextTheme == Theme.NIGHT || leavingSleep) Motion.NIGHT else Motion.THEME
                    addUpdateListener { displayedBrightness = it.animatedValue as Float; onBrightness(displayedBrightness) }
                    start()
                }
            }
            brightness = target
        }
    }
}
