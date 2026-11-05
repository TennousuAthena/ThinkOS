package moe.kiwi.thinkos.ui

import org.json.JSONObject

/**
 * The channel the `tv` component shows, as the plugin's state describes it (ADR-038). Free of Android so that it is tested on the
 * JVM: every field is optional, and a field of the wrong type counts as missing, because a plugin's state is not trusted to be tidy.
 */
internal data class TvChannel(
    val number: Int,
    val name: String,
    val group: String,
    val line: Int,
    val lines: Int,
    val favorite: Boolean,
    val now: Programme?,
    val next: Programme?,
    val notice: String,
) {
    /** [start] and [end] are epoch milliseconds; [end] is 0 for "until the next programme" (the one after it ends it). */
    data class Programme(val title: String, val start: Long, val end: Long)

    /** Whether the plugin offers other lines of this channel to switch to. */
    val hasLines: Boolean get() = lines > 1

    companion object {
        val EMPTY = TvChannel(0, "", "", 0, 0, false, null, null, "")

        fun parse(json: JSONObject?): TvChannel {
            json ?: return EMPTY
            fun text(key: String, limit: Int) = (json.opt(key) as? String).orEmpty().take(limit)
            fun whole(key: String) = (json.opt(key) as? Number)?.toDouble()?.takeIf { it.isFinite() }?.toLong()?.coerceIn(0L, 100_000L)?.toInt() ?: 0
            return TvChannel(whole("number"), text("name", 120), text("group", 80), whole("line"), whole("lines"),
                json.opt("favorite") == true, programme(json.optJSONObject("program")), programme(json.optJSONObject("next")), text("notice", 200))
        }

        private fun programme(json: JSONObject?): Programme? {
            json ?: return null
            val title = (json.opt("title") as? String).orEmpty().take(160)
            val start = (json.opt("start") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > 0 }?.toLong() ?: return null
            val end = (json.opt("end") as? Number)?.toDouble()?.takeIf { it.isFinite() && it > start }?.toLong() ?: 0L
            return if (title.isBlank()) null else Programme(title, start, end)
        }

        /** How far into [programme] it is [now], 0..1; null when it has no known end or is not on air yet. */
        fun progress(programme: Programme, now: Long): Float? {
            if (programme.end <= programme.start || now < programme.start) return null
            return ((now - programme.start).toDouble() / (programme.end - programme.start)).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
