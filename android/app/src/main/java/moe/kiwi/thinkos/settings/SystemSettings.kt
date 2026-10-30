package moe.kiwi.thinkos.settings

import android.util.AtomicFile
import moe.kiwi.thinkos.core.AttentionTimings
import moe.kiwi.thinkos.core.ThemeThresholds
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** The OS's own settings: `shared/schema/settings/system.json`, parsed with the plugin field rules. */
object SystemSettings {
    object Keys {
        const val THEME_MODE = "theme.mode"
        const val THEME_NIGHT = "theme.night"
        const val DARK_BELOW = "theme.dark_below_lux"
        const val LIGHT_ABOVE = "theme.light_above_lux"
        const val NIGHT_BELOW = "theme.night_below_lux"
        const val NIGHT_ABOVE = "theme.night_above_lux"
        const val ORIENTATION = "display.orientation"
        const val BRIGHTNESS_MAX = "display.brightness_max"
        const val AWAKE_TO_AMBIENT = "attention.awake_to_ambient_s"
        const val TO_ASLEEP = "attention.to_asleep_s"
        const val ENGAGED_IDLE = "attention.engaged_idle_s"
        const val AMBIENT_PAGE_AFTER = "attention.ambient_page_after_s"
        const val SERVER_URL = "server.url"
        const val SERVER_TOKEN = "server.token"
        const val KIOSK_LOCK = "kiosk.lock"
        const val NTP_SERVERS = "time.ntp_servers"
        const val TIME_SYNC_HOUR = "time.sync_hour"
        const val LOCATION_MODE = "location.mode"
        const val LOCATION_NAME = "location.name"
        const val LOCATION_LATITUDE = "location.latitude"
        const val LOCATION_LONGITUDE = "location.longitude"
        const val LOCATION_ELEVATION = "location.elevation"
        const val LOCATION_TIMEZONE = "location.timezone"
        const val LOCATION_SEARCH_URL = "location.search_url"
    }

    fun parse(json: String, sources: OptionSource = AnyOption): SettingsScope {
        val document = JSONObject(json)
        val declared = document.getJSONObject("fields")
        val fields = linkedMapOf<String, SettingField>()
        for (key in declared.keys()) fields[key] = SettingField.parse(key, declared.getJSONObject(key), system = true, sources = sources)
        val constraints = document.optJSONArray("constraints")?.let { array ->
            List(array.length()) { i ->
                val pair = array.getJSONArray(i)
                val low = pair.getString(0)
                val high = pair.getString(1)
                require(low in fields && high in fields) { "constraint names an unknown setting: $low < $high" }
                low to high
            }
        }.orEmpty()
        return SettingsScope(SettingsStore.SYSTEM, "ThinkOS", fields, constraints)
    }
}

/** Typed reads of system settings; every consumer goes through here, none hard-codes a limit. */
class SystemValues(private val store: SettingsStore) {
    private fun string(key: String) = store.get(SettingsStore.SYSTEM, key) as String
    private fun bool(key: String) = store.get(SettingsStore.SYSTEM, key) as Boolean
    private fun number(key: String) = (store.get(SettingsStore.SYSTEM, key) as Number).toDouble()

    val themeThresholds: ThemeThresholds
        get() = ThemeThresholds(
            mode = string(SystemSettings.Keys.THEME_MODE),
            nightEnabled = bool(SystemSettings.Keys.THEME_NIGHT),
            darkBelow = number(SystemSettings.Keys.DARK_BELOW).toFloat(),
            lightAbove = number(SystemSettings.Keys.LIGHT_ABOVE).toFloat(),
            nightBelow = number(SystemSettings.Keys.NIGHT_BELOW).toFloat(),
            nightAbove = number(SystemSettings.Keys.NIGHT_ABOVE).toFloat(),
        )

    val attentionTimings: AttentionTimings
        get() = AttentionTimings(
            engagedIdleMillis = (number(SystemSettings.Keys.ENGAGED_IDLE) * 1000).toLong(),
            ambientAfterMillis = (number(SystemSettings.Keys.AWAKE_TO_AMBIENT) * 1000).toLong(),
            asleepAfterMillis = (number(SystemSettings.Keys.TO_ASLEEP) * 1000).toLong(),
        )

    /** `auto`, `landscape` or `portrait`. */
    val orientation: String get() = string(SystemSettings.Keys.ORIENTATION)

    /** Fraction 0.2..1.0 used for AWAKE / ENGAGED. */
    val brightnessMax: Float get() = (number(SystemSettings.Keys.BRIGHTNESS_MAX) / 100.0).toFloat()

    val ambientPageAfterMillis: Long get() = (number(SystemSettings.Keys.AMBIENT_PAGE_AFTER) * 1000).toLong()
    val serverUrl: String get() = string(SystemSettings.Keys.SERVER_URL)
    val serverToken: String get() = string(SystemSettings.Keys.SERVER_TOKEN)
    val kioskLock: Boolean get() = bool(SystemSettings.Keys.KIOSK_LOCK)
    /** Host names, comma or whitespace separated. */
    val ntpServers: List<String> get() = string(SystemSettings.Keys.NTP_SERVERS).split(',', ' ', '\n').filter { it.isNotBlank() }
    val timeSyncHour: Int get() = number(SystemSettings.Keys.TIME_SYNC_HOUR).toInt()
}

/** Timezone options are exactly the zones the bundled IANA database knows; aliases normalise to canonical ids. */
object TimezoneSource : OptionSource {
    override fun normalize(source: String, value: String): String? = when (source) {
        "timezone" -> Zones.canonical(value)
        "place" -> value.trim().takeIf { it.length in 1..160 && it.none { c -> c < ' ' } }
        else -> null
    }
}

/** Files under filesDir/settings, written with AtomicFile so a power cut never leaves half a document. */
class AndroidSettingsPersistence(private val directory: File) : SettingsPersistence {
    override fun read(name: String): ByteArray? = try {
        AtomicFile(File(directory, name)).openRead().use { it.readBytes() }
    } catch (_: FileNotFoundException) {
        null
    }

    override fun write(name: String, bytes: ByteArray) {
        directory.mkdirs()
        val atomic = AtomicFile(File(directory, name))
        val output = atomic.startWrite()
        try {
            output.write(bytes)
            atomic.finishWrite(output)
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }
}
