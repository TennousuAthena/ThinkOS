package moe.kiwi.thinkos.tz

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** UTC offset in seconds, whether it is daylight saving time, and the zone abbreviation. */
data class LocalType(val offset: Int, val dst: Boolean, val abbreviation: String)

/**
 * One zone's rules, read from an IANA-compiled TZif file (RFC 8536).
 *
 * Explicit transitions answer for the period they cover. At or after the last explicit
 * transition the POSIX TZ footer takes over, so DST keeps working past the end of the table.
 */
class ZoneRules internal constructor(
    private val transitions: LongArray,
    private val typeIndex: IntArray,
    private val types: List<LocalType>,
    private val footer: PosixRule?,
) {
    fun typeAt(epochSecond: Long): LocalType {
        val count = transitions.size
        if (count == 0) return footer?.typeAt(epochSecond) ?: types.first()
        if (epochSecond < transitions[0]) return types.first()      // RFC 8536 3.2: time type 0
        if (epochSecond >= transitions[count - 1] && footer != null) return footer.typeAt(epochSecond)
        var low = 0
        var high = count - 1
        while (low < high) {
            val middle = (low + high + 1) ushr 1
            if (transitions[middle] <= epochSecond) low = middle else high = middle - 1
        }
        return types[typeIndex[low]]
    }

    fun offsetAt(epochSecond: Long): Int = typeAt(epochSecond).offset
}

/** Reads the 64-bit data block and the footer of a TZif version 2+ file. */
object TzIf {
    private const val MAX_TRANSITIONS = 20_000
    private const val MAX_CHARS = 2_048

