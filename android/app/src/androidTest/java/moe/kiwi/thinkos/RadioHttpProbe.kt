package moe.kiwi.thinkos

import android.content.Context
import android.media.AudioManager
import android.os.Process
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.kiwi.thinkos.net.PluginDnsResolver
import moe.kiwi.thinkos.net.PluginHttp
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** Fixed official metadata GETs through unchanged production PluginHttp.
 * No activity, playback, stream URL, public probe, source setting or signing key.
 * Response bodies and raw exception messages stay in worker RAM, never reports.
 * The parent driver owns foreground/input checks, APK identity and restoration.
 */
internal object RadioHttpProbe {
    private const val SEED = "all.api.radio-browser.info"
    private const val QUERY = "/json/stations/search?hidebroken=true&is_https=true&order=clickcount&reverse=true&limit=100&offset=0&countrycode=DE"
    private const val URL = "https://all.api.radio-browser.info$QUERY"
    private const val DEADLINE_MS = 30_000L
    private const val JOIN_MS = 5_000L
    // Reserve cleanup inside the new discovery phase's 30-second network
    // budget. The historical radio_http phase keeps its original deadline.
    private const val DISCOVERY_WORK_MS = DEADLINE_MS - JOIN_MS
    private const val RADIO_BODY_LIMIT = 256 * 1024
    // PluginHttp returns decoded UTF-8 String, not raw wire octets. Invalid
    // UTF-8 replacement characters can expand the 1MiB input on re-encoding.
    private const val RETURNED_UTF8_LIMIT = 3 * 1024 * 1024
    private val immutable = listOf("settings/local.json", "store/welcome.json",
        "cast/identity-seed", "cast/airplay-identity", "management-exit")
    private val pending = listOf("pending.json", "pending.json.bak", "module-pending.json", "module-pending.json.bak")
    private val fields = listOf("lastcheckok", "ssl_error", "url_resolved", "country", "countrycode")
    private val candidateName = Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.api\\.radio-browser\\.info")
    private val headers = mapOf("headers" to mapOf("User-Agent" to "ThinkOS-Radio/0.1.0", "Accept" to "application/json"))

