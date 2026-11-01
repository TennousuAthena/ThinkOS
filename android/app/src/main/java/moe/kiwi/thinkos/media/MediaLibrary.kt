package moe.kiwi.thinkos.media

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.os.Environment
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.Locale
import java.util.concurrent.CancellationException

/** Local device media only. [list] is blocking and must be dispatched to an IO executor by its host. */
class MediaLibrary(context: Context) {
    private val app = context.applicationContext

    data class Result(
        val items: List<Map<String, Any?>>,
        val truncated: Boolean,
        /** Directory entries examined, including unsupported files and skipped directories. */
        val scanned: Int,
        val directory: String,
    ) {
        fun toHost(): Map<String, Any?> = mapOf("items" to items, "truncated" to truncated,
            "scanned" to scanned.toLong(), "directory" to directory)
    }

    /** IO-only preflight. True means this path relies on READ_EXTERNAL_STORAGE, even if already granted. */
    fun requiresStoragePermission(options: Map<*, *>): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Media library must run on an IO thread" }
        val request = LibraryRequest.parse(options)
        val own = app.getExternalFilesDirs(null).filterNotNull()
        val directory = request.directory ?: own.firstOrNull()?.let { File(it, request.kind.folder) }
            ?: throw IOException("App external storage is unavailable")
        return LibraryAccess(app).requiresStoragePermission(directory)
    }

    fun requiresStoragePermissionForUrls(urls: List<String>): Boolean = LibraryAccess(app).requiresStoragePermissionForUrls(urls)

    fun list(options: Map<*, *>): Result {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Media library must run on an IO thread" }
        LibraryCancellation.check()
        val request = LibraryRequest.parse(options)
        val ownRoots = app.getExternalFilesDirs(null).filterNotNull().map(File::getCanonicalFile)
        val access = LibraryAccess(app)
        val directory = request.directory ?: ownRoots.firstOrNull()?.let { File(it, request.kind.folder) }
            ?: throw IOException("App external storage is unavailable")
        // The application's own standard subdirectory may not have been created yet. Return an
        // empty library there, without silently falling back to a scan of the shared storage root.
        if (request.directory == null && !directory.exists()) {
            access.authorize(directory)
            return Result(emptyList(), false, 0, directory.canonicalPath)
        }
        return LibraryScanner(access, AndroidLibraryMetadata(access)::read).list(request, directory)
    }
}

internal enum class LibraryKind(val id: String, val folder: String, val extensions: Map<String, String>) {
    AUDIO("audio", "Music", mapOf("mp3" to "audio/mpeg", "flac" to "audio/flac", "ogg" to "audio/ogg",
        "oga" to "audio/ogg", "opus" to "audio/ogg", "m4a" to "audio/mp4", "aac" to "audio/aac",
        "wav" to "audio/wav", "amr" to "audio/amr", "mka" to "audio/x-matroska", "ac3" to "audio/ac3",
        "eac3" to "audio/eac3", "dts" to "audio/vnd.dts")),
    VIDEO("video", "Movies", mapOf("mp4" to "video/mp4", "m4v" to "video/mp4", "3gp" to "video/3gpp",
        "webm" to "video/webm", "mkv" to "video/x-matroska", "ts" to "video/mp2t", "mov" to "video/quicktime",
        "avi" to "video/x-msvideo", "flv" to "video/x-flv", "mpg" to "video/mpeg", "mpeg" to "video/mpeg",
        "vob" to "video/mpeg")),
    IMAGE("image", "Pictures", mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png",
        "webp" to "image/webp", "gif" to "image/gif", "bmp" to "image/bmp"));
}

internal data class LibraryRequest(val kind: LibraryKind, val directory: File?, val recursive: Boolean) {
    companion object {
        fun parse(options: Map<*, *>): LibraryRequest {
            require(options.keys.all { it in setOf("kind", "dir", "recursive") }) { "Unknown library option" }
            val kind = LibraryKind.entries.firstOrNull { it.id == options["kind"] }
                ?: throw IllegalArgumentException("Library kind must be audio, video or image")
            val recursive = options["recursive"]?.let {
                require(it is Boolean) { "library.recursive must be a boolean" }; it
            } ?: false
            val directory = options["dir"]?.let { value ->
                require(value is String && value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 4096 &&
                    value.none { it.code < 32 || it.code == 127 }) { "Invalid library directory" }
                LibraryAccess.localFile(value)
            }
            return LibraryRequest(kind, directory, recursive)
        }
    }
}

