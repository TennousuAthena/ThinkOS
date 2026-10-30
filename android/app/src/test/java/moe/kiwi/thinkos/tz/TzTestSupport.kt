package moe.kiwi.thinkos.tz

import java.io.File

internal object TzTestSupport {
    /** `shared/tzdata`, passed in by Gradle so tests do not depend on the working directory. */
    val dir: File = File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata")

    val database: TzDatabase by lazy { TzDatabase(FileTzSource(dir)) }

    /** Fixed UTC instants (seconds) used by several tests. */
    fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Civil.daysFromCivil(year, month, day) * 86_400L + hour * 3_600L + minute * 60L
}
