package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale

/** Directory installer shared by bundled assets and debug overrides. Never follows a link or executes content. */
internal object PluginFiles {
    const val MAX_INDEX = 64 * 1024
    private const val MAX_FILE = 1024 * 1024
    private const val MAX_TEXT = 256 * 1024
    private const val MAX_TOTAL = 8L * 1024 * 1024
    private val hex = Regex("[0-9a-f]{64}")
    private val idPattern = Regex("[a-z][a-z0-9-]{0,63}")
    data class Entry(val path: String, val sha256: String, val size: Long)
    data class Index(val id: String, val version: String, val digest: String, val files: List<Entry>, val json: JSONObject)

    /** Canonical digest bytes: UTF-8 path, NUL, lowercase hex SHA-256, LF, in UTF-8 path order. */
    fun parse(json: JSONObject): Index {
        PluginSchema.validateIndex(json)
        require(json.keySet() == setOf("id", "version", "digest", "files")) { "unknown file index fields" }
        val id = json.getString("id")
        require(idPattern.matches(id) && id != "system") { "invalid plugin id" }
        val version = json.getString("version")
        val digest = json.getString("digest")
        require(hex.matches(digest)) { "invalid plugin digest" }
        val array = json.getJSONArray("files")
        require(array.length() in 1..128) { "file index exceeds 128 files" }
        val folded = mutableMapOf<String, String>()
        var total = 0L
        val files = (0 until array.length()).map { n ->
            val item = array.getJSONObject(n)
            require(item.keySet() == setOf("path", "sha256", "size")) { "unknown file entry fields" }
            val path = item.getString("path")
            validatePath(path)
            require(allowed(path)) { "unsupported plugin file: $path" }
            val parts = path.split('/')
            parts.indices.forEach { end ->
                val prefix = parts.take(end + 1).joinToString("/")
                val previous = folded.put(prefix.lowercase(Locale.ROOT), prefix)
                require(previous == null || previous == prefix) { "case-colliding plugin path: $path" }
            }
            val sha = item.getString("sha256")
            require(hex.matches(sha)) { "invalid file SHA-256" }
            val value = item.get("size") as? Number ?: error("invalid file size")
            val size = value.toLong()
            require(value.toDouble() == size.toDouble() && size in 0..MAX_FILE.toLong()) { "invalid file size" }
            if (path.endsWith(".json") || path.endsWith(".lua")) require(size <= MAX_TEXT) { "JSON/Lua exceeds 256 KiB" }
            total += size
            require(total <= MAX_TOTAL) { "plugin exceeds 8 MiB" }
            Entry(path, sha, size)
        }
        require(files.map { it.path }.toSet().size == files.size) { "duplicate plugin path" }
        require(files.any { it.path == "manifest.json" }) { "missing manifest.json" }
        require(files == files.sortedWith { a, b -> compareUtf8(a.path, b.path) }) { "file index is not sorted" }
        val canonical = buildString { files.forEach { append(it.path); append('\u0000'); append(it.sha256); append('\n') } }
        require(sha256(canonical.toByteArray(Charsets.UTF_8)) == digest) { "file index digest mismatch" }
        return Index(id, version, digest, files, json)
    }

    fun readIndex(input: InputStream): Index = parse(JSONObject(String(input.use { readLimited(it, MAX_INDEX) }, Charsets.UTF_8)))

    /** Keep a published override visible throughout a two-rename replacement, including queued scans. */
    fun developmentDirectories(root: File, knownIds: Set<String>): List<Pair<String, File>> {
        val names = root.listFiles().orEmpty().map { it.name.removePrefix(".previous-") }.toSet() + knownIds
        return names.filter { idPattern.matches(it) }.sorted().mapNotNull { id ->
            val published = File(root, id)
            val previous = File(root, ".previous-$id")
            // Return even a malformed publication: the caller must retain its last working version.
            when {
                Files.exists(published.toPath(), LinkOption.NOFOLLOW_LINKS) -> id to published
                Files.exists(previous.toPath(), LinkOption.NOFOLLOW_LINKS) -> id to previous
                else -> null
            }
        }
    }

