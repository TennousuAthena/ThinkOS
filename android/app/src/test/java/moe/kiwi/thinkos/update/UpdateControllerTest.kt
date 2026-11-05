package moe.kiwi.thinkos.update

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.kiwi.thinkos.core.Attention
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class UpdateControllerTest {
    private fun policy(enabled: Boolean = true) = UpdatePolicy(enabled, "stable", listOf("http://127.0.0.1/stable.json"),
        600, 3600, 24_000, 2000, 60, 3600, 5000, 2024, 1000, 1000)
    private class Work(channel: String) : UpdateController.Work {
        override val status = MutableStateFlow(UpdateStateDocument(1, channel, "IDLE", 0, 0))
        var checks = 0; var downloads = 0; var installs = 0; var closed = false; var cancellations = 0
        override var installable = false
        var onCheck: suspend Work.() -> Boolean = { true }
        var onDownload: suspend Work.(Boolean, StateFlow<UpdateSession.Environment>) -> Boolean = { _, _ -> ready(); true }
        var onInstall: suspend Work.(Boolean, StateFlow<UpdateSession.Environment>) -> Boolean = { _, _ ->
            status.value = status.value.copy(phase = "INSTALLING"); awaitCancellation()
        }
        val installModes = mutableListOf<Boolean>()
        override suspend fun restore() = Unit
        override suspend fun check(sources: List<String>): Boolean { checks++; return onCheck() }
        override suspend fun download(manual: Boolean, environment: StateFlow<UpdateSession.Environment>): Boolean {
            downloads++
            try { return onDownload(manual, environment) } catch (e: CancellationException) { cancellations++; throw e }
        }
        override suspend fun install(manual: Boolean, environment: StateFlow<UpdateSession.Environment>): Boolean {
            assertTrue(environment.value.allowsInstall()); installs++; installModes += manual
            return onInstall(manual, environment)
        }
        fun available(seq: Long = 1) { status.value = status.value.copy(phase = "AVAILABLE", size = 10, seq = seq, catalog_digest = "$seq") }
        fun ready() { status.value = status.value.copy(phase = "READY", downloaded = 10) }
        override fun close() { closed = true }
    }
    private class Harness(scope: CoroutineScope, initial: UpdatePolicy) {
        var now = 0L
        val policy = MutableStateFlow(initial)
        val environment = MutableStateFlow(UpdateSession.Environment(Attention.ENGAGED, false, false))
        val online = MutableStateFlow(UpdateController.Availability(false, false))
        val works = mutableListOf<Work>()
        val autoInstall = MutableStateFlow(false)
        val controller = UpdateController(scope, policy, environment, online, { now }, { Work(it.channel).also(works::add) }, { low, _ -> low }, autoInstall)
        val work get() = works.last()
        suspend fun started() { controller.start(); until { controller.status.value.configured } }
        suspend fun tick(at: Long) {
            now = at
            environment.value = environment.value.copy(attention = if (environment.value.attention == Attention.ENGAGED) Attention.AWAKE else Attention.ENGAGED)
            yield(); delay(2)
        }
        suspend fun stop() = controller.stop()
    }
    companion object {
        private suspend fun until(test: () -> Boolean) = withTimeout(3000) { while (!test()) { yield(); delay(1) } }
    }

    @Test fun automaticChecksWaitForBothPrerequisitesThenUseJitteredDeadlines() = runBlocking {
        val h = Harness(this, policy()); try {
            h.started(); h.online.value = UpdateController.Availability(true, false); h.tick(100)
            assertNull(h.controller.status.value.nextCheckElapsed)
            h.online.value = UpdateController.Availability(true, true)
            until { h.controller.status.value.nextCheckElapsed != null }
            assertEquals(700L, h.controller.status.value.nextCheckElapsed)
            h.tick(699); assertEquals(0, h.work.checks)
            h.tick(700); until { h.work.checks == 1 && !h.controller.status.value.busy }
            assertEquals(22_700L, h.controller.status.value.nextCheckElapsed)
            h.tick(22_700); until { h.work.checks == 2 }
        } finally { h.stop() }
    }

    @Test fun failuresAndOfflineCacheBackOffWithoutPrompts() = runBlocking {
        val h = Harness(this, policy()); try {
            h.started(); h.work.onCheck = { if (checks == 1) throw IOException() else false }
            h.online.value = UpdateController.Availability(true, true)
            until { h.controller.status.value.nextCheckElapsed != null }
            h.tick(600); until { h.controller.status.value.nextCheckElapsed == 660L }
            h.tick(660); until { h.controller.status.value.nextCheckElapsed == 780L }
            h.tick(780); until { h.controller.status.value.nextCheckElapsed == 1020L }
            assertEquals(3, h.work.checks)
        } finally { h.stop() }
    }

    @Test fun disabledAutomaticWorkStillAcceptsFreshManualChecksAndRejectsStaleOrFutureTaps() = runBlocking {
        val h = Harness(this, policy(false)); try {
            h.started(); h.online.value = UpdateController.Availability(true, true); h.tick(10_000)
            assertEquals(0, h.work.checks)
            assertFalse(h.controller.manual(UpdateController.Action.CHECK, 4999))
            assertFalse(h.controller.manual(UpdateController.Action.CHECK, 10_001))
            assertTrue(h.controller.manual(UpdateController.Action.CHECK, 5000))
            until { h.work.checks == 1 }
            assertFalse(h.controller.manual(UpdateController.Action.DOWNLOAD, -1))
        } finally { h.stop() }
    }

    @Test fun manualDownloadBypassesAttentionButCannotBypassVideoOrForeignApp() = runBlocking {
        val h = Harness(this, policy(false)); try {
            h.started(); h.work.available()
            h.environment.value = h.environment.value.copy(video = true)
            assertFalse(h.controller.manual(UpdateController.Action.DOWNLOAD, h.now))
            h.environment.value = h.environment.value.copy(video = false, foreign = true)
            assertFalse(h.controller.manual(UpdateController.Action.DOWNLOAD, h.now))
            h.environment.value = h.environment.value.copy(foreign = false)
            assertTrue(h.controller.manual(UpdateController.Action.DOWNLOAD, h.now))
            until { h.work.status.value.phase == "READY" }
        } finally { h.stop() }
    }

    @Test fun manualInstallUsesFreshTapAndCannotBypassAnyLiveSessionOrInstallTwice() = runBlocking {
        val h = Harness(this, policy(false)); try {
            h.started(); h.now = 10_000; h.work.available(); h.work.ready()
            assertFalse(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            h.work.installable = true
            assertFalse(h.controller.manual(UpdateController.Action.INSTALL, 4999))
            for (environment in listOf(h.environment.value.copy(media = true), h.environment.value.copy(video = true),
                h.environment.value.copy(casting = true), h.environment.value.copy(foreign = true))) {
                h.environment.value = environment
                assertFalse(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            }
            h.environment.value = UpdateSession.Environment(Attention.ENGAGED, false, false)
            assertTrue(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            until { h.work.installs == 1 }
            assertFalse(h.controller.manual(UpdateController.Action.INSTALL, h.now))
            assertFalse(h.controller.manual(UpdateController.Action.CHECK, h.now))
            assertFalse(h.controller.manual(UpdateController.Action.DOWNLOAD, h.now))
        } finally { h.stop() }
    }

    @Test fun readyCanAutoInstallOfflineWithAutomaticDownloadsDisabledAndNoTap() = runBlocking {
        val h = Harness(this, policy(false)); try {
            h.started(); h.work.installable = true; h.work.available(); h.work.ready()
            h.environment.value = UpdateSession.Environment(Attention.ASLEEP, false, false)
            delay(5); assertEquals(0, h.work.installs)
            h.autoInstall.value = true
            until { h.work.installs == 1 }
            assertEquals(listOf(false), h.work.installModes)
            h.online.value = UpdateController.Availability(true, false); yield()
            h.online.value = UpdateController.Availability(false, false); delay(5)
            assertEquals("INSTALLING", h.work.status.value.phase)
            assertFalse(h.controller.manual(UpdateController.Action.CHECK, h.now))
        } finally { h.stop() }
    }

    @Test fun refusedAutoInstallUsesBoundedRetryInsteadOfSpinningOnReady() = runBlocking {
        val h = Harness(this, policy(false)); try {
            h.started(); h.work.installable = true; h.work.available(); h.work.ready()
            h.work.onInstall = { _, _ -> false }
            h.autoInstall.value = true; until { h.work.installs == 1 && !h.controller.status.value.busy }
            h.tick(59); assertEquals(1, h.work.installs)
            h.tick(60); until { h.work.installs == 2 }
        } finally { h.stop() }
    }

    @Test fun disablingAutomaticUpdatesCancelsAutoTransferButLeavesManualTransferRunning() = runBlocking {
        val h = Harness(this, policy()); try {
            h.started(); h.work.available(); h.work.onDownload = { _, _ -> awaitCancellation() }
            h.environment.value = h.environment.value.copy(attention = Attention.AMBIENT)
            h.online.value = UpdateController.Availability(true, true)
            until { h.work.downloads == 1 }
            h.policy.value = h.policy.value.copy(enabled = false)
            until { h.work.cancellations == 1 && !h.controller.status.value.busy }
            assertTrue(h.controller.manual(UpdateController.Action.DOWNLOAD, h.now))
            until { h.work.downloads == 2 }
            h.policy.value = h.policy.value.copy(enabled = true); yield()
            h.policy.value = h.policy.value.copy(enabled = false); delay(5)
            assertEquals(1, h.work.cancellations); assertTrue(h.controller.status.value.busy)
        } finally { h.stop() }
    }

    @Test fun channelChangesJoinAndCloseOldWorkBeforeConstructingTheNext() = runBlocking {
        val h = Harness(this, policy(false)); try {
            h.started(); val old = h.work; old.available(); old.onDownload = { _, _ -> awaitCancellation() }
            assertTrue(h.controller.manual(UpdateController.Action.DOWNLOAD, h.now)); until { old.downloads == 1 }
            h.policy.value = h.policy.value.copy(channel = "beta")
            until { h.works.size == 2 && h.controller.status.value.configured }
            assertTrue(old.closed); assertEquals(1, old.cancellations)
            assertEquals("beta", h.controller.status.value.state.channel)
            assertEquals(0, h.work.checks)
        } finally { h.stop() }
    }

    @Test fun aNewCatalogAfterReadyCanDownloadWithoutWaitingForTheOldRetryDeadline() = runBlocking {
        val h = Harness(this, policy()); try {
            h.started(); h.work.available()
            h.environment.value = h.environment.value.copy(attention = Attention.AMBIENT)
            h.online.value = UpdateController.Availability(true, true)
            until { h.work.status.value.phase == "READY" }
            h.work.available(2)
            until { h.work.downloads == 2 && h.work.status.value.phase == "READY" }
        } finally { h.stop() }
    }

    @Test fun missingDeploymentKeysFailClosedWithoutStartingRequests() = runBlocking {
        val p = MutableStateFlow(policy())
        val c = UpdateController(this, p, MutableStateFlow(UpdateSession.Environment(Attention.AMBIENT, false, false)),
            MutableStateFlow(UpdateController.Availability(true, true)), { 0 }, { throw IOException("Missing keys") })
        try {
            c.start(); until { c.status.value.error != null }
            assertFalse(c.status.value.configured); assertFalse(c.status.value.busy)
            assertFalse(c.manual(UpdateController.Action.CHECK, 0))
        } finally { c.stop() }
    }

    @Test fun authenticatedCachedDownloadDoesNotNeedATrustedWallClock() = runBlocking {
        val h = Harness(this, policy()); try {
            h.started(); h.work.available()
            h.environment.value = h.environment.value.copy(attention = Attention.AMBIENT)
            h.online.value = UpdateController.Availability(true, false)
            until { h.work.status.value.phase == "READY" }
            assertEquals(0, h.work.checks); assertEquals(1, h.work.downloads)
            assertNull(h.controller.status.value.nextCheckElapsed)
        } finally { h.stop() }
    }

    @Test fun freshManualCheckPreemptsAutomaticTransferAndResourcesCloseOnStop() = runBlocking {
        val h = Harness(this, policy()); try {
            h.started(); h.work.available(); h.work.onDownload = { _, _ -> awaitCancellation() }
            h.environment.value = h.environment.value.copy(attention = Attention.AMBIENT)
            h.online.value = UpdateController.Availability(true, true)
            until { h.work.downloads == 1 }
            assertTrue(h.controller.manual(UpdateController.Action.CHECK, h.now))
            until { h.work.checks == 1 }
            assertEquals(1, h.work.cancellations)
        } finally { h.stop() }
        assertTrue(h.work.closed)
        assertFalse(h.controller.manual(UpdateController.Action.CHECK, h.now))
    }

    @Test fun channelChangeDuringManualPreemptionRefusesTheWaitingCaller() = runBlocking {
        val h = Harness(this, policy())
        val cleanupStarted = CompletableDeferred<Unit>(); val cleanupFinished = CompletableDeferred<Unit>()
        try {
            h.started(); h.work.available()
            h.work.onDownload = { _, _ ->
                try { awaitCancellation() } finally {
                    cleanupStarted.complete(Unit)
                    withContext(NonCancellable) { cleanupFinished.await() }
                }
            }
            h.environment.value = h.environment.value.copy(attention = Attention.AMBIENT)
            h.online.value = UpdateController.Availability(true, true)
            until { h.work.downloads == 1 }
            val manual = async { h.controller.manual(UpdateController.Action.CHECK, h.now) }
            withTimeout(3000) { cleanupStarted.await() }
            h.policy.value = h.policy.value.copy(channel = "beta")
            assertFalse(withTimeout(3000) { manual.await() })
            cleanupFinished.complete(Unit)
            until { h.works.size == 2 }
            assertTrue(h.works.first().closed)
        } finally { cleanupFinished.complete(Unit); h.stop() }
    }
}
