package moe.kiwi.thinkos.device

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/** The API27 system permission is supplied by provisioning, never granted by the app. */
internal class DeviceLocale(private val context: Context) {
    fun available(): Boolean = Build.VERSION.SDK_INT == 27 &&
        context.checkSelfPermission("android.permission.CHANGE_CONFIGURATION") == PackageManager.PERMISSION_GRANTED

    fun set(tag: String): Boolean {
        if (!available() || !tag.matches(Regex("[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8}){0,5}"))) return false
        val locale = Locale.forLanguageTag(tag)
        if (locale.language.isBlank()) return false
        return runCatching {
            val picker = Class.forName("com.android.internal.app.LocalePicker")
            picker.getDeclaredMethod("updateLocales", LocaleList::class.java).invoke(null, LocaleList(locale))
            true
        }.getOrDefault(false)
    }
}
