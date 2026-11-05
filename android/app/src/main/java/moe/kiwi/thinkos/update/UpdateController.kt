package moe.kiwi.thinkos.update

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import java.io.Closeable
import kotlin.random.Random

/** Confined to the supplied serial dispatcher (Main in production). One operation
 * at a time; a fresh manual action can preempt automatic checks/downloads. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class UpdateController(
    private val scope: CoroutineScope,
    private val policy: StateFlow<UpdatePolicy>,
    private val environment: StateFlow<UpdateSession.Environment>,
    private val availability: StateFlow<Availability>,
    private val elapsed: () -> Long,
    private val factory: suspend (UpdatePolicy) -> Work,
    private val random: (Long, Long) -> Long = { low, high -> if (low == high) low else Random.nextLong(low, high + 1) },
    private val automaticInstall: StateFlow<Boolean> = MutableStateFlow(false),
) {
    data class Availability(val connected: Boolean, val timeTrusted: Boolean)
    data class Status(val state: UpdateStateDocument, val configured: Boolean = false,
                      val busy: Boolean = false, val error: String? = null, val nextCheckElapsed: Long? = null,
                      val checking: Boolean = false, val offer: UpdateSession.Offer? = null, val manual: Boolean = false,
                      val installable: Boolean = false)
    interface Work : Closeable {
        val status: StateFlow<UpdateStateDocument>
        val offer: UpdateSession.Offer? get() = null
        suspend fun restore()
        /** False means authenticated offline cache; it still calls for network backoff. */
        suspend fun check(sources: List<String>): Boolean
        /** False means paused by the live environment. */
        suspend fun download(manual: Boolean, environment: StateFlow<UpdateSession.Environment>): Boolean
        val installable: Boolean get() = false
        suspend fun install(manual: Boolean, environment: StateFlow<UpdateSession.Environment>): Boolean = false
    }
    enum class Action { CHECK, DOWNLOAD, INSTALL }
    class Unconfigured(val code: String) : Exception()
    private data class Request(val action: Action, val tap: Long, val policy: UpdatePolicy, val accepted: CompletableDeferred<Boolean>)
    private data class NativeRequest(val owner: String, val tap: Long, val policy: UpdatePolicy, val installing: Boolean,
                                     val body: suspend (Work) -> Unit, val accepted: CompletableDeferred<Boolean>)
    private val nativeRequests = Channel<NativeRequest>(1)
    private var nativeLease: String? = null
    private var nativeOwner: String? = null
    private var nativeOperation: Job? = null
    private var installingOperation = false
    private var activeOperation: Job? = null
    private var activeWork: Work? = null
    private var ordinaryReady = false
    private var nativeRelease: Job? = null
    private val requests = Channel<Request>(1)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val mutable = MutableStateFlow(Status(idle(policy.value.channel)))
    val status: StateFlow<Status> = mutable
    private var runner: Job? = null

    fun start() {
        if (runner != null) return
        runner = scope.launch {
            // The auto switch only cancels automatic work. It must not interrupt
            // a manual transfer already authorized while auto updates were off.
            policy.map { it.copy(enabled = true) }.distinctUntilChanged().collectLatest { current ->
                mutable.value = Status(idle(current.channel))
                try {
                    factory(current).use { work -> run(current, work) }
                } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                    mutable.value = Status(idle(current.channel), error = (error as? Unconfigured)?.code ?: "unavailable")
                }
            }
        }
    }

    /** The UI/API adapter supplies its real user-tap timestamp, never 'now' on
     * behalf of a plugin. Check twice so a queued or old-channel tap cannot run. */
    suspend fun manual(action: Action, tapElapsed: Long): Boolean {
        val current = policy.value
        if (!ordinaryReady || nativeLease != null || runner?.isActive != true || !status.value.configured || !current.fresh(tapElapsed, elapsed())) return false
        val request = Request(action, tapElapsed, current.copy(enabled = true), CompletableDeferred())
        if (!requests.trySend(request).isSuccess) return false
        return try { request.accepted.await() } finally { request.accepted.cancel() }
    }

    /** Native feature windows reserve this controller, not a second updater.
     * Existing automatic check/download is joined at native admission; an
     * active manual operation or INSTALL can never be stolen. */
    fun reserveNative(owner: String): Boolean {
        if (nativeLease != null || installingOperation || status.value.state.phase == "INSTALLING" || status.value.busy && status.value.manual) return false
        nativeLease = owner; wake.trySend(Unit)
        return true
    }
    fun releaseNative(owner: String, cancelOperation: Boolean = true): Job? {
        if (nativeLease != owner) return null
        nativeRelease?.takeIf { it.isActive }?.let { return it }
        val operation = activeOperation
        val work = activeWork
        val config = policy.value.copy(enabled = true)
        ordinaryReady = false
        // Returning from an accepted native INSTALL removes its UI, not its
        // already-confirmed operation. Media admission remains live in the
        // module adapter; hold the lease until this operation actually ends.
        if (cancelOperation) operation?.cancel()
        // Keep the lease until stale ordinary RAM READY has been reauthenticated
        // against the sequence/cache advanced by native preparation.
        val cleanup = scope.launch {
            operation?.join()
            if (nativeLease != owner) return@launch
            if (work != null && activeWork === work && policy.value.copy(enabled = true) == config) {
                try {
                    work.restore()
                    if (activeWork === work && policy.value.copy(enabled = true) == config) {
                        ordinaryReady = true
                        mutable.value = mutable.value.copy(state = work.status.value, offer = work.offer,
                            configured = true, installable = work.installable, busy = false, manual = false)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    ordinaryReady = false
                    mutable.value = Status(idle(policy.value.channel), error = "unavailable")
                }
            }
            if (nativeLease == owner) nativeLease = null
            wake.trySend(Unit)
        }
        nativeRelease = cleanup
        return cleanup
    }
    suspend fun native(owner: String, tapElapsed: Long, installing: Boolean,
                       body: suspend (Work) -> Unit): Boolean {
        val current = policy.value
        if (!ordinaryReady || nativeRelease?.isActive == true || nativeLease != owner || runner?.isActive != true || !status.value.configured ||
            !current.fresh(tapElapsed, elapsed())) return false
        val request = NativeRequest(owner, tapElapsed, current.copy(enabled = true), installing, body, CompletableDeferred())
        if (!nativeRequests.trySend(request).isSuccess) return false
        return try { request.accepted.await() } finally { request.accepted.cancel() }
    }

    suspend fun stop() {
        runner?.cancelAndJoin(); runner = null
        nativeRelease?.cancelAndJoin(); nativeRelease = null
        nativeLease = null; nativeOwner = null; nativeOperation = null
        activeWork = null; activeOperation = null; ordinaryReady = false
        while (true) (nativeRequests.tryReceive().getOrNull() ?: break).accepted.complete(false)
        while (true) (requests.tryReceive().getOrNull() ?: break).accepted.complete(false)
        mutable.value = mutable.value.copy(configured = false, busy = false, checking = false, manual = false, nextCheckElapsed = null)
    }

    private suspend fun run(current: UpdatePolicy, work: Work): Unit = coroutineScope {
        var operation: Job? = null
        var automatic = false
        var operationAction = Action.CHECK
        var nextCheck: Long? = null
        var nextDownload = 0L
        var nextInstall = 0L
        var checkRetry = current.retryMinMillis
        var downloadRetry = current.retryMinMillis
        var downloadDigest: String? = null
        activeWork = work
        ordinaryReady = false
        try {
            launch { work.status.collect { mutable.value = mutable.value.copy(state = it, offer = work.offer, installable = work.installable); wake.trySend(Unit) } }
            launch { combine(policy, environment, availability, automaticInstall) { _, _, _, _ -> Unit }.collect { wake.trySend(Unit) } }
            work.restore()
            ordinaryReady = true
            mutable.value = mutable.value.copy(configured = true, offer = work.offer, installable = work.installable)

            fun deadline(duration: Long) = elapsed().let { if (Long.MAX_VALUE - it < duration) Long.MAX_VALUE else it + duration }
            fun retry(value: Long) = if (value >= current.retryMaxMillis / 2) current.retryMaxMillis else value * 2
            fun begin(action: Action, manual: Boolean) {
                automatic = !manual
                operationAction = action
                installingOperation = action == Action.INSTALL
                mutable.value = mutable.value.copy(busy = true, checking = action == Action.CHECK, error = null, manual = manual)
                operation = launch {
                    try {
                        if (action == Action.CHECK) {
                            val online = work.check(current.sources)
                            nextCheck = deadline(if (online) random(current.intervalMillis - current.jitterMillis, current.intervalMillis + current.jitterMillis) else checkRetry)
                            checkRetry = if (online) current.retryMinMillis else retry(checkRetry)
                        } else if (action == Action.DOWNLOAD) {
                            val complete = work.download(manual, environment)
                            // A policy pause resumes on a policy change without
                            // treating the user's video/attention as a failure.
                            nextDownload = if (complete) Long.MAX_VALUE else 0L
                            downloadRetry = current.retryMinMillis
                        } else {
                            work.install(manual, environment)
                            nextInstall = deadline(current.retryMinMillis)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
                        mutable.value = mutable.value.copy(error = if (action == Action.INSTALL) "package" else "network")
                        if (action == Action.CHECK) { nextCheck = deadline(checkRetry); checkRetry = retry(checkRetry) }
                        else if (action == Action.DOWNLOAD) { nextDownload = deadline(downloadRetry); downloadRetry = retry(downloadRetry) }
                        else { nextInstall = deadline(current.retryMinMillis) }
                    } finally {
                        installingOperation = false
                        mutable.value = mutable.value.copy(busy = false, checking = false, manual = false, offer = work.offer)
                        wake.trySend(Unit)
                    }
                }
                activeOperation = operation
                operation!!.invokeOnCompletion { wake.trySend(Unit) }
            }

            while (isActive) {
                if (operation?.isCompleted == true) operation = null
                val networkReady = policy.value.enabled && availability.value.connected
                val automaticReady = networkReady && availability.value.timeTrusted
                if (automatic && operation != null && operationAction != Action.INSTALL && (!networkReady || operationAction == Action.CHECK && !automaticReady)) {
                    operation!!.cancelAndJoin(); operation = null
                }
                if (automaticReady && nextCheck == null && current.sources.isNotEmpty()) {
                    nextCheck = deadline(random(current.initialMinMillis, current.initialMaxMillis))
                }
                val now = elapsed()
                val state = work.status.value
                if (state.catalog_digest != downloadDigest) {
                    downloadDigest = state.catalog_digest
                    nextDownload = 0L; downloadRetry = current.retryMinMillis
                    nextInstall = 0L
                }
                val canDownload = networkReady && environment.value.allows(false) && state.size > 0 &&
                    state.phase in setOf(UpdateState.Phase.AVAILABLE.name, UpdateState.Phase.FAILED.name)
                val canInstall = ordinaryReady && nativeLease == null && automaticInstall.value && work.installable && state.phase == UpdateState.Phase.READY.name
                if (operation == null && nativeLease == null && ordinaryReady) {
                    if (canInstall && now >= nextInstall) begin(Action.INSTALL, false)
                    else if (automaticReady && nextCheck != null && now >= nextCheck!! && current.sources.isNotEmpty()) begin(Action.CHECK, false)
                    else if (canDownload && now >= nextDownload) begin(Action.DOWNLOAD, false)
                }
                mutable.value = mutable.value.copy(nextCheckElapsed = nextCheck)
                val next = if (operation == null && nativeLease == null && ordinaryReady) listOfNotNull(nextCheck?.takeIf { automaticReady }, nextDownload.takeIf { canDownload }, nextInstall.takeIf { canInstall }).minOrNull() else null
                select<Unit> {
                    requests.onReceive { request ->
                        try {
                            val valid = ordinaryReady && nativeLease == null && !request.accepted.isCancelled && request.policy == current && current.fresh(request.tap, elapsed()) &&
                                (request.action != Action.CHECK || current.sources.isNotEmpty()) &&
                                (request.action != Action.DOWNLOAD || environment.value.allows(true)) &&
                                (request.action != Action.INSTALL || work.installable && environment.value.allowsInstall() && work.status.value.phase == "READY") &&
                                work.status.value.phase != "INSTALLING"
                            if (!valid || operation != null && (!automatic || operationAction == Action.INSTALL)) request.accepted.complete(false)
                            else {
                                operation?.cancelAndJoin(); operation = null
                                // Cancellation/IO cleanup can outlast the five-second window.
                                if (!current.fresh(request.tap, elapsed())) request.accepted.complete(false)
                                else { begin(request.action, true); request.accepted.complete(true) }
                            }
                        } finally {
                            // A channel/source change can cancel this handler
                            // while it waits for old IO to close. Its caller must
                            // receive refusal instead of waiting forever.
                            request.accepted.complete(false)
                        }
                    }
                    nativeRequests.onReceive { request ->
                        try {
                            fun valid() = ordinaryReady && nativeRelease?.isActive != true && nativeLease == request.owner && !request.accepted.isCancelled &&
                                request.policy == current && current.fresh(request.tap, elapsed()) &&
                                current.sources.isNotEmpty() && work is FeatureModuleWork &&
                                (if (request.installing) environment.value.allowsInstall() else environment.value.allows(true)) &&
                                work.status.value.phase != "INSTALLING"
                            if (!valid() || operation != null && (!automatic || operationAction == Action.INSTALL)) request.accepted.complete(false)
                            else {
                                operation?.cancelAndJoin(); operation = null
                                // The old transport may take longer than five seconds to close.
                                if (!valid()) request.accepted.complete(false)
                                else {
                                    automatic = false
                                    operationAction = if (request.installing) Action.INSTALL else Action.DOWNLOAD
                                    installingOperation = request.installing
                                    mutable.value = mutable.value.copy(busy = true, checking = false, manual = true, error = null)
                                    nativeOwner = request.owner
                                    operation = launch {
                                        try { request.body(work) }
                                        catch (cancelled: CancellationException) { throw cancelled }
                                        catch (_: Exception) { mutable.value = mutable.value.copy(error = if (request.installing) "package" else "network") }
                                        finally {
                                            installingOperation = false
                                            if (nativeOwner == request.owner) { nativeOwner = null; nativeOperation = null }
                                            mutable.value = mutable.value.copy(busy = false, checking = false, manual = false)
                                            wake.trySend(Unit)
                                        }
                                    }
                                    nativeOperation = operation
                                    activeOperation = operation
                                    operation!!.invokeOnCompletion { wake.trySend(Unit) }
                                    request.accepted.complete(true)
                                }
                            }
                        } finally { request.accepted.complete(false) }
                    }
                    wake.onReceive { }
                    if (next != null) onTimeout((next - elapsed()).coerceAtLeast(1)) { }
                }
            }
        } finally {
            if (activeWork === work) { activeWork = null; activeOperation = null; ordinaryReady = false }
            // coroutineScope waits for transfer cancellation, before use closes
            // transport or collectLatest constructs the next channel/session.
            while (true) (requests.tryReceive().getOrNull() ?: break).accepted.complete(false)
            while (true) (nativeRequests.tryReceive().getOrNull() ?: break).accepted.complete(false)
        }
    }

    companion object {
        private fun idle(channel: String) = UpdateStateDocument(1, channel, UpdateState.Phase.IDLE.name, 0, 0)
    }
}
