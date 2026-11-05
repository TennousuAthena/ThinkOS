package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SettingTimeTest {
    private val field = SettingField.parse("at", JSONObject("""{"type":"time","label":"At","default":"23:55"}"""), false)

    @Test fun strictAsciiClockValuesStayStrings() {
        for (value in listOf("00:00", "07:05", "12:30", "23:55")) {
            assertEquals(value, field.validate(value))
            assertEquals(value, SettingTime.format(requireNotNull(SettingTime.minutes(value))))
        }
        for (bad in listOf("7:00", "24:00", "12:60", "12:03", "12:00:00", "１２:００", "12:00\n", " 12:00"))
            assertEquals("time", assertThrows(SettingError::class.java) { field.validate(bad) }.code)
        assertThrows(SettingError::class.java) { field.validate(720) }
        assertThrows(SettingError::class.java) { field.validate(false) }
        assertEquals("23:55", SettingField.parse("at", field.toJson(), false).default)
    }

    @Test fun cancelledDraftCannotSubmitAndConfirmIsOneShot() {
        val cancelled = SettingTime.Edit("23:55")
        cancelled.move(5)
        assertEquals("00:00", cancelled.value)
        cancelled.cancel()
        cancelled.move(60)
        assertNull(cancelled.commit())
        assertEquals("00:00", cancelled.value)
        val submitted = SettingTime.Edit("00:00")
        submitted.move(-5)
        assertEquals("23:55", submitted.commit())
        assertNull(submitted.commit())
        submitted.move(60)
        assertEquals("23:55", submitted.value)
    }

    @Test fun allEditorStepsRemainOnTheGridAcrossMidnight() {
        for (minute in 0 until 1440 step 5) for (step in listOf(-60, -5, 5, 60)) {
            val moved = SettingTime.shift(SettingTime.format(minute), step)
            assertEquals((minute + step + 1440) % 1440, SettingTime.minutes(moved))
        }
    }
}
