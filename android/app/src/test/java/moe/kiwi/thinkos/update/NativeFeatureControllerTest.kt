package moe.kiwi.thinkos.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Real serial controller/cancellation semantics; no sockets, APK or module installation. */
class NativeFeatureControllerTest {
    private fun policy(enabled: Boolean = false) = UpdatePolicy(enabled, "stable", listOf("https://example.invalid/stable.json"),
        600, 3600, 24000, 2000, 60, 3600, 5000, 2024, 1000, 1000)
    private class Work : UpdateController.Work, FeatureModuleWork {
        override val status = MutableStateFlow(UpdateStateDocument(1, "stable", "IDLE", 0, 0))
        override val installable = true
        var checks = 0; var downloads = 0; var installs = 0; var restores = 0
        var phaseAfterRestore = "IDLE"
        var failRestore = false
        var onDownload: suspend () -> Unit = { awaitCancellation() }
        var onInstall: suspend () -> Unit = { awaitCancellation() }
        override suspend fun restore() {
            restores++
            if (failRestore) throw IOException("fixture cache cannot authenticate")
            status.value = status.value.copy(phase = phaseAfterRestore)
        }
        override suspend fun check(sources: List<String>): Boolean { checks++; return true }
        override suspend fun download(manual: Boolean, environment: StateFlow<UpdateSession.Environment>): Boolean {
            downloads++; onDownload(); return true
        }
        override suspend fun install(manual: Boolean, environment: StateFlow<UpdateSession.Environment>): Boolean {
            installs++; onInstall(); return true
        }
        override suspend fun prepareFeature(scope: FeatureScope, progress: (Long, Long) -> Unit): FeaturePreparedPlan =
            error("Native test body chooses whether to invoke its feature work")
        override suspend fun installFeature(plan: FeaturePreparedPlan, admitted: suspend () -> Boolean): UpdateInstaller.Outcome =
            error("Native test body chooses whether to invoke its feature work")
        override fun close() = Unit
    }
    private class Harness(scope: CoroutineScope, config: UpdatePolicy) {
        var now = 10000L
        val policy = MutableStateFlow(config)
        val environment = MutableStateFlow(UpdateSession.Environment(Attention.ENGAGED, false, false))
        val availability = MutableStateFlow(UpdateController.Availability(false, false))
        val automatic = MutableStateFlow(false)
        val work = Work()
        val controller = UpdateController(scope, policy, environment, availability, { now }, { work }, { low, _ -> low }, automatic)
        suspend fun start() { controller.start(); until { controller.status.value.configured } }
        suspend fun stop() = controller.stop()
    }
    companion object {
        private suspend fun until(test: () -> Boolean) = withTimeout(3000) { while (!test()) { yield(); delay(1) } }
    }

