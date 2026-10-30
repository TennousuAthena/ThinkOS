package moe.kiwi.thinkos.i18n

import android.content.Context
import android.text.format.DateFormat
import java.util.Locale

/** The device's own idea of the locale, the 24-hour switch and the best pattern for each skeleton. */
class AndroidFormatEnv(private val context: Context) : FormatEnv {
    override val locale: Locale get() = Locale.getDefault()
    override val hour24: Boolean get() = DateFormat.is24HourFormat(context)
    override fun bestPattern(skeleton: String): String = DateFormat.getBestDateTimePattern(locale, skeleton)
}
