package moe.kiwi.thinkos.update

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** F2's serial native action only. Admission and the read model stay in Runtime;
 * this owns the stop/isolate/resume ordering used by that actual action.
 * stopController defaults to the real controller; the override permits a narrow
 * stop-failure regression without replacing the controller or its runner. */
internal suspend fun runUpdateRepair(
    controller: UpdateController,
    runtimeScope: CoroutineScope,
    setInProgress: (Boolean) -> Unit,
    isolate: suspend () -> Boolean,
    afterRestart: (Boolean) -> Unit = {},
    stopController: suspend () -> Unit = controller::stop,
): Boolean {
    setInProgress(true)
    return try {
        // Joining must finish even when dismiss cancels the native caller.
        withContext(NonCancellable) { stopController() }
        // NonCancellable uses the same dispatcher, so explicitly check the
        // original caller before invoking even the first isolation instruction.
        currentCoroutineContext().ensureActive()
        isolate()
    } finally {
        setInProgress(false)
        if (runtimeScope.isActive) {
            controller.start()
            afterRestart(!currentCoroutineContext().isActive)
        }
    }
}