    /** Delete application-owned trees without ever walking through a symbolic link. */
    fun deleteTree(file: File) {
        if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            (file.listFiles() ?: error("cannot read plugin directory")).forEach(::deleteTree)
        }
        if (Files.exists(file.toPath(), LinkOption.NOFOLLOW_LINKS)) check(file.delete()) { "cannot delete plugin path" }
    }

    fun readDirectory(directory: File): Index {
        require(Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) { "plugin must be a directory" }
        val indexFile = File(directory, "index.json")
        require(Files.isRegularFile(indexFile.toPath(), LinkOption.NOFOLLOW_LINKS)) { "missing file index" }
        val index = readIndex(indexFile.inputStream())
        val expected = index.files.map { it.path }.toSet() + "index.json"
        val actual = mutableSetOf<String>()
        var visited = 0
        fun visit(parent: File) {
            (parent.listFiles() ?: error("cannot read plugin directory")).forEach { file ->
                require(++visited <= 512) { "too many plugin directory entries" }
                require(!Files.isSymbolicLink(file.toPath())) { "symlinks are forbidden" }
                if (Files.isDirectory(file.toPath(), LinkOption.NOFOLLOW_LINKS)) visit(file)
                else {
                    require(Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "not a regular plugin file" }
                    val relative = file.relativeTo(directory).invariantSeparatorsPath
                    require(relative in expected) { "unlisted plugin file: $relative" }
                    actual.add(relative)
                }
            }
        }
        visit(directory)
        require(actual == expected) { "missing plugin files" }
        return index
    }

    /** Copies and verifies bytes into a fresh staging directory. Source mutations never publish partial content. */
    fun copy(index: Index, staging: File, open: (String) -> InputStream) {
        require(!staging.exists()) { "staging directory already exists" }
        check(staging.mkdirs())
        try {
            index.files.forEach { entry ->
                val target = resolve(staging, entry.path)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val bytes = open(entry.path).use { readLimited(it, entry.size.toInt()) }
                require(bytes.size.toLong() == entry.size && sha256(bytes) == entry.sha256) { "file hash/size mismatch: ${entry.path}" }
                target.writeBytes(bytes)
            }
            File(staging, "index.json").writeText(index.json.toString(), Charsets.UTF_8)
        } catch (error: Exception) {
            deleteTree(staging)
            throw error
        }
    }

    fun openDirectory(directory: File, path: String): InputStream {
        val target = resolve(directory, path)
        var file: File? = target
        while (file != null && file != directory) {
            require(!Files.isSymbolicLink(file.toPath())) { "symlinks are forbidden" }
            file = file.parentFile
        }
        require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "not a regular plugin file" }
        return target.inputStream()
    }

    fun resolve(root: File, relative: String): File {
        validatePath(relative)
        val file = File(root, relative)
        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator)) { "path escapes plugin" }
        return file
    }

    fun validatePath(path: String) {
        require(path.isNotBlank() && !path.startsWith('/') && '\\' !in path && path.none { it.code < 32 || it.code == 127 }) { "unsafe plugin path" }
        require(path.toByteArray(Charsets.UTF_8).size <= 512 && path.split('/').none { it.isEmpty() || it == "." || it == ".." }) { "unsafe plugin path" }
    }

    private fun allowed(path: String): Boolean {
        val parts = path.split('/')
        if (parts.any { it.startsWith('.') }) return false
        if (parts.first() == "store" || path == "store.json") return false
        if (path in setOf("LICENSE", "LICENSE.txt", "LICENSE.md", "COPYING", "NOTICE", "NOTICE.txt")) return true
        val ext = path.substringAfterLast('.', "")
        return path == "manifest.json" || ext == "lua" ||
            (parts.size > 1 && parts[0] in setOf("views", "i18n") && ext == "json") ||
            (parts.size > 1 && parts[0] == "assets" && ext.lowercase(Locale.ROOT) in setOf("png", "jpg", "jpeg", "webp", "ogg", "wav"))
    }

    private fun readLimited(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            require(output.size().toLong() + n <= limit) { "file exceeds declared size" }
            output.write(buffer, 0, n)
        }
        return output.toByteArray()
    }
    private fun compareUtf8(a: String, b: String): Int {
        val left = a.toByteArray(Charsets.UTF_8); val right = b.toByteArray(Charsets.UTF_8)
        for (n in 0 until minOf(left.size, right.size)) {
            val comparison = (left[n].toInt() and 255).compareTo(right[n].toInt() and 255)
            if (comparison != 0) return comparison
        }
        return left.size.compareTo(right.size)
    }
    private fun JSONObject.keySet(): Set<String> = keys().asSequence().toSet()
    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