    fun parse(bytes: ByteArray): ZoneRules {
        require(bytes.size > 44 && bytes[0] == 'T'.code.toByte() && bytes[1] == 'Z'.code.toByte() &&
            bytes[2] == 'i'.code.toByte() && bytes[3] == 'f'.code.toByte()) { "not a TZif file" }
        require(bytes[4] >= '2'.code.toByte()) { "TZif version 1 has no 64-bit data" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val first = Counts(buffer, 0)
        val secondHeader = 44 + first.dataSize(4)
        require(secondHeader + 44 <= bytes.size) { "truncated TZif file" }
        val counts = Counts(buffer, secondHeader)
        var position = secondHeader + 44

        require(position + counts.dataSize(8) <= bytes.size) { "truncated TZif data" }
        val transitions = LongArray(counts.transitions) { buffer.getLong(position + it * 8) }
        position += counts.transitions * 8
        val indexes = IntArray(counts.transitions) { bytes[position + it].toInt() and 0xff }
        position += counts.transitions
        val raw = List(counts.types) { index ->
            val at = position + index * 6
            Triple(buffer.getInt(at), bytes[at + 4].toInt() != 0, bytes[at + 5].toInt() and 0xff)
        }
        position += counts.types * 6
        val characters = bytes.copyOfRange(position, position + counts.chars)
        position += counts.chars + counts.leaps * 12 + counts.standardIndicators + counts.utcIndicators

        val types = raw.map { (offset, dst, nameIndex) ->
            require(nameIndex < characters.size) { "bad abbreviation index" }
            var end = nameIndex
            while (end < characters.size && characters[end] != 0.toByte()) end++
            LocalType(offset, dst, String(characters, nameIndex, end - nameIndex, Charsets.US_ASCII))
        }
        require(indexes.all { it < types.size }) { "bad transition type" }
        for (i in 1 until transitions.size) require(transitions[i] > transitions[i - 1]) { "transitions out of order" }

        // Footer: "\n<POSIX TZ string>\n"
        var footer: PosixRule? = null
        if (position < bytes.size && bytes[position] == '\n'.code.toByte()) {
            val end = (position + 1 until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() } ?: bytes.size
            val text = String(bytes, position + 1, end - position - 1, Charsets.US_ASCII)
            if (text.isNotEmpty()) footer = PosixRule.parse(text)
        }
        return ZoneRules(transitions, indexes, types, footer)
    }

    private class Counts(buffer: ByteBuffer, at: Int) {
        val utcIndicators = buffer.getInt(at + 20)
        val standardIndicators = buffer.getInt(at + 24)
        val leaps = buffer.getInt(at + 28)
        val transitions = buffer.getInt(at + 32)
        val types = buffer.getInt(at + 36)
        val chars = buffer.getInt(at + 40)

        init {
            require(types in 1..256 && transitions in 0..MAX_TRANSITIONS && chars in 1..MAX_CHARS &&
                leaps in 0..1_000 && standardIndicators in 0..256 && utcIndicators in 0..256) { "implausible TZif counts" }
        }
        fun dataSize(timeSize: Int) = transitions * timeSize + transitions + types * 6 + chars +
            leaps * (timeSize + 4) + standardIndicators + utcIndicators
    }
}

/**
 * POSIX TZ rule such as `CET-1CEST,M3.5.0,M10.5.0/3` or `<-02>2<-01>,M3.5.0/-1,M10.5.0/0`.
 * Supports `Mm.w.d`, `Jn` and `n` dates and signed or >24h transition times.
 */
class PosixRule private constructor(
    private val standard: LocalType,
    private val daylight: LocalType?,
    private val start: RuleDate?,
    private val startTime: Int,
    private val end: RuleDate?,
    private val endTime: Int,
) {
    sealed class RuleDate {
        /** `Jn`: day 1..365, February 29 is never counted. */
        data class Julian(val day: Int) : RuleDate()
        /** `n`: day 0..365, February 29 counted in leap years. */
        data class ZeroBased(val day: Int) : RuleDate()
        /** `Mm.w.d`: week 5 means the last such weekday of the month; day 0 is Sunday. */
        data class MonthWeekDay(val month: Int, val week: Int, val weekday: Int) : RuleDate()

        fun dayIn(year: Int): Long {
            val january1 = Civil.daysFromCivil(year, 1, 1)
            return when (this) {
                is Julian -> january1 + day - 1 + if (Civil.isLeap(year) && day >= 60) 1 else 0
                is ZeroBased -> january1 + day
                is MonthWeekDay -> {
                    val first = Civil.daysFromCivil(year, month, 1)
                    var result = first + Math.floorMod(weekday - Civil.dayOfWeek(first), 7) + (week - 1) * 7L
                    while (result >= first + Civil.daysInMonth(year, month)) result -= 7
                    result
                }
            }
        }
    }

    fun typeAt(epochSecond: Long): LocalType {
        val daylight = daylight ?: return standard
        val start = start ?: return standard
        val end = end ?: return standard
        val year = Civil.civilFromDays(Math.floorDiv(epochSecond + standard.offset, 86_400L))[0]
        // Rule dates are wall-clock: the start is in standard time, the end in daylight time.
        var latest = Long.MIN_VALUE
        var latestIsDaylight = false
        var earliest = Long.MAX_VALUE
        var earliestIsDaylight = false
        for (y in year - 1..year + 1) {
            val begins = start.dayIn(y) * 86_400L + startTime - standard.offset
            val ends = end.dayIn(y) * 86_400L + endTime - daylight.offset
            for ((instant, toDaylight) in listOf(begins to true, ends to false)) {
                if (instant <= epochSecond && instant > latest) { latest = instant; latestIsDaylight = toDaylight }
                if (instant < earliest) { earliest = instant; earliestIsDaylight = toDaylight }
            }
        }
        val inDaylight = if (latest == Long.MIN_VALUE) !earliestIsDaylight else latestIsDaylight
        return if (inDaylight) daylight else standard
    }

    companion object {
        fun parse(text: String): PosixRule {
            val reader = Reader(text)
            val standardName = reader.name()
            val standard = LocalType(-reader.offset(), false, standardName)
            if (reader.finished()) return PosixRule(standard, null, null, 0, null, 0)
            val daylightName = reader.name()
            val daylightOffset = if (reader.finished() || reader.peek() == ',') standard.offset + 3_600 else -reader.offset()
            val daylight = LocalType(daylightOffset, true, daylightName)
            if (reader.finished()) {
                // POSIX leaves the default to the implementation: the historic US rule.
                return PosixRule(standard, daylight, RuleDate.MonthWeekDay(3, 2, 0), 7_200, RuleDate.MonthWeekDay(11, 1, 0), 7_200)
            }
            reader.expect(',')
            val (start, startTime) = reader.rule()
            reader.expect(',')
            val (end, endTime) = reader.rule()
            require(reader.finished()) { "unexpected text in TZ rule: $text" }
            return PosixRule(standard, daylight, start, startTime, end, endTime)
        }
    }

    private class Reader(val text: String) {
        var position = 0
        fun finished() = position >= text.length
        fun peek() = text[position]
        fun expect(c: Char) { require(!finished() && text[position] == c) { "expected '$c' in TZ rule: $text" }; position++ }

        fun name(): String {
            require(!finished()) { "missing zone name in TZ rule: $text" }
            if (text[position] == '<') {
                val close = text.indexOf('>', position)
                require(close > position) { "unterminated <name> in TZ rule: $text" }
                return text.substring(position + 1, close).also { position = close + 1 }
            }
            val begin = position
            while (!finished() && text[position].isLetter()) position++
            require(position - begin >= 3) { "bad zone name in TZ rule: $text" }
            return text.substring(begin, position)
        }

        /** [+-]hh[:mm[:ss]] in seconds; the sign is POSIX's (positive = west of Greenwich). */
        fun offset(): Int {
            var sign = 1
            if (!finished() && (text[position] == '+' || text[position] == '-')) { if (text[position] == '-') sign = -1; position++ }
            val hours = number()
            var seconds = hours * 3_600
            if (!finished() && text[position] == ':') { position++; seconds += number() * 60
                if (!finished() && text[position] == ':') { position++; seconds += number() } }
            return sign * seconds
        }

        fun number(): Int {
            val begin = position
            while (!finished() && text[position].isDigit()) position++
            require(position > begin && position - begin <= 3) { "bad number in TZ rule: $text" }
            return text.substring(begin, position).toInt()
        }

        fun rule(): Pair<RuleDate, Int> {
            val date = when {
                finished() -> error("missing rule date in TZ rule: $text")
                text[position] == 'M' -> {
                    position++
                    val month = number(); expect('.'); val week = number(); expect('.'); val weekday = number()
                    require(month in 1..12 && week in 1..5 && weekday in 0..6) { "bad Mm.w.d in TZ rule: $text" }
                    RuleDate.MonthWeekDay(month, week, weekday)
                }
                text[position] == 'J' -> { position++; RuleDate.Julian(number().also { require(it in 1..365) }) }
                else -> RuleDate.ZeroBased(number().also { require(it in 0..365) })
            }
            var time = 7_200
            if (!finished() && text[position] == '/') { position++; time = offset() }
            return date to time
        }
    }
}
