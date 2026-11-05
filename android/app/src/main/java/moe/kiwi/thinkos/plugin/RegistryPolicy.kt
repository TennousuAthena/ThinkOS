package moe.kiwi.thinkos.plugin

import moe.kiwi.thinkos.update.RegistryModels
import moe.kiwi.thinkos.update.UpdateJson
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Registry policy is checked again on-device, independently of publication CI. */
internal object RegistryPolicy {
    val permissions = setOf("network", "alert", "media", "library", "presence", "server", "location")
    fun objects(array: JSONArray?): List<JSONObject> = (0 until (array?.length() ?: 0)).map { array!!.getJSONObject(it) }
    fun strings(array: JSONArray?): Set<String> = (0 until (array?.length() ?: 0)).map { array!!.getString(it) }.toSet()
    fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun parseCatalog(bytes: ByteArray): JSONObject {
        val doc = UpdateJson.read(bytes, 1024 * 1024)
        RegistryModels.readRegistryCatalog(doc)
        require(doc.getLong("expires") > doc.getLong("generated")) { "Invalid registry dates" }
        val plugins = objects(doc.getJSONArray("plugins"))
        require(plugins.map { it.getString("id") }.distinct().size == plugins.size) { "Duplicate registry ID" }
        plugins.forEach { plugin ->
            require(plugin.getString("id").matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "Invalid registry ID" }
            require(plugin.getJSONObject("name").keys().asSequence().any { plugin.getJSONObject("name").getString(it).isNotBlank() }) { "Empty registry name" }
            val releases = objects(plugin.getJSONArray("releases"))
            require(releases.map { it.getString("digest") }.distinct().size == releases.size) { "Duplicate registry digest" }
            releases.forEach { release ->
                val declared = strings(release.getJSONArray("permissions"))
                require(release.getJSONObject("why").keys().asSequence().toSet() == declared) { "Permission reasons disagree" }
                declared.forEach { permission ->
                    val reason = release.getJSONObject("why").getJSONObject(permission)
                    require(reason.keys().asSequence().any { reason.getString(it).isNotBlank() }) { "Empty permission reason" }
                }
                require(release.getString("digest").matches(Regex("[0-9a-f]{64}")) && release.getString("commit").matches(Regex("[0-9a-f]{40}"))) { "Invalid registry identity hash" }
            }
            require(releases.zipWithNext().all { (a, b) -> compare(a.getString("version"), b.getString("version")) > 0 }) { "Registry versions are not descending" }
        }
        return doc
    }
    /** Semantic version comparison with unbounded numeric components, never integer overflow. */
    fun compare(a: String, b: String): Int {
        val left = a.substringBefore('-').split('.'); val right = b.substringBefore('-').split('.')
        require(left.size == 3 && right.size == 3)
        fun numeric(x: String, y: String): Int {
            val l = x.trimStart('0').ifEmpty { "0" }; val r = y.trimStart('0').ifEmpty { "0" }
            return l.length.compareTo(r.length).takeIf { it != 0 } ?: l.compareTo(r)
        }
        for (i in 0..2) numeric(left[i], right[i]).takeIf { it != 0 }?.let { return it }
        val ap = a.substringAfter('-', ""); val bp = b.substringAfter('-', "")
        if (ap.isEmpty() || bp.isEmpty()) return if (ap == bp) 0 else if (ap.isEmpty()) 1 else -1
        val aa = ap.split('.'); val bb = bp.split('.')
        for (i in 0 until minOf(aa.size, bb.size)) {
            val x = aa[i]; val y = bb[i]
            val xn = x.all(Char::isDigit); val yn = y.all(Char::isDigit)
            val n = if (xn && yn) numeric(x, y) else if (xn != yn) if (xn) -1 else 1 else x.compareTo(y)
            if (n != 0) return n
        }
        return aa.size.compareTo(bb.size)
    }
    fun selected(plugin: JSONObject): JSONObject? = objects(plugin.getJSONArray("releases")).firstOrNull { it.getInt("api") <= PluginSchema.API }
    fun manifest(manifest: JSONObject, expected: JSONObject, record: JSONObject, factory: JSONObject?) {
        PluginSchema.validateManifest(manifest)
        require(manifest.getString("id") == record.getString("id") && manifest.getString("version") == record.getString("version")) { "Registry manifest identity mismatch" }
        val declared = strings(manifest.optJSONArray("permissions"))
        require(declared.all { it in permissions } && declared == strings(expected.optJSONArray("permissions"))) { "Registry permission mismatch" }
        require(declared.all { it in strings(record.getJSONArray("confirmed")) }) { "Registry permissions are not confirmed" }
        require(objects(manifest.optJSONArray("pages")).all { (it.optJSONArray("roles")?.length() ?: 0) == 0 }) { "Registry plugins cannot hold roles" }
        if (factory != null) {
            require(record.getString("tier") == "official") { "Factory ID is reserved" }
            require(strings(factory.optJSONArray("permissions")).none { it.startsWith("system.") } && objects(factory.optJSONArray("pages")).all { (it.optJSONArray("roles")?.length() ?: 0) == 0 }) { "System factory plugins update with the APK" }
        }
    }
    /** Every installed byte is checked on the IO worker, including cached restarts. */
    fun verifyDirectory(directory: File, digest: String): PluginFiles.Index {
        val index = PluginFiles.readDirectory(directory)
        require(index.digest == digest)
        index.files.forEach { entry ->
            val bytes = PluginFiles.openDirectory(directory, entry.path).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val n = input.read(buffer, 0, minOf(buffer.size, entry.size.toInt() - output.size() + 1))
                    if (n < 0) break
                    require(n <= entry.size.toInt() - output.size()) { "Installed file exceeds signed size" }
                    output.write(buffer, 0, n)
                }
                output.toByteArray()
            }
            require(bytes.size.toLong() == entry.size && hash(bytes) == entry.sha256) { "Installed plugin changed" }
        }
        return index
    }
}
