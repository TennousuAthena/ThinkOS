package moe.kiwi.thinkos.update

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

/** Signed-catalog → durable sequence floor → eligibility → content-verified download.
 * No scheduling, Android package verification or installation side effects here. */
internal class UpdateCatalogClient(private val fetcher: Fetcher, private val keys: UpdateKeys, private val sequences: UpdateSequenceStore,
                                   private val cache: UpdateCatalogCache? = null, private val selection: Selection = Selection()) {
    enum class Origin { NETWORK, NOT_MODIFIED, OFFLINE_CACHE }
    data class Selection(val staggerMillis: Long = 3_000, val settleMillis: Long = 4_000) {
        init { require(staggerMillis in 1..60_000 && settleMillis in 0..60_000) }
    }
    data class Checked(val catalog: UpdateCatalog, val origin: Origin, val networkError: IOException? = null)

    suspend fun check(candidates: List<String>, channel: String): UpdateCatalog = checkDetailed(candidates, channel).catalog

    suspend fun cached(channel: String): UpdateCatalog? = cachedEntry(channel)?.catalog

    private suspend fun cachedEntry(channel: String): UpdateCatalogCache.Entry? = UpdateIo.run {
        require(channel in setOf("stable", "beta"))
        val entry = try { cache?.read(channel) } catch (_: UpdateCatalogCache.Invalid) { null }
        if (entry == null) null else try { sequences.requireCurrent(entry.catalog); entry } catch (_: UpdateSequenceStore.Rollback) { null }
    }

    /** Several mirrors are asked in configured order, the next one starting as soon as the previous one fails or has been
     * silent for [Selection.staggerMillis], so a blackholed first source delays the check by seconds, not a connect timeout.
     * Once one authenticated catalog arrives the rest get [Selection.settleMillis] to answer, and the highest sequence wins:
     * a lagging mirror that says "nothing new" must not hide a newer release served elsewhere. Every response is
     * authenticated on its own, and the persistent floor still rejects anything older than what was already seen. */
    suspend fun checkDetailed(candidates: List<String>, channel: String): Checked {
        require(candidates.size in 1..MAX_SOURCES)
        val cached = cachedEntry(channel)
        val failures = IOException("No authenticated update catalog available")
        val sources = ArrayList<String>()
        // Mirrors announced by the newest AUTHENTICATED catalog follow the configured sources, so a source can be added or
        // replaced by publishing, without shipping a new APK. They never displace a configured source (eight in total).
        for (candidate in (candidates.toList() + cached?.catalog?.document?.mirrors.orEmpty()).take(MAX_SOURCES)) {
            try {
                val source = UpdateIo.run {
                    require(candidate.length in 1..2048)
                    candidate.toHttpUrl().toString().also(UpdateCatalog::validateUrl)
                }
                if (source !in sources) sources.add(source)
            } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) { failures.addSuppressed(error) }
        }
        fetcher.health?.beginCheck()
        val outcomes = if (sources.isEmpty()) emptyList() else race(sources, cached, channel)
        outcomes.firstNotNullOfOrNull { it.error as? CancellationException }?.let { throw it }
        outcomes.forEach { outcome -> outcome.error?.let(failures::addSuppressed) }
        val ranked = outcomes.filter { it.fetched != null }
            .sortedWith(compareByDescending<Outcome> { it.fetched!!.catalog.document.seq }.thenBy { it.index })
        for (outcome in ranked) {
            val fetched = outcome.fetched!!
            try { UpdateIo.run {
                sequences.accept(fetched.catalog)
                if (fetched.origin == Origin.NETWORK) cache?.write(fetched.catalog, fetched.source, fetched.etag, sequences)
                sequences.requireCurrent(fetched.catalog)
            } } catch (error: UpdateSequenceStore.Rollback) {
                failures.addSuppressed(error)
                continue
            }
            return Checked(fetched.catalog, fetched.origin)
        }
        cachedEntry(channel)?.let { return Checked(it.catalog, Origin.OFFLINE_CACHE, failures) }
        throw failures
    }

    private class Fetched(val source: String, val catalog: UpdateCatalog, val etag: String?, val origin: Origin)
    private class Outcome(val index: Int, val fetched: Fetched?, val error: Exception?)

    private suspend fun attempt(source: String, cached: UpdateCatalogCache.Entry?, channel: String): Fetched {
        val prior = cached?.takeIf { it.source == source && it.etag != null }
        val signatureUrl = UpdateIo.run { signatureUrl(source) }
        return when (val document = fetcher.readDocument(listOf(source), Fetcher.DocumentKind.CATALOG, etag = prior?.etag)) {
            Fetcher.Document.NotModified -> {
                check(prior != null) { "Not-modified response without authenticated cache" }
                Fetched(source, prior.catalog, prior.etag, Origin.NOT_MODIFIED)
            }
            is Fetcher.Document.Body -> {
                val signature = fetcher.readDocument(listOf(signatureUrl), Fetcher.DocumentKind.SIGNATURE) as Fetcher.Document.Body
                Fetched(source, UpdateIo.run { UpdateCatalog.read(document.bytes, signature.bytes, keys, channel) }, document.etag, Origin.NETWORK)
            }
        }
    }

    private suspend fun race(sources: List<String>, cached: UpdateCatalogCache.Entry?, channel: String): List<Outcome> = coroutineScope {
        val results = Channel<Outcome>(Channel.UNLIMITED)
        val collected = ArrayList<Outcome>()
        val stagger = selection.staggerMillis * NANOS_PER_MILLI
        val settle = selection.settleMillis * NANOS_PER_MILLI
        var started = 0
        fun start() {
            val index = started++
            launch {
                val outcome = try { Outcome(index, attempt(sources[index], cached, channel), null) } catch (error: Exception) {
                    if (!isActive) throw error
                    Outcome(index, null, error)
                }
                results.trySend(outcome)
            }
        }
        start()
        var nextStart = System.nanoTime() + stagger
        var settleBy = Long.MAX_VALUE
        while (collected.size < sources.size) {
            val deadline = if (started < sources.size) minOf(settleBy, nextStart) else settleBy
            val outcome = if (deadline == Long.MAX_VALUE) results.receive()
                else withTimeoutOrNull(maxOf(0L, deadline - System.nanoTime()) / NANOS_PER_MILLI + 1) { results.receive() }
            if (outcome != null) {
                collected += outcome
                if (outcome.fetched != null) {
                    if (settleBy == Long.MAX_VALUE) settleBy = System.nanoTime() + settle
                    while (started < sources.size) start()
                } else if (settleBy == Long.MAX_VALUE && started < sources.size) {
                    start()
                    nextStart = System.nanoTime() + stagger
                }
            } else {
                val now = System.nanoTime()
                if (now >= settleBy) break
                if (started < sources.size && now >= nextStart) { start(); nextStart = now + stagger }
            }
        }
        coroutineContext.cancelChildren()
        collected
    }

    /** Caller's live media/attention/user-action policy still gates starting this operation.
     * Returned bytes have not passed PackageManager's APK/certificate checks and are not READY. */
    suspend fun downloadApk(catalog: UpdateCatalog, device: UpdateCatalog.Device, directory: File, onProgress: (Long) -> Unit = {}): File {
        require(catalog.ineligible(device) == null) { "Update release is incompatible or not newer" }
        UpdateIo.run { sequences.accept(catalog) }
        val file = fetcher.download(catalog.document.release.urls, catalog.apk(), directory, onProgress)
        UpdateIo.run { sequences.accept(catalog) } // A newer check during transfer must prevent handing out an older candidate.
        return file
    }

    suspend fun downloadVerifiedApk(catalog: UpdateCatalog, device: UpdateCatalog.Device, directory: File,
                                   verifier: UpdatePackageVerifier, onProgress: (Long) -> Unit = {}): UpdatePackageVerifier.Verified {
        val file = downloadApk(catalog, device, directory, onProgress)
        val verified = verifier.verify(catalog, file)
        UpdateIo.run { sequences.accept(catalog) }
        return verified
    }

    internal companion object {
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val MAX_SOURCES = 8

        fun signatureUrl(source: String): String {
            UpdateCatalog.validateUrl(source)
            val url = source.toHttpUrl()
            return url.newBuilder().encodedPath(url.encodedPath + ".sig").build().toString()
        }
    }
}
