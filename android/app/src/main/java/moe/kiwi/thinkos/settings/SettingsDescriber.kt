package moe.kiwi.thinkos.settings

import moe.kiwi.thinkos.i18n.Labels
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
        settings.scopes().filter { it.visibleFields.isNotEmpty() }
            .sortedBy { if (it.id == SettingsStore.SYSTEM) 0 else 1 }
            .map { mapOf("scope" to it.id, "name" to scopeName(it.id, locale), "count" to it.visibleFields.size.toLong()) }

    fun describe(scopeId: String, locale: Locale): Map<String, Any?>? {
        val scope = settings.scope(scopeId) ?: return null
        val numbers = NumberFormat.getInstance(locale).apply { maximumFractionDigits = 3 }
        val fields = scope.visibleFields.map { field ->
            val resolved = settings.resolved(scopeId, field.key)
            val entry = linkedMapOf<String, Any?>(
                "key" to field.key,
                "type" to field.type.wire,
                "label" to labels.resolve(scopeId, field.label, locale),
                "value" to if (field.secret) "" else resolved.value,
                "default" to if (field.secret) "" else field.default,
                "overridden" to resolved.overridden,
                "display" to display(scopeId, field, resolved.value, locale, numbers),
            )
            field.help?.let { entry["help"] = labels.resolve(scopeId, it, locale) }
            field.group?.let { entry["group"] = labels.resolve(scopeId, it, locale) }
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
            if (field.type == FieldType.TEXT) entry["maxLength"] = field.maxLength.toLong()
            if (field.secret) { entry["secret"] = true; entry["hasValue"] = (resolved.value as String).isNotEmpty() }
            entry
        }
        return mapOf("scope" to scopeId, "name" to scopeName(scopeId, locale), "fields" to fields)
    }

    private fun display(scope: String, field: SettingField, value: Any, locale: Locale, numbers: NumberFormat): String = when (field.type) {
        FieldType.BOOL -> ""
        FieldType.NUMBER -> {
            numbers.format(value as Number) + (field.unit?.let { " $it" } ?: "")
        }
        FieldType.TEXT -> if (field.secret) (if ((value as String).isEmpty()) "" else "••••") else value as String
        FieldType.CHOICE -> {
            val text = value as String
            when {
                text.isEmpty() -> ""
                field.source == "timezone" -> zoneLabel(text, locale)
                else -> field.choices.firstOrNull { it.value == text }?.let { labels.resolve(scope, it.label, locale) } ?: text
            }
        }
    }

    private companion object { val PLACE_PARTS = listOf("mode", "name", "latitude", "longitude", "elevation", "timezone") }

    private fun zoneLabel(id: String, locale: Locale): String {
        val offset = Zones.clock(id).typeAt(System.currentTimeMillis()).offset
        return ZoneNames.city(id, locale) + "  " + ZoneFormat.offsetLabel(offset)
    }
}
