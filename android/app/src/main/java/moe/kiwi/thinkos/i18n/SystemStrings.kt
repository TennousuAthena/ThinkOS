package moe.kiwi.thinkos.i18n

import android.content.res.Resources

/** The OS's own strings are ordinary Android resources, so lint checks every shipped language is complete. */
class SystemStrings(private val resources: Resources, private val packageName: String) {
    /** `setting.theme.mode` reads the string resource `setting_theme_mode`. */
    fun get(key: String): String? {
        val id = resources.getIdentifier(key.replace('.', '_'), "string", packageName)
        return if (id == 0) null else resources.getString(id)
    }
}
