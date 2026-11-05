package moe.kiwi.thinkos.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.Canvas
import android.util.LruCache
import android.media.ExifInterface
import android.os.Handler
import android.os.Looper
import moe.kiwi.thinkos.media.LibraryAccess
import moe.kiwi.thinkos.plugin.PluginFiles
import moe.kiwi.thinkos.net.MediaHttpClient
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class LoadedImage(val bitmap: Bitmap, private val release: () -> Unit = {}) : Closeable {
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) release() }
}

/** One bounded decode worker shared by every plugin; no image bytes or bitmaps enter Lua. */
internal class ImageLoader(context: Context, private val owner: (String) -> ImageOwner?) {
    private val app = context.applicationContext
    private val decode = Executors.newSingleThreadExecutor { Thread(it, "ThinkOS-ImageDecode") }
    private val network = Executors.newFixedThreadPool(2) { Thread(it, "ThinkOS-ImageIO") }
    private val cache by lazy { ImageDiskCache(File(app.cacheDir, "images")) }
    private val client by lazy { MediaHttpClient.create() }
    private val main = Handler(Looper.getMainLooper())
    private val appIcons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
    }
    fun grant(id: String) = owner(id)

    fun load(id: String, src: String, width: Int, height: Int, result: (Result<LoadedImage>) -> Unit): Future<*> {
        val grant = owner(id)
        val job = ImageRequest()
        fun deliver(value: Result<LoadedImage>) {
            main.post {
                try {
                    if (job.isCancelled || owner(id) !== grant) value.getOrNull()?.close() else result(value)
                } finally { job.complete() }
            }
        }
        fun submit(executor: ExecutorService, action: () -> Unit) {
            val task = FutureTask<Unit> { if (!job.isCancelled) try { action() } catch (error: Throwable) { deliver(Result.failure(error)) } }
            job.track(task); executor.execute(task)
        }
        fun decodeCached(url: String, lease: ImageDiskCache.Lease) {
            job.onCancel { network.execute { lease.close() } }
            if (job.isCancelled || owner(id) !== grant) {
                lease.close()
                throw java.util.concurrent.CancellationException("Image owner is no longer active")
            }
            try {
                val bytes = lease.file.inputStream().use(::readBounded)
                val image = decode(bytes, width, height)
                deliver(Result.success(LoadedImage(image) { network.execute { lease.close() } }))
            } catch (error: Throwable) {
                lease.close()
                if (!job.isCancelled) cache.discard(url)
                throw error
            }
        }
        submit(decode) {
                require(grant != null && owner(id) === grant) { "Image owner is no longer active" }
                when (val source = ImageSource.parse(src, grant.permissions)) {
                    is ImageSource.AppIcon -> {
                        val manager = app.packageManager
                        val revision = manager.getPackageInfo(source.packageName, 0).lastUpdateTime
                        val size = minOf(width, height).coerceIn(1, 256)
                        val key = "${source.packageName}:$revision:$size"
                        val bitmap = appIcons.get(key) ?: Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
                            val drawable = manager.getApplicationIcon(source.packageName).mutate()
                            drawable.setBounds(0, 0, size, size)
                            drawable.draw(Canvas(bitmap))
                            bitmap.prepareToDraw(); appIcons.put(key, bitmap)
                        }
                        deliver(Result.success(LoadedImage(bitmap)))
                    }
                    is ImageSource.Remote -> {
                        // Cache hits and local files never queue behind a slow/offline HTTP request.
                        val hit = cache.cached(source.url)
                        if (hit != null) decodeCached(source.url, hit)
                        else submit(network) {
                            val lease = cache.acquire(source.url, { file -> download(source.url, file, job) }, ::validateHeader)
                            job.onCancel { network.execute { lease.close() } }
                            submit(decode) { decodeCached(source.url, lease) }
                        }
                    }
                    is ImageSource.Asset -> {
                        val bytes = PluginFiles.resolve(grant.directory, source.path).inputStream().use(::readBounded)
                        deliver(Result.success(LoadedImage(decode(bytes, width, height))))
                    }
                    is ImageSource.Local -> {
                        val bytes = android.os.ParcelFileDescriptor.AutoCloseInputStream(
                            LibraryAccess(app).openAuthorized(source.path)).use(::readBounded)
                        deliver(Result.success(LoadedImage(decode(bytes, width, height))))
                    }
                }
        }
        return job
    }

    private fun download(url: String, file: File, job: ImageRequest) {
        check(!job.isCancelled) { "Image request cancelled" }
        val call = client.newCall(Request.Builder().url(url).header("Accept", "image/*").build())
        job.onCancel { call.cancel() }
        call.execute().use { response ->
            require(response.isSuccessful) { "Image HTTP ${response.code}" }
            val body = response.body ?: error("Image response has no body")
            require(body.contentType()?.type == "image") { "Image response must have an image Content-Type" }
            require(body.contentLength() <= 8L * 1024 * 1024) { "Image file exceeds 8 MiB" }
            body.byteStream().use { input -> file.outputStream().use { out ->
                var bytes = 0L; val buffer = ByteArray(16 * 1024)
                while (true) {
                    check(!job.isCancelled && !Thread.currentThread().isInterrupted) { "Image request cancelled" }
                    val count = input.read(buffer); if (count < 0) break
                    bytes += count; require(bytes <= 8L * 1024 * 1024) { "Image file exceeds 8 MiB" }
                    out.write(buffer, 0, count)
                }
            } }
        }
    }

    private fun validateHeader(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outMimeType?.startsWith("image/") == true) { "Unsupported image data" }
        ImageGeometry.decodeSize(bounds.outWidth, bounds.outHeight, 1, 1)
    }

    private class ImageRequest : Future<Unit> {
        private val result = CompletableFuture<Unit>()
        private val children = CopyOnWriteArrayList<Future<*>>()
        private val cancelled = CopyOnWriteArrayList<() -> Unit>()
        fun track(future: Future<*>) { children += future; if (isCancelled) future.cancel(true) }
        fun onCancel(action: () -> Unit) { cancelled += action; if (isCancelled) action() }
        fun complete() { result.complete(Unit) }
        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            if (!result.cancel(false)) return false
            children.forEach { it.cancel(mayInterruptIfRunning) }; cancelled.forEach { it() }; return true
        }
        override fun isCancelled() = result.isCancelled
        override fun isDone() = result.isDone
        override fun get() { result.get() }
        override fun get(timeout: Long, unit: TimeUnit) { result.get(timeout, unit) }
    }

    private fun readBounded(input: InputStream): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            check(!Thread.currentThread().isInterrupted)
            val count = input.read(buffer)
            if (count < 0) break
            require(out.size() + count <= 8 * 1024 * 1024) { "Image file exceeds 8 MiB" }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    private fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outMimeType?.startsWith("image/") == true) { "Unsupported image data" }
        val (w, h) = ImageGeometry.decodeSize(bounds.outWidth, bounds.outHeight, width, height)
        var sample = 1
        while (bounds.outWidth / sample > w * 2 || bounds.outHeight / sample > h * 2) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("Image decode failed")
        if (bitmap.width > w || bitmap.height > h) {
            val resized = Bitmap.createScaledBitmap(bitmap, w, h, true)
            if (resized !== bitmap) bitmap.recycle()
            bitmap = resized
        }
        val orientation = runCatching { ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }.getOrDefault(1)
        val matrix = Matrix().apply {
            when (orientation) {
                2 -> setScale(-1f, 1f)
                3 -> setRotate(180f)
                4 -> setScale(1f, -1f)
                5 -> { setRotate(90f); postScale(-1f, 1f) }
                6 -> setRotate(90f)
                7 -> { setRotate(270f); postScale(-1f, 1f) }
                8 -> setRotate(270f)
            }
        }
        if (!matrix.isIdentity) {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        bitmap.prepareToDraw()
        return bitmap
    }
}
