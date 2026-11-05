package moe.kiwi.thinkos.update

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Real UpdateController runners, stopped by the production F2 function.
 * Deferred events choose the boundary; timeouts are hang limits only.
 * This does not install packages or stand in for the real 600-second health test. */
class UpdateRepairActionTest {
    private fun policy() = UpdatePolicy(false, "stable", listOf("http://127.0.0.1/stable.json"),
        600, 3600, 24_000, 2000, 60, 3600, 5000, 2024, 1000, 1000)

    private class Work(private val id: Int, private val events: MutableList<String>,
                       private val holdRestore: Boolean) : UpdateController.Work {
        override val status = MutableStateFlow(UpdateStateDocument(1, "stable", "IDLE", 0, 0))
        val restoreEntered = CompletableDeferred<Unit>()
        val cleanupEntered = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val checked = CompletableDeferred<Unit>()
        var checks = 0
        var closed = false
        override suspend fun restore() {
            if (!holdRestore) return
            restoreEntered.complete(Unit)
            try { awaitCancellation() }
            finally {
                cleanupEntered.complete(Unit)
                withContext(NonCancellable) { releaseCleanup.await() }
            }
        }
        override suspend fun check(sources: List<String>): Boolean {
            checks++; checked.complete(Unit); return true
        }
        override suspend fun download(manual: Boolean, environment: StateFlow<UpdateSession.Environment>) = false
        override fun close() { closed = true; events += "close:$id" }
    }

    private class Harness(parent: CoroutineScope, initial: UpdatePolicy, holdFirstRestore: Boolean = false) {
        val runtimeJob = SupervisorJob(parent.coroutineContext[Job])
        private val callerJob = SupervisorJob(parent.coroutineContext[Job])
        val runtimeScope = CoroutineScope(parent.coroutineContext + runtimeJob)
        val callers = CoroutineScope(parent.coroutineContext + callerJob)
        val works = mutableListOf<Work>()
        val events = mutableListOf<String>()
        private val created = Channel<Work>(Channel.UNLIMITED)
        var inProgress = false
        val restarts = mutableListOf<Boolean>()
        val controller = UpdateController(runtimeScope, MutableStateFlow(initial),
            MutableStateFlow(UpdateSession.Environment(Attention.ENGAGED, false, false)),
            MutableStateFlow(UpdateController.Availability(true, true)), { 10_000L }, {
                Work(works.size, events, holdFirstRestore && works.isEmpty()).also { work ->
                    events += "create:${works.size}"
                    works += work
                    check(created.trySend(work).isSuccess)
                }
            })

        suspend fun start(): Work { controller.start(); return nextWork() }
        suspend fun startDuringRestore(): Work {
            controller.start()
            return created.receive().also { it.restoreEntered.await() }
        }
        suspend fun nextWork(): Work = created.receive().also {
            controller.status.first { state -> state.configured }
        }
        suspend fun repair(stop: suspend () -> Unit = controller::stop, isolate: suspend () -> Boolean): Boolean =
            runUpdateRepair(controller, runtimeScope, { inProgress = it }, isolate,
                afterRestart = { restarts += it }, stopController = stop)
        suspend fun checkOrdinaryAction(work: Work) {
            assertTrue(controller.manual(UpdateController.Action.CHECK, 10_000))
            work.checked.await()
            controller.status.first { !it.busy }
            assertEquals(1, work.checks)
        }
        suspend fun close() = withContext(NonCancellable) {
            works.forEach { it.releaseCleanup.complete(Unit) }
            callerJob.cancelAndJoin()
            controller.stop()
            runtimeJob.cancelAndJoin()
            created.close()
        }
    }