/** A boundary is canonicalized before every authorization; permissions never grant access outside these roots. */
class LibraryAccess internal constructor(
    ownRoots: List<File>, publicRoots: List<File>, private val context: Context? = null,
    private val canReadPublic: () -> Boolean,
) {
    /** Construct and call on an IO thread: canonicalizing the media roots may access storage. */
    constructor(context: Context) : this(externalRoots(context), listOf(Environment.getExternalStorageDirectory()), context.applicationContext, {
        context.applicationContext.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    })

    private val own = ownRoots.map(File::getCanonicalFile)
    private val public = publicRoots.map(File::getCanonicalFile)

    private fun classify(file: File): Pair<File, Boolean> {
        checkIo()
        LibraryCancellation.check()
        val canonical = file.canonicalFile
        if (own.any { contains(it, canonical) }) return canonical to false
        val shared = public.firstOrNull { contains(it, canonical) }
            ?: throw SecurityException("Media path is outside device media storage")
        val relative = canonical.relativeTo(shared).invariantSeparatorsPath.split('/')
        if (relative.size >= 2 && relative[0].equals("Android", true) &&
            relative[1].lowercase(Locale.ROOT) in setOf("data", "obb")) {
            throw SecurityException("Other applications' private media directories are unavailable")
        }
        return canonical to true
    }

    /** Validates the path before returning whether it relies on the shared-storage permission. */
    fun requiresStoragePermission(file: File): Boolean = classify(file).second

    fun requiresStoragePermission(path: String): Boolean {
        checkIo()
        val value = path.removePrefix("embedded:")
        if (value.startsWith("content:")) return contentRequiresPermission(value)
        return requiresStoragePermission(localFile(value))
    }

    /** Every URL is validated, including entries after the first one that requires a grant. */
    fun requiresStoragePermissionForUrls(urls: List<String>): Boolean {
        checkIo()
        require(urls.size <= 1024) { "Too many media URLs" }
        var required = false
        urls.forEach { value ->
            validateValue(value)
            if (value.startsWith("http://") || value.startsWith("https://")) {
                val uri = URI(value)
                require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.fragment == null) { "Invalid media HTTP URL" }
            } else {
                val needs = requiresStoragePermission(value)
                required = required || needs
            }
        }
        return required
    }

    fun authorize(file: File): File {
        val (canonical, required) = classify(file)
        if (required && !canReadPublic()) throw SecurityException("READ_EXTERNAL_STORAGE permission is required for this path")
        return canonical
    }

    fun authorize(path: String): File = authorize(localFile(path.removePrefix("embedded:")))
    fun allowed(file: File): Boolean = try { authorize(file); true } catch (_: SecurityException) { false }

    /** Final-open authorization for image decoders, metadata readers and ExoPlayer local sources. */
    fun openAuthorized(path: String): ParcelFileDescriptor {
        checkIo()
        val value = path.removePrefix("embedded:")
        if (value.startsWith("content:")) {
            val required = contentRequiresPermission(value)
            if (required && !canReadPublic()) throw SecurityException("READ_EXTERNAL_STORAGE permission is required for this content URI")
            return context!!.contentResolver.openFileDescriptor(android.net.Uri.parse(value), "r")
                ?: throw FileNotFoundException("Media provider returned no file")
        }
        return openAuthorized(localFile(value))
    }

    /** O_NOFOLLOW/O_NONBLOCK prevents a swapped link or FIFO from escaping the local media grant. */
    fun openAuthorized(file: File): ParcelFileDescriptor {
        checkIo()
        val canonical = authorize(file)
        val descriptor = Os.open(canonical.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC or OsConstants.O_NOFOLLOW or OsConstants.O_NONBLOCK, 0)
        try {
            require(OsConstants.S_ISREG(Os.fstat(descriptor).st_mode)) { "Not a regular media file" }
            val copy = ParcelFileDescriptor.dup(descriptor)
            try {
                val actual = File(Os.readlink("/proc/self/fd/${copy.fd}"))
                require(actual.canonicalFile == canonical) { "Media file changed during open" }
                authorize(actual)
                return copy
            } catch (error: Exception) { copy.close(); throw error }
        } finally { Os.close(descriptor) }
    }

    private fun contentRequiresPermission(value: String): Boolean {
        validateValue(value)
        val app = context ?: throw SecurityException("Content URI access requires a device context")
        val uri = android.net.Uri.parse(value)
        require(uri.scheme == "content" && !uri.authority.isNullOrBlank() && !uri.path.isNullOrBlank() &&
            uri.fragment == null) { "Invalid content URI" }
        if (app.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) == PackageManager.PERMISSION_GRANTED) return false
        if (uri.authority != "media") throw SecurityException("Content provider requires an explicit URI read grant")
        return true
    }

    private fun checkIo() {
        if (context != null) check(Looper.myLooper() != Looper.getMainLooper()) { "Media path checks must run on an IO thread" }
    }

    companion object {
        private fun externalRoots(context: Context): List<File> {
            check(Looper.myLooper() != Looper.getMainLooper()) { "Media path checks must run on an IO thread" }
            return context.applicationContext.getExternalFilesDirs(null).filterNotNull()
        }
        private fun contains(root: File, file: File): Boolean =
            file.path == root.path || file.path.startsWith(root.path + File.separator)
        private fun validateValue(value: String) {
            require(value.isNotBlank() && value.toByteArray(Charsets.UTF_8).size <= 8192 &&
                value.none { it.code < 32 || it.code == 127 }) { "Invalid media path" }
        }
        internal fun localFile(value: String): File {
            validateValue(value)
            val path = if (value.startsWith('/')) value else {
                val uri = URI(value)
                require(uri.scheme == "file" && (uri.rawAuthority.isNullOrEmpty() || uri.rawAuthority.equals("localhost", true)) &&
                    uri.userInfo == null && uri.query == null && uri.fragment == null) { "Media path must be a local absolute path" }
                uri.path
            }
            require(path != null && path.startsWith('/') && path.none { it.code < 32 || it.code == 127 }) { "Invalid media path" }
            return File(path)
        }
    }
}

