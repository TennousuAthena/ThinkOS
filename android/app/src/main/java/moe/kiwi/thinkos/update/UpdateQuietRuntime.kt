package moe.kiwi.thinkos.update

import android.content.Context
import android.os.Looper
import android.os.StatFs
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.kiwi.thinkos.core.Store
import moe.kiwi.thinkos.core.WallClock
import moe.kiwi.thinkos.settings.SystemValues
import java.time.Instant
import java.time.ZoneId

/** Main owns observations; package/space/journal reads stay on IO. Poll only
 * while READY/INSTALLING. Admission repeats the full check after staging, so a
 * stale cached eligibility bit cannot override a touch, screen or media change. */
internal class UpdateQuietRuntime(
    private val context: Context,
    private val system: SystemValues,
    private val store: Store,
    private val environment: StateFlow<UpdateSession.Environment>,
    private val status: () -> UpdateController.Status,
    private val touches: () -> StateFlow<Long>,
    private val screens: () -> UpdateScreens,
    private val trustedWall: () -> Long?,
    private val output: MutableStateFlow<Boolean>,
    private val rootInstaller: () -> Boolean = { false },
) {
    private val activity = UpdateQuietActivity(WallClock.elapsed())
    private val changes = MutableStateFlow(0L)
    private val installs = UpdateInstallContext.store(context)
    private var started = false
    fun changed() { changes.value++ }

    fun start(scope: CoroutineScope, states: StateFlow<UpdateController.Status>) {
        if (started) return
        started = true
        scope.launch {
            combine(states, environment, touches(), store.state, changes) { _, _, _, _, _ -> Unit }.collectLatest {
                observe()
                output.value = false
                if (!waiting()) return@collectLatest
                while (isActive && waiting()) {
                    output.value = try { admitted() } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                        android.util.Log.e("ThinkOS", "Automatic update eligibility unavailable", error)
                        false
                    }
                    delay(system.updateAutomaticPolicy.pollMillis)
                }
            }
        }
    }

    suspend fun admitted(): Boolean {
        check(Looper.myLooper() == Looper.getMainLooper())
        observe()
        if (!waiting() || !system.updateAutoInstall) return false
        val state = status().state
        val facts = UpdateIo.run {
            val installer = UpdateInstaller(context, installs) { UpdateInstallContext.now(context) }
            Facts(installer.silent() || rootInstaller(), StatFs(context.filesDir.path).availableBytes,
                installs.snapshot().pending, state.size > 0 && status().offer?.release?.code?.let {
                    installs.autoBlocked(it, system.updateInstallLimits.failureLimit)
                } != false)
        }
        // Re-read after IO: media and touch changes are synchronous Main events.
        observe()
        val current = status().state
        if (current.catalog_digest != state.catalog_digest || current.size != state.size || !waiting() || !facts.silent || facts.blocked) return false
        val pending = facts.pending
        if (pending != null && !(current.phase == "INSTALLING" && pending.catalog_digest == current.catalog_digest &&
                pending.mode == "automatic" && pending.phase in setOf("PREPARED", "INSTALLING"))) return false
        val now = UpdateInstallContext.now(context)
        val wall = trustedWall()
        val screen = screens()
        val media = environment.value
        return UpdateSchedule.automaticInstallAllowed(system.updateAutomaticPolicy.quiet, UpdateSchedule.InstallState(
            system.updateAutoInstall, store.current.attention, wall != null,
            wall?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).hour }, activity.sinceTouch(now.elapsed),
            UpdateState.waitingReadyMillis(current, UpdateState.Time(now.boot, now.elapsed, wall)), activity.asleep(now.elapsed),
            media.media || media.video, media.casting, screen.settings, screen.onboarding,
            media.foreign, facts.free, current.size))
    }

    private fun observe() { activity.observe(store.current.attention, touches().value, WallClock.elapsed()) }
    private fun waiting() = status().configured && status().state.phase in setOf("READY", "INSTALLING")
    private data class Facts(val silent: Boolean, val free: Long, val pending: UpdatePending?, val blocked: Boolean)
}