    @Test fun cancellationDuringRunnerJoinWaitsForCloseAndNeverEntersIsolation() = runBlocking {
        withTimeout(5_000) {
            val h = Harness(this, policy(), holdFirstRestore = true)
            try {
                // Restore can still be joining while busy is false, matching
                // Runtime's repair admission rather than bypassing a busy transfer.
                val old = h.startDuringRestore()
                assertFalse(h.controller.status.value.busy)
                assertFalse(h.controller.status.value.configured)
                var isolations = 0
                var returnedNormally = false
                var observedCancellation = false
                val caller = h.callers.async {
                    try { h.repair { isolations++; true }.also { returnedNormally = true } }
                    catch (cancelled: CancellationException) { observedCancellation = true; throw cancelled }
                }
                old.cleanupEntered.await()
                assertTrue(h.inProgress)
                caller.cancel()
                assertFalse(caller.isCompleted)
                assertFalse(old.closed)
                assertEquals(1, h.works.size)
                assertEquals(0, isolations)
                old.releaseCleanup.complete(Unit)
                caller.join()
                assertTrue(caller.isCancelled)
                assertTrue(observedCancellation)
                assertFalse(returnedNormally)
                assertEquals(0, isolations)
                assertFalse(h.inProgress)
                val fresh = h.nextWork()
                assertTrue(old.closed)
                assertTrue(h.events.indexOf("close:0") < h.events.indexOf("create:1"))
                assertEquals(listOf(true), h.restarts)
                h.checkOrdinaryAction(fresh)
            } finally { h.close() }
        }
    }

    @Test fun cancellationInsideIsolationPropagatesAndRestoresOrdinaryActions() = runBlocking {
        withTimeout(5_000) {
            val h = Harness(this, policy())
            try {
                val old = h.start()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var isolationFinished = false
                var returnedNormally = false
                var observedCancellation = false
                val caller = h.callers.async {
                    try {
                        h.repair { entered.complete(Unit); release.await(); isolationFinished = true; true }
                            .also { returnedNormally = true }
                    } catch (cancelled: CancellationException) { observedCancellation = true; throw cancelled }
                }
                entered.await()
                assertTrue(old.closed)
                assertTrue(h.inProgress)
                caller.cancelAndJoin()
                assertTrue(caller.isCancelled)
                assertTrue(observedCancellation)
                assertFalse(returnedNormally)
                assertFalse(isolationFinished)
                assertFalse(h.inProgress)
                assertEquals(listOf(true), h.restarts)
                h.checkOrdinaryAction(h.nextWork())
            } finally { h.close() }
        }
    }

    @Test fun stopFailurePropagatesWithoutIsolationAndLeavesTheRealControllerUsable() = runBlocking {
        withTimeout(5_000) {
            val h = Harness(this, policy())
            try {
                val old = h.start()
                val failure = IOException("stop completion failed")
                var isolations = 0
                val error = runCatching {
                    h.repair(stop = { h.controller.stop(); throw failure }) { isolations++; true }
                }.exceptionOrNull()
                // Coroutine stack-trace recovery may copy the throwable across withContext.
                assertTrue(error is IOException)
                assertEquals(failure.message, error?.message)
                assertEquals(0, isolations)
                assertFalse(h.inProgress)
                assertTrue(old.closed)
                assertEquals(listOf(false), h.restarts)
                h.checkOrdinaryAction(h.nextWork())
            } finally { h.close() }
        }
    }

    @Test fun anOrdinaryCompletedRepairRestoresTheControllerAndReturnsItsRealResult() = runBlocking {
        withTimeout(5_000) {
            for (result in listOf(false, true)) {
                val h = Harness(this, policy())
                try {
                    val old = h.start()
                    assertEquals(result, h.repair { assertTrue(h.inProgress); assertTrue(old.closed); result })
                    assertFalse(h.inProgress)
                    assertEquals(listOf(false), h.restarts)
                    h.checkOrdinaryAction(h.nextWork())
                } finally { h.close() }
            }
        }
    }

    @Test fun aClosedRuntimeScopeIsNeverRestartedOrReinspectedOnCallerCancellation() = runBlocking {
        withTimeout(5_000) {
            val h = Harness(this, policy())
            try {
                h.start()
                val entered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var returnedNormally = false
                var observedCancellation = false
                val caller = h.callers.async {
                    try { h.repair { entered.complete(Unit); release.await(); true }.also { returnedNormally = true } }
                    catch (cancelled: CancellationException) { observedCancellation = true; throw cancelled }
                }
                entered.await()
                h.runtimeJob.cancelAndJoin()
                caller.cancelAndJoin()
                assertTrue(observedCancellation)
                assertFalse(returnedNormally)
                assertFalse(h.inProgress)
                assertTrue(h.restarts.isEmpty())
                assertEquals(1, h.works.size)
                assertFalse(h.controller.manual(UpdateController.Action.CHECK, 10_000))
            } finally { h.close() }
        }
    }
}
