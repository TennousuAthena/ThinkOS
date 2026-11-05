package moe.kiwi.thinkos.cast.miracast

/** Written only by Miracast's serial executor. Readers may observe the latest
 * value on other threads. A queued notification cannot replay an older answer
 * after disable (null) or a later enable/inspection supersedes it. */
internal class MiracastAvailability(
    private val post: (() -> Unit) -> Unit,
    private val changed: () -> Unit,
) {
    @Volatile var value: Boolean? = null
        private set
    @Volatile private var revision = 0L

    fun set(next: Boolean?) {
        if (value == next) return
        value = next
        val ticket = Math.addExact(revision, 1L)
        revision = ticket
        post { if (revision == ticket) changed() }
    }
}