    @Test fun nativePrepareRequiresFreshTouchAndCannotGrantOrdinaryOrAutomaticInstall(): Unit = runBlocking {
        val h = Harness(this, policy())
        try {
            h.start(); assertTrue(h.controller.reserveNative("window"))
            h.work.status.value = h.work.status.value.copy(phase = "READY", size = 10, downloaded = 10)
            h.automatic.value = true
            var prepared = 0
            assertFalse(h.controller.native("window", 4999, false) { prepared++ })
            assertFalse(h.controller.native("window", 10001, false) { prepared++ })
            assertTrue(h.controller.native("window", 10000, false) { prepared++ })
            until { prepared == 1 && !h.controller.status.value.busy }
            assertFalse(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            delay(10); assertEquals(0, h.work.installs)
            h.automatic.value = false
            h.controller.releaseNative("window")?.join()
            assertEquals("IDLE", h.work.status.value.phase)
        } finally { h.stop() }
    }

    @Test fun autoInstallCannotBeReservedEvenBeforeItPublishesInstalling(): Unit = runBlocking {
        val h = Harness(this, policy())
        try {
            h.start(); h.work.status.value = h.work.status.value.copy(phase = "READY", size = 10, downloaded = 10)
            h.automatic.value = true
            until { h.work.installs == 1 }
            assertEquals("READY", h.work.status.value.phase)
            assertFalse(h.controller.reserveNative("must-refuse"))
        } finally { h.stop() }
    }

    @Test fun nativeBodyFailureDoesNotKillTheControllerOrTheNextNativeRequest(): Unit = runBlocking {
        val h = Harness(this, policy())
        try {
            h.start(); assertTrue(h.controller.reserveNative("window"))
            assertTrue(h.controller.native("window", h.now, false) { throw IOException("fixture download failed") })
            until { h.controller.status.value.error == "network" && !h.controller.status.value.busy }
            var retry = 0
            assertTrue(h.controller.native("window", h.now, false) { retry++ })
            until { retry == 1 && !h.controller.status.value.busy }
            h.controller.releaseNative("window")?.join()
            assertTrue(h.controller.manual(UpdateController.Action.CHECK, h.now))
            until { h.work.checks == 1 }
        } finally { h.stop() }
    }

    @Test fun queuedNativeTouchExpiresWhileOldAutomaticIoIsJoining(): Unit = runBlocking {
        val h = Harness(this, policy(true))
        val cleaning = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        try {
            h.start()
            h.work.onDownload = { try { awaitCancellation() } finally {
                cleaning.complete(Unit); withContext(NonCancellable) { finish.await() }
            } }
            h.work.status.value = h.work.status.value.copy(phase = "AVAILABLE", size = 10)
            h.environment.value = h.environment.value.copy(attention = Attention.AMBIENT)
            h.availability.value = UpdateController.Availability(true, true)
            until { h.work.downloads == 1 }
            assertTrue(h.controller.reserveNative("window"))
            var prepared = 0
            val request = async { h.controller.native("window", h.now, false) { prepared++ } }
            withTimeout(3000) { cleaning.await() }
            h.now += 5001; finish.complete(Unit)
            assertFalse(withTimeout(3000) { request.await() })
            assertEquals(0, prepared)
            h.availability.value = UpdateController.Availability(false, false)
            h.controller.releaseNative("window")?.join()
        } finally { finish.complete(Unit); h.stop() }
    }

    @Test fun liveVideoCastingForeignAndAudioInstallAreNeverBypassed(): Unit = runBlocking {
        val h = Harness(this, policy())
        try {
            h.start(); assertTrue(h.controller.reserveNative("window"))
            var operations = 0
            for (blocked in listOf(h.environment.value.copy(video = true), h.environment.value.copy(casting = true),
                h.environment.value.copy(foreign = true))) {
                h.environment.value = blocked
                assertFalse(h.controller.native("window", h.now, false) { operations++ })
            }
            h.environment.value = UpdateSession.Environment(Attention.ENGAGED, false, false, media = true)
            assertFalse(h.controller.native("window", h.now, true) { operations++ })
            assertEquals(0, operations)
            h.controller.releaseNative("window")?.join()
        } finally { h.stop() }
    }

    @Test fun closeJoinsLateIoAndRestoresOrdinaryPlanBeforeReleasingItsLease(): Unit = runBlocking {
        val h = Harness(this, policy())
        val started = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        try {
            h.start(); assertTrue(h.controller.reserveNative("window"))
            h.work.status.value = h.work.status.value.copy(phase = "READY", size = 10, downloaded = 10)
            assertTrue(h.controller.native("window", h.now, false) {
                started.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { finish.await() } }
            })
            started.await()
            h.work.phaseAfterRestore = "AVAILABLE" // New shared catalog/floor invalidates the old RAM READY.
            val release = h.controller.releaseNative("window")
            assertFalse(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            assertFalse(h.controller.reserveNative("other"))
            finish.complete(Unit); release?.join()
            assertEquals(2, h.work.restores)
            assertEquals("AVAILABLE", h.work.status.value.phase)
            assertFalse(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            assertEquals(0, h.work.installs)
        } finally { finish.complete(Unit); h.stop() }
    }

    @Test fun returningFromAcceptedInstallWaitsWithoutCancellingItOrReleasingOrdinaryAdmissionEarly(): Unit = runBlocking {
        val h = Harness(this, policy())
        val entered = CompletableDeferred<Unit>(); val complete = CompletableDeferred<Unit>()
        var installed = false; var cancelled = false
        try {
            h.start(); assertTrue(h.controller.reserveNative("window"))
            assertTrue(h.controller.native("window", h.now, true) {
                entered.complete(Unit)
                try { complete.await(); installed = true }
                catch (error: CancellationException) { cancelled = true; throw error }
            })
            entered.await()
            val release = h.controller.releaseNative("window", cancelOperation = false)
            delay(10)
            assertFalse(cancelled); assertFalse(installed)
            assertFalse(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            assertFalse(h.controller.reserveNative("other"))
            assertEquals(1, h.work.restores)
            complete.complete(Unit); release?.join()
            assertTrue(installed); assertFalse(cancelled)
            assertEquals(2, h.work.restores)
            assertTrue(h.controller.manual(UpdateController.Action.CHECK, h.now))
            until { h.work.checks == 1 }
        } finally { complete.complete(Unit); h.stop() }
    }

    @Test fun failedPostFeatureRestoreCannotExposeTheOldReadyToQuietOrManualInstall(): Unit = runBlocking {
        val h = Harness(this, policy())
        try {
            h.start(); assertTrue(h.controller.reserveNative("window"))
            h.work.status.value = h.work.status.value.copy(phase = "READY", size = 10, downloaded = 10)
            h.work.failRestore = true
            h.automatic.value = true
            h.controller.releaseNative("window")?.join()
            assertFalse(h.controller.status.value.configured)
            assertFalse(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            delay(10); assertEquals(0, h.work.installs)
        } finally { h.stop() }
    }
}
