package moe.kiwi.thinkos.core

import moe.kiwi.thinkos.settings.SettingsStore

/** One coherent schema-backed snapshot. There are no second copies of product defaults here. */
internal data class ModeSettings(
    val config: ModeController.Config,
    val manualHold: ModeController.ManualHold,
    val dayFallback: ModeController.LocalWindow,
    /** Corrupt/legacy cross-field values use schema defaults without rewriting settings. */
    val fallbackWindows: Set<String>,
) {
    companion object {
        fun read(store: SettingsStore): ModeSettings = parse(store.snapshot(SettingsStore.SYSTEM),
            requireNotNull(store.scope(SettingsStore.SYSTEM)).fields.mapValues { it.value.default })

        internal fun parse(values: Map<String, Any>, defaults: Map<String, Any>): ModeSettings {
            val recovered = linkedSetOf<String>()
            fun text(key: String) = values.getValue(key) as String
            fun lux(key: String) = (values.getValue(key) as Number).toFloat()
            fun millis(key: String) = ((values.getValue(key) as Number).toDouble() * 1_000).toLong()
            fun window(start: String, end: String): ModeController.LocalWindow {
                val a = minute(text(start))
                val b = minute(text(end))
                if (a != b) return ModeController.LocalWindow(a, b)
                recovered += start
                return ModeController.LocalWindow(minute(defaults.getValue(start) as String), minute(defaults.getValue(end) as String))
            }
            return ModeSettings(
                ModeController.Config(
                    trigger = when (text("redshift.trigger")) {
                        "sun" -> ModeController.Trigger.SUN
                        "lux" -> ModeController.Trigger.LUX
                        "fixed" -> ModeController.Trigger.FIXED
                        "off" -> ModeController.Trigger.OFF
                        else -> error("Invalid redshift trigger")
                    },
                    fixedWindow = window("redshift.start", "redshift.end"),
                    polarWindow = window("redshift.polar_start", "redshift.polar_end"),
                    sleepEnabled = values.getValue("sleep.enabled") as Boolean,
                    sleepWindow = window("sleep.start", "sleep.end"),
                    veryDarkEnterLux = lux("theme.night_below_lux"),
                    veryDarkExitLux = lux("theme.night_above_lux"),
                    veryDarkDwellMillis = millis("redshift.dark_dwell_s"),
                    windowEnterLux = lux("theme.dark_below_lux"),
                    windowExitLux = lux("theme.light_above_lux"),
                    windowDwellMillis = millis("redshift.window_dwell_s"),
                    minimumSwitchMillis = millis("mode.minimum_switch_s"),
                    engagedDelayMillis = millis("mode.engaged_delay_s"),
                    sleepDelayMillis = millis("sleep.start_delay_s"),
                ),
                when (text("mode.manual_hold")) {
                    "next" -> ModeController.ManualHold.NEXT_BOUNDARY
                    "forever" -> ModeController.ManualHold.UNTIL_CHANGED
                    else -> error("Invalid manual mode hold")
                },
                window("theme.fallback_start", "theme.fallback_end"),
                recovered,
            )
        }

        internal fun minute(value: String): Int {
            require(value.length == 5 && value[2] == ':' &&
                value.indices.filter { it != 2 }.all { value[it] in '0'..'9' }) { "Expected HH:mm" }
            val hour = value.substring(0, 2).toInt()
            val minute = value.substring(3, 5).toInt()
            require(hour in 0..23 && minute in 0..59) { "Invalid local time" }
            return hour * 60 + minute
        }
    }
}
