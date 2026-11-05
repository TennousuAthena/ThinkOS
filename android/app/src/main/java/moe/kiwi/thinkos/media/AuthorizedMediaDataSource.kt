package moe.kiwi.thinkos.media

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import java.io.IOException
import moe.kiwi.thinkos.net.PublicNetworkPolicy

/** All local playback opens go through the same descriptor gate as media listing and images. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class AuthorizedMediaDataSourceFactory(context: Context, private val http: DataSource.Factory,
                                               private val publicOnly: Boolean = false) : DataSource.Factory {
    private val app = context.applicationContext
    override fun createDataSource(): DataSource = Routing(app, http, publicOnly)

    private class Routing(private val app: Context, private val http: DataSource.Factory, private val publicOnly: Boolean) : DataSource {
        private var current: DataSource? = null
        private val listeners = ArrayList<TransferListener>()
        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            current?.addTransferListener(transferListener)
        }
        override fun open(dataSpec: DataSpec): Long {
            check(current == null) { "Media source is already open" }
            // Media3 sends child manifests/segments/keys and side-loaded text
            // through this same factory. Never route their local schemes to
            // Local(), even when the plugin also has a library grant.
            if (publicOnly) try { PublicNetworkPolicy.validate(dataSpec.uri.toString()) }
                catch (error: IllegalArgumentException) { throw IOException(error.message, error) }
            val selected = when (dataSpec.uri.scheme) {
                "https", "http" -> http.createDataSource()
                "file", "content" -> Local(app)
                else -> throw IOException("Unsupported media source")
            }
            current = selected
            listeners.forEach(selected::addTransferListener)
            return selected.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: throw IOException("Media source is not open")
        override fun getUri(): Uri? = current?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()
        override fun close() {
            val closing = current
            current = null
            closing?.close()
        }
    }

    private class Local(private val app: Context) : BaseDataSource(false) {
        private var uri: Uri? = null
        private var stream: ParcelFileDescriptor.AutoCloseInputStream? = null
        private var remaining = 0L
        private var opened = false
        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            uri = dataSpec.uri
            try {
                val descriptor = LibraryAccess(app).openAuthorized(dataSpec.uri.toString())
                val size = descriptor.statSize
                val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
                stream = input
                if (size >= 0 && dataSpec.position > size) throw DataSourceException(DataSourceException.POSITION_OUT_OF_RANGE)
                // LibraryAccess returns a regular local descriptor or an explicitly granted content
                // descriptor. Seeking happens on ExoPlayer's loader thread, never the main thread.
                if (dataSpec.position > 0) input.channel.position(dataSpec.position)
                remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
                    else if (size >= 0) size - dataSpec.position else C.LENGTH_UNSET.toLong()
                opened = true
                transferStarted(dataSpec)
                return remaining
            } catch (error: Exception) {
                runCatching { close() }
                if (error is IOException && error !is java.io.FileNotFoundException) throw error
                // Say what happened, so the player reports "not found" or "not allowed" rather than a network fault.
                val missing = error is java.io.FileNotFoundException ||
                    (error as? android.system.ErrnoException)?.errno == android.system.OsConstants.ENOENT
                val denied = error is SecurityException || (error as? android.system.ErrnoException)?.errno in
                    setOf(android.system.OsConstants.EACCES, android.system.OsConstants.EPERM)
                throw DataSourceException(error, when {
                    missing -> androidx.media3.common.PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND
                    denied -> androidx.media3.common.PlaybackException.ERROR_CODE_IO_NO_PERMISSION
                    else -> androidx.media3.common.PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                })
            }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
            val wanted = if (remaining == C.LENGTH_UNSET.toLong()) length else minOf(length.toLong(), remaining).toInt()
            val count = stream?.read(buffer, offset, wanted) ?: throw IOException("Media source is not open")
            if (count < 0) return C.RESULT_END_OF_INPUT
            if (remaining != C.LENGTH_UNSET.toLong()) remaining -= count
            bytesTransferred(count)
            return count
        }
        override fun getUri(): Uri? = uri
        override fun close() {
            uri = null
            try { stream?.close() } finally {
                stream = null
                remaining = 0
                if (opened) { opened = false; transferEnded() }
            }
        }
    }
}