internal data class LibraryMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val mime: String? = null,
    val duration: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val rotation: Int? = null,
)

/** Hard resource budgets, not plugin-controlled settings. Tests may supply smaller budgets. */
internal data class LibraryLimits(val items: Int = 512, val entries: Int = 8192, val depth: Int = 16,
    val textBytes: Int = 1024 * 1024) {
    init { require(items in 1..512 && entries in 1..8192 && depth in 0..16 && textBytes in 1..1024 * 1024) }
}

internal class LibraryScanner(
    private val access: LibraryAccess,
    private val metadata: (File, LibraryKind, String) -> LibraryMetadata?,
    private val limits: LibraryLimits = LibraryLimits(),
) {
    fun list(request: LibraryRequest, requestedDirectory: File): MediaLibrary.Result {
        LibraryCancellation.check()
        val directory = access.authorize(requestedDirectory)
        if (!Files.isDirectory(directory.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            throw FileNotFoundException("Library directory does not exist: ${directory.path}")
        }
        val items = ArrayList<Map<String, Any?>>(minOf(limits.items, 512))
        var scanned = 0
        var bytes = 0
        var truncated = false
        var stopped = false
        fun visit(parent: File, depth: Int) {
            LibraryCancellation.check()
            // DirectoryStream keeps memory bounded even for folders with millions of entries.
            Files.newDirectoryStream(parent.toPath()).use { stream ->
                val iterator = stream.iterator()
                while (!stopped && iterator.hasNext()) {
                    LibraryCancellation.check()
                    if (scanned >= limits.entries) { truncated = true; stopped = true; break }
                    val path = iterator.next()
                    scanned++
                    if (Files.isSymbolicLink(path)) continue
                    val file = path.toFile()
                    if (!access.allowed(file)) continue
                    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                        if (request.recursive) {
                            if (depth >= limits.depth) truncated = true
                            else try { visit(file, depth + 1) }
                            catch (error: CancellationException) { throw error }
                            catch (_: IOException) { /* An unreadable child does not hide the rest of the library. */ }
                            catch (_: DirectoryIteratorException) { /* Entry iteration may fail after the directory was opened. */ }
                        }
                        continue
                    }
                    if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue
                    val fallbackMime = request.kind.extensions[file.extension.lowercase(Locale.ROOT)] ?: continue
                    val detail = try { metadata(file, request.kind, fallbackMime) }
                    catch (error: CancellationException) { throw error }
                    catch (_: Exception) { null } ?: continue
                    LibraryCancellation.check()
                    val url = file.toURI().toASCIIString()
                    if (url.length > 8192) continue
                    val title = detail.title?.takeIf(String::isNotBlank)?.take(256) ?: file.nameWithoutExtension.take(256)
                    val values = linkedMapOf<String, Any?>(
                        "url" to url, "src" to url, "kind" to request.kind.id, "title" to title,
                        "artist" to detail.artist.orEmpty().take(256), "album" to detail.album.orEmpty().take(256),
                        // The image loader resolves the optional embedded cover lazily; listing never allocates it.
                        "art" to if (request.kind == LibraryKind.IMAGE) "" else "embedded:${file.path}",
                        "mime" to (detail.mime ?: fallbackMime), "duration" to detail.duration,
                        "width" to detail.width?.toLong(), "height" to detail.height?.toLong(),
                        "rotation" to detail.rotation?.toLong(),
                    )
                    val cost = values.values.sumOf { (it as? String)?.toByteArray(Charsets.UTF_8)?.size ?: 16 } + 256
                    if (bytes + cost > limits.textBytes) { truncated = true; stopped = true; break }
                    items.add(values)
                    bytes += cost
                    if (items.size >= limits.items) {
                        truncated = truncated || iterator.hasNext() || depth > 0
                        stopped = true
                    }
                }
            }
        }
        visit(directory, 0)
        LibraryCancellation.check()
        items.sortBy { (it["title"] as String).lowercase(Locale.ROOT) }
        return MediaLibrary.Result(items, truncated, scanned, directory.path)
    }
}

