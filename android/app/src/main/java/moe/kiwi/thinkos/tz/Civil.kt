package moe.kiwi.thinkos.tz

/** Proleptic Gregorian calendar arithmetic on days since 1970-01-01 (Howard Hinnant's algorithms). */
internal object Civil {
    fun isLeap(year: Int): Boolean = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)

    fun daysInMonth(year: Int, month: Int): Int = when (month) {
        2 -> if (isLeap(year)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }

    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = Math.floorDiv(y, 400)
        val yearOfEra = y - era * 400
        val monthIndex = (month + 9) % 12
        val dayOfYear = (153 * monthIndex + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era.toLong() * 146_097 + dayOfEra - 719_468
    }

    /** Returns [year, month, day] for a day count since the epoch. */
    fun civilFromDays(days: Long): IntArray {
        val z = days + 719_468
        val era = Math.floorDiv(z, 146_097L)
        val dayOfEra = z - era * 146_097
        val yearOfEra = (dayOfEra - dayOfEra / 1_460 + dayOfEra / 36_524 - dayOfEra / 146_096) / 365
        val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
        val monthIndex = (5 * dayOfYear + 2) / 153
        val day = (dayOfYear - (153 * monthIndex + 2) / 5 + 1).toInt()
        val month = (if (monthIndex < 10) monthIndex + 3 else monthIndex - 9).toInt()
        val year = (yearOfEra + era * 400 + if (month <= 2) 1 else 0).toInt()
        return intArrayOf(year, month, day)
    }

    /** 0 = Sunday. 1970-01-01 was a Thursday. */
    fun dayOfWeek(days: Long): Int = Math.floorMod(days + 4, 7L).toInt()
}

/** Wall-clock fields of an instant in some zone. [shiftedMillis] is UTC millis plus the zone offset. */
class LocalFields(shiftedMillis: Long) {
    val days: Long = Math.floorDiv(shiftedMillis, 86_400_000L)
    private val millisOfDay = Math.floorMod(shiftedMillis, 86_400_000L)
    val year: Int
    val month: Int
    val day: Int
    val hour: Int = (millisOfDay / 3_600_000).toInt()
    val minute: Int = (millisOfDay / 60_000 % 60).toInt()
    val second: Int = (millisOfDay / 1_000 % 60).toInt()
    /** 0 = Sunday. */
    val dayOfWeek: Int = Civil.dayOfWeek(days)

    init {
        val civil = Civil.civilFromDays(days)
        year = civil[0]
        month = civil[1]
        day = civil[2]
    }
}
