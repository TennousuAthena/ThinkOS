package moe.kiwi.thinkos.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class Attention { ASLEEP, AMBIENT, AWAKE, ENGAGED }
enum class Theme { LIGHT, DARK, NIGHT }
enum class Link { UNPAIRED, OFFLINE, ONLINE }
enum class Orientation { LANDSCAPE, PORTRAIT }
data class OverlayState(val message: String? = null)
/** How the screen gets from one page to the next. [LAUNCH] and [RETURN] are the flip that opens an app and closes it again. */
enum class Transition { SLIDE, LAUNCH, RETURN, RISE }
data class DeckState(val page: String = "", val variant: String = "digital", val direction: Int = 0, val vertical: Boolean = false,
    val transition: Transition = Transition.SLIDE)
data class DeviceState(
    val attention: Attention = Attention.AWAKE,
    val deck: DeckState = DeckState(),
    val overlay: OverlayState = OverlayState(),
    val link: Link = Link.UNPAIRED,
    val theme: Theme = Theme.DARK,
    val orientation: Orientation = Orientation.LANDSCAPE,
    val safeMode: Boolean = false,
    /** Independent of attention.ASLEEP; MODE-3 owns the later sleep screen/audio effects. */
    val mode: Mode = Mode.DAY,
)

/** Mutations belong to the main thread; neither reducers nor collectors do disk/network I/O. */
class Store(initial: DeviceState = DeviceState()) {
    private val mutable = MutableStateFlow(initial)
    val state: StateFlow<DeviceState> = mutable
    val current get() = mutable.value
    fun update(transform: (DeviceState) -> DeviceState) { mutable.value = transform(mutable.value) }
}

/** How long before the display eases down. Values come from the system settings (ADR-017). */
data class AttentionTimings(
    val engagedIdleMillis: Long = 15_000,
    val ambientAfterMillis: Long = 120_000,
    val asleepAfterMillis: Long = 180_000,
)

/** Uses monotonic milliseconds so wall-clock corrections cannot dim the display unexpectedly. */
class AttentionPolicy(private val started: Long, private val timings: () -> AttentionTimings = { AttentionTimings() }) {
    var lastTouch = Long.MIN_VALUE; private set
    var lastPresence = started; private set
    var present = false; private set
    private var lastActivity = started
    fun touch(now: Long) { lastTouch = now; lastPresence = now; lastActivity = now }
    fun presence(value: Boolean, now: Long) {
        if (value != present) { lastPresence = now; if (value) lastActivity = now }
        present = value
    }
    fun level(now: Long, interactionActive: Boolean = false): Attention {
        if (interactionActive) return Attention.ENGAGED
        val limits = timings()
        val touchAge = if (lastTouch == Long.MIN_VALUE) Long.MAX_VALUE else now - lastTouch
        if (touchAge < limits.engagedIdleMillis) return Attention.ENGAGED
        if (!present && now - lastPresence >= limits.asleepAfterMillis) return Attention.ASLEEP
        if (now - lastActivity >= limits.ambientAfterMillis) return Attention.AMBIENT
        return Attention.AWAKE
    }
}

/** DAY-theme limits. Legacy night fields remain source-compatible; ModeController alone owns NIGHT. */
data class ThemeThresholds(
    val mode: String = "auto",
    val nightEnabled: Boolean = true,
    val darkBelow: Float = 50f,
    val lightAbove: Float = 120f,
    val nightBelow: Float = 5f,
    val nightAbove: Float = 15f,
)

/** Production injects schema values through ModeRuntime; defaults retain the legacy pure-policy API. */
data class ThemeTimings(val dwellMillis: Long = 30_000, val minimumSwitchMillis: Long = 120_000) {
    init { require(dwellMillis >= 0 && minimumSwitchMillis >= 0) }
}

/** DAY's LIGHT/DARK policy only. REDSHIFT/SLEEP are projected to NIGHT by SensorController. */
class ThemePolicy(
    initial: Theme = Theme.DARK,
    /** True between sunrise and sunset at the configured place, null while that is unknown (untrusted clock). */
    private val daylight: () -> Boolean? = { null },
    private val timings: () -> ThemeTimings = { ThemeTimings() },
    private val thresholds: () -> ThemeThresholds = { ThemeThresholds() },
) {
    var theme = initial.takeUnless { it == Theme.NIGHT } ?: Theme.DARK; private set
    private var candidate = theme
    private var candidateSince = 0L
    private var lastDaySwitch = Long.MIN_VALUE

    /** The theme a pinned mode asks for, ignoring light. Null in the automatic modes. */
    fun pinned(): Theme? = when (thresholds().mode) { "light" -> Theme.LIGHT; "dark" -> Theme.DARK; else -> null }

    private fun bySun(): Theme? = if (thresholds().mode == "sun") daylight()?.let { if (it) Theme.LIGHT else Theme.DARK } else null

    /** What the setting dictates without reading the light sensor: a pinned theme, or day/night by the Sun. Null = use light. */
    fun scheduled(): Theme? = pinned() ?: bySun()

    fun sample(lux: Float, now: Long, engaged: Boolean): Theme {
        val limits = thresholds()
        val pin = pinned()
        val sun = if (pin == null) bySun() else null
        val fixed = pin ?: sun
        val target = when {
            fixed != null -> fixed
            theme == Theme.LIGHT && lux < limits.darkBelow -> Theme.DARK
            theme == Theme.DARK && lux > limits.lightAbove -> Theme.LIGHT
            else -> theme
        }
        if (candidate != target) { candidate = target; candidateSince = now }
        // A user's explicit choice and the Sun's schedule apply at once: neither is a noisy sensor reading, and a
        // touching hand cannot shadow them. Only light-driven changes need to dwell.
        val explicit = fixed != null
        val limitsTime = timings()
        val dwell = if (explicit) 0L else limitsTime.dwellMillis
        val intervalOk = explicit || lastDaySwitch == Long.MIN_VALUE || now - lastDaySwitch >= limitsTime.minimumSwitchMillis
        if (target != theme && now - candidateSince >= dwell && (!engaged || explicit) && intervalOk) {
            if (!explicit) lastDaySwitch = now
            theme = target
            candidateSince = now
        }
        return theme
    }
}
