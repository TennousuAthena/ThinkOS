// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_kotlin.py.
package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject

data class SchemaPluginCatalog(
    val `plugins`: List<SchemaPluginIndex>
)

data class SchemaPluginIndex(
    val `id`: String,
    val `version`: String,
    val `digest`: String,
    val `files`: List<SchemaPluginFile>
)

data class SchemaPluginFile(
    val `path`: String,
    val `sha256`: String,
    val `size`: Int
)

data class SchemaText(
    val `type`: String,
    val `value`: String,
    val `style`: String? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaIcon(
    val `type`: String,
    val `name`: String,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaClock(
    val `type`: String,
    val `style`: String? = null,
    val `format`: String? = null,
    val `sun`: String? = null,
    val `zones`: List<SchemaClockZone>? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaClockZone(
    val `label`: String? = null,
    val `timezone`: String
)

data class SchemaWidgetReference(
    val `widget`: String
)

data class SchemaStacksView(
    val `variants`: List<SchemaVariant>? = null,
    val `mood`: String? = null,
    val `layout`: String,
    val `slots`: JSONObject
)

data class SchemaVariant(
    val `id`: String,
    val `title`: String
)

data class SchemaFocusView(
    val `variants`: List<SchemaVariant>? = null,
    val `mood`: String? = null,
    val `layout`: String,
    val `slots`: JSONObject
)

data class SchemaGlanceView(
    val `template`: String,
    val `children`: List<Any>,
    val `relevance`: Any? = null
)

data class SchemaForm(
    val `type`: String,
    val `path`: String,
    val `title`: String? = null,
    val `done`: String? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null,
    val `nav`: String? = null,
    val `subtitle`: String? = null,
    val `primary`: String? = null,
    val `secondary`: String? = null,
    val `back`: String? = null
)

data class SchemaFormView(
    val `layout`: String,
    val `slots`: JSONObject
)

data class SchemaProgress(
    val `type`: String,
    val `style`: String? = null,
    val `value`: Any,
    val `max`: Any? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaChart(
    val `type`: String,
    val `style`: String? = null,
    val `values`: Any,
    val `min`: Any? = null,
    val `max`: Any? = null,
    val `highlight`: Any? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaListRow(
    val `icon`: String? = null,
    val `title`: String,
    val `subtitle`: String? = null,
    val `trailing`: String? = null,
    val `progress`: Any? = null,
    val `max`: Any? = null
)

data class SchemaList(
    val `type`: String,
    val `items`: String,
    val `item`: SchemaListRow,
    val `tap`: String? = null,
    val `weight`: Double? = null,
    val `current`: Any? = null
)

data class SchemaDaylight(
    val `type`: String,
    val `path`: String,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaMoon(
    val `type`: String,
    val `path`: String,
    val `hemisphere`: String? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaComponentGroup(
    val `children`: List<Any>
)

data class SchemaSplitView(
    val `layout`: String,
    val `variants`: List<SchemaVariant>? = null,
    val `mood`: String? = null,
    val `slots`: JSONObject
)

data class SchemaStageView(
    val `layout`: String,
    val `variants`: List<SchemaVariant>? = null,
    val `mood`: String? = null,
    val `slots`: JSONObject
)

data class SchemaControls(
    val `type`: String,
    val `tap`: String? = null,
    val `weight`: Double? = null,
    val `mode`: String? = null
)

data class SchemaVideo(
    val `type`: String,
    val `tap`: String? = null,
    val `weight`: Double? = null,
    val `fit`: String? = null
)

data class SchemaSeekbar(
    val `type`: String,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaImage(
    val `type`: String,
    val `src`: String,
    val `fit`: String? = null,
    val `pan`: Any? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null,
    val `interval`: Any? = null,
    val `pan_speed`: Any? = null,
    val `pan_seconds`: Any? = null,
    val `pan_direction`: String? = null
)

data class SchemaCarousel(
    val `type`: String,
    val `items`: String,
    val `interval`: Any,
    val `duration`: Any,
    val `transition`: String? = null,
    val `fit`: String? = null,
    val `pan`: Any? = null,
    val `shuffle`: Any? = null,
    val `weight`: Double? = null,
    val `pan_speed`: Any? = null,
    val `caption`: String? = null,
    val `caption_hold`: Any? = null,
    val `pan_seconds`: Any? = null,
    val `pan_direction`: String? = null
)

data class SchemaTileReference(
    val `widget`: String,
    val `size`: String,
    val `color`: String? = null
)

data class SchemaTileFace(
    val `icon`: String? = null,
    val `value`: String? = null,
    val `label`: String,
    val `detail`: String? = null,
    val `content`: Any? = null
)

data class SchemaTileView(
    val `template`: String,
    val `color`: String? = null,
    val `faces`: List<SchemaTileFace>,
    val `badge`: String? = null,
    val `content`: Any? = null,
    val `image`: SchemaImage? = null
)

data class SchemaTilesView(
    val `layout`: String,
    val `slots`: SchemaTilesSlots,
    val `variants`: List<SchemaVariant>? = null,
    val `mood`: String? = null
)

data class SchemaTilesSlots(
    val `tiles`: List<SchemaTileReference>
)

data class SchemaAero(
    val `layout`: String,
    val `slots`: SchemaAeroSlots
)

data class SchemaAeroSlots(
    val `windows`: List<SchemaWidgetReference>
)

data class SchemaNowPlaying(
    val `type`: String,
    val `style`: String? = null,
    val `idle`: String? = null,
    val `notice`: String? = null,
    val `weight`: Double? = null
)

data class SchemaTv(
    val `type`: String,
    val `channel`: String? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null,
    val `fit`: String? = null
)

data class SchemaTabs(
    val `type`: String,
    val `items`: String,
    val `current`: Any? = null,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaSlider(
    val `type`: String,
    val `level`: String,
    val `weight`: Double? = null
)

data class SchemaToggle(
    val `type`: String,
    val `icon`: String,
    val `label`: String,
    val `on`: Any? = null,
    val `tap`: String,
    val `weight`: Double? = null
)

data class SchemaGridCell(
    val `icon`: String? = null,
    val `image`: String? = null,
    val `title`: String
)

data class SchemaGrid(
    val `type`: String,
    val `items`: String,
    val `item`: SchemaGridCell,
    val `tap`: String? = null,
    val `weight`: Double? = null
)

data class SchemaPanelView(
    val `layout`: String,
    val `slots`: JSONObject
)

data class SchemaFormLink(
    val `key`: String,
    val `type`: String,
    val `label`: String,
    val `display`: String? = null,
    val `icon`: String? = null,
    val `group`: String? = null,
    val `confirm`: String? = null
)

data class SchemaFormNav(
    val `items`: List<SchemaFormNavItem>,
    val `current`: String? = null
)

data class SchemaFormNavItem(
    val `id`: String,
    val `label`: String,
    val `icon`: String? = null
)

data class SchemaManifest(
    val `id`: String,
    val `name`: String,
    val `version`: String,
    val `api`: Int,
    val `icon`: String? = null,
    val `main`: String? = null,
    val `permissions`: List<String>? = null,
    val `settings`: JSONObject? = null,
    val `pages`: List<SchemaPage>? = null,
    val `widgets`: List<SchemaWidget>? = null,
    val `commands`: List<SchemaCommand>? = null,
    val `exports`: JSONObject? = null,
    val `uses`: JSONObject? = null,
    val `calls`: List<String>? = null,
    val `apps`: List<SchemaApp>? = null,
    val `related`: List<String>? = null
)

data class SchemaPage(
    val `id`: String,
    val `view`: String,
    val `title`: String,
    val `roles`: List<String>? = null,
    val `priority`: Int? = null,
    val `live`: Boolean? = null,
    val `variants`: List<SchemaVariant>? = null
)

data class SchemaWidget(
    val `id`: String,
    val `view`: String,
    val `opens`: String? = null,
    val `alternates`: SchemaWidgetAlternates? = null
)

data class SchemaWidgetAlternates(
    val `glance`: String? = null,
    val `tile`: String? = null
)

data class SchemaSetting(
    val `type`: String,
    val `label`: String,
    val `default`: Any,
    val `help`: String? = null,
    val `group`: String? = null,
    val `choices`: List<SchemaSettingChoice>? = null,
    val `source`: String? = null,
    val `allowEmpty`: Boolean? = null,
    val `min`: Double? = null,
    val `max`: Double? = null,
    val `step`: Double? = null,
    val `unit`: String? = null,
    val `maxLength`: Int? = null,
    val `secret`: Boolean? = null,
    val `hidden`: Boolean? = null,
    val `warn`: String? = null,
    val `placeholder`: String? = null,
    val `level`: String? = null,
    val `depends`: Any? = null,
    val `format`: String? = null,
    val `zero`: String? = null,
    val `presets`: List<Double>? = null
)

data class SchemaSettingChoice(
    val `value`: String,
    val `label`: String
)

data class SchemaCommand(
    val `id`: String,
    val `phrases`: List<String>? = null,
    val `gesture`: String? = null,
    val `public`: Boolean? = null,
    val `cooldown`: Double? = null
)

data class SchemaExport(
    val `path`: String,
    val `shape`: String,
    val `requires`: List<String>? = null
)

data class SchemaUse(
    val `export`: String,
    val `shape`: String
)

data class SchemaApp(
    val `id`: String,
    val `page`: String,
    val `title`: String,
    val `icon`: String? = null,
    val `listed`: Boolean? = null
)

object LayoutVocabulary {
    val slots: Map<String, List<String>> = mapOf(
        "stacks" to listOf("left", "right"),
        "focus" to listOf("over", "hero", "under"),
        "form" to listOf("body", "hero"),
        "split" to listOf("lead", "main"),
        "stage" to listOf("stage", "over", "under"),
        "tiles" to listOf("tiles"),
        "aero" to listOf("windows"),
        "panel" to listOf("header", "quick", "levels", "media", "footer", "update")
    )
    val roles: Map<String, Map<String, String>> = mapOf(
        "stacks" to mapOf("left" to "stack", "right" to "stack"),
        "focus" to mapOf("over" to "header", "hero" to "content", "under" to "footer"),
        "form" to mapOf("body" to "content", "hero" to "hero"),
        "split" to mapOf("lead" to "lead", "main" to "main"),
        "stage" to mapOf("stage" to "stage", "over" to "over", "under" to "under"),
        "tiles" to mapOf("tiles" to "tiles"),
        "aero" to mapOf("windows" to "aero"),
        "panel" to mapOf("header" to "header", "quick" to "row", "levels" to "levels", "media" to "media", "footer" to "footer", "update" to "notice")
    )
}

object PluginSchema {
    const val API = 10
    private val renderApis by lazy { JSONObject("{\"current\":10,\"componentTypes\":{\"text\":1,\"icon\":1,\"clock\":1,\"form\":1,\"progress\":2,\"chart\":2,\"list\":2,\"daylight\":2,\"moon\":2,\"image\":3,\"carousel\":3,\"controls\":7,\"video\":7,\"seekbar\":7,\"nowplaying\":8,\"tv\":8,\"tabs\":8,\"slider\":8,\"toggle\":8,\"grid\":8},\"layouts\":{\"stacks\":1,\"focus\":1,\"form\":1,\"split\":2,\"stage\":2,\"tiles\":4,\"aero\":5,\"panel\":8},\"componentProperties\":{\"tap\":2,\"weight\":2,\"current\":8,\"nav\":9,\"subtitle\":10,\"primary\":10,\"secondary\":10,\"back\":10},\"componentGroups\":2,\"templates\":{\"glance\":1,\"tile\":4},\"slotWidgets\":6,\"manifestFields\":{\"exports\":6,\"uses\":6,\"calls\":6,\"apps\":8,\"related\":9},\"widgetFields\":{\"alternates\":6},\"commandFields\":{\"public\":6,\"cooldown\":6},\"tileFields\":{\"image\":6},\"tileFaceFields\":{\"content\":6},\"settingFields\":{\"warn\":9,\"placeholder\":9,\"level\":9,\"depends\":9,\"format\":9,\"zero\":9,\"presets\":9},\"settingSources\":{\"region\":10},\"settingTypes\":{\"time\":10},\"formFields\":{\"link\":1,\"confirm\":9,\"preview\":9,\"nav\":9,\"link.icon\":10,\"nav.items.icon\":10,\"time\":10},\"runtimeEvents\":{\"cast\":9,\"form.layout\":9,\"form.navigate\":9,\"apps\":9,\"update\":10,\"practice\":10,\"plugins\":10,\"mode\":10},\"mediaEntryFields\":{\"public_only\":10},\"mediaProbeOptions\":{\"public_only\":10},\"runtimeMethods\":{\"net.resolve\":10,\"device.mode\":10,\"device.setMode\":10,\"settings.about\":9,\"apps.list\":9,\"apps.open\":9,\"update.status\":10,\"update.open\":10,\"update.check\":10,\"update.download\":10,\"update.install\":10,\"onboarding.open\":10,\"onboarding.status\":10,\"onboarding.practice\":10,\"onboarding.stop\":10,\"device.canSetLocale\":10,\"device.setLocale\":10,\"device.canSetTimeZone\":10,\"device.setTimeZone\":10,\"tz.zones\":10,\"plugins.catalog\":10,\"plugins.installed\":10,\"plugins.open\":10,\"plugins.install\":10,\"plugins.update\":10,\"plugins.uninstall\":10,\"plugins.setAuto\":10},\"pageRoles\":{\"launcher\":9,\"onboarding\":10},\"permissions\":{\"system.update\":10,\"system.plugins\":10},\"layoutSlots\":{\"panel.update\":10,\"form.hero\":10}}") }
    // Generated from system.json, so install validation also works before plugins are started.
    private val relatedKeys = setOf("theme.mode", "redshift.trigger", "redshift.start", "redshift.end", "sleep.enabled", "sleep.start", "sleep.end", "sleep.wake", "mode.manual_hold", "display.brightness_max", "display.orientation", "attention.awake_to_ambient_s", "attention.to_asleep_s", "motion.clock", "location.name", "units.system", "units.wind", "tiles.live", "tiles.interval", "tiles.background", "panel.enabled", "media.keep_awake_video", "cast.enabled", "cast.airplay", "cast.airplay_mirror", "cast.miracast", "cast.name", "kiosk.lock", "media.public_http", "apps.return_after_s", "attention.engaged_idle_s", "attention.ambient_page_after_s", "apps.android", "update.enabled", "update.auto_install", "update.channel", "update.mirror", "plugins.auto_update", "plugins.community", "plugins.mirror")
    private val documents by lazy { mapOf(
        "catalog.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"catalog.json\",\"title\":\"SchemaPluginCatalog\",\"description\":\"APK assets/plugins/index.json. Each entry names assets/plugins/<id>/ and includes its complete index.\",\"type\":\"object\",\"required\":[\"plugins\"],\"additionalProperties\":false,\"properties\":{\"plugins\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"\$ref\":\"index.json\"}}}}"),
        "index.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"index.json\",\"title\":\"SchemaPluginIndex\",\"description\":\"Directory installation index (ADR-018). Digest hashes UTF-8 path + NUL + lowercase file SHA-256 + LF in UTF-8 path order; excludes index.json itself. Semantic path/hash/count/total checks also apply.\",\"type\":\"object\",\"required\":[\"id\",\"version\",\"digest\",\"files\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"\$ref\":\"manifest.json#/properties/id\"},\"version\":{\"\$ref\":\"manifest.json#/properties/version\"},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"files\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":128,\"items\":{\"\$ref\":\"#/\$defs/file\"}}},\"\$defs\":{\"file\":{\"title\":\"SchemaPluginFile\",\"type\":\"object\",\"required\":[\"path\",\"sha256\",\"size\"],\"additionalProperties\":false,\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512,\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.{1,2}(?:/|\$))[^/\\\\\\\\\\\\x00-\\\\x1f\\\\x7f]+(?:/[^/\\\\\\\\\\\\x00-\\\\x1f\\\\x7f]+)*\$\"},\"sha256\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"size\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1048576}}}}}"),
        "layouts.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"layouts.json\",\"description\":\"Fixed OS render vocabulary. API 1: stacks/focus/form/glance and text/icon/clock. API 2: progress/chart/list/daylight/moon, groups, split/stage, tap and weight. No plugin styles or executable view expressions. API 3: local/asset/HTTP(S) image and carousel with lossless framing and native panning. API 4: tiles grid and tile template; only tile backgrounds allow plugin colors (ADR-025). API 5: fixed Aero desktop with up to three referenced widget windows; all chrome belongs to OS. API 6: widget references inside focus/split/stage slots. API 6: full-bleed tile image. API 6: a tile face may bring its own content component. API 8: the OS music player view (cover, queue, volume, bar and card styles), tabs, and a marked row in lists.\",\"\$defs\":{\"component\":{\"oneOf\":[{\"\$ref\":\"#/\$defs/text\"},{\"\$ref\":\"#/\$defs/icon\"},{\"\$ref\":\"#/\$defs/clock\"},{\"\$ref\":\"#/\$defs/progress\"},{\"\$ref\":\"#/\$defs/chart\"},{\"\$ref\":\"#/\$defs/list\"},{\"\$ref\":\"#/\$defs/daylight\"},{\"\$ref\":\"#/\$defs/moon\"},{\"\$ref\":\"#/\$defs/image\"},{\"\$ref\":\"#/\$defs/carousel\"},{\"\$ref\":\"#/\$defs/controls\"},{\"\$ref\":\"#/\$defs/video\"},{\"\$ref\":\"#/\$defs/seekbar\"},{\"\$ref\":\"#/\$defs/nowplaying\"},{\"\$ref\":\"#/\$defs/tv\"},{\"\$ref\":\"#/\$defs/tabs\"},{\"\$ref\":\"#/\$defs/slider\"},{\"\$ref\":\"#/\$defs/toggle\"},{\"\$ref\":\"#/\$defs/grid\"}]},\"text\":{\"title\":\"SchemaText\",\"type\":\"object\",\"required\":[\"type\",\"value\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"text\"},\"value\":{\"type\":\"string\",\"maxLength\":2048},\"style\":{\"enum\":[\"display\",\"title\",\"body\",\"caption\"]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"icon\":{\"title\":\"SchemaIcon\",\"type\":\"object\",\"required\":[\"type\",\"name\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"icon\"},\"name\":{\"type\":\"string\",\"maxLength\":2048},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"clock\":{\"title\":\"SchemaClock\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"clock\"},\"style\":{\"type\":\"string\",\"pattern\":\"^(digital|analog|solar|world|float|\\\\{\\\\{variant\\\\}\\\\})\$\"},\"format\":{\"type\":\"string\",\"maxLength\":80},\"sun\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64,\"description\":\"State path to tos.astro.sun() output for the solar dial. Derived data only; no coordinates.\"},\"zones\":{\"type\":\"array\",\"maxItems\":4,\"minItems\":1,\"items\":{\"\$ref\":\"#/\$defs/zone\"}},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"zone\":{\"title\":\"SchemaClockZone\",\"type\":\"object\",\"required\":[\"timezone\"],\"additionalProperties\":false,\"properties\":{\"label\":{\"type\":\"string\",\"maxLength\":40,\"description\":\"Optional. When absent the city name comes from the OS, localised.\"},\"timezone\":{\"type\":\"string\",\"maxLength\":80}}},\"widgetReference\":{\"title\":\"SchemaWidgetReference\",\"type\":\"object\",\"required\":[\"widget\"],\"additionalProperties\":false,\"properties\":{\"widget\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\\\\.[a-z][a-z0-9-]*\$\",\"maxLength\":130}}},\"stacks\":{\"title\":\"SchemaStacksView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100},\"layout\":{\"const\":\"stacks\"},\"slots\":{\"type\":\"object\",\"required\":[\"left\",\"right\"],\"additionalProperties\":false,\"properties\":{\"left\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":16,\"items\":{\"\$ref\":\"#/\$defs/widgetReference\"}},\"right\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":16,\"items\":{\"\$ref\":\"#/\$defs/widgetReference\"}}}}},\"x-thinkos-slot-roles\":{\"left\":\"stack\",\"right\":\"stack\"}},\"focus\":{\"title\":\"SchemaFocusView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100},\"layout\":{\"const\":\"focus\"},\"slots\":{\"type\":\"object\",\"required\":[\"hero\"],\"additionalProperties\":false,\"properties\":{\"hero\":{\"\$ref\":\"#/\$defs/slotContent\"},\"under\":{\"\$ref\":\"#/\$defs/slotContent\"},\"over\":{\"\$ref\":\"#/\$defs/slotContent\"}}}},\"x-thinkos-slot-roles\":{\"over\":\"header\",\"hero\":\"content\",\"under\":\"footer\"}},\"glance\":{\"title\":\"SchemaGlanceView\",\"type\":\"object\",\"required\":[\"template\",\"children\"],\"additionalProperties\":false,\"properties\":{\"template\":{\"const\":\"glance\"},\"children\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,\"items\":{\"\$ref\":\"#/\$defs/component\"}},\"relevance\":{\"type\":[\"string\",\"number\"]}}},\"formComponent\":{\"title\":\"SchemaForm\",\"type\":\"object\",\"required\":[\"type\",\"path\"],\"additionalProperties\":false,\"description\":\"Native settings form. 'path' names the state array of field descriptors; the OS draws the controls.\",\"properties\":{\"type\":{\"const\":\"form\"},\"path\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":2048},\"done\":{\"type\":\"string\",\"maxLength\":2048},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"nav\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64,\"description\":\"State path to a formNav descriptor. Optional; the OS shows a sidebar at widths of at least 700dp.\"},\"subtitle\":{\"type\":\"string\",\"maxLength\":2048},\"primary\":{\"type\":\"string\",\"maxLength\":2048},\"secondary\":{\"type\":\"string\",\"maxLength\":2048},\"back\":{\"type\":\"string\",\"maxLength\":2048}}},\"formView\":{\"title\":\"SchemaFormView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"form\"},\"slots\":{\"type\":\"object\",\"required\":[\"body\"],\"additionalProperties\":false,\"properties\":{\"body\":{\"\$ref\":\"#/\$defs/formComponent\"},\"hero\":{\"\$ref\":\"#/\$defs/slotContent\"}}}},\"x-thinkos-slot-roles\":{\"body\":\"content\",\"hero\":\"hero\"}},\"progress\":{\"title\":\"SchemaProgress\",\"type\":\"object\",\"required\":[\"type\",\"value\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"progress\"},\"style\":{\"enum\":[\"bar\",\"ring\"]},\"value\":{\"type\":[\"string\",\"number\"]},\"max\":{\"type\":[\"string\",\"number\"]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"chart\":{\"title\":\"SchemaChart\",\"type\":\"object\",\"required\":[\"type\",\"values\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"chart\"},\"style\":{\"enum\":[\"line\",\"bar\",\"area\"]},\"values\":{\"oneOf\":[{\"type\":\"string\",\"maxLength\":160},{\"type\":\"array\",\"maxItems\":168,\"items\":{\"type\":[\"number\",\"null\"]}}]},\"min\":{\"type\":[\"string\",\"number\"]},\"max\":{\"type\":[\"string\",\"number\"]},\"highlight\":{\"type\":[\"string\",\"number\"]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"listRow\":{\"title\":\"SchemaListRow\",\"type\":\"object\",\"required\":[\"title\"],\"additionalProperties\":false,\"properties\":{\"icon\":{\"type\":\"string\",\"maxLength\":512},\"title\":{\"type\":\"string\",\"maxLength\":512},\"subtitle\":{\"type\":\"string\",\"maxLength\":512},\"trailing\":{\"type\":\"string\",\"maxLength\":512},\"progress\":{\"type\":[\"string\",\"number\"]},\"max\":{\"type\":[\"string\",\"number\"]}}},\"list\":{\"title\":\"SchemaList\",\"type\":\"object\",\"required\":[\"type\",\"items\",\"item\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"list\"},\"items\":{\"type\":\"string\",\"maxLength\":160},\"item\":{\"\$ref\":\"#/\$defs/listRow\"},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"current\":{\"type\":[\"string\",\"number\"]}},\"description\":\"'current' is the 1-based row to mark as the one in use (the track being played); the list scrolls it into view when it changes.\"},\"daylight\":{\"title\":\"SchemaDaylight\",\"type\":\"object\",\"required\":[\"type\",\"path\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"daylight\"},\"path\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"moon\":{\"title\":\"SchemaMoon\",\"type\":\"object\",\"required\":[\"type\",\"path\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"moon\"},\"path\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64},\"hemisphere\":{\"type\":\"string\",\"pattern\":\"^(north|south|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":80},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"componentGroup\":{\"title\":\"SchemaComponentGroup\",\"type\":\"object\",\"required\":[\"children\"],\"additionalProperties\":false,\"properties\":{\"children\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,\"items\":{\"\$ref\":\"#/\$defs/component\"}}}},\"slotContent\":{\"oneOf\":[{\"\$ref\":\"#/\$defs/component\"},{\"\$ref\":\"#/\$defs/componentGroup\"},{\"\$ref\":\"#/\$defs/widgetReference\"}]},\"split\":{\"title\":\"SchemaSplitView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"split\"},\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100},\"slots\":{\"type\":\"object\",\"required\":[\"lead\",\"main\"],\"additionalProperties\":false,\"properties\":{\"lead\":{\"\$ref\":\"#/\$defs/slotContent\"},\"main\":{\"\$ref\":\"#/\$defs/slotContent\"}}}},\"x-thinkos-slot-roles\":{\"lead\":\"lead\",\"main\":\"main\"}},\"stage\":{\"title\":\"SchemaStageView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"stage\"},\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100},\"slots\":{\"type\":\"object\",\"required\":[\"stage\"],\"additionalProperties\":false,\"properties\":{\"stage\":{\"\$ref\":\"#/\$defs/slotContent\"},\"over\":{\"\$ref\":\"#/\$defs/slotContent\"},\"under\":{\"\$ref\":\"#/\$defs/slotContent\"}}}},\"x-thinkos-slot-roles\":{\"stage\":\"stage\",\"over\":\"over\",\"under\":\"under\"}},\"controls\":{\"title\":\"SchemaControls\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"controls\"},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"mode\":{\"enum\":[\"queue\",\"seek\",\"play\"]}},\"description\":\"Transport buttons wired to the OS media session. 'queue' = previous / play-pause / next (default), 'seek' = back 10 s / play-pause / forward 10 s, 'play' = play-pause only. The OS draws them and hides them while nobody is touching the screen on a video page.\"},\"video\":{\"title\":\"SchemaVideo\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"video\"},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"fit\":{\"enum\":[\"contain\",\"cover\"]}},\"description\":\"The OS video surface with its cinema overlay: subtitles, buffering and error cards, and (when touched) title, seek bar, transport and audio/subtitle track buttons. The plugin only supplies the media.\"},\"seekbar\":{\"title\":\"SchemaSeekbar\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"seekbar\"},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}},\"description\":\"Draggable position bar with elapsed and remaining time, bound to the OS media session. Live streams show LIVE and a back-to-live action instead of the remaining time.\"},\"image\":{\"title\":\"SchemaImage\",\"type\":\"object\",\"required\":[\"type\",\"src\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"image\"},\"src\":{\"type\":\"string\",\"maxLength\":2048},\"fit\":{\"type\":\"string\",\"pattern\":\"^(contain|pan|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160},\"pan\":{\"oneOf\":[{\"type\":\"boolean\"},{\"type\":\"string\",\"pattern\":\"^\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\}\$\",\"maxLength\":160}]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"interval\":{\"type\":[\"number\",\"string\"],\"minimum\":0.1,\"maximum\":86400,\"maxLength\":160},\"pan_speed\":{\"type\":[\"number\",\"string\"],\"minimum\":1,\"maximum\":120,\"maxLength\":160,\"description\":\"Maximum pan speed in dp/s; dwell is extended until the entire image has been shown.\"},\"pan_seconds\":{\"type\":[\"number\",\"string\"],\"minimum\":0.1,\"maximum\":120,\"maxLength\":160,\"description\":\"Minimum duration of a continuous eased sweep in seconds (default 12). Longer sweeps respect pan_speed; interval may include a stationary hold.\"},\"pan_direction\":{\"type\":\"string\",\"pattern\":\"^(random|forward|reverse|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160,\"description\":\"Random direction per photo by default. Forward follows reading order; reverse swaps both edges.\"}},\"description\":\"API 3. Local library files, plugin asset:assets/... or HTTP(S) with network permission. OS owns bounded downloads/cache and decoding. pan sweeps the entire image; contain displays the whole image at once. Continuous native panning, independently of scene tick cadence.\"},\"carousel\":{\"title\":\"SchemaCarousel\",\"type\":\"object\",\"required\":[\"type\",\"items\",\"interval\",\"duration\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"carousel\"},\"items\":{\"type\":\"string\",\"maxLength\":160},\"interval\":{\"type\":[\"number\",\"string\"],\"minimum\":0.1,\"maximum\":86400,\"maxLength\":160},\"duration\":{\"type\":[\"number\",\"string\"],\"minimum\":0,\"maximum\":10000,\"maxLength\":160},\"transition\":{\"type\":\"string\",\"pattern\":\"^(fade|slide|zoom|random|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160},\"fit\":{\"type\":\"string\",\"pattern\":\"^(contain|pan|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160},\"pan\":{\"oneOf\":[{\"type\":\"boolean\"},{\"type\":\"string\",\"pattern\":\"^\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\}\$\",\"maxLength\":160}]},\"shuffle\":{\"oneOf\":[{\"type\":\"boolean\"},{\"type\":\"string\",\"pattern\":\"^\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\}\$\",\"maxLength\":160}]},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"pan_speed\":{\"type\":[\"number\",\"string\"],\"minimum\":1,\"maximum\":120,\"maxLength\":160,\"description\":\"Maximum pan speed in dp/s; dwell is extended until the entire image has been shown.\"},\"caption\":{\"type\":\"string\",\"pattern\":\"^(auto|tap|off|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160},\"caption_hold\":{\"type\":[\"number\",\"string\"],\"minimum\":1,\"maximum\":60,\"maxLength\":160},\"pan_seconds\":{\"type\":[\"number\",\"string\"],\"minimum\":0.1,\"maximum\":120,\"maxLength\":160,\"description\":\"Minimum duration of a continuous eased sweep in seconds (default 12). Longer sweeps respect pan_speed; interval may include a stationary hold.\"},\"pan_direction\":{\"type\":\"string\",\"pattern\":\"^(random|forward|reverse|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160,\"description\":\"Random direction per photo by default. Forward follows reading order; reverse swaps both edges.\"}},\"description\":\"API 3. Local library files, plugin asset:assets/... or HTTP(S) with network permission. OS owns bounded downloads/cache and decoding. pan sweeps the entire image; contain displays the whole image at once. Continuous native panning, independently of scene tick cadence. Vertical drag browses adjacent items without wrapping; horizontal navigation and long press belong to the OS.\"},\"tileColor\":{\"type\":\"string\",\"pattern\":\"^(#[0-9A-Fa-f]{6}|\\\\{\\\\{[^{}]+\\\\}\\\\})\$\",\"maxLength\":2048},\"tileReference\":{\"title\":\"SchemaTileReference\",\"type\":\"object\",\"required\":[\"widget\",\"size\"],\"additionalProperties\":false,\"properties\":{\"widget\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\\\\.[a-z][a-z0-9-]*\$\",\"maxLength\":130},\"size\":{\"enum\":[\"small\",\"medium\",\"wide\",\"large\"]},\"color\":{\"\$ref\":\"#/\$defs/tileColor\"}}},\"tileFace\":{\"title\":\"SchemaTileFace\",\"type\":\"object\",\"required\":[\"label\"],\"additionalProperties\":false,\"properties\":{\"icon\":{\"type\":\"string\",\"maxLength\":2048},\"value\":{\"type\":\"string\",\"maxLength\":2048},\"label\":{\"type\":\"string\",\"maxLength\":2048},\"detail\":{\"type\":\"string\",\"maxLength\":2048},\"content\":{\"\$ref\":\"#/\$defs/tileContent\"}}},\"tileContent\":{\"oneOf\":[{\"\$ref\":\"#/\$defs/clock\"},{\"\$ref\":\"#/\$defs/progress\"},{\"\$ref\":\"#/\$defs/chart\"},{\"\$ref\":\"#/\$defs/daylight\"},{\"\$ref\":\"#/\$defs/moon\"}]},\"tile\":{\"title\":\"SchemaTileView\",\"type\":\"object\",\"required\":[\"template\",\"faces\"],\"additionalProperties\":false,\"properties\":{\"template\":{\"const\":\"tile\"},\"color\":{\"\$ref\":\"#/\$defs/tileColor\"},\"faces\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":4,\"items\":{\"\$ref\":\"#/\$defs/tileFace\"}},\"badge\":{\"type\":\"string\",\"maxLength\":2048},\"content\":{\"\$ref\":\"#/\$defs/tileContent\"},\"image\":{\"\$ref\":\"#/\$defs/image\"}}},\"tiles\":{\"title\":\"SchemaTilesView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"tiles\"},\"slots\":{\"title\":\"SchemaTilesSlots\",\"type\":\"object\",\"required\":[\"tiles\"],\"additionalProperties\":false,\"properties\":{\"tiles\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":16,\"items\":{\"\$ref\":\"#/\$defs/tileReference\"}}}},\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100}},\"x-thinkos-slot-roles\":{\"tiles\":\"tiles\"}},\"aero\":{\"title\":\"SchemaAero\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"aero\"},\"slots\":{\"title\":\"SchemaAeroSlots\",\"type\":\"object\",\"required\":[\"windows\"],\"additionalProperties\":false,\"properties\":{\"windows\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":3,\"items\":{\"\$ref\":\"#/\$defs/widgetReference\"}}}}},\"x-thinkos-slot-roles\":{\"windows\":\"aero\"}},\"nowplaying\":{\"title\":\"SchemaNowPlaying\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"nowplaying\"},\"style\":{\"enum\":[\"full\",\"bar\",\"card\"]},\"idle\":{\"type\":\"string\",\"maxLength\":512},\"notice\":{\"type\":\"string\",\"maxLength\":512},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}},\"description\":\"The OS music player view, bound to the media session: cover (the artwork, or a cover drawn from the title when there is none), title, artist, a format line, position bar, transport buttons, volume and, as a page variant, the queue. 'full' fills a page (variant 'queue' shows the queue beside the cover), 'bar' is a one-line mini player, 'card' fits a home card. 'idle' is shown while nothing plays; 'notice' is a line of text (a playback error) under the title. Buttons, the bar and the queue call the player directly; the plugin only provides the media.\"},\"tv\":{\"title\":\"SchemaTv\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"tv\"},\"channel\":{\"type\":\"string\",\"maxLength\":160},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"fit\":{\"enum\":[\"contain\",\"cover\"]}},\"description\":\"The OS television view: the cinema picture of 'video' (subtitles, buffering and error cards, brightness and volume by vertical drag) with a live-TV overlay in place of the transport bar. 'channel' is a state path to an object {number, name, group, line, lines, favorite, program:{title,start,end}, next:{title,start}} (times are epoch milliseconds, every field optional); the OS draws the channel banner, the programme now and next with its progress, and buttons for the previous / next channel, the channel list, the next line of the channel and the favourite mark. A button press, or a double tap on the left or right quarter of the picture, sends the 'tap' event with ('tap' value, action) where action is 'prev', 'next', 'list', 'line' or 'favorite'. The plugin only supplies the media and the channel.\"},\"tabs\":{\"title\":\"SchemaTabs\",\"type\":\"object\",\"required\":[\"type\",\"items\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"tabs\"},\"items\":{\"type\":\"string\",\"maxLength\":160},\"current\":{\"type\":[\"string\",\"number\"]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}},\"description\":\"A row of up to six labelled tabs. 'items' is a state path to an array of strings, 'current' the 1-based selected tab; tapping one sends the 'tap' event with its 1-based index.\"},\"slider\":{\"title\":\"SchemaSlider\",\"type\":\"object\",\"required\":[\"type\",\"level\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"slider\"},\"level\":{\"enum\":[\"brightness\",\"volume\"]},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}},\"description\":\"A slider for one of the device's own levels: the screen brightness (the 'display.brightness_max' setting) or the media volume the volume keys change. The OS draws it and applies the level as the finger moves, without going through Lua. Needs the system.device permission, which only built-in plugins hold.\"},\"toggle\":{\"title\":\"SchemaToggle\",\"type\":\"object\",\"required\":[\"type\",\"icon\",\"label\",\"tap\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"toggle\"},\"icon\":{\"type\":\"string\",\"maxLength\":2048},\"label\":{\"type\":\"string\",\"maxLength\":2048},\"on\":{\"type\":[\"string\",\"boolean\"]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}},\"description\":\"A labelled round button with an icon, lit while 'on' is true. Tapping it sends the 'tap' event; the plugin decides what that does. Colours, shape and press feedback belong to the OS.\"},\"gridCell\":{\"title\":\"SchemaGridCell\",\"type\":\"object\",\"required\":[\"title\"],\"additionalProperties\":false,\"properties\":{\"icon\":{\"type\":\"string\",\"maxLength\":512},\"image\":{\"type\":\"string\",\"maxLength\":512},\"title\":{\"type\":\"string\",\"maxLength\":512}}},\"grid\":{\"title\":\"SchemaGrid\",\"type\":\"object\",\"required\":[\"type\",\"items\",\"item\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"grid\"},\"items\":{\"type\":\"string\",\"maxLength\":160},\"item\":{\"\$ref\":\"#/\$defs/gridCell\"},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}},\"description\":\"A scrolling grid of icon cells with a title under each. 'items' is a state path to an array; each cell shows 'icon' (a name from the icon vocabulary) or 'image' (an image source, including app:<package> for an installed Android app, which needs system.apps), and 'title'. Tapping a cell sends 'tap' with its name and 1-based index.\"},\"panel\":{\"title\":\"SchemaPanelView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"panel\"},\"slots\":{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"header\":{\"\$ref\":\"#/\$defs/slotContent\"},\"quick\":{\"\$ref\":\"#/\$defs/slotContent\"},\"levels\":{\"\$ref\":\"#/\$defs/slotContent\"},\"media\":{\"\$ref\":\"#/\$defs/slotContent\"},\"footer\":{\"\$ref\":\"#/\$defs/slotContent\"},\"update\":{\"\$ref\":\"#/\$defs/text\"}}}},\"x-thinkos-slot-roles\":{\"header\":\"header\",\"quick\":\"row\",\"levels\":\"levels\",\"media\":\"media\",\"footer\":\"footer\",\"update\":\"notice\"},\"description\":\"The control sheet that slides down from the top edge over whatever page is showing. 'quick' is one row of toggles, 'levels' a stack of sliders, 'media' the media controls (the OS collapses it while nothing is loaded); 'header' and 'footer' are text rows. Only the plugin that holds the panel role and system.device can supply it; the OS owns the gesture, the sheet and its look. API10 adds the optional update text slot: OS-owned two-line result/notes preview, collapsed when empty. Its tap opens the complete native update page.\"},\"formIcon\":{\"description\":\"A registered monochrome vector glyph; no arbitrary image, font, path or style.\",\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"enum\":[\"wifi\",\"wifi-off\",\"volume\",\"volume-off\",\"brightness\",\"tv\",\"radio\",\"cast\",\"apps\",\"settings\",\"clear-day\",\"clear-night\",\"partly-day\",\"partly-night\",\"cloud\",\"fog\",\"rain\",\"shower\",\"snow\",\"thunder\",\"sun\",\"moon\",\"unknown\",\"monitor\",\"map-pin\",\"house\",\"sliders-horizontal\",\"puzzle\",\"moon-star\",\"sunset\",\"bluetooth\"]},\"formLink\":{\"title\":\"SchemaFormLink\",\"description\":\"A link descriptor in the state array named by form.path. It emits form.change(key, true), after confirmation when confirm is present. API10 adds an optional fixed 24dp vector icon.\",\"type\":\"object\",\"required\":[\"key\",\"type\",\"label\"],\"additionalProperties\":false,\"properties\":{\"key\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128},\"type\":{\"const\":\"link\"},\"label\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"display\":{\"type\":\"string\",\"maxLength\":200},\"icon\":{\"\$ref\":\"#/\$defs/formIcon\"},\"group\":{\"type\":\"string\",\"maxLength\":80},\"confirm\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":200}}},\"formPreview\":{\"description\":\"OS-computed number descriptor preview. Only brightness is supported; a drag temporarily changes DeviceLevels and cancellation restores the saved value. This is not a manifest setting property or a plugin permission.\",\"type\":\"string\",\"enum\":[\"brightness\"]},\"formNav\":{\"title\":\"SchemaFormNav\",\"type\":\"object\",\"description\":\"State descriptor read by form.nav. form.navigate(id) replaces the selected category; form.layout(wide) reports the OS breakpoint. API10 adds optional registered icons; categories show all icons or none. No style parameters.\",\"required\":[\"items\"],\"additionalProperties\":false,\"properties\":{\"items\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"title\":\"SchemaFormNavItem\",\"type\":\"object\",\"required\":[\"id\",\"label\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9_-]{0,63}\$\"},\"label\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"icon\":{\"\$ref\":\"#/\$defs/formIcon\"}}}},\"current\":{\"type\":\"string\",\"maxLength\":64}}}},\"x-thinkos-api\":{\"current\":10,\"componentTypes\":{\"text\":1,\"icon\":1,\"clock\":1,\"form\":1,\"progress\":2,\"chart\":2,\"list\":2,\"daylight\":2,\"moon\":2,\"image\":3,\"carousel\":3,\"controls\":7,\"video\":7,\"seekbar\":7,\"nowplaying\":8,\"tv\":8,\"tabs\":8,\"slider\":8,\"toggle\":8,\"grid\":8},\"layouts\":{\"stacks\":1,\"focus\":1,\"form\":1,\"split\":2,\"stage\":2,\"tiles\":4,\"aero\":5,\"panel\":8},\"componentProperties\":{\"tap\":2,\"weight\":2,\"current\":8,\"nav\":9,\"subtitle\":10,\"primary\":10,\"secondary\":10,\"back\":10},\"componentGroups\":2,\"templates\":{\"glance\":1,\"tile\":4},\"slotWidgets\":6,\"manifestFields\":{\"exports\":6,\"uses\":6,\"calls\":6,\"apps\":8,\"related\":9},\"widgetFields\":{\"alternates\":6},\"commandFields\":{\"public\":6,\"cooldown\":6},\"tileFields\":{\"image\":6},\"tileFaceFields\":{\"content\":6},\"settingFields\":{\"warn\":9,\"placeholder\":9,\"level\":9,\"depends\":9,\"format\":9,\"zero\":9,\"presets\":9},\"settingSources\":{\"region\":10},\"settingTypes\":{\"time\":10},\"formFields\":{\"link\":1,\"confirm\":9,\"preview\":9,\"nav\":9,\"link.icon\":10,\"nav.items.icon\":10,\"time\":10},\"runtimeEvents\":{\"cast\":9,\"form.layout\":9,\"form.navigate\":9,\"apps\":9,\"update\":10,\"practice\":10,\"plugins\":10,\"mode\":10},\"mediaEntryFields\":{\"public_only\":10},\"mediaProbeOptions\":{\"public_only\":10},\"runtimeMethods\":{\"net.resolve\":10,\"device.mode\":10,\"device.setMode\":10,\"settings.about\":9,\"apps.list\":9,\"apps.open\":9,\"update.status\":10,\"update.open\":10,\"update.check\":10,\"update.download\":10,\"update.install\":10,\"onboarding.open\":10,\"onboarding.status\":10,\"onboarding.practice\":10,\"onboarding.stop\":10,\"device.canSetLocale\":10,\"device.setLocale\":10,\"device.canSetTimeZone\":10,\"device.setTimeZone\":10,\"tz.zones\":10,\"plugins.catalog\":10,\"plugins.installed\":10,\"plugins.open\":10,\"plugins.install\":10,\"plugins.update\":10,\"plugins.uninstall\":10,\"plugins.setAuto\":10},\"pageRoles\":{\"launcher\":9,\"onboarding\":10},\"permissions\":{\"system.update\":10,\"system.plugins\":10},\"layoutSlots\":{\"panel.update\":10,\"form.hero\":10}}}"),
        "manifest.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"manifest.json\",\"title\":\"SchemaManifest\",\"description\":\"ThinkOS plugin contract. Settings (ADR-017) replace the M0 'config' field. API 6 adds explicitly public data, consumed data and callable commands (ADR-027); everything else stays private to the plugin.\",\"type\":\"object\",\"required\":[\"id\",\"name\",\"version\",\"api\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"name\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"version\":{\"type\":\"string\",\"pattern\":\"^[0-9]+\\\\.[0-9]+\\\\.[0-9]+(?:-[A-Za-z0-9.-]+)?\$\",\"maxLength\":48},\"api\":{\"type\":\"integer\",\"enum\":[1,2,3,4,5,6,7,8,9,10]},\"icon\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\$\",\"maxLength\":200},\"main\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\\\.lua\$\",\"maxLength\":200},\"permissions\":{\"type\":\"array\",\"items\":{\"enum\":[\"network\",\"alert\",\"media\",\"library\",\"presence\",\"server\",\"system.settings\",\"location\",\"system.device\",\"system.apps\",\"system.update\",\"system.plugins\"]},\"uniqueItems\":true,\"maxItems\":12},\"settings\":{\"type\":\"object\",\"maxProperties\":32,\"additionalProperties\":{\"\$ref\":\"#/\$defs/setting\"}},\"pages\":{\"type\":\"array\",\"maxItems\":16,\"items\":{\"\$ref\":\"#/\$defs/page\"}},\"widgets\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"\$ref\":\"#/\$defs/widget\"}},\"commands\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"\$ref\":\"#/\$defs/command\"}},\"exports\":{\"type\":\"object\",\"maxProperties\":16,\"additionalProperties\":{\"\$ref\":\"#/\$defs/export\"}},\"uses\":{\"type\":\"object\",\"maxProperties\":16,\"additionalProperties\":{\"\$ref\":\"#/\$defs/use\"}},\"calls\":{\"type\":\"array\",\"maxItems\":16,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\\\\.[a-z][a-z0-9-]*\$\",\"maxLength\":130}},\"apps\":{\"type\":\"array\",\"maxItems\":8,\"items\":{\"\$ref\":\"#/\$defs/app\"}},\"related\":{\"type\":\"array\",\"maxItems\":32,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9_]*(\\\\.[a-z][a-z0-9_]*)+\$\",\"maxLength\":128},\"description\":\"System setting keys shown by the settings administrator. Grants no read or write permission. Each must exist, be non-hidden and non-secret.\"}},\"\$defs\":{\"page\":{\"title\":\"SchemaPage\",\"type\":\"object\",\"required\":[\"id\",\"view\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"view\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\$\",\"maxLength\":200},\"title\":{\"type\":\"string\",\"maxLength\":80},\"roles\":{\"type\":\"array\",\"items\":{\"enum\":[\"home\",\"ambient\",\"sleep\",\"settings\",\"panel\",\"launcher\",\"onboarding\"]},\"uniqueItems\":true,\"maxItems\":7},\"priority\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},\"live\":{\"type\":\"boolean\"},\"variants\":{\"type\":\"array\",\"items\":{\"\$ref\":\"#/\$defs/variant\"},\"minItems\":1,\"maxItems\":16}}},\"widget\":{\"title\":\"SchemaWidget\",\"type\":\"object\",\"required\":[\"id\",\"view\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"view\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\$\",\"maxLength\":200},\"opens\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"alternates\":{\"title\":\"SchemaWidgetAlternates\",\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"glance\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\$\",\"maxLength\":200},\"tile\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\$\",\"maxLength\":200}}}}},\"variant\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"setting\":{\"title\":\"SchemaSetting\",\"type\":\"object\",\"required\":[\"type\",\"label\",\"default\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"enum\":[\"bool\",\"choice\",\"number\",\"text\",\"time\"]},\"label\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"default\":{\"type\":[\"string\",\"number\",\"boolean\"]},\"help\":{\"type\":\"string\",\"maxLength\":200},\"group\":{\"type\":\"string\",\"maxLength\":80},\"choices\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":64,\"items\":{\"\$ref\":\"#/\$defs/settingChoice\"}},\"source\":{\"enum\":[\"timezone\",\"place\",\"region\"]},\"allowEmpty\":{\"type\":\"boolean\"},\"min\":{\"type\":\"number\"},\"max\":{\"type\":\"number\"},\"step\":{\"type\":\"number\"},\"unit\":{\"type\":\"string\",\"maxLength\":16},\"maxLength\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1024},\"secret\":{\"type\":\"boolean\"},\"hidden\":{\"type\":\"boolean\"},\"warn\":{\"type\":\"string\",\"maxLength\":200},\"placeholder\":{\"type\":\"string\",\"maxLength\":200},\"level\":{\"enum\":[\"normal\",\"advanced\",\"hidden\"]},\"depends\":{\"\$ref\":\"#/\$defs/settingDependency\"},\"format\":{\"enum\":[\"duration\"]},\"zero\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"presets\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":64,\"uniqueItems\":true,\"items\":{\"type\":\"number\"}}}},\"settingChoice\":{\"title\":\"SchemaSettingChoice\",\"type\":\"object\",\"required\":[\"value\",\"label\"],\"additionalProperties\":false,\"properties\":{\"value\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9_.-]+\$\",\"minLength\":1,\"maxLength\":64},\"label\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80}}},\"command\":{\"title\":\"SchemaCommand\",\"type\":\"object\",\"required\":[\"id\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"phrases\":{\"type\":\"array\",\"maxItems\":16,\"items\":{\"type\":\"string\",\"maxLength\":80}},\"gesture\":{\"enum\":[\"wave\",\"left\",\"right\",\"fist\"]},\"public\":{\"type\":\"boolean\"},\"cooldown\":{\"type\":\"number\",\"minimum\":0.1,\"maximum\":86400}}},\"export\":{\"title\":\"SchemaExport\",\"type\":\"object\",\"required\":[\"path\",\"shape\"],\"additionalProperties\":false,\"properties\":{\"path\":{\"type\":\"string\",\"pattern\":\"^(?!(?:config|uses|variant)(?:\\\\.|\$))[A-Za-z_][A-Za-z0-9_]*(?:\\\\.[A-Za-z_][A-Za-z0-9_]*)*\$\",\"maxLength\":128},\"shape\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*/[1-9][0-9]*\$\",\"maxLength\":64},\"requires\":{\"type\":\"array\",\"uniqueItems\":true,\"maxItems\":8,\"items\":{\"enum\":[\"network\",\"alert\",\"media\",\"library\",\"presence\",\"server\",\"location\"]}}}},\"use\":{\"title\":\"SchemaUse\",\"type\":\"object\",\"required\":[\"export\",\"shape\"],\"additionalProperties\":false,\"properties\":{\"export\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\\\\.[a-z][a-z0-9-]*\$\",\"maxLength\":130},\"shape\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*/[1-9][0-9]*\$\",\"maxLength\":64}}},\"app\":{\"title\":\"SchemaApp\",\"type\":\"object\",\"required\":[\"id\",\"page\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"page\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80},\"icon\":{\"type\":\"string\",\"maxLength\":64},\"listed\":{\"type\":\"boolean\"}},\"description\":\"An app: one of this plugin's pages shown full screen on its own. The page is not in the left-right page sequence and sideways swipes inside it belong to the app; it is entered from a tile, a card, the app list or the plugin's own request, and left with Back (the edge swipe or the Back key), which the app may take for itself with tos.back.intercept. The page cannot hold a role. 'listed' false keeps the page an app without showing it in the list.\"},\"settingDependency\":{\"description\":\"A condition on another setting in the same scope: value, in, or not (mutually exclusive). Equality is type-sensitive; numeric equality compares the numeric value. Every condition in a dependency chain must hold; values are retained while rows are collapsed. Targets and cycles are validated at install time.\",\"oneOf\":[{\"type\":\"object\",\"required\":[\"key\",\"value\"],\"additionalProperties\":false,\"properties\":{\"key\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128},\"value\":{\"type\":[\"string\",\"number\",\"boolean\"]}}},{\"type\":\"object\",\"required\":[\"key\",\"in\"],\"additionalProperties\":false,\"properties\":{\"key\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128},\"in\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":64,\"uniqueItems\":true,\"items\":{\"type\":[\"string\",\"number\",\"boolean\"]}}}},{\"type\":\"object\",\"required\":[\"key\",\"not\"],\"additionalProperties\":false,\"properties\":{\"key\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128},\"not\":{\"type\":[\"string\",\"number\",\"boolean\"]}}}]}}}"),
        "view.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"view.json\",\"description\":\"Page or widget declaration with API-versioned fixed OS vocabulary\",\"oneOf\":[{\"\$ref\":\"layouts.json#/\$defs/stacks\"},{\"\$ref\":\"layouts.json#/\$defs/focus\"},{\"\$ref\":\"layouts.json#/\$defs/glance\"},{\"\$ref\":\"layouts.json#/\$defs/formView\"},{\"\$ref\":\"layouts.json#/\$defs/split\"},{\"\$ref\":\"layouts.json#/\$defs/stage\"},{\"\$ref\":\"layouts.json#/\$defs/tiles\"},{\"\$ref\":\"layouts.json#/\$defs/tile\"},{\"\$ref\":\"layouts.json#/\$defs/aero\"},{\"\$ref\":\"layouts.json#/\$defs/panel\"}]}")
    ) }

    fun validateIndex(value: JSONObject) {
        validate(value, documents.getValue("index.json"), "index.json", "index", 0)
    }
    fun validateCatalog(value: JSONObject) {
        validate(value, documents.getValue("catalog.json"), "catalog.json", "catalog", 0)
    }

    fun formIconNames(): Set<String> = documents.getValue("layouts.json").getJSONObject("\$defs")
        .getJSONObject("formIcon").getJSONArray("enum").let { names ->
            (0 until names.length()).map { names.getString(it) }.toSet()
        }

    fun validateFormLink(value: JSONObject, api: Int = API) {
        require(api in 1..API) { "unsupported plugin API" }
        validate(value, documents.getValue("layouts.json").getJSONObject("\$defs").getJSONObject("formLink"), "layouts.json", "formLink", 0)
        require(!value.has("confirm") || renderApis.getJSONObject("formFields").getInt("confirm") <= api) { "form confirmation requires a newer plugin API" }
        require(!value.has("icon") || renderApis.getJSONObject("formFields").getInt("link.icon") <= api) { "form link icon requires a newer plugin API" }
    }

    fun validateFormNavigation(value: JSONObject, api: Int = API) {
        require(api in 1..API) { "unsupported plugin API" }
        require(renderApis.getJSONObject("formFields").getInt("nav") <= api) { "form navigation requires a newer plugin API" }
        validate(value, documents.getValue("layouts.json").getJSONObject("\$defs").getJSONObject("formNav"), "layouts.json", "formNav", 0)
        val items = value.getJSONArray("items")
        for (index in 0 until items.length()) require(!items.getJSONObject(index).has("icon") ||
            renderApis.getJSONObject("formFields").getInt("nav.items.icon") <= api) { "form navigation icon requires a newer plugin API" }
    }

    fun validateManifest(value: JSONObject) {
        validate(value, documents.getValue("manifest.json"), "manifest.json", "manifest", 0)
        for (name in listOf("pages", "widgets", "commands", "apps")) {
            val entries = value.optJSONArray(name) ?: continue
            val ids = HashSet<String>()
            for (i in 0 until entries.length()) require(ids.add(entries.getJSONObject(i).getString("id"))) { "$name: duplicate id" }
        }
        require(value.getString("id") != "system") { "manifest.id: 'system' is reserved for the OS's own settings" }
        value.optJSONObject("settings")?.let { declared ->
            // Semantic rules live in one place, shared with the runtime store.
            val fields = declared.keys().asSequence().associateWith { key -> moe.kiwi.thinkos.settings.SettingField.parse(key, declared.getJSONObject(key), false) }
            moe.kiwi.thinkos.settings.SettingDeclarations.validate(fields)
        }
        value.optJSONArray("related")?.let { keys ->
            moe.kiwi.thinkos.settings.SettingDeclarations.related(List(keys.length()) { keys.getString(it) }, relatedKeys)
        }
        val pages = value.optJSONArray("pages")
        val pageIds = (0 until (pages?.length() ?: 0)).map { pages!!.getJSONObject(it).getString("id") }.toSet()
        value.optJSONArray("widgets")?.let { widgets ->
            for (i in 0 until widgets.length()) {
                val widget = widgets.getJSONObject(i)
                require(!widget.has("opens") || widget.getString("opens") in pageIds) { "widget opens unknown page" }
            }
        }
        value.optJSONArray("apps")?.let { apps ->
            for (i in 0 until apps.length()) {
                val page = apps.getJSONObject(i).getString("page")
                require(page in pageIds) { "app opens unknown page" }
                // An app is entered and left by the user; a page that holds a role (home, sleep, settings, …) is placed by the OS.
                val declared = (0 until pages!!.length()).map { pages.getJSONObject(it) }.first { it.getString("id") == page }
                require((declared.optJSONArray("roles")?.length() ?: 0) == 0) { "app page cannot hold a role" }
            }
        }
        validatePublicContract(value)
    }

    /** API 6 public data and commands: rules JSON Schema cannot express here (ADR-027). */
    private fun validatePublicContract(value: JSONObject) {
        val api = value.getInt("api")
        fun gate(entry: JSONObject, table: String) {
            val minimums = renderApis.getJSONObject(table)
            for (key in minimums.keys()) if (entry.has(key)) require(minimums.getInt(key) <= api) { "$key requires a newer plugin API" }
        }
        gate(value, "manifestFields")
        value.optJSONArray("permissions")?.let { permissions ->
            val versions = renderApis.getJSONObject("permissions")
            for (i in 0 until permissions.length()) require(versions.optInt(permissions.getString(i), 1) <= api) {
                "permission requires a newer plugin API"
            }
        }
        value.optJSONArray("pages")?.let { pages ->
            val roleVersions = renderApis.getJSONObject("pageRoles")
            val permissions = value.optJSONArray("permissions")
            for (i in 0 until pages.length()) pages.getJSONObject(i).optJSONArray("roles")?.let { roles ->
                for (j in 0 until roles.length()) {
                    val role = roles.getString(j)
                    require(roleVersions.optInt(role, 1) <= api) { "page role requires a newer plugin API" }
                    require(role != "launcher" || (0 until (permissions?.length() ?: 0)).any { permissions!!.getString(it) == "system.apps" }) {
                        "the launcher role needs the system.apps permission"
                    }
                    require(role !in setOf("onboarding", "settings") || (0 until (permissions?.length() ?: 0)).any { permissions!!.getString(it) == "system.settings" }) {
                        "the settings/onboarding role needs the system.settings permission"
                    }
                }
            }
        }
        value.optJSONObject("settings")?.let { fields ->
            val sources = renderApis.getJSONObject("settingSources")
            for (key in fields.keys()) {
                val field = fields.getJSONObject(key)
                gate(field, "settingFields")
                require(renderApis.getJSONObject("settingTypes").optInt(field.optString("type"), 1) <= api) { "setting type requires a newer plugin API" }
                require(sources.optInt(field.optString("source"), 1) <= api) { "setting source requires a newer plugin API" }
            }
        }
        value.optJSONArray("widgets")?.let { for (i in 0 until it.length()) gate(it.getJSONObject(i), "widgetFields") }
        value.optJSONArray("commands")?.let {
            for (i in 0 until it.length()) {
                val command = it.getJSONObject(i)
                gate(command, "commandFields")
                require(!command.has("cooldown") || command.optBoolean("public")) { "cooldown only applies to public commands" }
            }
        }
        val id = value.getString("id")
        value.optJSONObject("exports")?.let { exports ->
            for (name in exports.keys()) require(name.matches(Regex("[a-z][a-z0-9-]{0,63}"))) { "manifest.exports: invalid name" }
            // A path inside another export would let its readers bypass the inner one's `requires`.
            val paths = exports.keys().asSequence().map { exports.getJSONObject(it).getString("path") }.toList()
            for (i in paths.indices) for (other in paths.drop(i + 1)) {
                require(paths[i] != other && !paths[i].startsWith("$other.") && !other.startsWith(paths[i] + ".")) { "manifest.exports: paths overlap" }
            }
        }
        value.optJSONObject("uses")?.let { uses ->
            for (alias in uses.keys()) {
                require(alias.matches(Regex("[a-z][a-z0-9_]{0,63}"))) { "manifest.uses: invalid alias" }
                require(uses.getJSONObject(alias).getString("export").substringBefore('.') != id) { "a plugin cannot use its own export" }
            }
        }
        value.optJSONArray("calls")?.let { for (i in 0 until it.length()) require(it.getString(i).substringBefore('.') != id) { "a plugin cannot call its own command" } }
    }

    /** Runtime media options share the same version registry as render vocabulary. */
    fun mediaPublicOnlyApi(probe: Boolean): Int = renderApis
        .getJSONObject(if (probe) "mediaProbeOptions" else "mediaEntryFields").getInt("public_only")

    fun validateView(value: JSONObject, api: Int = API) {
        require(api in 1..API) { "unsupported plugin API" }
        validate(value, documents.getValue("view.json"), "view.json", "view", 0)
        fun gate(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) gate(value.opt(i))
                is JSONObject -> {
                    if (value.has("layout")) require(renderApis.getJSONObject("layouts").optInt(value.getString("layout"), API + 1) <= api) { "layout requires a newer plugin API" }
                    value.optJSONObject("slots")?.let { slots ->
                        val versions = renderApis.optJSONObject("layoutSlots")
                        for (key in slots.keys()) require((versions?.optInt(value.optString("layout") + "." + key, 1) ?: 1) <= api) { "layout slot requires a newer plugin API" }
                        for (key in slots.keys()) require(slots.optJSONObject(key)?.has("widget") != true || renderApis.getInt("slotWidgets") <= api) { "widget references in slots require a newer plugin API" }
                    }
                    if (value.has("template")) require(renderApis.getJSONObject("templates").optInt(value.getString("template"), API + 1) <= api) { "template requires a newer plugin API" }
                    if (value.optString("template") == "tile") {
                        val fields = renderApis.getJSONObject("tileFields")
                        for (key in fields.keys()) if (value.has(key)) require(fields.getInt(key) <= api) { "tile field requires a newer plugin API" }
                        val faceFields = renderApis.getJSONObject("tileFaceFields")
                        val faces = value.optJSONArray("faces")
                        for (i in 0 until (faces?.length() ?: 0)) for (key in faceFields.keys())
                            if (faces!!.optJSONObject(i)?.has(key) == true) require(faceFields.getInt(key) <= api) { "tile face field requires a newer plugin API" }
                    }
                    val kind = value.optString("type").takeIf { it.isNotEmpty() }
                    if (kind != null) {
                        require(renderApis.getJSONObject("componentTypes").optInt(kind, API + 1) <= api) { "component requires a newer plugin API" }
                        val properties = renderApis.getJSONObject("componentProperties")
                        for (key in properties.keys()) if (value.has(key)) require(properties.getInt(key) <= api) { "component property requires a newer plugin API" }
                    }
                    if (value.has("children") && !value.has("template") && kind == null) require(renderApis.getInt("componentGroups") <= api) { "component groups require a newer plugin API" }
                    for (key in value.keys()) gate(value.opt(key))
                }
            }
        }
        gate(value)
        value.optJSONArray("variants")?.let { variants ->
            val ids = HashSet<String>()
            for (i in 0 until variants.length()) require(ids.add(variants.getJSONObject(i).getString("id"))) { "duplicate variant" }
        }
    }

    private fun matchesType(value: Any?, type: String): Boolean = when (type) {
        "object" -> value is JSONObject
        "array" -> value is JSONArray
        "string" -> value is String
        "boolean" -> value is Boolean
        "number" -> value is Number && value.toDouble().isFinite()
        "integer" -> value is Number && value.toDouble().isFinite() && value.toDouble() % 1.0 == 0.0
        "null" -> value == null || value === JSONObject.NULL
        else -> error("Unknown schema type $type")
    }

    private val patterns = java.util.concurrent.ConcurrentHashMap<String, Regex>()

    /** Eliminate impossible branches before validation; preserves oneOf uniqueness without exception-driven dispatch. */
    private fun possible(value: Any?, schema: JSONObject, document: String, depth: Int = 0): Boolean {
        if (depth > 32) return true // The validator reports excessive nesting.
        if (schema.has("\$ref")) {
            val parts = schema.getString("\$ref").split('#', limit = 2)
            val doc = parts[0].ifEmpty { document }
            var target: Any = documents.getValue(doc)
            if (parts.size > 1) for (part in parts[1].split('/').drop(1)) target = (target as JSONObject).get(part.replace("~1", "/").replace("~0", "~"))
            return possible(value, target as JSONObject, doc, depth + 1)
        }
        if (schema.has("const") && !equivalent(value, schema.get("const"))) return false
        if (value is JSONObject) {
            schema.optJSONArray("required")?.let { keys -> for (i in 0 until keys.length()) if (!value.has(keys.getString(i))) return false }
            schema.optJSONObject("properties")?.let { properties ->
                for (key in properties.keys()) {
                    val field = properties.getJSONObject(key)
                    if (value.has(key) && field.has("const") && !equivalent(value.get(key), field.get("const"))) return false
                }
            }
        }
        return true
    }

    private fun validate(value: Any?, schema: JSONObject, document: String, path: String, depth: Int) {
        require(depth <= 32) { "$path: maximum nesting exceeded" }
        if (schema.has("\$ref")) {
            val parts = schema.getString("\$ref").split('#', limit = 2)
            val doc = parts[0].ifEmpty { document }
            var target: Any = documents.getValue(doc)
            if (parts.size > 1) for (part in parts[1].split('/').drop(1)) {
                target = (target as JSONObject).get(part.replace("~1", "/").replace("~0", "~"))
            }
            validate(value, target as JSONObject, doc, path, depth + 1)
            return
        }
        for (keyword in listOf("oneOf", "anyOf")) schema.optJSONArray(keyword)?.let { alternatives ->
            var matches = 0
            for (i in 0 until alternatives.length()) {
                if (!possible(value, alternatives.getJSONObject(i), document)) continue
                try { validate(value, alternatives.getJSONObject(i), document, path, depth + 1); matches++ }
                catch (_: IllegalArgumentException) { }
            }
            require(if (keyword == "oneOf") matches == 1 else matches >= 1) { "$path: unsupported declaration shape" }
        }
        if (schema.has("type")) {
            val type = schema.get("type")
            val valid = if (type is JSONArray) (0 until type.length()).any { matchesType(value, type.getString(it)) }
                        else matchesType(value, type as String)
            require(valid) { "$path: wrong value type" }
        }
        if (schema.has("const")) require(equivalent(value, schema.get("const"))) { "$path: unsupported value" }
        schema.optJSONArray("enum")?.let { options ->
            require((0 until options.length()).any { equivalent(value, options.get(it)) }) { "$path: unsupported value" }
        }
        when (value) {
            is JSONObject -> {
                require(value.length() <= schema.optInt("maxProperties", Int.MAX_VALUE)) { "$path: too many properties" }
                schema.optJSONArray("required")?.let { required ->
                    for (i in 0 until required.length()) require(value.has(required.getString(i))) { "$path: missing ${required.getString(i)}" }
                }
                val properties = schema.optJSONObject("properties")
                for (key in value.keys()) {
                    val child = properties?.optJSONObject(key)
                    if (child != null) validate(value.get(key), child, document, "$path.$key", depth + 1)
                    else when (val extra = schema.opt("additionalProperties")) {
                        false -> throw IllegalArgumentException("$path: unknown property $key")
                        is JSONObject -> validate(value.get(key), extra, document, "$path.$key", depth + 1)
                    }
                }
            }
            is JSONArray -> {
                require(value.length() >= schema.optInt("minItems", 0) && value.length() <= schema.optInt("maxItems", Int.MAX_VALUE)) { "$path: invalid item count" }
                if (schema.optBoolean("uniqueItems")) {
                    val items = HashSet<String>()
                    for (i in 0 until value.length()) require(items.add(value.get(i).toString())) { "$path: duplicate item" }
                }
                schema.optJSONObject("items")?.let { itemSchema ->
                    for (i in 0 until value.length()) validate(value.get(i), itemSchema, document, "$path[$i]", depth + 1)
                }
            }
            is String -> {
                require(value.length >= schema.optInt("minLength", 0) && value.length <= schema.optInt("maxLength", Int.MAX_VALUE)) { "$path: invalid string length" }
                if (schema.has("pattern")) require(patterns.getOrPut(schema.getString("pattern")) { Regex(schema.getString("pattern")) }.containsMatchIn(value)) { "$path: invalid string format" }
            }
            is Number -> require(value.toDouble() >= schema.optDouble("minimum", Double.NEGATIVE_INFINITY) && value.toDouble() <= schema.optDouble("maximum", Double.POSITIVE_INFINITY)) { "$path: number outside allowed range" }
        }
    }

    private fun equivalent(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
}
