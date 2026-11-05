package moe.kiwi.thinkos.update

import android.content.Context
import android.os.FileObserver
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*
import moe.kiwi.thinkos.BuildConfig
import moe.kiwi.thinkos.R
import moe.kiwi.thinkos.ui.LegalText
import java.util.Locale

/** Process-owned journal observer, independent of distribution keys/plugins.
 * It does not create an update directory on an unconfigured device. */
internal class UpdateNoticeRuntime(private val context: Context, private val scope: CoroutineScope,
                                   private val clock: () -> UpdateInstallTime) {
    data class State(val notice: UpdateResultStatus? = null, val silent: Boolean? = null)
    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable
    private val changes = MutableStateFlow(0L)
    private var started = false
    fun changed() { changes.value++ }
    fun start() {
        if (started) return
        started = true
        scope.launch {
            combine(signals(), changes) { _, _ -> Unit }.collectLatest {
                val item = try { UpdateIo.run {
                    val store = UpdateInstallContext.store(context)
                    val silent = UpdateInstaller(context, store) { clock() }.silent()
                    val value = if (store.directory.isDirectory) UpdateNotice.read(store.snapshot(), BuildConfig.VERSION_CODE, clock(), Locale.getDefault()) else null
                    silent to value
                } } catch (cancelled: CancellationException) { throw cancelled } catch (error: Exception) {
                    android.util.Log.e("ThinkOS", "Update result unavailable; preserving its journal", error)
                    null to null
                }
                val notice = item.second?.let { value ->
                    val message = when (value.kind) {
                        "updated" -> context.getString(R.string.update_updated, value.version)
                        "rolled_back" -> context.getString(R.string.update_rolled_back)
                        "rollback_failed" -> context.getString(R.string.update_rollback_failed)
                        else -> context.getString(R.string.update_install_failed)
                    }
                    val reason = value.reason?.let { context.getString(when (it) {
                        "crash_loop" -> R.string.update_startup_failed
                        "interrupted" -> R.string.update_interrupted
                        "package" -> R.string.update_package_mismatch
                        "rollback_failed" -> R.string.update_rollback_failed
                        else -> R.string.update_install_failed
                    }) }
                    val plain = withContext(Dispatchers.Default) {
                        val text = LegalText.plain(value.notes)
                        if (text.length <= 2048) text else text.take(if (text[2047].isHighSurrogate()) 2047 else 2048)
                    }
                    UpdateResultStatus(value.kind, value.version, value.code, message, plain, reason)
                }
                mutable.value = State(notice, item.first)
                item.second?.let { value ->
                    delay(value.remaining)
                    mutable.value = State(silent = item.first)
                }
            }
        }
    }

    private fun signals() = callbackFlow {
        val root = UpdateInstallContext.directory(context)
        @Suppress("DEPRECATION")
        val child = object : FileObserver(root.path, MOVED_TO or DELETE) {
            override fun onEvent(event: Int, path: String?) {
                if (path in setOf("pending.json", "install-history.json")) trySend(Unit)
            }
        }
        @Suppress("DEPRECATION")
        val parent = object : FileObserver(context.filesDir.path, CREATE or DELETE or MOVED_TO or MOVED_FROM) {
            override fun onEvent(event: Int, path: String?) {
                if (path == "update") {
                    synchronized(child) { child.stopWatching(); child.startWatching() }
                    trySend(Unit)
                }
            }
        }
        parent.startWatching(); child.startWatching(); trySend(Unit)
        awaitClose { parent.stopWatching(); synchronized(child) { child.stopWatching() } }
    }
}