    suspend fun run(runner: UpdateInstrumentation, directory: File, token: String, result: JSONObject,
                    test: suspend (String, suspend () -> Unit) -> Unit, discovery: Boolean = false) {
        check(token.matches(Regex("[a-f0-9]{32}")))
        val label = if (discovery) "radio_discovery" else "radio_http"
        val workBudget = if (discovery) DISCOVERY_WORK_MS else DEADLINE_MS
        val context = runner.targetContext
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        fun quiet() = check((1..5).all { audio.getStreamVolume(it) == 0 }) { "Audio streams 1-5 must remain zero" }
        val area = File(directory, if (discovery) "radio-discovery" else "radio-http")
        check(!area.exists() && area.mkdirs()) { "Use a fresh owned $label run_id" }
        val original = immutable.associateWith { name ->
            File(context.filesDir, name).let { file ->
                if (!file.exists()) null else { check(file.isFile && file.length() <= 8 * 1024 * 1024); file.readBytes() }
            }
        }
        fun filesUnchanged() = original.all { (name, before) ->
            val file = File(context.filesDir, name)
            if (before == null) !file.exists() else file.isFile && file.length() == before.size.toLong() && file.readBytes().contentEquals(before)
        }
        val report = JSONObject().put("scope", if (discovery)
            "Real production DNS discovery, then at most two named official metadata GETs through real PluginHttp.request; no stream playback/probe, Lua parser or actual peer claim"
            else "One fixed official radio-browser metadata GET through real PluginHttp.request; no stream playback/probe or Lua parser claim")
            .put("request", if (discovery) QUERY else URL).put("method", "GET")
            .put("headers", JSONObject().put("User-Agent", "ThinkOS-Radio/0.1.0").put("Accept", "application/json"))
            .put("production_connect_ms", 3000).put("production_read_ms", 3000)
            .put("outer_deadline_ms", DEADLINE_MS).put("worker_join_ms", JOIN_MS)
            .put("worker_budget_ms", workBudget)
            .put("redirect_policy", if (discovery) "Unchanged production PluginHttp; at most two discovered named origins, no TLS override or extra diagnostic host"
                else "Unchanged production PluginHttp; no extra diagnostic host or retry")
            .put("body_encoding", "UTF-8 re-encoding of the production returned String, not raw wire bytes")
        if (discovery) report.put("discovery_seed", SEED).put("maximum_attempts", 2)
            .put("peer_available", false).put("peer_observation", "Production PluginHttp does not expose the connected peer; discovery addresses are not connection evidence")
        result.put(label, report)
        val outcome = AtomicReference<JSONObject?>()
        val io = Executors.newSingleThreadExecutor { task ->
            Thread(task, "ThinkOS-RadioHttp-" + token.take(8)).apply { isDaemon = true }
        }
        var work: Future<*>? = null
        var networkStarted: Long? = null
        var host: RuntimeHost? = null
        var baseline: Map<String, Any?>? = null
        suspend fun safeTest(name: String, action: suspend () -> Unit) {
            test(name) {
                try { action() }
                catch (error: Throwable) {
                    // The shared runner logs thrown stacks, so never rethrow a
                    // JSON/HTTP exception that might quote response contents.
                    report.put("fixture_causes", causes(error))
                    throw IllegalStateException("$label check failed; see sanitized $label observations")
                }
            }
        }
        suspend fun unchanged() {
            quiet()
            check(filesUnchanged()) { "Preserve changed immutable bytes; never restore over them" }
            check(pending.none { File(context.filesDir, "update/" + it).exists() })
            host?.let { actual ->
                check(withContext(Dispatchers.Main) { actual.media.snapshot() } == baseline)
                check(withContext(Dispatchers.Main) {
                    val update = actual.updates.status.value
                    !update.busy && !update.checking && update.state.phase !in setOf("CHECKING", "DOWNLOADING", "READY", "INSTALLING")
                })
            }
        }
        try {
            safeTest("radio official GET preflight has five silent streams, no media or update transaction, and unchanged private files") {
                quiet()
                check(Process.myUid() >= 10_000)
                check(pending.none { File(context.filesDir, "update/" + it).exists() })
                val actual = withContext(Dispatchers.Main) { (context.applicationContext as ThinkApplication).runtime }
                host = actual
                baseline = withContext(Dispatchers.Main) {
                    check(!actual.router.onboardingOpen && !actual.store.current.safeMode)
                    check(!actual.updates.screensSnapshot().settings)
                    actual.media.snapshot().also { state ->
                        check(state["status"] == "stopped" && state["owner"].toString().isEmpty() &&
                            (state["count"] as Number).toInt() == 0 && state["kind"].toString().isEmpty())
                    }
                }
                unchanged()
            }
            runner.checkedSnapshot()
            safeTest(if (discovery) "production DNS discovers a named official server whose unchanged PluginHttp GET returns actual HTTP200 and a nonempty JSON array"
                else "unchanged production PluginHttp returns actual HTTP200 and a nonempty JSON array from the fixed official directory") {
                unchanged()
                val started = SystemClock.elapsedRealtime()
                networkStarted = started
                work = io.submit {
                    val observation = JSONObject()
                    try {
                        if (discovery) discover(observation, started + workBudget)
                        else describe(PluginHttp.request("GET", URL, null, headers), observation)
                    } catch (error: Throwable) {
                        observation.put("outcome", "exception").put("causes", causes(error))
                    } finally {
                        observation.put("elapsed_ms", SystemClock.elapsedRealtime() - started)
                        outcome.set(observation)
                    }
                }
                val remaining = workBudget - (SystemClock.elapsedRealtime() - started)
                try {
                    check(remaining > 0)
                    requireNotNull(work).get(remaining, TimeUnit.MILLISECONDS)
                } catch (error: TimeoutException) {
                    requireNotNull(work).cancel(true)
                    report.put("deadline_exceeded", true).put("wait_causes", causes(error))
                } catch (error: Throwable) {
                    requireNotNull(work).cancel(true)
                    report.put("wait_causes", causes(error))
                }
                val observed = outcome.get()
                if (observed != null) report.put("response", observed)
                report.put("wait_elapsed_ms", SystemClock.elapsedRealtime() - started)
                check(!report.optBoolean("deadline_exceeded") && observed != null &&
                    (if (discovery) observed.optBoolean("discovery_http_ok") else success(observed)))
                unchanged()
            }
            // Keep the observation even when the actual GET fails. Do not
            // checkedSnapshot() here: the preservation check must still run.
            safeTest("radio official GET leaves five immutable files, all media state and all five silent streams unchanged") {
                unchanged()
                report.put("five_immutable_files_unchanged", true).put("media_snapshot_unchanged", true)
                    .put("audio_streams_1_to_5_zero", true)
            }
        } finally {
            work?.let { if (!it.isDone) it.cancel(true) }
            io.shutdownNow()
            val cleanupBudget = if (discovery) networkStarted?.let {
                (it + DEADLINE_MS - SystemClock.elapsedRealtime()).coerceIn(0L, JOIN_MS)
            } ?: JOIN_MS else JOIN_MS
            val stopped = withContext(Dispatchers.IO) { io.awaitTermination(cleanupBudget, TimeUnit.MILLISECONDS) }
            outcome.get()?.let { report.put("response", it) }
            report.put("owned_worker_stopped", stopped)
            if (!stopped) report.put("cleanup", "Production HTTP did not stop after cancellation; fail and let the controlled instrumentation finish terminate its process")
            try {
                unchanged()
                report.put("finally_private_files_unchanged", true).put("finally_media_unchanged", true)
                    .put("finally_audio_streams_1_to_5_zero", true)
            } catch (error: Throwable) {
                report.put("preservation_causes", causes(error))
            }
            area.resolve("observations.json").writeText(report.toString(2))
            // This happens outside shared test() too; on failure complete stays
            // false, even if earlier HTTP and shape assertions happened to pass.
            check(stopped && report.optBoolean("finally_private_files_unchanged") &&
                report.optBoolean("finally_media_unchanged") && report.optBoolean("finally_audio_streams_1_to_5_zero")) {
                "$label cleanup or preservation failed; retain its private recovery journal"
            }
        }
        runner.checkedSnapshot()
    }

