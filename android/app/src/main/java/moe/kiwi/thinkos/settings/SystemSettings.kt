package moe.kiwi.thinkos.settings

import android.util.AtomicFile
import moe.kiwi.thinkos.core.AttentionTimings
import moe.kiwi.thinkos.core.ThemeThresholds
import moe.kiwi.thinkos.media.MediaPolicy
import moe.kiwi.thinkos.tz.Zones
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException

/** The OS's own settings: `shared/schema/settings/system.json`, parsed with the plugin field rules. */
object SystemSettings {
    object Keys {
        const val THEME_MODE = "theme.mode"
        const val THEME_NIGHT = "theme.night"
        const val REDSHIFT_TRIGGER = "redshift.trigger"
        const val REDSHIFT_START = "redshift.start"
        const val REDSHIFT_END = "redshift.end"
        const val SLEEP_ENABLED = "sleep.enabled"
        const val SLEEP_START = "sleep.start"
        const val SLEEP_END = "sleep.end"
        const val SLEEP_WAKE = "sleep.wake"
        const val MODE_MANUAL_HOLD = "mode.manual_hold"
        const val DARK_BELOW = "theme.dark_below_lux"
        const val LIGHT_ABOVE = "theme.light_above_lux"
        const val NIGHT_BELOW = "theme.night_below_lux"
        const val NIGHT_ABOVE = "theme.night_above_lux"
        const val ORIENTATION = "display.orientation"
        const val UNITS_SYSTEM = "units.system"
        const val UNITS_WIND = "units.wind"
        const val TILES_LIVE = "tiles.live"
        const val TILES_INTERVAL = "tiles.interval"
        const val TILES_BACKGROUND = "tiles.background"
        const val CLOCK_MOTION = "motion.clock"
        const val BRIGHTNESS_MAX = "display.brightness_max"
        const val AWAKE_TO_AMBIENT = "attention.awake_to_ambient_s"
        const val TO_ASLEEP = "attention.to_asleep_s"
        const val ENGAGED_IDLE = "attention.engaged_idle_s"
        const val AMBIENT_PAGE_AFTER = "attention.ambient_page_after_s"
        const val SERVER_URL = "server.url"
        const val SERVER_TOKEN = "server.token"
        const val KIOSK_LOCK = "kiosk.lock"
        const val PANEL_ENABLED = "panel.enabled"
        const val PANEL_EDGE_DP = "panel.edge_dp"
        const val PANEL_IDLE = "panel.idle_s"
        const val NAV_BACK_ENABLED = "nav.back_enabled"
        const val NAV_BACK_EDGE_DP = "nav.back_edge_dp"
        const val NAV_UP_ENABLED = "nav.up_enabled"
        const val NAV_UP_EDGE_DP = "nav.up_edge_dp"
        const val APPS_ANDROID = "apps.android"
        const val APPS_HIDDEN = "apps.hidden"
        const val APPS_RETURN_AFTER = "apps.return_after_s"
        const val NTP_SERVERS = "time.ntp_servers"
        const val TIME_SYNC_INTERVAL = "time.sync_interval"
        const val LOCATION_MODE = "location.mode"
        const val LOCATION_NAME = "location.name"
        const val LOCATION_LATITUDE = "location.latitude"
        const val LOCATION_LONGITUDE = "location.longitude"
        const val LOCATION_ELEVATION = "location.elevation"
        const val LOCATION_TIMEZONE = "location.timezone"
        const val LOCATION_SEARCH_URL = "location.search_url"
        const val MEDIA_PUBLIC_HTTP = "media.public_http"
        const val MEDIA_KEEP_AWAKE_VIDEO = "media.keep_awake_video"
        const val MEDIA_BUFFER_S = "media.buffer_s"
        const val MEDIA_BUFFER_MB = "media.buffer_mb"
        const val CAST_ENABLED = "cast.enabled"
        const val CAST_AIRPLAY = "cast.airplay"
        const val CAST_AIRPLAY_MIRROR = "cast.airplay_mirror"
        const val CAST_MIRACAST = "cast.miracast"
        const val CAST_NAME = "cast.name"
        const val UPDATE_ENABLED = "update.enabled"
        const val UPDATE_AUTO_INSTALL = "update.auto_install"
        const val UPDATE_CHANNEL = "update.channel"
        const val UPDATE_MIRROR = "update.mirror"
        const val UPDATE_SOURCES = "update.sources"
        const val ONBOARDING_DONE = "onboarding.done"
        const val ONBOARDING_INITIALIZED = "onboarding.initialized"
        const val ONBOARDING_ATTEMPTS = "onboarding.attempts"
        const val ONBOARDING_MAX_PROMPTS = "onboarding.max_prompts"
        const val ONBOARDING_IDLE = "onboarding.idle_s"
        const val ONBOARDING_GREETING = "onboarding.greeting_s"
    }

