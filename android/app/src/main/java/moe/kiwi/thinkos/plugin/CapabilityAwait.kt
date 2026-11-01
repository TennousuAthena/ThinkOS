package moe.kiwi.thinkos.plugin

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/** One suspended capability call, including its permission request and queued UI/IO work. */
internal class CapabilityAwait(
    private val generationIsLive: () -> Boolean,
    private val completed: (Array<Any?>) -> Unit,
) : Future<Unit> {
    private data class Child(val future: Future<*>, val interruptible: Boolean)
    private val result = CompletableFuture<Unit>()
    private val children = CopyOnWriteArrayList<Child>()
    private val cancellation = CopyOnWriteArrayList<() -> Unit>()

    fun live(): Boolean = !result.isDone && generationIsLive()

    fun track(future: Future<*>, interruptible: Boolean = true) {
        val child = Child(future, interruptible)
        if (result.isCancelled) { future.cancel(interruptible); return }
        children += child
        if (result.isCancelled && children.remove(child)) future.cancel(interruptible)
    }

    fun onCancel(action: () -> Unit) {
        if (result.isCancelled) { action(); return }
        cancellation += action
        if (result.isCancelled && cancellation.remove(action)) action()
    }

    fun finish(value: Any?, error: String? = null) {
        if (!generationIsLive()) { cancel(true); return }
        if (result.complete(Unit)) completed(arrayOf(value, error))
    }

    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        if (!result.cancel(false)) return false
        children.toList().forEach { child ->
            if (children.remove(child)) child.future.cancel(mayInterruptIfRunning && child.interruptible)
        }
        cancellation.toList().forEach { action -> if (cancellation.remove(action)) action() }
        return true
    }

    override fun isCancelled(): Boolean = result.isCancelled
    override fun isDone(): Boolean = result.isDone
    override fun get() { result.get() }
    override fun get(timeout: Long, unit: TimeUnit) { result.get(timeout, unit) }
}