    private fun success(observation: JSONObject) = observation.optString("outcome") == "response" &&
        observation.optInt("status") == 200 && observation.optString("json_top_level") == "array" &&
        observation.optInt("json_count") > 0

    /** This calls the same bounded resolver exposed by tos.net.resolve. Only
     * its forward-confirmed canonical names can become HTTPS origins. No IP
     * URL, Host override, forced server, alternate TLS client or verifier. */
    private fun discover(out: JSONObject, deadline: Long) {
        val resolved = PluginDnsResolver().use { resolver ->
            val request = resolver.resolve(SEED)
            try {
                val remaining = deadline - SystemClock.elapsedRealtime()
                check(remaining > 0 && !Thread.currentThread().isInterrupted)
                request.get(remaining, TimeUnit.MILLISECONDS)
            } finally { if (!request.isDone) request.cancel(true) }
        }
        check(resolved.size <= 8)
        val candidates = linkedMapOf<String, MutableList<String>>()
        val records = JSONArray()
        for (entry in resolved) {
            // Production entries are numeric canonical addresses, not arbitrary
            // DNS TXT or HTTP data. Keep that bound before copying to reports.
            check(entry.address.length in 3..64 && entry.address.matches(Regex("[0-9A-Fa-f:.]+")))
            val name = entry.name
            val accepted = name != null && name != SEED && candidateName.matches(name)
            records.put(JSONObject().put("address", entry.address).put("name_present", name != null)
                .put("candidate_accepted", accepted).apply { if (accepted) put("name", name) })
            if (accepted) candidates.getOrPut(requireNotNull(name)) { mutableListOf() }.add(entry.address)
        }
        out.put("discovery", records).put("discovery_count", resolved.size)
            .put("eligible_candidate_count", candidates.size)
        val attempts = JSONArray()
        out.put("attempts", attempts).put("discovery_http_ok", false)
        check(candidates.isNotEmpty()) { "No eligible named official server" }
        for ((name, addresses) in candidates.entries.take(2)) {
            // Never start another HTTPS call after timeout/cancellation. The
            // production client retains its 3s connection/read settings.
            check(SystemClock.elapsedRealtime() < deadline && !Thread.currentThread().isInterrupted)
            val attempt = JSONObject().put("hostname", name).put("discovery_addresses", JSONArray(addresses))
                .put("peer_available", false)
            attempts.put(attempt)
            val started = SystemClock.elapsedRealtime()
            try { describe(PluginHttp.request("GET", "https://$name$QUERY", null, headers), attempt) }
            catch (error: Throwable) { attempt.put("outcome", "exception").put("causes", causes(error)) }
            finally { attempt.put("elapsed_ms", SystemClock.elapsedRealtime() - started) }
            if (success(attempt)) { out.put("discovery_http_ok", true); break }
        }
    }

