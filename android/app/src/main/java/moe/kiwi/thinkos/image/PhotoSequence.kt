package moe.kiwi.thinkos.image

import org.json.JSONArray

internal data class Photo(val id: String, val src: String, val title: String = "", val credit: String = "")

/** Identity rather than array position survives data refreshes and reordering. */
internal class PhotoSequence {
    var items: List<Photo> = emptyList(); private set
    var current: String? = null
    private var source = emptyList<Photo>()
    private var shuffled = false
    fun update(next: List<Photo>, shuffle: Boolean) {
        if (source != next || shuffled != shuffle) {
            source = next; shuffled = shuffle
            items = if (shuffle) next.shuffled() else next
        }
        if (items.isNotEmpty() && items.none { it.id == current }) current = items.first().id
    }
    fun next(excluded: Set<String> = emptySet()): Photo? {
        if (items.isEmpty()) return null
        val index = items.indexOfFirst { it.id == current }.coerceAtLeast(0)
        return (1..items.size).asSequence().map { items[(index + it) % items.size] }.firstOrNull { it.id !in excluded }
    }
    fun selected() = items.firstOrNull { it.id == current }
    /** Manual history browsing stops at either end; autoplay remains a loop. */
    fun adjacent(direction: Int, from: String? = current, excluded: Set<String> = emptySet()): Photo? {
        if (direction !in setOf(-1, 1)) return null
        val start = items.indexOfFirst { it.id == from }
        if (start < 0) return null
        var index = start + direction
        while (index in items.indices) {
            if (items[index].id !in excluded) return items[index]
            index += direction
        }
        return null
    }

    companion object {
        fun parse(array: JSONArray?): List<Photo> {
            val seen = hashSetOf<String>()
            return (0 until minOf(array?.length() ?: 0, 300)).mapNotNull { n ->
                val item = array?.optJSONObject(n) ?: return@mapNotNull null
                val src = (item.opt("src") as? String)?.takeIf { it.isNotBlank() && it.length <= 2048 } ?: return@mapNotNull null
                val id = (item.opt("id") as? String)?.takeIf { it.isNotBlank() && it.length <= 2048 } ?: src
                if (!seen.add(id)) return@mapNotNull null
                Photo(id, src, (item.opt("title") as? String).orEmpty().take(512), (item.opt("credit") as? String).orEmpty().take(512))
            }
        }
    }
}
