package moe.kiwi.thinkos.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Native Android permission UI, shared by all capability callers without granting plugin powers. */
class StoragePermission(context: Context, private val launch: (Long) -> Boolean) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val next = AtomicLong()
    private var requestId: Long? = null
    private data class Pending(val ticket: Ticket, val complete: (Boolean, String?) -> Unit)
    private val pending = linkedMapOf<Long, Pending>()

    inner class Ticket internal constructor(val owner: String, internal val id: Long) : AutoCloseable {
        internal val cancelled = AtomicBoolean(false)
        override fun close() {
            if (cancelled.compareAndSet(false, true)) main.post { pending.remove(id) }
        }
    }

    /** Main-thread only. Cancellation is safe from a plugin/IO thread. */
    fun request(owner: String, complete: (Boolean, String?) -> Unit): Ticket {
        checkMain()
        val ticket = Ticket(owner, next.incrementAndGet())
        if (app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) {
            complete(true, null)
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
        val actualGrant = granted && app.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        resolve(id, actualGrant, if (actualGrant) null else "READ_EXTERNAL_STORAGE permission was denied")
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
        check(Looper.myLooper() == Looper.getMainLooper()) { "Storage permission UI must run on main" }
    }
}
