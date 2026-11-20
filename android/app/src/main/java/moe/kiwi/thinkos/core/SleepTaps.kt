package moe.kiwi.thinkos.core

/** A complete single-finger tap counts once. A new sleep session starts at zero. */
internal class SleepTaps(private val slop: Float, private val longPressMillis: Int, private val debounceMillis: Long) {
    private data class Down(val id: Int, val x: Float, val y: Float, val time: Long)
    private var down: Down? = null
    private var lastTap: Long? = null
    private var count = 0

    fun reset() { down = null; lastTap = null; count = 0 }
    fun begin(id: Int, x: Float, y: Float, time: Long) { down = Down(id, x, y, time) }
    fun cancelGesture() { down = null }

    fun move(id: Int, x: Float, y: Float) {
        val start = down ?: return
        val dx = x - start.x
        val dy = y - start.y
        if (id != start.id || !(dx * dx + dy * dy <= slop * slop)) cancelGesture()
    }

    fun end(id: Int, x: Float, y: Float, time: Long): Boolean {
        move(id, x, y)
        val start = down ?: return false
        cancelGesture()
        if (time - start.time !in 0 until longPressMillis.toLong()) return false
        if (lastTap?.let { start.time - it < debounceMillis } == true) return false
        lastTap = time
        if (++count < 6) return false
        reset()
        return true
    }
}
