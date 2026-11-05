package moe.kiwi.thinkos.update

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Clock-free memory of which update hosts answered, so a blackholed mirror stops costing a connect timeout on every
 * attempt. It only orders candidates: nothing here is trusted, grants access or removes a source, and a host that is
 * demoted is still tried after the others. Corrupt, oversized or unwritable state is ignored, never an update failure.
 * Time is counted in update checks (device has no RTC): a host that failed in check N stays last through check N+skip,
 * with skip 1, 2, 4 then 8 for repeated failures, and is tried in its configured place again after that. */
internal class UpdateSourceHealth(private val file: File?) {
    private class Entry(var fails: Int, var until: Long, var at: Long)

    private val lock = Any()
    private var checks = 0L
    private val hosts = LinkedHashMap<String, Entry>()
    private var loaded = false

    /** One call per catalog check. Demotion expires by counting these, not by wall time. */
    fun beginCheck() = synchronized(lock) {
        load()
        checks++
        save()
    }

    /** Stable: healthy hosts keep their configured order, demoted hosts follow, soonest-to-expire first. */
    fun order(urls: List<String>): List<String> {
        if (urls.size < 2) return urls
        return synchronized(lock) {
            load()
            val demoted = if (hosts.isEmpty()) emptyList() else urls.filter(::isDemoted)
            if (demoted.isEmpty()) urls else urls.filterNot(::isDemoted) + demoted.sortedBy { hosts.getValue(key(it)!!).until }
        }
    }

    fun success(url: String) {
        val key = key(url) ?: return
        synchronized(lock) {
            load()
            if (hosts.remove(key) != null) save()
        }
    }

    fun failure(url: String) {
        val key = key(url) ?: return
        synchronized(lock) {
            load()
            val entry = hosts[key]
            // Several requests to one dead host inside a single check count once, so one outage does not exile it for long.
            if (entry != null && entry.at == checks) return
            val fails = minOf((entry?.fails ?: 0) + 1, MAX_FAILS)
            hosts.remove(key)
            hosts[key] = Entry(fails, checks + minOf(1L shl (fails - 1), MAX_SKIP), checks)
            while (hosts.size > MAX_HOSTS) hosts.remove(hosts.keys.first())
            save()
        }
    }

    internal fun isDemoted(url: String): Boolean = synchronized(lock) {
        load()
        val entry = key(url)?.let { hosts[it] }
        entry != null && checks <= entry.until
    }

    private fun key(url: String): String? = url.toHttpUrlOrNull()?.let { "${it.host.lowercase()}:${it.port}" }

    private fun load() {
        if (loaded) return
        loaded = true
        val source = file ?: return
        try {
            if (!source.isFile || source.length() > MAX_BYTES) return
            val json = JSONObject(source.readText(Charsets.UTF_8))
            val values = json.getJSONObject("hosts")
            val parsed = LinkedHashMap<String, Entry>()
            val names = values.keys()
            while (names.hasNext() && parsed.size < MAX_HOSTS) {
                val name = names.next()
                if (name.length !in 1..300) continue
                val item = values.getJSONObject(name)
                val fails = item.getInt("fails")
                val until = item.getLong("until")
                val at = item.getLong("at")
                if (fails !in 1..MAX_FAILS || until < 0 || at < 0) continue
                parsed[name] = Entry(fails, until, at)
            }
            checks = json.getLong("checks").coerceAtLeast(0)
            hosts.putAll(parsed)
        } catch (_: Exception) {
            // A hint file that cannot be read means "no history", never a failed update.
            hosts.clear(); checks = 0
        }
    }

    private fun save() {
        val target = file ?: return
        try {
            val values = JSONObject()
            for ((name, entry) in hosts) values.put(name, JSONObject().put("fails", entry.fails).put("until", entry.until).put("at", entry.at))
            val bytes = JSONObject().put("checks", checks).put("hosts", values).toString().toByteArray(Charsets.UTF_8)
            val parent = target.absoluteFile.parentFile ?: return
            if (!parent.isDirectory && !parent.mkdirs()) return
            val temporary = File(parent, target.name + ".tmp")
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!temporary.renameTo(target)) temporary.delete()
        } catch (_: IOException) {
            // Best effort only.
        }
    }

    private companion object {
        const val MAX_HOSTS = 16
        const val MAX_FAILS = 4
        const val MAX_SKIP = 8L
        const val MAX_BYTES = 8L * 1024
    }
}
