package moe.kiwi.thinkos.net

import java.io.Closeable
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** System DNS discovery only. None of its output grants permission to connect. */
internal class PluginDnsResolver(
    private val backend: Backend = SystemBackend,
    private val timeoutMillis: Long = 3_000,
) : Closeable {
    data class Entry(val address: String, val name: String? = null)

    /** Injectable system calls keep JVM tests offline, including stuck libc calls. */
    interface Backend {
        fun lookup(host: String): List<InetAddress>
        fun canonical(address: InetAddress): String?
    }

    private object SystemBackend : Backend {
        override fun lookup(host: String): List<InetAddress> = InetAddress.getAllByName(host).toList()
        override fun canonical(address: InetAddress): String? = address.canonicalHostName
    }

    class DnsFailure internal constructor(message: String) : Exception(message)

    private val serial = NEXT_POOL.incrementAndGet()
    private fun threads(kind: String) = ThreadFactory { task ->
        Thread(task, "ThinkOS-Resolve-$serial-$kind").apply { isDaemon = true }
    }
    // libc DNS need not respond to interruption. Never replace its stuck
    // threads or permit an unbounded queue to accumulate behind them.
    private val workers = ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(2), threads("DNS"), ThreadPoolExecutor.AbortPolicy())
    private val deadlines = ScheduledThreadPoolExecutor(1, threads("Deadline")).apply {
        removeOnCancelPolicy = true
        setExecuteExistingDelayedTasksAfterShutdownPolicy(false)
    }
    private val requests = ConcurrentHashMap.newKeySet<Request>()
    private val closed = AtomicBoolean()

    init { require(timeoutMillis in 1..3_000) }

    /** Deadline includes queueing, A/AAAA, reverse lookup and forward confirmation. */
    fun resolve(host: String, completion: (Result<List<Entry>>) -> Unit = {}): Future<List<Entry>> {
        val began = System.nanoTime()
        val request = Request(completion)
        val normalized = normalizeHost(host)
        if (normalized == null) {
            request.fail("dns invalid host")
            return request
        }
        if (closed.get()) {
            request.fail("dns unavailable")
            return request
        }
        requests.add(request)
        request.task = FutureTask {
            if (!request.finished.get()) {
                try {
                    val entries = discover(normalized, request)
                    if (!request.finished.get()) {
                        if (System.nanoTime() - began >= TimeUnit.MILLISECONDS.toNanos(timeoutMillis)) request.fail("dns timeout")
                        else request.succeed(entries)
                    }
                } catch (_: Exception) {
                    request.fail("dns unavailable")
                }
            }
            Unit
        }
        try {
            val remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis) - (System.nanoTime() - began)
            if (remaining <= 0) request.fail("dns timeout")
            else {
                request.timer = deadlines.schedule({ request.fail("dns timeout") }, remaining, TimeUnit.NANOSECONDS)
                // A very short injected deadline can fire before its handle is assigned.
                if (request.finished.get()) request.timer?.cancel(false)
                if (!request.finished.get()) workers.execute(request.task)
                // Cancellation can race execute(), including while both DNS
                // threads are stuck. A cancelled queued task must not keep a slot.
                if (request.finished.get()) workers.remove(request.task)
            }
        } catch (_: RejectedExecutionException) {
            request.fail(if (closed.get()) "dns unavailable" else "dns busy")
        }
        return request
    }

    private fun discover(host: String, request: Request): List<Entry> {
        val unique = linkedMapOf<List<Byte>, InetAddress>()
        for (address in backend.lookup(host)) {
            if (request.finished.get()) return emptyList()
            if (address.address.size in setOf(4, 16)) unique.putIfAbsent(address.address.toList(), address)
            if (unique.size == MAX_RESULTS) break
        }
        if (unique.isEmpty()) throw DnsFailure("dns unavailable")
        return unique.values.map { address ->
            if (request.finished.get()) return emptyList()
            val canonical = try { backend.canonical(address)?.let(::normalizeHost) } catch (_: Exception) { null }
            val confirmed = if (canonical != null && !request.finished.get()) {
                try { backend.lookup(canonical).any { it.address.contentEquals(address.address) } }
                catch (_: Exception) { false }
            } else false
            Entry(address.hostAddress ?: throw DnsFailure("dns unavailable"), canonical.takeIf { confirmed })
        }
    }

    private inner class Request(completion: (Result<List<Entry>>) -> Unit) : Future<List<Entry>> {
        val finished = AtomicBoolean()
        private val value = CompletableFuture<List<Entry>>()
        @Volatile var task: FutureTask<Unit>? = null
        @Volatile var timer: ScheduledFuture<*>? = null

        init {
            value.whenComplete { result, error ->
                completion(if (error == null) Result.success(result) else Result.failure(
                    if (error is DnsFailure) error else DnsFailure("dns unavailable")))
            }
        }

        private fun cleanup(interrupt: Boolean) {
            timer?.cancel(false)
            task?.let { work ->
                if (interrupt) work.cancel(true)
                workers.remove(work)
            }
            requests.remove(this)
        }
        fun succeed(entries: List<Entry>) {
            if (!finished.compareAndSet(false, true)) return
            cleanup(false)
            value.complete(entries)
        }
        fun fail(message: String) {
            if (!finished.compareAndSet(false, true)) return
            cleanup(true)
            value.completeExceptionally(DnsFailure(message))
        }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            if (!finished.compareAndSet(false, true)) return false
            cleanup(mayInterruptIfRunning)
            // Even cancel(false) removes an unstarted task; a running libc call
            // stays within the same two fixed workers and its result is ignored.
            task?.cancel(mayInterruptIfRunning)
            return value.cancel(mayInterruptIfRunning)
        }
        override fun isCancelled(): Boolean = value.isCancelled
        override fun isDone(): Boolean = value.isDone
        override fun get(): List<Entry> = value.get()
        override fun get(timeout: Long, unit: TimeUnit): List<Entry> = value.get(timeout, unit)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        requests.toList().forEach { it.cancel(true) }
        workers.shutdownNow()
        deadlines.shutdownNow()
    }

    companion object {
        const val MAX_RESULTS = 8
        private val NEXT_POOL = AtomicInteger()
        private val LABEL = Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")
        private val NUMERIC = Regex("(?:0[xX][0-9A-Fa-f]+|[0-9]+)")

        /** ASCII DNS host only: no URI, port, IP literal, search syntax or IDN conversion. */
        fun normalizeHost(host: String): String? {
            if (host.isEmpty() || host.length > 253 || host.any { it.code !in 33..126 }) return null
            val value = host.removeSuffix(".")
            val labels = value.split('.')
            if (labels.any { !LABEL.matches(it) } || labels.all { NUMERIC.matches(it) }) return null
            return value.lowercase(Locale.ROOT)
        }
    }
}
