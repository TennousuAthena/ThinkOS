package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.astro.Place
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.tz.ZoneClock
import moe.kiwi.thinkos.tz.Zones
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/**
 * One process-local mode session, driven by SensorController's existing main-thread tick.
 * Settings callbacks only mark a revision; every policy/cache mutation stays on the owner thread.
 * It does not schedule another ticker or implement MODE-3 screen/audio effects.
 */
class ModeRuntime internal constructor(
    private val settings: SettingsStore,
    private val astronomy: ModeAstronomy,
) : Closeable {
    constructor(settings: SettingsStore, place: () -> Place?, zone: () -> ZoneClock = Zones::home) :
        this(settings, ModeAstronomy(zone, place))

    private val revision = AtomicLong()
    private var readRevision = -1L
    private var values: ModeSettings? = null
    private var owner: Thread? = null
    private var action: ModeController.ManualAction? = null
    private val listener = SettingsStore.Listener { scope, keys ->
        if (scope == SettingsStore.SYSTEM && keys.any { key ->
                listOf("theme.", "redshift.", "sleep.", "mode.", "location.").any { prefix -> key.startsWith(prefix) }
            }) revision.incrementAndGet()
    }
    // evaluate prepares one snapshot before entering the pure policy; a concurrent
    // settings notification is consumed on the next tick, never halfway through it.
    private val controller = ModeController({ requireNotNull(values).config }, astronomy, astronomy)
    @Volatile var decision: ModeController.Decision? = null; private set
    val current: Mode get() = decision?.mode ?: Mode.DAY

    init { settings.addListener(listener) }

    private fun checkOwner() {
        val current = Thread.currentThread()
        if (owner == null) owner = current
        check(owner === current) { "ModeRuntime must be called on its single owner thread" }
    }

    private fun configuration(): ModeSettings {
        val next = revision.get()
        if (values == null || readRevision != next) {
            values = ModeSettings.read(settings)
            readRevision = next
            astronomy.invalidate()
        }
        return requireNotNull(values)
    }

    /** Caller evaluates immediately afterwards; actions are consumed once, not replayed each tick. */
    fun setManual(mode: Mode) {
        checkOwner()
        action = ModeController.ManualAction.Set(mode, configuration().manualHold)
    }

    fun clearManual() { checkOwner(); action = ModeController.ManualAction.Clear }
    fun invalidateTime() { checkOwner(); astronomy.invalidate() }

    fun evaluate(elapsedMillis: Long, wallMillis: Long, lux: Float?, engaged: Boolean, touched: Boolean = false): ModeController.Decision {
        checkOwner()
        configuration()
        val trustedWall = astronomy.prepare(wallMillis)
        val pending = action
        action = null
        return controller.evaluate(ModeController.Input(elapsedMillis, trustedWall, lux, engaged, touched, pending))
            .also { decision = it }
    }

    /** SensorController calls these after evaluate, using the same coherent settings/zone snapshot. */
    internal val dayTimings: ThemeTimings get() = requireNotNull(values).config.let {
        ThemeTimings(it.windowDwellMillis, it.minimumSwitchMillis)
    }
    internal fun daylight(wallMillis: Long): Boolean? = astronomy.daylight(wallMillis)
    internal fun fallbackTheme(wallMillis: Long): Theme =
        if (astronomy.inWindow(wallMillis, requireNotNull(values).dayFallback)) Theme.LIGHT else Theme.DARK

    override fun close() { settings.removeListener(listener) }
}
