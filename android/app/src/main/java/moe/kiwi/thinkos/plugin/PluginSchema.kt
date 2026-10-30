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
    val `weight`: Double? = null
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
    val `weight`: Double? = null
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
    val `weight`: Double? = null
)

data class SchemaVideo(
    val `type`: String,
    val `tap`: String? = null,
    val `weight`: Double? = null,
    val `fit`: String? = null
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
    val `commands`: List<SchemaCommand>? = null
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
    val `opens`: String? = null
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
    val `hidden`: Boolean? = null
)

data class SchemaSettingChoice(
    val `value`: String,
    val `label`: String
)

data class SchemaCommand(
    val `id`: String,
    val `phrases`: List<String>? = null,
    val `gesture`: String? = null
)

object LayoutVocabulary {
    val slots: Map<String, List<String>> = mapOf(
        "stacks" to listOf("left", "right"),
        "focus" to listOf("over", "hero", "under"),
        "form" to listOf("body"),
        "split" to listOf("lead", "main"),
        "stage" to listOf("stage", "over", "under")
    )
    val roles: Map<String, Map<String, String>> = mapOf(
        "stacks" to mapOf("left" to "stack", "right" to "stack"),
        "focus" to mapOf("over" to "header", "hero" to "content", "under" to "footer"),
        "form" to mapOf("body" to "content"),
        "split" to mapOf("lead" to "lead", "main" to "main"),
        "stage" to mapOf("stage" to "stage", "over" to "over", "under" to "under")
    )
}