    fun parse(json: String, sources: OptionSource = AnyOption): SettingsScope {
        val document = JSONObject(json)
        document.keys().forEach { require(it in setOf("description", "scope", "fields", "constraints", "sections")) { "unknown system settings property $it" } }
        require(document.getString("scope") == SettingsStore.SYSTEM) { "invalid system settings scope" }
        val sections = if (document.has("sections")) {
            val array = document.getJSONArray("sections")
            require(array.length() <= 32) { "too many settings sections" }
            List(array.length()) { i ->
                val item = array.getJSONObject(i)
                require(item.has("id") && item.has("label") && item.keys().asSequence().all { it in setOf("id", "label", "icon") }) { "section needs id, label and optional icon" }
                SettingSection(item.get("id") as? String ?: throw IllegalArgumentException("section id must be text"),
                    item.get("label") as? String ?: throw IllegalArgumentException("section label must be text"),
                    if (item.has("icon")) item.get("icon") as? String ?: throw IllegalArgumentException("section icon must be text") else null)
            }
        } else emptyList()
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
        return SettingsScope(SettingsStore.SYSTEM, "ThinkOS", fields, constraints, sections)
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
            nightEnabled = string(SystemSettings.Keys.REDSHIFT_TRIGGER) != "off",
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
    val onboardingNeeded: Boolean get() = moe.kiwi.thinkos.core.OnboardingPolicy.prompt(
        number(SystemSettings.Keys.ONBOARDING_DONE).toInt(), number(SystemSettings.Keys.ONBOARDING_ATTEMPTS).toInt(),
        number(SystemSettings.Keys.ONBOARDING_MAX_PROMPTS).toInt(), false)
    val onboardingIdleMillis: Long get() = (number(SystemSettings.Keys.ONBOARDING_IDLE) * 1000).toLong()
    val onboardingGreetingSeconds: Double get() = number(SystemSettings.Keys.ONBOARDING_GREETING)

    /** Measurement units: `metric` or `imperial`, and the wind speed unit (`auto` follows the system). */
    val units: Units get() = Units(string(SystemSettings.Keys.UNITS_SYSTEM), string(SystemSettings.Keys.UNITS_WIND))

    /** `none`, `roll`, `fade`, `drop`, `flip` or `zoom`: how clock digits change. */
    val tilesLive: Boolean get() = bool(SystemSettings.Keys.TILES_LIVE)
    val tilesIntervalMillis: Long get() = (number(SystemSettings.Keys.TILES_INTERVAL) * 1000).toLong()

    /** `indigo`, `teal` or `wine`: the tile page backdrop. */
    val tilesBackground: String get() = string(SystemSettings.Keys.TILES_BACKGROUND)

    val clockMotion: String get() = string(SystemSettings.Keys.CLOCK_MOTION)

    /** Fraction 0.2..1.0 used for AWAKE / ENGAGED. */
    val brightnessMax: Float get() = (number(SystemSettings.Keys.BRIGHTNESS_MAX) / 100.0).toFloat()

    val ambientPageAfterMillis: Long get() = (number(SystemSettings.Keys.AMBIENT_PAGE_AFTER) * 1000).toLong()
    val serverUrl: String get() = string(SystemSettings.Keys.SERVER_URL)
    val serverToken: String get() = string(SystemSettings.Keys.SERVER_TOKEN)
    val kioskLock: Boolean get() = bool(SystemSettings.Keys.KIOSK_LOCK)

    /** The control sheet that opens from the top edge, and with it the block on Android's own notification shade (ADR-035). */
    val panelEnabled: Boolean get() = bool(SystemSettings.Keys.PANEL_ENABLED)
    val panelEdgeDp: Float get() = number(SystemSettings.Keys.PANEL_EDGE_DP).toFloat()
    val panelIdleMillis: Long get() = (number(SystemSettings.Keys.PANEL_IDLE) * 1000).toLong()
    /** The back swipe from the left edge (ADR-040): whether it is on, and how wide the strip it starts in is. */
    val navBackEnabled: Boolean get() = bool(SystemSettings.Keys.NAV_BACK_ENABLED)
    val navBackEdgeDp: Float get() = number(SystemSettings.Keys.NAV_BACK_EDGE_DP).toFloat()
    val navUpEnabled: Boolean get() = bool(SystemSettings.Keys.NAV_UP_ENABLED)
    val navUpEdgeDp: Float get() = number(SystemSettings.Keys.NAV_UP_EDGE_DP).toFloat()
    /** Whether Android's own apps are listed beside the ThinkOS ones, which package names stay out, and how long one may keep the screen. */
    val appsAndroid: Boolean get() = bool(SystemSettings.Keys.APPS_ANDROID)
    val appsHidden: Set<String> get() = string(SystemSettings.Keys.APPS_HIDDEN).split(',', ' ', '\n').filter { it.isNotBlank() }.toSet()
    /** 0 never returns. */
    val appsReturnAfterMillis: Long get() = (number(SystemSettings.Keys.APPS_RETURN_AFTER) * 1000).toLong()
    /** Host names, comma or whitespace separated. */
    val ntpServers: List<String> get() = string(SystemSettings.Keys.NTP_SERVERS).split(',', ' ', '\n').filter { it.isNotBlank() }
    val timeSyncIntervalMillis: Long get() = (number(SystemSettings.Keys.TIME_SYNC_INTERVAL) * 3_600_000).toLong()

    /** Buffering budget and the cleartext rule for the media channel; read whenever a queue is loaded. */
    val mediaPolicy: MediaPolicy
        get() = MediaPolicy(
            bufferMillis = (number(SystemSettings.Keys.MEDIA_BUFFER_S) * 1000).toInt(),
            bufferBytes = (number(SystemSettings.Keys.MEDIA_BUFFER_MB) * 1024 * 1024).toInt(),
            publicCleartext = bool(SystemSettings.Keys.MEDIA_PUBLIC_HTTP),
        )

    /** Receive casts from phones and computers on the local network (ADR-031). Off until a person turns it on. */
    val castEnabled: Boolean get() = bool(SystemSettings.Keys.CAST_ENABLED)
    /** Video from apps on iPhones, iPads and Macs (AirPlay). Off until a person turns it on. */
    val castAirplay: Boolean get() = bool(SystemSettings.Keys.CAST_AIRPLAY)
    /** Whole-screen sharing over AirPlay as well as video apps; only means something while [castAirplay] is on. */
    val castAirplayMirror: Boolean get() = bool(SystemSettings.Keys.CAST_AIRPLAY_MIRROR)
    /** Screen sharing from Android phones and Windows PCs (Miracast); needs the ThinkOS Cast system module. */
    val castMiracast: Boolean get() = bool(SystemSettings.Keys.CAST_MIRACAST)
    /** The name controllers list this device under. */
    val castName: String get() = string(SystemSettings.Keys.CAST_NAME).trim()

    internal val updatePolicy: moe.kiwi.thinkos.update.UpdatePolicy
        get() {
            val channel = string(SystemSettings.Keys.UPDATE_CHANNEL)
            fun millis(key: String, scale: Long = 1000) = (number("update.$key") * scale).toLong()
            return moe.kiwi.thinkos.update.UpdatePolicy(bool(SystemSettings.Keys.UPDATE_ENABLED), channel,
                moe.kiwi.thinkos.update.UpdatePolicy.sources(string(SystemSettings.Keys.UPDATE_MIRROR), string(SystemSettings.Keys.UPDATE_SOURCES), channel),
                millis("initial_min_s"), millis("initial_max_s"), millis("check_hours", 3_600_000), millis("jitter_hours", 3_600_000),
                millis("retry_min_s"), millis("retry_max_s"), millis("manual_grace_s"), number("update.trusted_year").toInt(),
                millis("connect_s"), millis("read_s"))
        }
    val updateAutoInstall: Boolean get() = bool(SystemSettings.Keys.UPDATE_AUTO_INSTALL)
    internal val updateManualMillis: Long get() = (number("update.manual_grace_s") * 1000).toLong()
    val updateInstallHour: Int get() = number("update.install_hour").toInt()
    internal val updateAutomaticPolicy: moe.kiwi.thinkos.update.UpdateAutomaticPolicy
        get() {
            fun millis(key: String) = (number("update.$key") * 1000).toLong()
            return moe.kiwi.thinkos.update.UpdateAutomaticPolicy(updateAutoInstall,
                moe.kiwi.thinkos.update.UpdateSchedule.QuietPolicy(updateInstallHour, number("update.install_hours").toInt(),
                    millis("touch_grace_s"), millis("fallback_wait_s"), millis("fallback_asleep_s"), number("update.space_factor").toInt()), millis("install_poll_s"))
        }
    internal val updateInstallLimits: moe.kiwi.thinkos.update.UpdateInstallStore.Limits
        get() = moe.kiwi.thinkos.update.UpdateInstallStore.Limits(number("update.max_attempts").toInt(),
            (number("update.trial_s") * 1000).toLong(), (number("update.notice_s") * 1000).toLong(), number("update.failure_limit").toInt())

    /** Video playback keeps the display at least awake: a film must not fall into the ambient clock. */
    val keepAwakeVideo: Boolean get() = bool(SystemSettings.Keys.MEDIA_KEEP_AWAKE_VIDEO)
}

/** Timezone options are exactly the zones the bundled IANA database knows; aliases normalise to canonical ids. */
object TimezoneSource : OptionSource {
    override fun normalize(source: String, value: String): String? = when (source) {
        "timezone" -> Zones.canonical(value)
        "region" -> RegionOptions.normalize(value)
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
