package moe.kiwi.thinkos.plugin

/** Ordinary event errors preserve the instance. Repeated/resource failures restart with backoff. */
internal class PluginHealth {
    enum class Action { KEEP, RESTART, COOL_DOWN }
    private val errors = ArrayDeque<Long>()
    private val restarts = ArrayDeque<Long>()
    var retryAt = 0L
        private set

    fun failure(now: Long, resource: Boolean): Action {
        while (errors.isNotEmpty() && now - errors.first() >= HOUR) errors.removeFirst()
        while (restarts.isNotEmpty() && now - restarts.first() >= HOUR) restarts.removeFirst()
        errors.addLast(now)
        if (!resource && errors.size < 5) return Action.KEEP
        errors.clear()
        restarts.addLast(now)
        retryAt = now + if (restarts.size >= 5) HOUR else (1L shl restarts.size.coerceAtMost(5)) * 1000
        return if (restarts.size >= 5) Action.COOL_DOWN else Action.RESTART
    }
    fun cooledDown() { errors.clear(); restarts.clear(); retryAt = 0 }
    companion object { const val HOUR = 3_600_000L }
}