    private fun describe(response: Map<String, Any?>, out: JSONObject) {
        val status = (response["status"] as? Number)?.toInt() ?: error("Invalid production HTTP status type")
        val body = response["body"] as? String ?: error("Invalid production HTTP body type")
        val bytes = body.toByteArray(Charsets.UTF_8)
        check(bytes.size <= RETURNED_UTF8_LIMIT)
        val headers = response["headers"] as? Map<*, *>
        val contentType = headers?.entries?.firstOrNull { it.key is String && (it.key as String).equals("content-type", true) }?.value as? String
        // Only a bounded, known MIME token is reported; header parameters and
        // unexpected header text may contain arbitrary data from the server.
        val mime = contentType?.substringBefore(';')?.trim()?.lowercase()
        out.put("outcome", "response").put("status", status).put("body_utf8_bytes", bytes.size)
            .put("body_sha256", MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) })
            .put("content_type", if (mime in setOf("application/json", "text/json", "text/html", "text/plain", "application/octet-stream")) mime else "other_or_missing")
            .put("radio_body_limit_bytes", RADIO_BODY_LIMIT).put("exceeds_radio_body_limit", bytes.size > RADIO_BODY_LIMIT)
        try {
            boundedJson(body)
            val tokener = JSONTokener(body)
            val top = tokener.nextValue()
            check(tokener.nextClean() == '\u0000')
            out.put("json_top_level", type(top))
            when (top) {
                is JSONArray -> {
                    out.put("json_count", top.length())
                    val statistics = JSONObject()
                    fields.forEach { statistics.put(it, JSONObject()) }
                    val rowTypes = JSONObject()
                    var resolvedEmpty = 0; var resolvedHttps = 0; var countryCodeDE = 0
                    var lastCheckOne = 0; var sslErrorZero = 0
                    for (index in 0 until top.length()) {
                        val row = top.opt(index)
                        increment(rowTypes, type(row))
                        for (field in fields) {
                            val kind = if (row is JSONObject && row.has(field)) type(row.opt(field)) else "missing"
                            increment(statistics.getJSONObject(field), kind)
                        }
                        if (row is JSONObject) {
                            val resolved = row.opt("url_resolved") as? String
                            if (resolved != null && resolved.isEmpty()) resolvedEmpty++
                            if (resolved?.startsWith("https://") == true) resolvedHttps++
                            if (row.opt("countrycode") == "DE") countryCodeDE++
                            if (row.opt("lastcheckok")?.toString() in setOf("1", "1.0")) lastCheckOne++
                            if (row.opt("ssl_error")?.toString() in setOf("0", "0.0")) sslErrorZero++
                        }
                    }
                    out.put("row_types", rowTypes).put("field_types", statistics)
                        .put("url_resolved_empty_count", resolvedEmpty).put("url_resolved_https_count", resolvedHttps)
                        .put("countrycode_DE_count", countryCodeDE).put("lastcheckok_one_count", lastCheckOne)
                        .put("ssl_error_zero_count", sslErrorZero)
                }
                is JSONObject -> out.put("json_count", top.length())
                else -> out.put("json_count", 0)
            }
        } catch (error: Throwable) {
            // Android JSON exceptions may include the response near an invalid
            // token. Record only class/approved categories, never that excerpt.
            out.put("json_top_level", "invalid_or_out_of_bounds").put("json_count", 0).put("json_causes", causes(error))
        }
    }

    private fun increment(counts: JSONObject, key: String) { counts.put(key, counts.optInt(key) + 1) }
    private fun type(value: Any?): String = when {
        value == null || value === JSONObject.NULL -> "null"
        value is JSONArray -> "array"
        value is JSONObject -> "object"
        value is String -> "string"
        value is Boolean -> "boolean"
        value is Number -> "number"
        else -> "other"
    }

    /** Bound nesting and top-level array members before allocating JSON trees. */
    private fun boundedJson(text: String) {
        var depth = 0; var quoted = false; var escaped = false; var rootArray = false
        var arrayValue = false; var count = 0; var first = true
        for (c in text) {
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                continue
            }
            if (first && !c.isWhitespace()) { rootArray = c == '['; first = false }
            if (rootArray && depth == 1 && arrayValue && !c.isWhitespace() && c != ']') { check(++count <= 512); arrayValue = false }
            when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; check(depth <= 32); if (depth == 1 && rootArray) arrayValue = true }
                '}', ']' -> { depth--; check(depth >= 0) }
                ',' -> if (depth == 1 && rootArray) arrayValue = true
            }
        }
        check(!quoted && depth == 0)
    }

    /** Only fixed diagnostic phrases/errno tokens survive. Raw TLS/JSON/network
     * messages can quote URLs, certificates or server contents and are omitted. */
    private fun causes(error: Throwable): JSONArray {
        val approved = listOf("timed out", "timeout", "unable to resolve host", "unresolved", "connection reset",
            "dns unavailable", "dns busy", "dns invalid host", "no eligible named official server",
            "connection refused", "connection abort", "network is unreachable", "no route to host", "failed to connect",
            "unexpected end of stream", "trust anchor", "certificate expired", "certificate not yet valid",
            "hostname", "handshake", "protocol_version", "certification path", "too many redirects",
            "https downgrade rejected", "http response exceeds 1mb", "cancelled", "interrupted")
        return JSONArray(generateSequence(error) { it.cause }.take(8).map { cause ->
            val message = cause.message.orEmpty().take(2048)
            val lower = message.lowercase()
            val labels = approved.filter { lower.contains(it) }.take(3)
            val knownErrno = setOf("EACCES", "EADDRNOTAVAIL", "EAFNOSUPPORT", "EAGAIN", "ECONNABORTED", "ECONNREFUSED",
                "ECONNRESET", "EHOSTUNREACH", "EINTR", "EINVAL", "EIO", "ENETDOWN", "ENETUNREACH", "ENOBUFS",
                "ENOMEM", "ENOTCONN", "EPERM", "EPROTO", "ETIMEDOUT")
            val errno = Regex("\\bE[A-Z_]{2,24}\\b").findAll(message).map { it.value }.filter { it in knownErrno }.distinct().take(2).toList()
            JSONObject().put("class", cause.javaClass.name.take(160))
                .put("message_safe", (labels + errno).joinToString("; ").take(160).ifEmpty { "message omitted" })
        }.toList())
    }
}
