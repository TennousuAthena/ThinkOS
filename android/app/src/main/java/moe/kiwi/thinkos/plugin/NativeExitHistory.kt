package moe.kiwi.thinkos.plugin

import org.json.JSONObject

/** Same-boot decisions use monotonic time; a missing RTC cannot make quarantine permanent. */
internal class NativeExitHistory(
    private val records: JSONObject,
    private val boot: Int,
    private val elapsed: () -> Long,
    private val wall: () -> Long
) {
    init {
        records.keys().forEach { key ->
            val record = records.getJSONObject(key)
            if (record.optInt("untilBoot", boot) != boot && record.optLong("untilElapsed") > 0) {
                val utc = wall()
                val remaining = if (utc >= TRUSTED_TIME && record.optLong("untilWall") >= TRUSTED_TIME)
                    (record.getLong("untilWall") - utc).coerceIn(0, PluginHealth.HOUR) else PluginHealth.HOUR
                record.put("untilBoot", boot).put("untilElapsed", elapsed() + remaining)
            }
        }
    }
    fun record(key: String) {
        val now = elapsed()
        val utc = wall()
        val record = records.optJSONObject(key) ?: JSONObject()
        val recent = if (record.optInt("lastBoot", -2) == boot) now - record.optLong("lastElapsed", -PluginHealth.HOUR) in 0 until PluginHealth.HOUR
            else utc >= TRUSTED_TIME && record.optLong("lastWall") >= TRUSTED_TIME && utc - record.getLong("lastWall") in 0 until PluginHealth.HOUR
        val count = if (recent) (record.optInt("count") + 1).coerceAtMost(3) else 1
        record.put("count", count).put("lastBoot", boot).put("lastElapsed", now).put("lastWall", utc)
        if (count >= 3) record.put("untilBoot", boot).put("untilElapsed", now + PluginHealth.HOUR).put("untilWall", if (utc >= TRUSTED_TIME) utc + PluginHealth.HOUR else 0)
        records.put(key, record)
    }
    fun blocked(key: String): Boolean = records.optJSONObject(key)?.let { it.optInt("untilBoot", -2) == boot && it.optLong("untilElapsed") > elapsed() } == true
    companion object { private const val TRUSTED_TIME = 1_704_067_200_000L }
}
