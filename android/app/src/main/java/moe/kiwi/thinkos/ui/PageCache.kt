package moe.kiwi.thinkos.ui

internal data class PageCacheKey(val page: String, val variant: String)

internal enum class WarmSlot { LEFT, RIGHT, BACK, LAUNCH }

internal class PageCachePolicy<T>(private val discard: (T) -> Unit) {
    private data class Entry<T>(val key: PageCacheKey, val view: T)
    private val entries = LinkedHashMap<WarmSlot, Entry<T>>()

    val views: List<T> get() = entries.values.map { it.view }
    val size: Int get() = entries.size
    fun key(slot: WarmSlot): PageCacheKey? = entries[slot]?.key
    operator fun contains(key: PageCacheKey): Boolean = entries.values.any { it.key == key }

    fun take(key: PageCacheKey): T? {
        val slot = entries.entries.firstOrNull { it.value.key == key }?.key ?: return null
        return entries.remove(slot)?.view
    }

    fun put(slot: WarmSlot, key: PageCacheKey, view: T) {
        drop(slot)
        take(key)?.let(discard)
        entries[slot] = Entry(key, view)
        if (entries.size > MAX_WARM) drop(entries.keys.first { it != WarmSlot.BACK })
    }

    fun drop(slot: WarmSlot) { entries.remove(slot)?.view?.let(discard) }

    fun retainOnly(keys: Set<PageCacheKey>) {
        entries.keys.filter { entries.getValue(it).key !in keys }.forEach(::drop)
    }

    fun assign(slots: Map<WarmSlot, PageCacheKey>) {
        retainOnly(slots.values.toSet())
        val byKey = slots.entries.associate { it.value to it.key }
        val ordered = entries.values.toList()
        entries.clear()
        ordered.forEach { entries[byKey.getValue(it.key)] = it }
    }

    fun clear() = retainOnly(emptySet())

    companion object { const val MAX_WARM = 3 }
}
