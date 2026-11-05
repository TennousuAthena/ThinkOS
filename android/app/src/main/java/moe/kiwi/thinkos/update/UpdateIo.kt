package moe.kiwi.thinkos.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Synchronous bounded work only: never keep a thread's priority lowered across suspension. */
internal object UpdateIo {
    suspend fun <T> run(block: () -> T): T = withContext(Dispatchers.IO) {
        ensureActive()
        val thread = Thread.currentThread()
        val previous = thread.priority
        try { thread.priority = Thread.MIN_PRIORITY; block() } finally { thread.priority = previous }
    }
}
