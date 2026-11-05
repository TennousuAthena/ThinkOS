// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_kotlin.py.
package moe.kiwi.thinkos.update

import org.json.JSONArray
import org.json.JSONObject

data class RegistryCache(
    val `schema`: Int,
    val `document`: String,
    val `signature`: String
)

data class RegistryCatalog(
    val `schema`: Int,
    val `seq`: Long,
    val `generated`: Long,
    val `expires`: Long,
    val `blob_bases`: List<String>,
    val `revoked`: List<RegistryRevocation>,
    val `plugins`: List<RegistryPlugin>
)

data class RegistryReasons(
    val `network`: Map<String, String>? = null,
    val `alert`: Map<String, String>? = null,
    val `media`: Map<String, String>? = null,
    val `library`: Map<String, String>? = null,
    val `presence`: Map<String, String>? = null,
    val `server`: Map<String, String>? = null,
    val `location`: Map<String, String>? = null
)

data class RegistryRelease(
    val `version`: String,
    val `commit`: String,
    val `digest`: String,
    val `api`: Int,
    val `size`: Long,
    val `permissions`: List<String>,
    val `why`: RegistryReasons,
    val `apps`: List<String>
)

data class RegistryPlugin(
    val `id`: String,
    val `tier`: String,
    val `name`: Map<String, String>,
    val `summary`: Map<String, String>,
    val `author`: String,
    val `repo`: String,
    val `license`: String,
    val `tags`: List<String>,
    val `icon`: String? = null,
    val `screenshots`: List<String>? = null,
    val `releases`: List<RegistryRelease>
)

data class RegistryRevocation(
    val `id`: String,
    val `digest`: String,
    val `reason`: String
)

data class RegistryFiles(
    val `id`: String,
    val `version`: String,
    val `digest`: String,
    val `files`: List<RegistryBlob>
)

data class RegistryBlob(
    val `path`: String,
    val `sha256`: String,
    val `size`: Int
)

data class RegistryLedger(
    val `schema`: Int,
    val `seq`: Long,
    val `digest`: String
)

data class RegistryState(
    val `schema`: Int,
    val `disabled`: Boolean,
    val `installed`: List<RegistryInstalled>,
    val `blocked`: List<RegistryBlocked>
)

data class RegistryPrevious(
    val `version`: String,
    val `digest`: String,
    val `commit`: String,
    val `repo`: String,
    val `tier`: String,
    val `name`: String,
    val `confirmed`: List<String>
)

data class RegistryInstalled(
    val `id`: String,
    val `version`: String,
    val `digest`: String,
    val `commit`: String,
    val `repo`: String,
    val `tier`: String,
    val `name`: String,
    val `confirmed`: List<String>,
    val `auto`: Boolean,
    val `enabled`: Boolean,
    val `trial`: Boolean,
    val `error`: String? = null,
    val `previous`: RegistryPrevious? = null
)

data class RegistryBlocked(
    val `id`: String,
    val `digest`: String,
    val `reason`: String
)