internal object LibraryCancellation {
    fun check() {
        if (Thread.currentThread().isInterrupted) throw CancellationException("Media library scan cancelled")
    }
}

private class AndroidLibraryMetadata(private val access: LibraryAccess) {
    fun read(file: File, kind: LibraryKind, fallbackMime: String): LibraryMetadata? {
        LibraryCancellation.check()
        return access.openAuthorized(file).use { descriptor ->
            if (kind == LibraryKind.IMAGE) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFileDescriptor(descriptor.fileDescriptor, null, bounds)
                LibraryCancellation.check()
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) null
                else {
                    val rotation = try {
                        // API 27's FD constructor duplicates the descriptor into a non-owning
                        // FileInputStream. Use the stream constructor and let our PFD own closing.
                        Os.lseek(descriptor.fileDescriptor, 0, OsConstants.SEEK_SET)
                        val orientation = FileInputStream(descriptor.fileDescriptor).use { stream ->
                            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        }
                        when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
                            ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
                            ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
                            else -> 0
                        }
                    } catch (_: Exception) { null }
                    LibraryCancellation.check()
                    val swap = rotation == 90 || rotation == 270
                    LibraryMetadata(mime = bounds.outMimeType ?: fallbackMime,
                        width = if (swap) bounds.outHeight else bounds.outWidth,
                        height = if (swap) bounds.outWidth else bounds.outHeight, rotation = rotation)
                }
            } else {
                val retriever = MediaMetadataRetriever()
                try {
                    // The platform parser does not know every container the player does (AVI, FLV, MPEG-PS...).
                    // Such a file is still listed by its extension; the player says so itself if it cannot play it.
                    try { retriever.setDataSource(descriptor.fileDescriptor) }
                    catch (_: RuntimeException) { return@use LibraryMetadata(mime = fallbackMime) }
                    LibraryCancellation.check()
                    fun text(key: Int): String? {
                        LibraryCancellation.check()
                        return try { retriever.extractMetadata(key)?.take(256) }
                        catch (_: RuntimeException) { null } // Missing optional tags do not invalidate a playable item.
                    }
                    val mime = text(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
                    if (mime != null && mime.substringBefore('/') in setOf("audio", "video", "image") && !mime.startsWith(kind.id + "/")) {
                        return@use null
                    }
                    val rotation = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull()?.takeIf { it in setOf(0, 90, 180, 270) }
                    var width = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.takeIf { it > 0 }
                    var height = text(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()?.takeIf { it > 0 }
                    if (rotation == 90 || rotation == 270) { val swap = width; width = height; height = swap }
                    LibraryMetadata(text(MediaMetadataRetriever.METADATA_KEY_TITLE), text(MediaMetadataRetriever.METADATA_KEY_ARTIST),
                        text(MediaMetadataRetriever.METADATA_KEY_ALBUM), mime?.takeIf { it.startsWith(kind.id + "/") } ?: fallbackMime,
                        text(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it >= 0 }, width, height, rotation)
                } finally { retriever.release() }
            }
        }
    }

}
