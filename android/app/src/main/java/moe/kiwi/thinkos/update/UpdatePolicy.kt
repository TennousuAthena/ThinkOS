package moe.kiwi.thinkos.update

import okhttp3.HttpUrl.Companion.toHttpUrl

/** Values come from the shared settings declaration, including all timing defaults. */
internal data class UpdatePolicy(
    val enabled: Boolean, val channel: String, val sources: List<String>,
    val initialMinMillis: Long, val initialMaxMillis: Long,
    val intervalMillis: Long, val jitterMillis: Long,
    val retryMinMillis: Long, val retryMaxMillis: Long,
    val manualMillis: Long, val trustedYear: Int,
    val connectMillis: Long, val readMillis: Long,
) {
    init {
        require(channel in setOf("stable", "beta") && sources.size <= 8)
        require(initialMinMillis >= 0 && initialMaxMillis >= initialMinMillis)
        require(intervalMillis > jitterMillis && jitterMillis >= 0)
        require(retryMinMillis > 0 && retryMaxMillis >= retryMinMillis)
        require(manualMillis in 1..5000 && trustedYear >= 2024)
        require(connectMillis > 0 && readMillis > 0)
    }

    fun fresh(tap: Long, now: Long) = tap >= 0 && now >= tap && now - tap <= manualMillis

    companion object {
        /** A mirror is a repository root, or an explicit catalog URL. Never add a
         * device identifier; the selected channel is the only path parameter. */
        fun sources(mirror: String, builtIn: String, channel: String): List<String> =
            (listOf(mirror.trim()).filter(String::isNotEmpty) + builtIn.split(',', ' ', '\n', '\t').filter(String::isNotBlank))
                .distinct().take(8).map { source ->
                    // Keep an invalid candidate for the client's bounded fallback,
                    // so a bad custom mirror does not disable valid later sources.
                    runCatching {
                        val url = expand(source, channel).toHttpUrl()
                        if (url.encodedPath.endsWith(".json")) url.toString()
                        else url.newBuilder().encodedPath(url.encodedPath.trimEnd('/') + "/ota/$channel.json").build().toString()
                    }.getOrDefault(source)
                }.distinct()

        /** Release-asset hosts have no "/ota/" directory, so one address cannot serve both channels; `{channel}` stands for
         * the selected channel. It is only honoured after the host: a placeholder inside the authority is left as written
         * (and so fails URL parsing) instead of choosing which server is contacted. */
        internal fun expand(source: String, channel: String): String {
            if (CHANNEL !in source) return source
            val authorityStart = source.indexOf("://").takeIf { it >= 0 }?.plus(3) ?: return source
            val pathStart = source.indexOf('/', authorityStart).takeIf { it >= 0 } ?: return source
            val head = source.substring(0, pathStart)
            return if (CHANNEL in head) source else head + source.substring(pathStart).replace(CHANNEL, channel)
        }

        private const val CHANNEL = "{channel}"
    }
}
