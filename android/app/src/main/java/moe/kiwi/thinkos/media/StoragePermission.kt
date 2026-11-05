package moe.kiwi.thinkos.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Native Android permission UI, shared by all capability callers without granting plugin powers. */
class StoragePermission internal constructor(
    private val granted: () -> Boolean,
    private val launch: (Long) -> Boolean,
    private val postToMain: (() -> Unit) -> Unit,
    private val assertMain: () -> Unit,
) {
    constructor(context: Context, launch: (Long) -> Boolean) : this(
        androidGrant(context),
        launch, androidPost(),
        { check(Looper.myLooper() == Looper.getMainLooper()) { "Storage permission UI must run on main" } },
    )
    private val next = AtomicLong()
    private var requestId: Long? = null
    // One explicit denial applies to all background capability retries in this
    // runtime. Do not turn the next audio/video scan into another system prompt.
    // Android's actual grant always wins; no preference or permission is changed.
    private var denied = false
    private data class Pending(val ticket: Ticket, val complete: (Boolean, String?) -> Unit)
    private val pending = linkedMapOf<Long, Pending>()

    inner class Ticket internal constructor(val owner: String, internal val id: Long) : AutoCloseable {
        internal val cancelled = AtomicBoolean(false)
        override fun close() {
            if (cancelled.compareAndSet(false, true)) postToMain { pending.remove(id) }
        }
    }

    /** Main-thread only. Cancellation is safe from a plugin/IO thread. */
    fun request(owner: String, complete: (Boolean, String?) -> Unit): Ticket {
        checkMain()
        val ticket = Ticket(owner, next.incrementAndGet())
        if (granted()) {
            denied = false
            complete(true, null)
            return ticket
        }
        if (denied) {
            complete(false, DENIED)
            return ticket
        }
        pending[ticket.id] = Pending(ticket, complete)
        if (requestId == null) {
            val id = next.incrementAndGet()
            requestId = id
            if (!launch(id)) resolve(id, false, "No active screen is available to request media access")
        }
        return ticket
    }

    fun result(id: Long, granted: Boolean) {
        checkMain()
        if (requestId != id) return
        val actualGrant = granted && this.granted()
        denied = !actualGrant // Set before resolving a Lua call that may retry immediately.
        resolve(id, actualGrant, if (actualGrant) null else DENIED)
    }

    /** An Activity cannot leave Lua suspended if it is destroyed while showing the system dialog. */
    fun hostClosed(id: Long) {
        checkMain()
        resolve(id, false, "Media access permission screen was closed")
    }

    private fun resolve(id: Long, granted: Boolean, error: String?) {
        if (requestId != id) return
        requestId = null
        val requests = pending.values.toList()
        pending.clear()
        requests.forEach { if (!it.ticket.cancelled.get()) it.complete(granted, error) }
    }

    private fun checkMain() {
        assertMain()
    }

    companion object {
        private const val DENIED = "READ_EXTERNAL_STORAGE permission was denied"
        private fun androidGrant(context: Context): () -> Boolean {
            val app = context.applicationContext
            return { app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED }
        }
        private fun androidPost(): (() -> Unit) -> Unit {
            val main = Handler(Looper.getMainLooper())
            return { task -> main.post { task() }; Unit }
        }
    }
}
