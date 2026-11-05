package moe.kiwi.thinkos.settings

import java.util.Locale

/** Local wall-clock time, without a date, zone or a seconds component. */
object SettingTime {
    private val wire = Regex("(?:[01][0-9]|2[0-3]):[0-5][05]")

    fun minutes(value: String): Int? = if (wire.matches(value))
        value.substring(0, 2).toInt() * 60 + value.substring(3, 5).toInt() else null

    fun format(minutes: Int): String {
        require(minutes in 0 until 1440 && minutes % 5 == 0)
        return String.format(Locale.ROOT, "%02d:%02d", minutes / 60, minutes % 60)
    }

    /** Wrap at midnight while preserving the five-minute grid. */
    fun shift(value: String, delta: Int): String {
        require(delta % 5 == 0)
        val current = requireNotNull(minutes(value))
        return format(((current.toLong() + delta) % 1440 + 1440).toInt() % 1440)
    }

    /** An editor owns a draft. Closing it cannot accidentally commit a queued second tap. */
    class Edit(initial: String) {
        var value: String = initial
            private set
        private var closed = false
        init { require(minutes(initial) != null) }
        fun move(delta: Int) { if (!closed) value = shift(value, delta) }
        fun commit(): String? = if (closed) null else value.also { closed = true }
        fun cancel() { closed = true }
    }
}