object PluginSchema {
    const val API = 3
    private val renderApis by lazy { JSONObject("{\"current\":3,\"componentTypes\":{\"text\":1,\"icon\":1,\"clock\":1,\"form\":1,\"progress\":2,\"chart\":2,\"list\":2,\"daylight\":2,\"moon\":2,\"image\":3,\"carousel\":3},\"layouts\":{\"stacks\":1,\"focus\":1,\"form\":1,\"split\":2,\"stage\":2},\"componentProperties\":{\"tap\":2,\"weight\":2},\"componentGroups\":2}") }
    private val documents by lazy { mapOf(
        "catalog.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"catalog.json\",\"title\":\"SchemaPluginCatalog\",\"description\":\"APK assets/plugins/index.json. Each entry names assets/plugins/<id>/ and includes its complete index.\",\"type\":\"object\",\"required\":[\"plugins\"],\"additionalProperties\":false,\"properties\":{\"plugins\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"\$ref\":\"index.json\"}}}}"),
        "index.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"index.json\",\"title\":\"SchemaPluginIndex\",\"description\":\"Directory installation index (ADR-018). Digest hashes UTF-8 path + NUL + lowercase file SHA-256 + LF in UTF-8 path order; excludes index.json itself. Semantic path/hash/count/total checks also apply.\",\"type\":\"object\",\"required\":[\"id\",\"version\",\"digest\",\"files\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"\$ref\":\"manifest.json#/properties/id\"},\"version\":{\"\$ref\":\"manifest.json#/properties/version\"},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"files\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":128,\"items\":{\"\$ref\":\"#/\$defs/file\"}}},\"\$defs\":{\"file\":{\"title\":\"SchemaPluginFile\",\"type\":\"object\",\"required\":[\"path\",\"sha256\",\"size\"],\"additionalProperties\":false,\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512,\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.{1,2}(?:/|\$))[^/\\\\\\\\\\\\x00-\\\\x1f\\\\x7f]+(?:/[^/\\\\\\\\\\\\x00-\\\\x1f\\\\x7f]+)*\$\"},\"sha256\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"size\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1048576}}}}}"),
        "layouts.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"layouts.json\",\"description\":\"Fixed OS render vocabulary. API 1: stacks/focus/form/glance and text/icon/clock. API 2: progress/chart/list/daylight/moon, groups, split/stage, tap and weight. No plugin styles or executable view expressions. API 3: local/asset/HTTP(S) image and carousel with lossless framing and native panning.\",\"\$defs\":{\"component\":{\"oneOf\":[{\"\$ref\":\"#/\$defs/text\"},{\"\$ref\":\"#/\$defs/icon\"},{\"\$ref\":\"#/\$defs/clock\"},{\"\$ref\":\"#/\$defs/progress\"},{\"\$ref\":\"#/\$defs/chart\"},{\"\$ref\":\"#/\$defs/list\"},{\"\$ref\":\"#/\$defs/daylight\"},{\"\$ref\":\"#/\$defs/moon\"},{\"\$ref\":\"#/\$defs/image\"},{\"\$ref\":\"#/\$defs/carousel\"}]},\"text\":{\"title\":\"SchemaText\",\"type\":\"object\",\"required\":[\"type\",\"value\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"text\"},\"value\":{\"type\":\"string\",\"maxLength\":2048},\"style\":{\"enum\":[\"display\",\"title\",\"body\",\"caption\"]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"icon\":{\"title\":\"SchemaIcon\",\"type\":\"object\",\"required\":[\"type\",\"name\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"icon\"},\"name\":{\"type\":\"string\",\"maxLength\":2048},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"clock\":{\"title\":\"SchemaClock\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"clock\"},\"style\":{\"type\":\"string\",\"pattern\":\"^(digital|analog|solar|world|float|\\\\{\\\\{variant\\\\}\\\\})\$\"},\"format\":{\"type\":\"string\",\"maxLength\":80},\"sun\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64,\"description\":\"State path to tos.astro.sun() output for the solar dial. Derived data only; no coordinates.\"},\"zones\":{\"type\":\"array\",\"maxItems\":4,\"minItems\":1,\"items\":{\"\$ref\":\"#/\$defs/zone\"}},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"zone\":{\"title\":\"SchemaClockZone\",\"type\":\"object\",\"required\":[\"timezone\"],\"additionalProperties\":false,\"properties\":{\"label\":{\"type\":\"string\",\"maxLength\":40,\"description\":\"Optional. When absent the city name comes from the OS, localised.\"},\"timezone\":{\"type\":\"string\",\"maxLength\":80}}},\"widgetReference\":{\"title\":\"SchemaWidgetReference\",\"type\":\"object\",\"required\":[\"widget\"],\"additionalProperties\":false,\"properties\":{\"widget\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\\\\.[a-z][a-z0-9-]*\$\",\"maxLength\":130}}},\"stacks\":{\"title\":\"SchemaStacksView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100},\"layout\":{\"const\":\"stacks\"},\"slots\":{\"type\":\"object\",\"required\":[\"left\",\"right\"],\"additionalProperties\":false,\"properties\":{\"left\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":16,\"items\":{\"\$ref\":\"#/\$defs/widgetReference\"}},\"right\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":16,\"items\":{\"\$ref\":\"#/\$defs/widgetReference\"}}}}},\"x-thinkos-slot-roles\":{\"left\":\"stack\",\"right\":\"stack\"}},\"focus\":{\"title\":\"SchemaFocusView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100},\"layout\":{\"const\":\"focus\"},\"slots\":{\"type\":\"object\",\"required\":[\"hero\"],\"additionalProperties\":false,\"properties\":{\"hero\":{\"\$ref\":\"#/\$defs/slotContent\"},\"under\":{\"\$ref\":\"#/\$defs/slotContent\"},\"over\":{\"\$ref\":\"#/\$defs/slotContent\"}}}},\"x-thinkos-slot-roles\":{\"over\":\"header\",\"hero\":\"content\",\"under\":\"footer\"}},\"glance\":{\"title\":\"SchemaGlanceView\",\"type\":\"object\",\"required\":[\"template\",\"children\"],\"additionalProperties\":false,\"properties\":{\"template\":{\"const\":\"glance\"},\"children\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,\"items\":{\"\$ref\":\"#/\$defs/component\"}},\"relevance\":{\"type\":[\"string\",\"number\"]}}},\"formComponent\":{\"title\":\"SchemaForm\",\"type\":\"object\",\"required\":[\"type\",\"path\"],\"additionalProperties\":false,\"description\":\"Native settings form. 'path' names the state array of field descriptors; the OS draws the controls.\",\"properties\":{\"type\":{\"const\":\"form\"},\"path\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":2048},\"done\":{\"type\":\"string\",\"maxLength\":2048},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"formView\":{\"title\":\"SchemaFormView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"form\"},\"slots\":{\"type\":\"object\",\"required\":[\"body\"],\"additionalProperties\":false,\"properties\":{\"body\":{\"\$ref\":\"#/\$defs/formComponent\"}}}},\"x-thinkos-slot-roles\":{\"body\":\"content\"}},\"progress\":{\"title\":\"SchemaProgress\",\"type\":\"object\",\"required\":[\"type\",\"value\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"progress\"},\"style\":{\"enum\":[\"bar\",\"ring\"]},\"value\":{\"type\":[\"string\",\"number\"]},\"max\":{\"type\":[\"string\",\"number\"]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"chart\":{\"title\":\"SchemaChart\",\"type\":\"object\",\"required\":[\"type\",\"values\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"chart\"},\"style\":{\"enum\":[\"line\",\"bar\",\"area\"]},\"values\":{\"oneOf\":[{\"type\":\"string\",\"maxLength\":160},{\"type\":\"array\",\"maxItems\":168,\"items\":{\"type\":[\"number\",\"null\"]}}]},\"min\":{\"type\":[\"string\",\"number\"]},\"max\":{\"type\":[\"string\",\"number\"]},\"highlight\":{\"type\":[\"string\",\"number\"]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"listRow\":{\"title\":\"SchemaListRow\",\"type\":\"object\",\"required\":[\"title\"],\"additionalProperties\":false,\"properties\":{\"icon\":{\"type\":\"string\",\"maxLength\":512},\"title\":{\"type\":\"string\",\"maxLength\":512},\"subtitle\":{\"type\":\"string\",\"maxLength\":512},\"trailing\":{\"type\":\"string\",\"maxLength\":512},\"progress\":{\"type\":[\"string\",\"number\"]},\"max\":{\"type\":[\"string\",\"number\"]}}},\"list\":{\"title\":\"SchemaList\",\"type\":\"object\",\"required\":[\"type\",\"items\",\"item\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"list\"},\"items\":{\"type\":\"string\",\"maxLength\":160},\"item\":{\"\$ref\":\"#/\$defs/listRow\"},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"daylight\":{\"title\":\"SchemaDaylight\",\"type\":\"object\",\"required\":[\"type\",\"path\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"daylight\"},\"path\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"moon\":{\"title\":\"SchemaMoon\",\"type\":\"object\",\"required\":[\"type\",\"path\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"moon\"},\"path\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_]*(\\\\.[A-Za-z0-9_]+)*\$\",\"maxLength\":64},\"hemisphere\":{\"type\":\"string\",\"pattern\":\"^(north|south|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":80},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}}},\"componentGroup\":{\"title\":\"SchemaComponentGroup\",\"type\":\"object\",\"required\":[\"children\"],\"additionalProperties\":false,\"properties\":{\"children\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12,\"items\":{\"\$ref\":\"#/\$defs/component\"}}}},\"slotContent\":{\"oneOf\":[{\"\$ref\":\"#/\$defs/component\"},{\"\$ref\":\"#/\$defs/componentGroup\"}]},\"split\":{\"title\":\"SchemaSplitView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"split\"},\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100},\"slots\":{\"type\":\"object\",\"required\":[\"lead\",\"main\"],\"additionalProperties\":false,\"properties\":{\"lead\":{\"\$ref\":\"#/\$defs/slotContent\"},\"main\":{\"\$ref\":\"#/\$defs/slotContent\"}}}},\"x-thinkos-slot-roles\":{\"lead\":\"lead\",\"main\":\"main\"}},\"stage\":{\"title\":\"SchemaStageView\",\"type\":\"object\",\"required\":[\"layout\",\"slots\"],\"additionalProperties\":false,\"properties\":{\"layout\":{\"const\":\"stage\"},\"variants\":{\"type\":\"array\",\"items\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"minItems\":1,\"maxItems\":16},\"mood\":{\"type\":\"string\",\"maxLength\":100},\"slots\":{\"type\":\"object\",\"required\":[\"stage\"],\"additionalProperties\":false,\"properties\":{\"stage\":{\"\$ref\":\"#/\$defs/slotContent\"},\"over\":{\"\$ref\":\"#/\$defs/slotContent\"},\"under\":{\"\$ref\":\"#/\$defs/slotContent\"}}}},\"x-thinkos-slot-roles\":{\"stage\":\"stage\",\"over\":\"over\",\"under\":\"under\"}},\"controls\":{\"title\":\"SchemaControls\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"controls\"},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100}},\"description\":\"Deferred: renderer/host integration is not enabled in the current UI test build.\"},\"video\":{\"title\":\"SchemaVideo\",\"type\":\"object\",\"required\":[\"type\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"video\"},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"fit\":{\"enum\":[\"contain\",\"cover\"]}},\"description\":\"Deferred: renderer/host integration is not enabled in the current UI test build.\"},\"image\":{\"title\":\"SchemaImage\",\"type\":\"object\",\"required\":[\"type\",\"src\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"image\"},\"src\":{\"type\":\"string\",\"maxLength\":2048},\"fit\":{\"type\":\"string\",\"pattern\":\"^(contain|pan|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160},\"pan\":{\"oneOf\":[{\"type\":\"boolean\"},{\"type\":\"string\",\"pattern\":\"^\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\}\$\",\"maxLength\":160}]},\"tap\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z][A-Za-z0-9_.-]*\$\",\"maxLength\":64},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"interval\":{\"type\":[\"number\",\"string\"],\"minimum\":0.1,\"maximum\":86400,\"maxLength\":160},\"pan_speed\":{\"type\":[\"number\",\"string\"],\"minimum\":1,\"maximum\":120,\"maxLength\":160,\"description\":\"Maximum pan speed in dp/s; dwell is extended until the entire image has been shown.\"},\"pan_seconds\":{\"type\":[\"number\",\"string\"],\"minimum\":0.1,\"maximum\":120,\"maxLength\":160,\"description\":\"Minimum duration of a continuous eased sweep in seconds (default 12). Longer sweeps respect pan_speed; interval may include a stationary hold.\"},\"pan_direction\":{\"type\":\"string\",\"pattern\":\"^(random|forward|reverse|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160,\"description\":\"Random direction per photo by default. Forward follows reading order; reverse swaps both edges.\"}},\"description\":\"API 3. Local library files, plugin asset:assets/... or HTTP(S) with network permission. OS owns bounded downloads/cache and decoding. pan sweeps the entire image; contain displays the whole image at once. Continuous native panning, independently of scene tick cadence.\"},\"carousel\":{\"title\":\"SchemaCarousel\",\"type\":\"object\",\"required\":[\"type\",\"items\",\"interval\",\"duration\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"const\":\"carousel\"},\"items\":{\"type\":\"string\",\"maxLength\":160},\"interval\":{\"type\":[\"number\",\"string\"],\"minimum\":0.1,\"maximum\":86400,\"maxLength\":160},\"duration\":{\"type\":[\"number\",\"string\"],\"minimum\":0,\"maximum\":10000,\"maxLength\":160},\"transition\":{\"type\":\"string\",\"pattern\":\"^(fade|slide|zoom|random|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160},\"fit\":{\"type\":\"string\",\"pattern\":\"^(contain|pan|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160},\"pan\":{\"oneOf\":[{\"type\":\"boolean\"},{\"type\":\"string\",\"pattern\":\"^\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\}\$\",\"maxLength\":160}]},\"shuffle\":{\"oneOf\":[{\"type\":\"boolean\"},{\"type\":\"string\",\"pattern\":\"^\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\}\$\",\"maxLength\":160}]},\"weight\":{\"type\":\"number\",\"minimum\":0,\"maximum\":100},\"pan_speed\":{\"type\":[\"number\",\"string\"],\"minimum\":1,\"maximum\":120,\"maxLength\":160,\"description\":\"Maximum pan speed in dp/s; dwell is extended until the entire image has been shown.\"},\"caption\":{\"type\":\"string\",\"pattern\":\"^(auto|tap|off|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160},\"caption_hold\":{\"type\":[\"number\",\"string\"],\"minimum\":1,\"maximum\":60,\"maxLength\":160},\"pan_seconds\":{\"type\":[\"number\",\"string\"],\"minimum\":0.1,\"maximum\":120,\"maxLength\":160,\"description\":\"Minimum duration of a continuous eased sweep in seconds (default 12). Longer sweeps respect pan_speed; interval may include a stationary hold.\"},\"pan_direction\":{\"type\":\"string\",\"pattern\":\"^(random|forward|reverse|\\\\{\\\\{[A-Za-z][A-Za-z0-9_.]*\\\\}\\\\})\$\",\"maxLength\":160,\"description\":\"Random direction per photo by default. Forward follows reading order; reverse swaps both edges.\"}},\"description\":\"API 3. Local library files, plugin asset:assets/... or HTTP(S) with network permission. OS owns bounded downloads/cache and decoding. pan sweeps the entire image; contain displays the whole image at once. Continuous native panning, independently of scene tick cadence. Vertical drag browses adjacent items without wrapping; horizontal navigation and long press belong to the OS.\"}},\"x-thinkos-api\":{\"current\":3,\"componentTypes\":{\"text\":1,\"icon\":1,\"clock\":1,\"form\":1,\"progress\":2,\"chart\":2,\"list\":2,\"daylight\":2,\"moon\":2,\"image\":3,\"carousel\":3},\"layouts\":{\"stacks\":1,\"focus\":1,\"form\":1,\"split\":2,\"stage\":2},\"componentProperties\":{\"tap\":2,\"weight\":2},\"componentGroups\":2}}"),
        "manifest.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"manifest.json\",\"title\":\"SchemaManifest\",\"description\":\"ThinkOS plugin API 1 contract. Settings (ADR-017) replace the M0 'config' field; data-source and media capabilities arrive in later milestones.\",\"type\":\"object\",\"required\":[\"id\",\"name\",\"version\",\"api\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"name\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"version\":{\"type\":\"string\",\"pattern\":\"^[0-9]+\\\\.[0-9]+\\\\.[0-9]+(?:-[A-Za-z0-9.-]+)?\$\",\"maxLength\":48},\"api\":{\"type\":\"integer\",\"enum\":[1,2,3]},\"icon\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\$\",\"maxLength\":200},\"main\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\\\\.lua\$\",\"maxLength\":200},\"permissions\":{\"type\":\"array\",\"items\":{\"enum\":[\"network\",\"alert\",\"media\",\"library\",\"presence\",\"server\",\"system.settings\",\"location\"]},\"uniqueItems\":true,\"maxItems\":8},\"settings\":{\"type\":\"object\",\"maxProperties\":32,\"additionalProperties\":{\"\$ref\":\"#/\$defs/setting\"}},\"pages\":{\"type\":\"array\",\"maxItems\":16,\"items\":{\"\$ref\":\"#/\$defs/page\"}},\"widgets\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"\$ref\":\"#/\$defs/widget\"}},\"commands\":{\"type\":\"array\",\"maxItems\":32,\"items\":{\"\$ref\":\"#/\$defs/command\"}}},\"\$defs\":{\"page\":{\"title\":\"SchemaPage\",\"type\":\"object\",\"required\":[\"id\",\"view\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"view\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\$\",\"maxLength\":200},\"title\":{\"type\":\"string\",\"maxLength\":80},\"roles\":{\"type\":\"array\",\"items\":{\"enum\":[\"home\",\"ambient\",\"sleep\",\"settings\"]},\"uniqueItems\":true,\"maxItems\":4},\"priority\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},\"live\":{\"type\":\"boolean\"},\"variants\":{\"type\":\"array\",\"items\":{\"\$ref\":\"#/\$defs/variant\"},\"minItems\":1,\"maxItems\":16}}},\"widget\":{\"title\":\"SchemaWidget\",\"type\":\"object\",\"required\":[\"id\",\"view\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"view\":{\"type\":\"string\",\"pattern\":\"^(?!/)(?!.*(?:^|/)\\\\.\\\\.(?:/|\$))(?!.*\\\\\\\\)[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)*\$\",\"maxLength\":200},\"opens\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64}}},\"variant\":{\"title\":\"SchemaVariant\",\"type\":\"object\",\"required\":[\"id\",\"title\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"title\":{\"type\":\"string\",\"maxLength\":80}}},\"setting\":{\"title\":\"SchemaSetting\",\"type\":\"object\",\"required\":[\"type\",\"label\",\"default\"],\"additionalProperties\":false,\"properties\":{\"type\":{\"enum\":[\"bool\",\"choice\",\"number\",\"text\"]},\"label\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"default\":{\"type\":[\"string\",\"number\",\"boolean\"]},\"help\":{\"type\":\"string\",\"maxLength\":200},\"group\":{\"type\":\"string\",\"maxLength\":80},\"choices\":{\"type\":\"array\",\"minItems\":2,\"maxItems\":64,\"items\":{\"\$ref\":\"#/\$defs/settingChoice\"}},\"source\":{\"enum\":[\"timezone\",\"place\"]},\"allowEmpty\":{\"type\":\"boolean\"},\"min\":{\"type\":\"number\"},\"max\":{\"type\":\"number\"},\"step\":{\"type\":\"number\"},\"unit\":{\"type\":\"string\",\"maxLength\":16},\"maxLength\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1024},\"secret\":{\"type\":\"boolean\"},\"hidden\":{\"type\":\"boolean\"}}},\"settingChoice\":{\"title\":\"SchemaSettingChoice\",\"type\":\"object\",\"required\":[\"value\",\"label\"],\"additionalProperties\":false,\"properties\":{\"value\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9_.-]+\$\",\"minLength\":1,\"maxLength\":64},\"label\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80}}},\"command\":{\"title\":\"SchemaCommand\",\"type\":\"object\",\"required\":[\"id\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]*\$\",\"minLength\":1,\"maxLength\":64},\"phrases\":{\"type\":\"array\",\"maxItems\":16,\"items\":{\"type\":\"string\",\"maxLength\":80}},\"gesture\":{\"enum\":[\"wave\",\"left\",\"right\",\"fist\"]}}}}}"),
        "view.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"view.json\",\"description\":\"Page or widget declaration with API-versioned fixed OS vocabulary\",\"oneOf\":[{\"\$ref\":\"layouts.json#/\$defs/stacks\"},{\"\$ref\":\"layouts.json#/\$defs/focus\"},{\"\$ref\":\"layouts.json#/\$defs/glance\"},{\"\$ref\":\"layouts.json#/\$defs/formView\"},{\"\$ref\":\"layouts.json#/\$defs/split\"},{\"\$ref\":\"layouts.json#/\$defs/stage\"}]}")
    ) }

    fun validateIndex(value: JSONObject) {
        validate(value, documents.getValue("index.json"), "index.json", "index", 0)
    }
    fun validateCatalog(value: JSONObject) {
        validate(value, documents.getValue("catalog.json"), "catalog.json", "catalog", 0)
    }

    fun validateManifest(value: JSONObject) {
        validate(value, documents.getValue("manifest.json"), "manifest.json", "manifest", 0)
        for (name in listOf("pages", "widgets", "commands")) {
            val entries = value.optJSONArray(name) ?: continue
            val ids = HashSet<String>()
            for (i in 0 until entries.length()) require(ids.add(entries.getJSONObject(i).getString("id"))) { "$name: duplicate id" }
        }
        require(value.getString("id") != "system") { "manifest.id: 'system' is reserved for the OS's own settings" }
        value.optJSONObject("settings")?.let { declared ->
            // Semantic rules live in one place, shared with the runtime store.
            for (key in declared.keys()) moe.kiwi.thinkos.settings.SettingField.parse(key, declared.getJSONObject(key), false)
        }
        val pages = value.optJSONArray("pages")
        val pageIds = (0 until (pages?.length() ?: 0)).map { pages!!.getJSONObject(it).getString("id") }.toSet()
        value.optJSONArray("widgets")?.let { widgets ->
            for (i in 0 until widgets.length()) {
                val widget = widgets.getJSONObject(i)
                require(!widget.has("opens") || widget.getString("opens") in pageIds) { "widget opens unknown page" }
            }
        }
    }

    fun validateView(value: JSONObject, api: Int = API) {
        require(api in 1..API) { "unsupported plugin API" }
        validate(value, documents.getValue("view.json"), "view.json", "view", 0)
        fun gate(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) gate(value.opt(i))
                is JSONObject -> {
                    if (value.has("layout")) require(renderApis.getJSONObject("layouts").optInt(value.getString("layout"), API + 1) <= api) { "layout requires a newer plugin API" }
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
                if (schema.has("pattern")) require(Regex(schema.getString("pattern")).containsMatchIn(value)) { "$path: invalid string format" }
            }
            is Number -> require(value.toDouble() >= schema.optDouble("minimum", Double.NEGATIVE_INFINITY) && value.toDouble() <= schema.optDouble("maximum", Double.POSITIVE_INFINITY)) { "$path: number outside allowed range" }
        }
    }

    private fun equivalent(a: Any?, b: Any?): Boolean =
        if (a is Number && b is Number) a.toDouble() == b.toDouble() else a == b
}
