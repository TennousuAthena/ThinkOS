package moe.kiwi.thinkos.settings

import moe.kiwi.thinkos.i18n.Labels
import moe.kiwi.thinkos.i18n.Plurals
import moe.kiwi.thinkos.location.LocationProvider
import moe.kiwi.thinkos.tz.ZoneFormat
import moe.kiwi.thinkos.tz.ZoneNames
import moe.kiwi.thinkos.tz.Zones
import java.text.NumberFormat
import java.util.Locale

/**
 * What the settings page shows: every field with its label, current value and a ready-to-display
 * string, all in the user's language. The settings plugin renders this and never needs to know
 * what any individual setting means.
 */
class SettingsDescriber(
    private val settings: SettingsStore,
    private val labels: Labels,
    private val scopeName: (scope: String, locale: Locale) -> String,
) {
    fun list(locale: Locale): List<Map<String, Any?>> =
        settings.scopes().filter { it.hasSettingsPage }
            .sortedBy { if (it.id == SettingsStore.SYSTEM) 0 else 1 }
            .map { mapOf("scope" to it.id, "name" to scopeName(it.id, locale), "count" to (it.visibleFields.size + it.related.size).toLong()) }

    fun describe(scopeId: String, locale: Locale): Map<String, Any?>? {
        val scope = settings.scope(scopeId) ?: return null
        val numbers = NumberFormat.getInstance(locale).apply { maximumFractionDigits = 3 }
        val related = settings.scope(SettingsStore.SYSTEM)?.let { system ->
            scope.related.mapNotNull { key -> system.fields[key]?.takeUnless { it.hidden || it.secret }?.let { system to it } }
        }.orEmpty()
        val declarations = related + scope.visibleFields.map { scope to it }
        val enabled = mutableMapOf<Pair<String, String>, Boolean>()
        fun conditionHolds(owner: SettingsScope, field: SettingField): Boolean = enabled.getOrPut(owner.id to field.key) {
            field.depends?.let { condition ->
                conditionHolds(owner, owner.fields.getValue(condition.key)) && condition.matches(settings.get(owner.id, condition.key))
            } ?: true
        }
        val fields = declarations.filter { (owner, field) ->
            conditionHolds(owner, field)
        }.map { (scope, field) ->
            val scopeId = scope.id
            val resolved = settings.resolved(scopeId, field.key)
            val entry = linkedMapOf<String, Any?>(
                "key" to field.key,
                "scope" to scopeId,
                "type" to field.type.wire,
                "level" to field.level.wire,
                "label" to labels.resolve(scopeId, field.label, locale),
                "value" to if (field.secret) "" else resolved.value,
                "default" to if (field.secret) "" else field.default,
                "overridden" to resolved.overridden,
                "display" to display(scopeId, field, resolved.value, locale, numbers),
            )
            field.help?.let { entry["help"] = labels.resolve(scopeId, it, locale) }
            if (resolved.value == true) field.warn?.let { entry["warn"] = labels.resolve(scopeId, it, locale) }
            field.placeholder?.let { entry["placeholder"] = labels.resolve(scopeId, it, locale) }
            field.group?.let { entry["group"] = labels.resolve(scopeId, it, locale) }
            field.section?.let { entry["section"] = it }
            preview(scopeId, field)?.let { entry["preview"] = it }
            if (field.choices.isNotEmpty()) entry["choices"] = field.choices.map { mapOf("value" to it.value, "label" to labels.resolve(scopeId, it.label, locale)) }
            field.source?.let { entry["source"] = it }
            if (field.source == "place") {
                val location = LocationProvider(settings).get(locale)
                // The editor must keep "follow the device" empty; consumers use the resolved zone.
                val timezone = if (location.source == "timezone") ""
                    else settings.get(SettingsStore.SYSTEM, SystemSettings.Keys.LOCATION_TIMEZONE)
                entry["place"] = location.toMap() + ("timezone" to timezone)
                // Choosing a place writes the mode, name, coordinates and zone together, so resetting it clears all of them.
                val prefix = field.key.removeSuffix("name")
                entry["overridden"] = PLACE_PARTS.any { part -> scope.fields.containsKey(prefix + part) && settings.resolved(scopeId, prefix + part).overridden }
                entry["display"] = location.name
                entry["searchUrl"] = settings.get(SettingsStore.SYSTEM, "location.search_url")
            }
            if (field.allowEmpty) entry["allowEmpty"] = true
            field.min?.let { entry["min"] = SettingField.canonical(it) }
            field.max?.let { entry["max"] = SettingField.canonical(it) }
            field.step?.let { entry["step"] = SettingField.canonical(it) }
            field.unit?.let { entry["unit"] = it }
            field.format?.let { entry["format"] = it }
            if (field.presets.isNotEmpty()) entry["presets"] = field.presets.map { value ->
                mapOf("value" to value, "label" to display(scopeId, field, value, locale, numbers))
            }
            if (field.type == FieldType.TEXT) entry["maxLength"] = field.maxLength.toLong()
            if (field.secret) { entry["secret"] = true; entry["hasValue"] = (resolved.value as String).isNotEmpty() }
            entry
        }
        return mapOf("scope" to scopeId, "name" to scopeName(scopeId, locale), "fields" to fields,
            "sections" to settings.scope(SettingsStore.SYSTEM)?.sections.orEmpty().map {
                linkedMapOf("id" to it.id, "label" to labels.resolve(SettingsStore.SYSTEM, it.label, locale)).apply {
                    it.icon?.let { icon -> put("icon", icon) }
                }
            })
    }

    private fun display(scope: String, field: SettingField, value: Any, locale: Locale, numbers: NumberFormat): String = when (field.type) {
        FieldType.BOOL -> ""
        FieldType.NUMBER -> {
            if (field.format == "duration") duration(scope, field, value as Number, locale, numbers)
            else numberDisplay(value as Number, field.unit, numbers)
        }
        FieldType.TEXT -> if (field.secret) (if ((value as String).isEmpty()) "" else "••••") else value as String
        FieldType.TIME -> value as String
        FieldType.CHOICE -> {
            val text = value as String
            when {
                text.isEmpty() && field.source == "region" -> labels.resolve(SettingsStore.SYSTEM, "@form.region.all", locale)
                text.isEmpty() -> ""
                field.source == "region" -> ZoneNames.country(text, locale)
                field.source == "timezone" -> zoneLabel(text, locale)
                else -> field.choices.firstOrNull { it.value == text }?.let { labels.resolve(scope, it.label, locale) } ?: text
            }
        }
    }

    companion object {
        private val PLACE_PARTS = listOf("mode", "name", "latitude", "longitude", "elevation", "timezone")

        /** Computed from the registered OS field; a plugin declaration cannot enable a preview. */
        fun preview(scope: String, field: SettingField): String? =
            if (scope == SettingsStore.SYSTEM && field.key == SystemSettings.Keys.BRIGHTNESS_MAX &&
                field.type == FieldType.NUMBER && field.presets.isEmpty()) "brightness" else null

        /** Shared by the descriptor and its in-progress native numeric control. */
        fun numberDisplay(value: Number, unit: String?, locale: Locale): String =
            numberDisplay(value, unit, NumberFormat.getInstance(locale).apply { maximumFractionDigits = 3 })

        private fun numberDisplay(value: Number, unit: String?, numbers: NumberFormat): String =
            numbers.format(value) + (unit?.takeIf(String::isNotEmpty)?.let { " $it" } ?: "")
    }

    private fun duration(scope: String, field: SettingField, value: Number, locale: Locale, numbers: NumberFormat): String {
        val seconds = value.toDouble()
        if (seconds == 0.0) field.zero?.let { return labels.resolve(scope, it, locale) }
        val (unit, count) = when {
            seconds != 0.0 && seconds % 3600.0 == 0.0 -> "hour" to seconds / 3600
            seconds != 0.0 && seconds % 60.0 == 0.0 -> "minute" to seconds / 60
            else -> "second" to seconds
        }
        val template = labels.resolve(SettingsStore.SYSTEM, "@setting.duration.$unit.${Plurals.category(locale, count)}", locale)
        // Shared tables use {1}; aapt converts the same placeholder to %1$s in resources.
        return template.replace("{1}", numbers.format(count)).replace("%1\$s", numbers.format(count))
    }

    private fun zoneLabel(id: String, locale: Locale): String {
        val offset = Zones.clock(id).typeAt(System.currentTimeMillis()).offset
        return ZoneNames.city(id, locale) + "  " + ZoneFormat.offsetLabel(offset)
    }
}