internal object RegistryModels {
    private val documents by lazy { mapOf(
        "cache.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"cache.json\",\"title\":\"RegistryCache\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"schema\",\"document\",\"signature\"],\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"document\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1398104},\"signature\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1368}}}"),
        "catalog.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"catalog.json\",\"title\":\"RegistryCatalog\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"schema\",\"seq\",\"generated\",\"expires\",\"blob_bases\",\"revoked\",\"plugins\"],\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"seq\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":1,\"maximum\":9007199254740991},\"generated\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":9007199254740991},\"expires\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":1,\"maximum\":9007199254740991},\"blob_bases\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048}},\"revoked\":{\"type\":\"array\",\"maxItems\":512,\"items\":{\"\$ref\":\"#/\$defs/revoked\"}},\"plugins\":{\"type\":\"array\",\"maxItems\":256,\"items\":{\"\$ref\":\"#/\$defs/plugin\"}}},\"\$defs\":{\"local\":{\"type\":\"object\",\"minProperties\":1,\"additionalProperties\":false,\"properties\":{\"zh-Hans\":{\"type\":\"string\",\"maxLength\":256},\"zh-Hant\":{\"type\":\"string\",\"maxLength\":256},\"en\":{\"type\":\"string\",\"maxLength\":256},\"es\":{\"type\":\"string\",\"maxLength\":256},\"fr\":{\"type\":\"string\",\"maxLength\":256},\"pt\":{\"type\":\"string\",\"maxLength\":256},\"ru\":{\"type\":\"string\",\"maxLength\":256},\"ar\":{\"type\":\"string\",\"maxLength\":256},\"hi\":{\"type\":\"string\",\"maxLength\":256},\"bn\":{\"type\":\"string\",\"maxLength\":256},\"ur\":{\"type\":\"string\",\"maxLength\":256}}},\"why\":{\"title\":\"RegistryReasons\",\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"network\":{\"\$ref\":\"#/\$defs/local\"},\"alert\":{\"\$ref\":\"#/\$defs/local\"},\"media\":{\"\$ref\":\"#/\$defs/local\"},\"library\":{\"\$ref\":\"#/\$defs/local\"},\"presence\":{\"\$ref\":\"#/\$defs/local\"},\"server\":{\"\$ref\":\"#/\$defs/local\"},\"location\":{\"\$ref\":\"#/\$defs/local\"}}},\"release\":{\"title\":\"RegistryRelease\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"version\",\"commit\",\"digest\",\"api\",\"size\",\"permissions\",\"why\",\"apps\"],\"properties\":{\"version\":{\"type\":\"string\",\"pattern\":\"^[0-9]+\\\\.[0-9]+\\\\.[0-9]+(?:-[A-Za-z0-9.-]+)?\$\",\"maxLength\":48},\"commit\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{40}\$\"},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"api\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":1000},\"size\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":1,\"maximum\":8388608},\"permissions\":{\"type\":\"array\",\"maxItems\":7,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"enum\":[\"network\",\"alert\",\"media\",\"library\",\"presence\",\"server\",\"location\"]}},\"why\":{\"\$ref\":\"#/\$defs/why\"},\"apps\":{\"type\":\"array\",\"maxItems\":16,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]{0,63}\$\"}}}},\"plugin\":{\"title\":\"RegistryPlugin\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\",\"tier\",\"name\",\"summary\",\"author\",\"repo\",\"license\",\"tags\",\"releases\"],\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]{0,63}\$\"},\"tier\":{\"type\":\"string\",\"enum\":[\"official\",\"reviewed\",\"community\"]},\"name\":{\"\$ref\":\"#/\$defs/local\"},\"summary\":{\"\$ref\":\"#/\$defs/local\"},\"author\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"repo\":{\"type\":\"string\",\"pattern\":\"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\$\",\"maxLength\":200},\"license\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":80},\"tags\":{\"type\":\"array\",\"maxItems\":16,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"maxLength\":40}},\"icon\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"screenshots\":{\"type\":\"array\",\"maxItems\":8,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"}},\"releases\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":3,\"items\":{\"\$ref\":\"#/\$defs/release\"}}}},\"revoked\":{\"title\":\"RegistryRevocation\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\",\"digest\",\"reason\"],\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]{0,63}\$\"},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"reason\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":160}}}}}"),
        "files.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"files.json\",\"title\":\"RegistryFiles\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\",\"version\",\"digest\",\"files\"],\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]{0,63}\$\"},\"version\":{\"type\":\"string\",\"pattern\":\"^[0-9]+\\\\.[0-9]+\\\\.[0-9]+(?:-[A-Za-z0-9.-]+)?\$\",\"maxLength\":48},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"files\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":128,\"items\":{\"\$ref\":\"#/\$defs/file\"}}},\"\$defs\":{\"file\":{\"title\":\"RegistryBlob\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"path\",\"sha256\",\"size\"],\"properties\":{\"path\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":512},\"sha256\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"size\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":1048576}}}}}"),
        "ledger.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"ledger.json\",\"title\":\"RegistryLedger\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"schema\",\"seq\",\"digest\"],\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"seq\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":1,\"maximum\":9007199254740991},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"}}}"),
        "state.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"state.json\",\"title\":\"RegistryState\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"schema\",\"disabled\",\"installed\",\"blocked\"],\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"disabled\":{\"type\":\"boolean\"},\"installed\":{\"type\":\"array\",\"maxItems\":16,\"items\":{\"\$ref\":\"#/\$defs/record\"}},\"blocked\":{\"type\":\"array\",\"maxItems\":256,\"items\":{\"\$ref\":\"#/\$defs/block\"}}},\"\$defs\":{\"previous\":{\"title\":\"RegistryPrevious\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"version\",\"digest\",\"commit\",\"repo\",\"tier\",\"name\",\"confirmed\"],\"properties\":{\"version\":{\"type\":\"string\",\"maxLength\":48,\"pattern\":\"^[0-9]+\\\\.[0-9]+\\\\.[0-9]+(?:-[A-Za-z0-9.-]+)?\$\"},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"commit\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{40}\$\"},\"repo\":{\"type\":\"string\",\"maxLength\":200,\"pattern\":\"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\$\"},\"tier\":{\"type\":\"string\",\"enum\":[\"official\",\"reviewed\",\"community\"]},\"name\":{\"type\":\"string\",\"maxLength\":256},\"confirmed\":{\"type\":\"array\",\"maxItems\":7,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"maxLength\":40,\"enum\":[\"network\",\"alert\",\"media\",\"library\",\"presence\",\"server\",\"location\"]}}}},\"record\":{\"title\":\"RegistryInstalled\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\",\"version\",\"digest\",\"commit\",\"repo\",\"tier\",\"name\",\"confirmed\",\"auto\",\"enabled\",\"trial\"],\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]{0,63}\$\"},\"version\":{\"type\":\"string\",\"maxLength\":48,\"pattern\":\"^[0-9]+\\\\.[0-9]+\\\\.[0-9]+(?:-[A-Za-z0-9.-]+)?\$\"},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"commit\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{40}\$\"},\"repo\":{\"type\":\"string\",\"maxLength\":200,\"pattern\":\"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+\$\"},\"tier\":{\"type\":\"string\",\"enum\":[\"official\",\"reviewed\",\"community\"]},\"name\":{\"type\":\"string\",\"maxLength\":256},\"confirmed\":{\"type\":\"array\",\"maxItems\":7,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"maxLength\":40,\"enum\":[\"network\",\"alert\",\"media\",\"library\",\"presence\",\"server\",\"location\"]}},\"auto\":{\"type\":\"boolean\"},\"enabled\":{\"type\":\"boolean\"},\"trial\":{\"type\":\"boolean\"},\"error\":{\"type\":\"string\",\"maxLength\":160},\"previous\":{\"\$ref\":\"#/\$defs/previous\"}}},\"block\":{\"title\":\"RegistryBlocked\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\",\"digest\",\"reason\"],\"properties\":{\"id\":{\"type\":\"string\",\"pattern\":\"^[a-z][a-z0-9-]{0,63}\$\"},\"digest\":{\"type\":\"string\",\"pattern\":\"^[0-9a-f]{64}\$\"},\"reason\":{\"type\":\"string\",\"maxLength\":160}}}}}")
    ) }

    fun readRegistryCache(value: JSONObject): RegistryCache {
        UpdateSchemaValidator.validate(value, documents.getValue("cache.json"), "cache.json", documents)
        return parseRegistryCache(value)
    }

    fun writeRegistryCache(value: RegistryCache): JSONObject = buildRegistryCache(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("cache.json"), "cache.json", documents)
    }

    fun readRegistryCatalog(value: JSONObject): RegistryCatalog {
        UpdateSchemaValidator.validate(value, documents.getValue("catalog.json"), "catalog.json", documents)
        return parseRegistryCatalog(value)
    }

    fun writeRegistryCatalog(value: RegistryCatalog): JSONObject = buildRegistryCatalog(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("catalog.json"), "catalog.json", documents)
    }

    fun readRegistryFiles(value: JSONObject): RegistryFiles {
        UpdateSchemaValidator.validate(value, documents.getValue("files.json"), "files.json", documents)
        return parseRegistryFiles(value)
    }

    fun writeRegistryFiles(value: RegistryFiles): JSONObject = buildRegistryFiles(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("files.json"), "files.json", documents)
    }

    fun readRegistryLedger(value: JSONObject): RegistryLedger {
        UpdateSchemaValidator.validate(value, documents.getValue("ledger.json"), "ledger.json", documents)
        return parseRegistryLedger(value)
    }

    fun writeRegistryLedger(value: RegistryLedger): JSONObject = buildRegistryLedger(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("ledger.json"), "ledger.json", documents)
    }

    fun readRegistryState(value: JSONObject): RegistryState {
        UpdateSchemaValidator.validate(value, documents.getValue("state.json"), "state.json", documents)
        return parseRegistryState(value)
    }

    fun writeRegistryState(value: RegistryState): JSONObject = buildRegistryState(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("state.json"), "state.json", documents)
    }

    private fun parseRegistryCache(value: JSONObject): RegistryCache = RegistryCache(
        `schema` = (value.get("schema") as Long).toInt(),
        `document` = (value.get("document") as String),
        `signature` = (value.get("signature") as String)
    )

    private fun buildRegistryCache(value: RegistryCache): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("document", value.`document`)
        put("signature", value.`signature`)
    }

    private fun parseRegistryCatalog(value: JSONObject): RegistryCatalog = RegistryCatalog(
        `schema` = (value.get("schema") as Long).toInt(),
        `seq` = (value.get("seq") as Long),
        `generated` = (value.get("generated") as Long),
        `expires` = (value.get("expires") as Long),
        `blob_bases` = list(value.get("blob_bases") as JSONArray) { item -> (item as String) },
        `revoked` = list(value.get("revoked") as JSONArray) { item -> parseRegistryRevocation(item as JSONObject) },
        `plugins` = list(value.get("plugins") as JSONArray) { item -> parseRegistryPlugin(item as JSONObject) }
    )

    private fun buildRegistryCatalog(value: RegistryCatalog): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("seq", value.`seq`)
        put("generated", value.`generated`)
        put("expires", value.`expires`)
        put("blob_bases", JSONArray().apply { value.`blob_bases`.forEach { item -> put(item) } })
        put("revoked", JSONArray().apply { value.`revoked`.forEach { item -> put(buildRegistryRevocation(item)) } })
        put("plugins", JSONArray().apply { value.`plugins`.forEach { item -> put(buildRegistryPlugin(item)) } })
    }

    private fun parseRegistryReasons(value: JSONObject): RegistryReasons = RegistryReasons(
        `network` = if (value.has("network")) stringMap(value.get("network") as JSONObject) else null,
        `alert` = if (value.has("alert")) stringMap(value.get("alert") as JSONObject) else null,
        `media` = if (value.has("media")) stringMap(value.get("media") as JSONObject) else null,
        `library` = if (value.has("library")) stringMap(value.get("library") as JSONObject) else null,
        `presence` = if (value.has("presence")) stringMap(value.get("presence") as JSONObject) else null,
        `server` = if (value.has("server")) stringMap(value.get("server") as JSONObject) else null,
        `location` = if (value.has("location")) stringMap(value.get("location") as JSONObject) else null
    )

    private fun buildRegistryReasons(value: RegistryReasons): JSONObject = JSONObject().apply {
        if (value.`network` != null) put("network", JSONObject(value.`network`))
        if (value.`alert` != null) put("alert", JSONObject(value.`alert`))
        if (value.`media` != null) put("media", JSONObject(value.`media`))
        if (value.`library` != null) put("library", JSONObject(value.`library`))
        if (value.`presence` != null) put("presence", JSONObject(value.`presence`))
        if (value.`server` != null) put("server", JSONObject(value.`server`))
        if (value.`location` != null) put("location", JSONObject(value.`location`))
    }

    private fun parseRegistryRelease(value: JSONObject): RegistryRelease = RegistryRelease(
        `version` = (value.get("version") as String),
        `commit` = (value.get("commit") as String),
        `digest` = (value.get("digest") as String),
        `api` = (value.get("api") as Long).toInt(),
        `size` = (value.get("size") as Long),
        `permissions` = list(value.get("permissions") as JSONArray) { item -> (item as String) },
        `why` = parseRegistryReasons(value.get("why") as JSONObject),
        `apps` = list(value.get("apps") as JSONArray) { item -> (item as String) }
    )

    private fun buildRegistryRelease(value: RegistryRelease): JSONObject = JSONObject().apply {
        put("version", value.`version`)
        put("commit", value.`commit`)
        put("digest", value.`digest`)
        put("api", value.`api`.toLong())
        put("size", value.`size`)
        put("permissions", JSONArray().apply { value.`permissions`.forEach { item -> put(item) } })
        put("why", buildRegistryReasons(value.`why`))
        put("apps", JSONArray().apply { value.`apps`.forEach { item -> put(item) } })
    }

    private fun parseRegistryPlugin(value: JSONObject): RegistryPlugin = RegistryPlugin(
        `id` = (value.get("id") as String),
        `tier` = (value.get("tier") as String),
        `name` = stringMap(value.get("name") as JSONObject),
        `summary` = stringMap(value.get("summary") as JSONObject),
        `author` = (value.get("author") as String),
        `repo` = (value.get("repo") as String),
        `license` = (value.get("license") as String),
        `tags` = list(value.get("tags") as JSONArray) { item -> (item as String) },
        `icon` = if (value.has("icon")) (value.get("icon") as String) else null,
        `screenshots` = if (value.has("screenshots")) list(value.get("screenshots") as JSONArray) { item -> (item as String) } else null,
        `releases` = list(value.get("releases") as JSONArray) { item -> parseRegistryRelease(item as JSONObject) }
    )

    private fun buildRegistryPlugin(value: RegistryPlugin): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("tier", value.`tier`)
        put("name", JSONObject(value.`name`))
        put("summary", JSONObject(value.`summary`))
        put("author", value.`author`)
        put("repo", value.`repo`)
        put("license", value.`license`)
        put("tags", JSONArray().apply { value.`tags`.forEach { item -> put(item) } })
        if (value.`icon` != null) put("icon", value.`icon`)
        if (value.`screenshots` != null) put("screenshots", JSONArray().apply { value.`screenshots`.forEach { item -> put(item) } })
        put("releases", JSONArray().apply { value.`releases`.forEach { item -> put(buildRegistryRelease(item)) } })
    }

    private fun parseRegistryRevocation(value: JSONObject): RegistryRevocation = RegistryRevocation(
        `id` = (value.get("id") as String),
        `digest` = (value.get("digest") as String),
        `reason` = (value.get("reason") as String)
    )

    private fun buildRegistryRevocation(value: RegistryRevocation): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("digest", value.`digest`)
        put("reason", value.`reason`)
    }

    private fun parseRegistryFiles(value: JSONObject): RegistryFiles = RegistryFiles(
        `id` = (value.get("id") as String),
        `version` = (value.get("version") as String),
        `digest` = (value.get("digest") as String),
        `files` = list(value.get("files") as JSONArray) { item -> parseRegistryBlob(item as JSONObject) }
    )

    private fun buildRegistryFiles(value: RegistryFiles): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("version", value.`version`)
        put("digest", value.`digest`)
        put("files", JSONArray().apply { value.`files`.forEach { item -> put(buildRegistryBlob(item)) } })
    }

    private fun parseRegistryBlob(value: JSONObject): RegistryBlob = RegistryBlob(
        `path` = (value.get("path") as String),
        `sha256` = (value.get("sha256") as String),
        `size` = (value.get("size") as Long).toInt()
    )

    private fun buildRegistryBlob(value: RegistryBlob): JSONObject = JSONObject().apply {
        put("path", value.`path`)
        put("sha256", value.`sha256`)
        put("size", value.`size`.toLong())
    }

    private fun parseRegistryLedger(value: JSONObject): RegistryLedger = RegistryLedger(
        `schema` = (value.get("schema") as Long).toInt(),
        `seq` = (value.get("seq") as Long),
        `digest` = (value.get("digest") as String)
    )

    private fun buildRegistryLedger(value: RegistryLedger): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("seq", value.`seq`)
        put("digest", value.`digest`)
    }

    private fun parseRegistryState(value: JSONObject): RegistryState = RegistryState(
        `schema` = (value.get("schema") as Long).toInt(),
        `disabled` = (value.get("disabled") as Boolean),
        `installed` = list(value.get("installed") as JSONArray) { item -> parseRegistryInstalled(item as JSONObject) },
        `blocked` = list(value.get("blocked") as JSONArray) { item -> parseRegistryBlocked(item as JSONObject) }
    )

    private fun buildRegistryState(value: RegistryState): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("disabled", value.`disabled`)
        put("installed", JSONArray().apply { value.`installed`.forEach { item -> put(buildRegistryInstalled(item)) } })
        put("blocked", JSONArray().apply { value.`blocked`.forEach { item -> put(buildRegistryBlocked(item)) } })
    }

    private fun parseRegistryPrevious(value: JSONObject): RegistryPrevious = RegistryPrevious(
        `version` = (value.get("version") as String),
        `digest` = (value.get("digest") as String),
        `commit` = (value.get("commit") as String),
        `repo` = (value.get("repo") as String),
        `tier` = (value.get("tier") as String),
        `name` = (value.get("name") as String),
        `confirmed` = list(value.get("confirmed") as JSONArray) { item -> (item as String) }
    )

    private fun buildRegistryPrevious(value: RegistryPrevious): JSONObject = JSONObject().apply {
        put("version", value.`version`)
        put("digest", value.`digest`)
        put("commit", value.`commit`)
        put("repo", value.`repo`)
        put("tier", value.`tier`)
        put("name", value.`name`)
        put("confirmed", JSONArray().apply { value.`confirmed`.forEach { item -> put(item) } })
    }

    private fun parseRegistryInstalled(value: JSONObject): RegistryInstalled = RegistryInstalled(
        `id` = (value.get("id") as String),
        `version` = (value.get("version") as String),
        `digest` = (value.get("digest") as String),
        `commit` = (value.get("commit") as String),
        `repo` = (value.get("repo") as String),
        `tier` = (value.get("tier") as String),
        `name` = (value.get("name") as String),
        `confirmed` = list(value.get("confirmed") as JSONArray) { item -> (item as String) },
        `auto` = (value.get("auto") as Boolean),
        `enabled` = (value.get("enabled") as Boolean),
        `trial` = (value.get("trial") as Boolean),
        `error` = if (value.has("error")) (value.get("error") as String) else null,
        `previous` = if (value.has("previous")) parseRegistryPrevious(value.get("previous") as JSONObject) else null
    )

    private fun buildRegistryInstalled(value: RegistryInstalled): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("version", value.`version`)
        put("digest", value.`digest`)
        put("commit", value.`commit`)
        put("repo", value.`repo`)
        put("tier", value.`tier`)
        put("name", value.`name`)
        put("confirmed", JSONArray().apply { value.`confirmed`.forEach { item -> put(item) } })
        put("auto", value.`auto`)
        put("enabled", value.`enabled`)
        put("trial", value.`trial`)
        if (value.`error` != null) put("error", value.`error`)
        if (value.`previous` != null) put("previous", buildRegistryPrevious(value.`previous`))
    }

    private fun parseRegistryBlocked(value: JSONObject): RegistryBlocked = RegistryBlocked(
        `id` = (value.get("id") as String),
        `digest` = (value.get("digest") as String),
        `reason` = (value.get("reason") as String)
    )

    private fun buildRegistryBlocked(value: RegistryBlocked): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("digest", value.`digest`)
        put("reason", value.`reason`)
    }

    private fun stringMap(value: JSONObject): Map<String, String> = java.util.Collections.unmodifiableMap(value.keys().asSequence().associateWith { value.getString(it) })
    private fun <T> list(value: JSONArray, read: (Any) -> T): List<T> = java.util.Collections.unmodifiableList((0 until value.length()).map { read(value.get(it)) })
}
