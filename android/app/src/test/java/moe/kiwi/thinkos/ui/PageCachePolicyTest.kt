package moe.kiwi.thinkos.ui

import org.junit.Assert.*
import org.junit.Test

class PageCachePolicyTest {
    private val dropped = mutableListOf<String>()
    private val cache = PageCachePolicy<String>(dropped::add)
    private fun key(page: String, variant: String = "main") = PageCacheKey(page, variant)

    @Test fun overflowDropsOldestPageAfterGivingBackPriority() {
        cache.put(WarmSlot.BACK, key("back"), "back")
        cache.put(WarmSlot.LEFT, key("left"), "left")
        cache.put(WarmSlot.RIGHT, key("right"), "right")
        cache.put(WarmSlot.LAUNCH, key("launch"), "launch")
        assertEquals(3, cache.size)
        assertEquals(listOf("left"), dropped)
        assertEquals(listOf("back", "right", "launch"), cache.views)
    }

    @Test fun takingAndRetainingUseTheExactVariant() {
        cache.put(WarmSlot.LEFT, key("clock", "digital"), "digital")
        cache.put(WarmSlot.RIGHT, key("clock", "analog"), "analog")
        assertEquals("analog", cache.take(key("clock", "analog")))
        assertNull(cache.take(key("clock")))
        cache.retainOnly(setOf(key("clock", "analog")))
        assertEquals(listOf("digital"), dropped)
        assertTrue(cache.views.isEmpty())
    }

    @Test fun replacingASlotOrKeyDiscardsEachOldEntryOnce() {
        cache.put(WarmSlot.LEFT, key("clock"), "first")
        cache.put(WarmSlot.RIGHT, key("clock"), "second")
        cache.put(WarmSlot.RIGHT, key("photos"), "third")
        cache.clear()
        assertEquals(listOf("first", "second", "third"), dropped)
    }
}
