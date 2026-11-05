package moe.kiwi.thinkos.settings

import moe.kiwi.thinkos.plugin.PluginSchema

/** Install-time checks shared by manifest validation and the live settings store. */
object SettingDeclarations {
    fun validate(fields: Map<String, SettingField>) {
        for (field in fields.values) {
            val depends = field.depends ?: continue
            val target = fields[depends.key] ?: throw IllegalArgumentException("settings.${field.key}: unknown dependency ${depends.key}")
            for (value in depends.values) target.validate(value)
        }
        val complete = mutableSetOf<String>()
        for (key in fields.keys) {
            val path = mutableSetOf<String>()
            var next: String? = key
            while (next != null && next !in complete) {
                require(path.add(next)) { "settings.$key: dependency cycle" }
                next = fields.getValue(next).depends?.key
            }
            complete.addAll(path)
        }
    }

    fun related(keys: List<String>, allowed: Set<String>) {
        require(keys.size <= 32 && keys.toSet().size == keys.size) { "related must list at most 32 unique system settings" }
        for (key in keys) require(key in allowed) { "related.$key: setting must exist and be visible and non-secret" }
    }
}

data class SettingSection(val id: String, val label: String, val icon: String? = null) {
    init {
        require(id.matches(Regex("[a-z][a-z0-9_-]{0,63}"))) { "invalid settings section id" }
        require(label.isNotBlank() && label.length <= 80) { "settings section label needs 1-80 characters" }
        require(icon == null || icon in PluginSchema.formIconNames()) { "settings section icon must be a registered vector glyph" }
    }
}
