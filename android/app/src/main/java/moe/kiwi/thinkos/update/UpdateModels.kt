// GENERATED - DO NOT EDIT. Run python3 shared/gen/gen_kotlin.py.
package moe.kiwi.thinkos.update

import org.json.JSONArray
import org.json.JSONObject

data class UpdateCachedCatalogDocument(
    val `schema`: Int,
    val `channel`: String,
    val `body`: String,
    val `signature`: String,
    val `source`: String,
    val `etag`: String? = null
)

data class UpdateCatalogDocument(
    val `schema`: Int,
    val `channel`: String,
    val `seq`: Long,
    val `published`: Long,
    val `expires`: Long,
    val `release`: UpdateRelease,
    val `mirrors`: List<String>? = null
)

data class UpdateRelease(
    val `version`: String,
    val `code`: Int,
    val `api`: Int,
    val `device`: List<String>,
    val `sdk`: Int,
    val `sha256`: String,
    val `size`: Long,
    val `signer`: String,
    val `urls`: List<String>,
    val `modules`: List<UpdateModule>,
    val `notes`: Map<String, String>,
    val `source`: String
)

data class UpdateModule(
    val `id`: String,
    val `kind`: String,
    val `version`: String,
    val `code`: Int,
    val `policy`: String,
    val `sha256`: String,
    val `size`: Long,
    val `urls`: List<String>,
    val `manifest_urls`: List<String>
)

data class UpdateDebugKeyDocument(
    val `schema`: Int,
    val `test`: UpdatePublicKey
)

data class UpdateFailureDocument(
    val `schema`: Int,
    val `entry`: UpdateInstallFailure
)

data class UpdateInstallHistory(
    val `schema`: Int,
    val `failures`: List<UpdateInstallFailure>,
    val `result`: UpdateInstallResult? = null
)

data class UpdateInstallFailure(
    val `code`: Int,
    val `version`: String,
    val `count`: Int,
    val `blocked`: Boolean,
    val `last_transaction`: String,
    val `reason`: String
)

data class UpdateInstallResult(
    val `transaction_id`: String,
    val `kind`: String,
    val `code`: Int,
    val `version`: String,
    val `recorded`: UpdateInstallTime,
    val `notice_ms`: Long,
    val `notes`: Map<String, String>,
    val `reason`: String? = null,
    val `needs_recovery_clear`: Boolean
)

data class UpdateRepairRecord(
    val `schema`: Int,
    val `id`: String,
    val `state`: String,
    val `current`: UpdateApkIdentity,
    val `pending`: UpdateRepairFile,
    val `previous`: UpdateRepairFile? = null,
    val `history`: UpdateRepairFile? = null,
    val `root_state`: String,
    val `recorded`: UpdateInstallTime,
    val `bound_transaction`: String? = null,
    val `prior_marker`: UpdateRepairFile? = null
)

data class UpdateRepairFile(
    val `size`: Long,
    val `sha256`: String,
    val `key`: String,
    val `modified`: String
)

data class UpdateKeyDocument(
    val `schema`: Int,
    val `channel`: UpdateKeyPair,
    val `registry`: UpdateKeyPair
)

data class UpdatePublicKey(
    val `id`: String,
    val `algorithm`: String,
    val `spki`: String
)

data class UpdateKeyPair(
    val `active`: UpdatePublicKey,
    val `backup`: UpdatePublicKey
)

data class ModuleUpdatePending(
    val `schema`: Int,
    val `id`: String,
    val `root_id`: String,
    val `previous_root_id`: String,
    val `catalog_digest`: String,
    val `channel`: String,
    val `from`: UpdateApkIdentity,
    val `to`: UpdateApkIdentity,
    val `mode`: String,
    val `phase`: String,
    val `operation`: String,
    val `operation_id`: String? = null,
    val `selected`: List<ModuleUpdateTarget>,
    val `staged`: List<String>,
    val `created`: UpdateInstallTime,
    val `attempts`: Int,
    val `max_attempts`: Int,
    val `health_ms`: Long,
    val `poll_ms`: Long,
    val `reboot_boot`: Long? = null,
    val `trial_id`: String? = null,
    val `trial_started`: UpdateInstallTime? = null,
    val `reason`: String? = null,
    val `feature`: String? = null
)

data class ModuleUpdateTarget(
    val `id`: String,
    val `code`: Int
)

data class UpdatePending(
    val `schema`: Int,
    val `transaction_id`: String,
    val `phase`: String,
    val `channel`: String,
    val `catalog_digest`: String,
    val `seq`: Long,
    val `from`: UpdateApkIdentity,
    val `to`: UpdateApkIdentity,
    val `mode`: String,
    val `attempts`: Int,
    val `max_attempts`: Int,
    val `health_ms`: Long,
    val `notice_ms`: Long,
    val `failure_limit`: Int,
    val `created`: UpdateInstallTime,
    val `notes`: Map<String, String>,
    val `session_id`: Int? = null,
    val `rollback_session_id`: Int? = null,
    val `trial_id`: String? = null,
    val `trial_started`: UpdateInstallTime? = null
)

data class UpdateApkIdentity(
    val `version`: String,
    val `code`: Int,
    val `sha256`: String,
    val `size`: Long,
    val `signer`: String
)

data class UpdateInstallTime(
    val `elapsed`: Long,
    val `boot`: Long? = null,
    val `wall`: Long? = null
)

data class UpdateSequenceDocument(
    val `schema`: Int,
    val `stable`: UpdateSequence? = null,
    val `beta`: UpdateSequence? = null
)

data class UpdateSequence(
    val `seq`: Long,
    val `digest`: String
)

data class UpdateStateDocument(
    val `schema`: Int,
    val `channel`: String,
    val `phase`: String,
    val `downloaded`: Long,
    val `size`: Long,
    val `seq`: Long? = null,
    val `catalog_digest`: String? = null,
    val `error`: String? = null,
    val `ready_elapsed`: Long? = null,
    val `ready_boot`: Long? = null,
    val `ready_wall`: Long? = null
)

data class UpdateStatusDocument(
    val `schema`: Int,
    val `phase`: String,
    val `channel`: String,
    val `current_version`: String,
    val `current_code`: Int,
    val `configured`: Boolean,
    val `can_check`: Boolean,
    val `can_download`: Boolean,
    val `can_install`: Boolean,
    val `downloaded`: Long,
    val `size`: Long,
    val `components`: List<UpdateComponentStatus>,
    val `version`: String? = null,
    val `code`: Int? = null,
    val `notes`: String? = null,
    val `error`: String? = null,
    val `blocked`: String? = null,
    val `silent_install`: Boolean? = null,
    val `result`: UpdateResultStatus? = null,
    val `inventory`: UpdateSystemInventory? = null
)

data class UpdateComponentStatus(
    val `id`: String,
    val `version`: String,
    val `code`: Int,
    val `state`: String,
    val `installed_code`: Int? = null,
    val `downloaded`: Long? = null,
    val `size`: Long? = null,
    val `result`: String? = null
)

data class UpdateResultStatus(
    val `kind`: String,
    val `version`: String,
    val `code`: Int,
    val `message`: String,
    val `notes`: String,
    val `reason`: String? = null
)

data class UpdateSystemInventory(
    val `updater`: String,
    val `modules`: List<UpdateInstalledComponent>
)

data class UpdateInstalledComponent(
    val `id`: String,
    val `installed_code`: Int? = null
)

data class UpdateSystemKeyDocument(
    val `schema`: Int,
    val `active`: UpdatePublicKey,
    val `backup`: UpdatePublicKey
)

data class UpdaterStatusDocument(
    val `protocol`: Int,
    val `keys`: Int,
    val `transaction`: Boolean,
    val `id`: String,
    val `phase`: Int,
    val `result`: Int,
    val `rollback_attempted`: Boolean,
    val `modules`: List<UpdaterModuleStatus>,
    val `setup`: UpdaterSetupCapabilities? = null
)

data class UpdaterModuleStatus(
    val `id`: String,
    val `code`: Int,
    val `floor`: Int,
    val `staged`: Int,
    val `active`: Boolean? = null
)

data class UpdaterSetupCapabilities(
    val `timezone`: Boolean,
    val `language`: Boolean,
    val `device_owner`: Boolean
)

internal object UpdateModels {
    private val documents by lazy { mapOf(
        "cache.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"cache.json\",\"title\":\"UpdateCachedCatalogDocument\",\"description\":\"Private atomic cache of original signed catalog bytes. Body/signature are canonical Base64; every read reauthenticates against APK-owned keys and the independent sequence floor. Source and ETag are transport hints, never trust roots.\",\"type\":\"object\",\"required\":[\"schema\",\"channel\",\"body\",\"signature\",\"source\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"channel\":{\"type\":\"string\",\"enum\":[\"stable\",\"beta\"]},\"body\":{\"type\":\"string\",\"minLength\":4,\"maxLength\":1398104,\"pattern\":\"^[A-Za-z0-9+/]+={0,2}\$\"},\"signature\":{\"type\":\"string\",\"minLength\":4,\"maxLength\":1368,\"pattern\":\"^[A-Za-z0-9+/]+={0,2}\$\"},\"source\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048},\"etag\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":1024,\"pattern\":\"^[ -~]+\$\"}}}"),
        "catalog.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"catalog.json\",\"title\":\"UpdateCatalogDocument\",\"type\":\"object\",\"required\":[\"schema\",\"channel\",\"seq\",\"published\",\"expires\",\"release\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"channel\":{\"type\":\"string\",\"enum\":[\"stable\",\"beta\"]},\"seq\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9223372036854775807,\"format\":\"int64\"},\"published\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9223372036854775807,\"format\":\"int64\"},\"expires\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9223372036854775807,\"format\":\"int64\"},\"release\":{\"\$ref\":\"#/\$defs/release\"},\"mirrors\":{\"type\":\"array\",\"maxItems\":8,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048,\"pattern\":\"^https?://\"},\"description\":\"Further URLs of THIS channel's catalog (each with its .sig next to it). The device keeps the list from the newest authenticated catalog and asks those sources after the configured ones, so a mirror can be added or replaced without shipping a new APK. Every response is still authenticated with the channel keys and the sequence floor.\"}},\"\$defs\":{\"release\":{\"title\":\"UpdateRelease\",\"type\":\"object\",\"required\":[\"version\",\"code\",\"api\",\"device\",\"sdk\",\"sha256\",\"size\",\"signer\",\"urls\",\"modules\",\"notes\",\"source\"],\"additionalProperties\":false,\"properties\":{\"version\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":16,\"pattern\":\"^(0|[1-9][0-9]{0,3})\\\\.(0|[1-9][0-9]{0,2})\\\\.(0|[1-9][0-9]{0,2})\$\"},\"code\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},\"api\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},\"device\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64,\"pattern\":\"^[a-z][a-z0-9_-]{0,63}\$\"},\"uniqueItems\":true},\"sdk\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},\"sha256\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"},\"size\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":67108864,\"format\":\"int64\"},\"signer\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"},\"urls\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048,\"pattern\":\"^https?://\"},\"uniqueItems\":true},\"modules\":{\"type\":\"array\",\"minItems\":0,\"maxItems\":7,\"items\":{\"\$ref\":\"#/\$defs/module\"}},\"notes\":{\"type\":\"object\",\"minProperties\":1,\"maxProperties\":11,\"additionalProperties\":false,\"properties\":{\"zh-Hans\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"zh-Hant\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"en\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"es\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"fr\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"pt\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"ru\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"ar\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"hi\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"bn\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048},\"ur\":{\"type\":\"string\",\"minLength\":0,\"maxLength\":2048}}},\"source\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048,\"pattern\":\"^https?://\"}}},\"module\":{\"title\":\"UpdateModule\",\"type\":\"object\",\"required\":[\"id\",\"kind\",\"version\",\"code\",\"policy\",\"sha256\",\"size\",\"urls\",\"manifest_urls\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"enum\":[\"thinkos-updater\",\"thinkos-cast\",\"thinkos-immersive\",\"thinkos-shell\",\"thinkos-certs\",\"thinkos-power\",\"timesync\"]},\"kind\":{\"type\":\"string\",\"enum\":[\"magisk\",\"vector\"]},\"version\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64},\"code\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},\"policy\":{\"type\":\"string\",\"enum\":[\"always\",\"installed\",\"feature\"]},\"sha256\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"},\"size\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16777216,\"format\":\"int64\"},\"urls\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048,\"pattern\":\"^https?://\"},\"uniqueItems\":true},\"manifest_urls\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":8,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":2048,\"pattern\":\"^https?://\"},\"uniqueItems\":true}}}},\"description\":\"Authenticate raw bytes before decoding. Integer fields use decimal integer tokens, without fractions/exponents. Expiry warns only; device clock may be untrusted. Semantic version/code equality, expiry ordering, module kind/ID uniqueness and URL validation also apply. release.api is the resulting runtime plugin API, not an old-runtime ceiling on APK updates.\"}"),
        "debug-keys.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"debug-keys.json\",\"title\":\"UpdateDebugKeyDocument\",\"type\":\"object\",\"required\":[\"schema\",\"test\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"test\":{\"\$ref\":\"keys.json#/\$defs/key\"}},\"description\":\"Additional public test key; load only from debug source-set assets, never in release.\"}"),
        "install-failure.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"install-failure.json\",\"title\":\"UpdateFailureDocument\",\"description\":\"One app-private failure record per version. The bounded recent history is not the blacklist authority, so old failed versions do not become eligible after history eviction.\",\"type\":\"object\",\"required\":[\"schema\",\"entry\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"entry\":{\"\$ref\":\"install-history.json#/properties/failures/items\"}}}"),
        "install-history.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"install-history.json\",\"description\":\"Private bounded install outcomes and failure ledger. A transaction failure is counted once even after interruption. Startup reconciliation handles committed history before deleting pending/previous bytes.\",\"title\":\"UpdateInstallHistory\",\"type\":\"object\",\"required\":[\"schema\",\"failures\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"failures\":{\"type\":\"array\",\"maxItems\":64,\"items\":{\"title\":\"UpdateInstallFailure\",\"type\":\"object\",\"required\":[\"code\",\"version\",\"count\",\"blocked\",\"last_transaction\",\"reason\"],\"additionalProperties\":false,\"properties\":{\"code\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},\"version\":{\"\$ref\":\"catalog.json#/\$defs/release/properties/version\"},\"count\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":100},\"blocked\":{\"type\":\"boolean\"},\"last_transaction\":{\"type\":\"string\",\"minLength\":32,\"maxLength\":32,\"pattern\":\"^[a-f0-9]{32}\$\"},\"reason\":{\"type\":\"string\",\"enum\":[\"install\",\"interrupted\",\"package\",\"crash_loop\",\"rollback_failed\"]}}}},\"result\":{\"title\":\"UpdateInstallResult\",\"type\":\"object\",\"required\":[\"transaction_id\",\"kind\",\"code\",\"version\",\"recorded\",\"notice_ms\",\"notes\",\"needs_recovery_clear\"],\"additionalProperties\":false,\"properties\":{\"transaction_id\":{\"type\":\"string\",\"minLength\":32,\"maxLength\":32,\"pattern\":\"^[a-f0-9]{32}\$\"},\"kind\":{\"type\":\"string\",\"enum\":[\"updated\",\"rolled_back\",\"install_failed\",\"rollback_failed\"]},\"code\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},\"version\":{\"\$ref\":\"catalog.json#/\$defs/release/properties/version\"},\"recorded\":{\"\$ref\":\"pending.json#/\$defs/time\"},\"notice_ms\":{\"type\":\"integer\",\"minimum\":60000,\"maximum\":604800000,\"format\":\"int64\"},\"notes\":{\"\$ref\":\"catalog.json#/\$defs/release/properties/notes\"},\"reason\":{\"type\":\"string\",\"enum\":[\"install\",\"interrupted\",\"package\",\"crash_loop\",\"rollback_failed\"]},\"needs_recovery_clear\":{\"type\":\"boolean\"}}}}}"),
        "install-repair.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"install-repair.json\",\"title\":\"UpdateRepairRecord\",\"description\":\"Private APK-only corrupt transaction isolation. Raw pending and previous are preserved under fixed quarantine/id filenames. This document never authorizes install or rollback. New authenticated prepare binds an independent transaction; only its real health acknowledgement retires the recovery fence.\",\"type\":\"object\",\"required\":[\"schema\",\"id\",\"state\",\"current\",\"pending\",\"root_state\",\"recorded\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"id\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":32,\"pattern\":\"^[a-f0-9]{32}\$\"},\"state\":{\"type\":\"string\",\"enum\":[\"PREPARED\",\"ISOLATED\"]},\"current\":{\"\$ref\":\"pending.json#/\$defs/apk\"},\"pending\":{\"\$ref\":\"#/\$defs/file\"},\"previous\":{\"\$ref\":\"#/\$defs/file\"},\"history\":{\"\$ref\":\"#/\$defs/file\"},\"root_state\":{\"type\":\"string\",\"enum\":[\"absent\",\"idle\"]},\"recorded\":{\"\$ref\":\"pending.json#/\$defs/time\"},\"bound_transaction\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":32,\"pattern\":\"^[a-f0-9]{32}\$\"},\"prior_marker\":{\"\$ref\":\"#/\$defs/file\"}},\"\$defs\":{\"file\":{\"title\":\"UpdateRepairFile\",\"type\":\"object\",\"required\":[\"size\",\"sha256\",\"key\",\"modified\"],\"additionalProperties\":false,\"properties\":{\"size\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":67108864,\"format\":\"int64\"},\"sha256\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"},\"key\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":256},\"modified\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":128}}}}}"),
        "keys.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"keys.json\",\"title\":\"UpdateKeyDocument\",\"type\":\"object\",\"required\":[\"schema\",\"channel\",\"registry\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"channel\":{\"\$ref\":\"#/\$defs/pair\"},\"registry\":{\"\$ref\":\"#/\$defs/pair\"}},\"\$defs\":{\"key\":{\"title\":\"UpdatePublicKey\",\"type\":\"object\",\"required\":[\"id\",\"algorithm\",\"spki\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64,\"pattern\":\"^[a-z][a-z0-9._-]{0,63}\$\"},\"algorithm\":{\"type\":\"string\",\"const\":\"SHA256withECDSA\"},\"spki\":{\"type\":\"string\",\"minLength\":16,\"maxLength\":512,\"pattern\":\"^[A-Za-z0-9+/]+={0,2}\$\"}}},\"pair\":{\"title\":\"UpdateKeyPair\",\"type\":\"object\",\"required\":[\"active\",\"backup\"],\"additionalProperties\":false,\"properties\":{\"active\":{\"\$ref\":\"#/\$defs/key\"},\"backup\":{\"\$ref\":\"#/\$defs/key\"}}}},\"description\":\"APK-only trust roots. Public keys are canonical Base64 DER SubjectPublicKeyInfo for P-256. Four IDs and keys must be distinct. Signatures are ASN.1 DER SHA256withECDSA, not JOSE raw r||s.\"}"),
        "module-pending.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"module-pending.json\",\"title\":\"ModuleUpdatePending\",\"description\":\"Private optional-updater transaction. Raw signed catalog/manifest pairs are separately retained unchanged and reauthenticated before use. Operation is committed BEFORE sending a mutating request; unresolved operations must never be blindly repeated.\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"schema\",\"id\",\"root_id\",\"previous_root_id\",\"catalog_digest\",\"channel\",\"from\",\"to\",\"mode\",\"phase\",\"operation\",\"selected\",\"staged\",\"created\",\"attempts\",\"max_attempts\",\"health_ms\",\"poll_ms\"],\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"id\":{\"type\":\"string\",\"pattern\":\"^[a-f0-9]{32}\$\",\"minLength\":32,\"maxLength\":32},\"root_id\":{\"type\":\"string\",\"pattern\":\"^(?:[a-f0-9]{32})?\$\",\"maxLength\":32},\"previous_root_id\":{\"type\":\"string\",\"pattern\":\"^(?:[a-f0-9]{32})?\$\",\"maxLength\":32},\"catalog_digest\":{\"\$ref\":\"pending.json#/properties/catalog_digest\"},\"channel\":{\"\$ref\":\"pending.json#/properties/channel\"},\"from\":{\"\$ref\":\"pending.json#/\$defs/apk\"},\"to\":{\"\$ref\":\"pending.json#/\$defs/apk\"},\"mode\":{\"\$ref\":\"pending.json#/properties/mode\"},\"phase\":{\"type\":\"string\",\"enum\":[\"PREPARED\",\"ACTIVE\",\"TRIAL\",\"HEALTHY_PENDING\",\"COMPLETE\",\"CANCELLED\",\"FAILED\",\"UNKNOWN\"]},\"operation\":{\"type\":\"string\",\"enum\":[\"\",\"BEGIN\",\"STAGE\",\"INSTALL_APK\",\"REBOOT\",\"HEALTHY\",\"ROLLBACK\"]},\"operation_id\":{\"type\":\"string\",\"maxLength\":64},\"selected\":{\"type\":\"array\",\"maxItems\":7,\"items\":{\"title\":\"ModuleUpdateTarget\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\",\"code\"],\"properties\":{\"id\":{\"\$ref\":\"catalog.json#/\$defs/module/properties/id\"},\"code\":{\"\$ref\":\"catalog.json#/\$defs/module/properties/code\"}}}},\"staged\":{\"type\":\"array\",\"maxItems\":7,\"uniqueItems\":true,\"items\":{\"type\":\"string\",\"minLength\":1,\"maxLength\":64}},\"created\":{\"\$ref\":\"pending.json#/\$defs/time\"},\"attempts\":{\"\$ref\":\"pending.json#/properties/attempts\"},\"max_attempts\":{\"\$ref\":\"pending.json#/properties/max_attempts\"},\"health_ms\":{\"\$ref\":\"pending.json#/properties/health_ms\"},\"poll_ms\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":1000,\"maximum\":60000},\"reboot_boot\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":9223372036854775807},\"trial_id\":{\"\$ref\":\"pending.json#/properties/trial_id\"},\"trial_started\":{\"\$ref\":\"pending.json#/\$defs/time\"},\"reason\":{\"type\":\"string\",\"maxLength\":128},\"feature\":{\"type\":\"string\",\"enum\":[\"miracast\"],\"description\":\"Final native-touch consent committed with PREPARED, never by feature download. Before the first BEGIN the app must enable and flush this schema setting; ordinary updates omit this field.\"}}}"),
        "pending.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"pending.json\",\"description\":\"Private install transaction, never a network API. Only fixed app-private filenames are used; no paths, URLs or keys. Every install/rollback revalidates the actual APK. Main-process attempts persist before native runtime startup.\",\"title\":\"UpdatePending\",\"type\":\"object\",\"required\":[\"schema\",\"transaction_id\",\"phase\",\"channel\",\"catalog_digest\",\"seq\",\"from\",\"to\",\"mode\",\"attempts\",\"max_attempts\",\"health_ms\",\"notice_ms\",\"failure_limit\",\"created\",\"notes\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"transaction_id\":{\"type\":\"string\",\"minLength\":32,\"maxLength\":32,\"pattern\":\"^[a-f0-9]{32}\$\"},\"phase\":{\"type\":\"string\",\"enum\":[\"PREPARED\",\"INSTALLING\",\"TRIAL\",\"ROLLING_BACK\"]},\"channel\":{\"type\":\"string\",\"enum\":[\"stable\",\"beta\"]},\"catalog_digest\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"},\"seq\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9223372036854775807,\"format\":\"int64\"},\"from\":{\"\$ref\":\"#/\$defs/apk\"},\"to\":{\"\$ref\":\"#/\$defs/apk\"},\"mode\":{\"type\":\"string\",\"enum\":[\"manual\",\"automatic\"]},\"attempts\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100},\"max_attempts\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10},\"health_ms\":{\"type\":\"integer\",\"minimum\":600000,\"maximum\":3600000,\"format\":\"int64\"},\"notice_ms\":{\"type\":\"integer\",\"minimum\":60000,\"maximum\":604800000,\"format\":\"int64\"},\"failure_limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":10},\"created\":{\"\$ref\":\"#/\$defs/time\"},\"notes\":{\"\$ref\":\"catalog.json#/\$defs/release/properties/notes\"},\"session_id\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},\"rollback_session_id\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},\"trial_id\":{\"type\":\"string\",\"minLength\":32,\"maxLength\":32,\"pattern\":\"^[a-f0-9]{32}\$\"},\"trial_started\":{\"\$ref\":\"#/\$defs/time\"}},\"\$defs\":{\"apk\":{\"title\":\"UpdateApkIdentity\",\"type\":\"object\",\"required\":[\"version\",\"code\",\"sha256\",\"size\",\"signer\"],\"additionalProperties\":false,\"properties\":{\"version\":{\"\$ref\":\"catalog.json#/\$defs/release/properties/version\"},\"code\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},\"sha256\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"},\"size\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":67108864,\"format\":\"int64\"},\"signer\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"}}},\"time\":{\"title\":\"UpdateInstallTime\",\"type\":\"object\",\"required\":[\"elapsed\"],\"additionalProperties\":false,\"properties\":{\"elapsed\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9223372036854775807,\"format\":\"int64\"},\"boot\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9223372036854775807,\"format\":\"int64\"},\"wall\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":9223372036854775807,\"format\":\"int64\"}}}}}"),
        "sequences.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"sequences.json\",\"title\":\"UpdateSequenceDocument\",\"description\":\"Private device anti-rollback ledger, not a Server API. Channel sequence/digest pairs are atomic; equal sequence accepts only identical authenticated bytes. Corruption fails closed.\",\"type\":\"object\",\"required\":[\"schema\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"stable\":{\"\$ref\":\"#/\$defs/floor\"},\"beta\":{\"\$ref\":\"#/\$defs/floor\"}},\"\$defs\":{\"floor\":{\"title\":\"UpdateSequence\",\"type\":\"object\",\"required\":[\"seq\",\"digest\"],\"additionalProperties\":false,\"properties\":{\"seq\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":9223372036854775807},\"digest\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"}}}},\"minProperties\":2}"),
        "state.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"state.json\",\"title\":\"UpdateStateDocument\",\"description\":\"Private workflow checkpoint. References only an authenticated catalog digest/sequence; paths and keys are not persisted here. READY must be revalidated from the signed cache and actual APK on recovery. Installation transaction authority remains pending.json/UpdateGuard.\",\"type\":\"object\",\"required\":[\"schema\",\"channel\",\"phase\",\"downloaded\",\"size\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"channel\":{\"type\":\"string\",\"enum\":[\"stable\",\"beta\"]},\"phase\":{\"type\":\"string\",\"enum\":[\"IDLE\",\"AVAILABLE\",\"DOWNLOADING\",\"READY\",\"INSTALLING\",\"FAILED\"]},\"downloaded\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":67108864},\"size\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":67108864},\"seq\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":9223372036854775807},\"catalog_digest\":{\"type\":\"string\",\"minLength\":64,\"maxLength\":64,\"pattern\":\"^[a-f0-9]{64}\$\"},\"error\":{\"type\":\"string\",\"enum\":[\"network\",\"metadata\",\"storage\",\"package\",\"interrupted\",\"unavailable\",\"keys\"]},\"ready_elapsed\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":9223372036854775807},\"ready_boot\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":9223372036854775807},\"ready_wall\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":9223372036854775807}}}"),
        "status.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"status.json\",\"title\":\"UpdateStatusDocument\",\"description\":\"Read-only tos.update.status API10 payload, also used by native recovery. No paths, download URLs or trust material. Components describe authenticated catalog targets. Optional inventory describes locally authenticated root STATUS independently of catalogs, channel keys and sources; it carries no targets, progress or results. Not a Server HTTP API.\",\"type\":\"object\",\"required\":[\"schema\",\"phase\",\"channel\",\"current_version\",\"current_code\",\"configured\",\"can_check\",\"can_download\",\"can_install\",\"downloaded\",\"size\",\"components\"],\"additionalProperties\":false,\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"phase\":{\"type\":\"string\",\"enum\":[\"idle\",\"checking\",\"available\",\"paused\",\"downloading\",\"ready\",\"installing\",\"failed\",\"up_to_date\",\"unavailable\"]},\"channel\":{\"type\":\"string\",\"enum\":[\"stable\",\"beta\"]},\"current_version\":{\"type\":\"string\",\"maxLength\":16},\"current_code\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},\"configured\":{\"type\":\"boolean\"},\"can_check\":{\"type\":\"boolean\"},\"can_download\":{\"type\":\"boolean\"},\"can_install\":{\"type\":\"boolean\"},\"downloaded\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":67108864},\"size\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":67108864},\"components\":{\"type\":\"array\",\"maxItems\":7,\"items\":{\"title\":\"UpdateComponentStatus\",\"type\":\"object\",\"required\":[\"id\",\"version\",\"code\",\"state\"],\"additionalProperties\":false,\"properties\":{\"id\":{\"type\":\"string\",\"enum\":[\"thinkos-updater\",\"thinkos-cast\",\"thinkos-immersive\",\"thinkos-shell\",\"thinkos-certs\",\"thinkos-power\",\"timesync\"]},\"version\":{\"type\":\"string\",\"maxLength\":64},\"code\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},\"state\":{\"type\":\"string\",\"enum\":[\"requires_updater\",\"current\",\"skipped\",\"available\",\"downloading\",\"ready\",\"staged\",\"failed\"]},\"installed_code\":{\"type\":\"integer\",\"minimum\":-1,\"maximum\":2147483647},\"downloaded\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":0,\"maximum\":16777216},\"size\":{\"type\":\"integer\",\"format\":\"int64\",\"minimum\":1,\"maximum\":16777216},\"result\":{\"type\":\"string\",\"enum\":[\"updated\",\"rolled_back\",\"install_failed\",\"rollback_failed\",\"cancelled\"]}}}},\"version\":{\"type\":\"string\",\"maxLength\":16},\"code\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},\"notes\":{\"type\":\"string\",\"maxLength\":2048},\"error\":{\"type\":\"string\",\"enum\":[\"keys\",\"sources\",\"network\",\"metadata\",\"storage\",\"package\",\"interrupted\",\"unavailable\",\"incompatible\",\"blacklisted\"]},\"blocked\":{\"type\":\"string\",\"enum\":[\"video\",\"casting\",\"foreign\",\"media\"]},\"silent_install\":{\"type\":\"boolean\"},\"result\":{\"title\":\"UpdateResultStatus\",\"type\":\"object\",\"required\":[\"kind\",\"version\",\"code\",\"message\",\"notes\"],\"additionalProperties\":false,\"properties\":{\"kind\":{\"type\":\"string\",\"enum\":[\"updated\",\"rolled_back\",\"install_failed\",\"rollback_failed\"]},\"version\":{\"type\":\"string\",\"maxLength\":16},\"code\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":2147483647},\"message\":{\"type\":\"string\",\"maxLength\":80},\"notes\":{\"type\":\"string\",\"maxLength\":2048},\"reason\":{\"type\":\"string\",\"maxLength\":80}}},\"inventory\":{\"title\":\"UpdateSystemInventory\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"updater\",\"modules\"],\"properties\":{\"updater\":{\"type\":\"string\",\"enum\":[\"unavailable\",\"read_only\",\"ready\"],\"description\":\"Local capability only, never installation admission. A reachable keyless updater or missing APK system-key asset is read_only; installed inventory remains visible.\"},\"modules\":{\"type\":\"array\",\"maxItems\":7,\"items\":{\"title\":\"UpdateInstalledComponent\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\"],\"properties\":{\"id\":{\"\$ref\":\"catalog.json#/\$defs/module/properties/id\"},\"installed_code\":{\"type\":\"integer\",\"minimum\":-1,\"maximum\":2147483647,\"description\":\"Actual root-reported installed build number. Zero means absent; -1 or omission means unknown. Never inferred from a catalog or converted into a version name.\"}}}}}}}}"),
        "system-keys.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"system-keys.json\",\"title\":\"UpdateSystemKeyDocument\",\"description\":\"APK-owned system-module roots only. Exactly the independent active/backup P-256 roots also compiled into the privileged updater; no debug, downloaded or fallback keys.\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"schema\",\"active\",\"backup\"],\"properties\":{\"schema\":{\"type\":\"integer\",\"const\":1},\"active\":{\"\$ref\":\"keys.json#/\$defs/key\"},\"backup\":{\"\$ref\":\"keys.json#/\$defs/key\"}}}"),
        "updater-status.json" to JSONObject("{\"\$schema\":\"https://json-schema.org/draft/2020-12/schema\",\"\$id\":\"updater-status.json\",\"title\":\"UpdaterStatusDocument\",\"description\":\"Bounded local protocol-1 STATUS response from the authenticated root socket, without the OK prefix. Not a Server API or downloaded trust document.\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"protocol\",\"keys\",\"transaction\",\"id\",\"phase\",\"result\",\"rollback_attempted\",\"modules\"],\"properties\":{\"protocol\":{\"type\":\"integer\",\"const\":1},\"keys\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2},\"transaction\":{\"type\":\"boolean\"},\"id\":{\"type\":\"string\",\"maxLength\":32,\"pattern\":\"^(?:[a-f0-9]{32})?\$\"},\"phase\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":6},\"result\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":3},\"rollback_attempted\":{\"type\":\"boolean\"},\"modules\":{\"type\":\"array\",\"maxItems\":7,\"items\":{\"title\":\"UpdaterModuleStatus\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"id\",\"code\",\"floor\",\"staged\"],\"properties\":{\"id\":{\"type\":\"string\",\"enum\":[\"thinkos-updater\",\"thinkos-cast\",\"thinkos-immersive\",\"thinkos-shell\",\"thinkos-certs\",\"thinkos-power\",\"timesync\"]},\"code\":{\"type\":\"integer\",\"minimum\":-1,\"maximum\":2147483647},\"floor\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},\"staged\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":2147483647},\"active\":{\"type\":\"boolean\",\"description\":\"Actual enabled installed component with no pending replacement/remove markers. Missing on an older daemon means unknown, never healthy.\"}}}},\"setup\":{\"title\":\"UpdaterSetupCapabilities\",\"type\":\"object\",\"additionalProperties\":false,\"required\":[\"timezone\",\"language\",\"device_owner\"],\"properties\":{\"timezone\":{\"type\":\"boolean\"},\"language\":{\"type\":\"boolean\"},\"device_owner\":{\"type\":\"boolean\",\"const\":false}}}}}")
    ) }

    fun readUpdateCachedCatalogDocument(value: JSONObject): UpdateCachedCatalogDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("cache.json"), "cache.json", documents)
        return parseUpdateCachedCatalogDocument(value)
    }

    fun writeUpdateCachedCatalogDocument(value: UpdateCachedCatalogDocument): JSONObject = buildUpdateCachedCatalogDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("cache.json"), "cache.json", documents)
    }

    fun readUpdateCatalogDocument(value: JSONObject): UpdateCatalogDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("catalog.json"), "catalog.json", documents)
        return parseUpdateCatalogDocument(value)
    }

    fun writeUpdateCatalogDocument(value: UpdateCatalogDocument): JSONObject = buildUpdateCatalogDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("catalog.json"), "catalog.json", documents)
    }

    fun readUpdateDebugKeyDocument(value: JSONObject): UpdateDebugKeyDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("debug-keys.json"), "debug-keys.json", documents)
        return parseUpdateDebugKeyDocument(value)
    }

    fun writeUpdateDebugKeyDocument(value: UpdateDebugKeyDocument): JSONObject = buildUpdateDebugKeyDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("debug-keys.json"), "debug-keys.json", documents)
    }

    fun readUpdateFailureDocument(value: JSONObject): UpdateFailureDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("install-failure.json"), "install-failure.json", documents)
        return parseUpdateFailureDocument(value)
    }

    fun writeUpdateFailureDocument(value: UpdateFailureDocument): JSONObject = buildUpdateFailureDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("install-failure.json"), "install-failure.json", documents)
    }

    fun readUpdateInstallHistory(value: JSONObject): UpdateInstallHistory {
        UpdateSchemaValidator.validate(value, documents.getValue("install-history.json"), "install-history.json", documents)
        return parseUpdateInstallHistory(value)
    }

    fun writeUpdateInstallHistory(value: UpdateInstallHistory): JSONObject = buildUpdateInstallHistory(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("install-history.json"), "install-history.json", documents)
    }

    fun readUpdateRepairRecord(value: JSONObject): UpdateRepairRecord {
        UpdateSchemaValidator.validate(value, documents.getValue("install-repair.json"), "install-repair.json", documents)
        return parseUpdateRepairRecord(value)
    }

    fun writeUpdateRepairRecord(value: UpdateRepairRecord): JSONObject = buildUpdateRepairRecord(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("install-repair.json"), "install-repair.json", documents)
    }

    fun readUpdateKeyDocument(value: JSONObject): UpdateKeyDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("keys.json"), "keys.json", documents)
        return parseUpdateKeyDocument(value)
    }

    fun writeUpdateKeyDocument(value: UpdateKeyDocument): JSONObject = buildUpdateKeyDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("keys.json"), "keys.json", documents)
    }

    fun readModuleUpdatePending(value: JSONObject): ModuleUpdatePending {
        UpdateSchemaValidator.validate(value, documents.getValue("module-pending.json"), "module-pending.json", documents)
        return parseModuleUpdatePending(value)
    }

    fun writeModuleUpdatePending(value: ModuleUpdatePending): JSONObject = buildModuleUpdatePending(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("module-pending.json"), "module-pending.json", documents)
    }

    fun readUpdatePending(value: JSONObject): UpdatePending {
        UpdateSchemaValidator.validate(value, documents.getValue("pending.json"), "pending.json", documents)
        return parseUpdatePending(value)
    }

    fun writeUpdatePending(value: UpdatePending): JSONObject = buildUpdatePending(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("pending.json"), "pending.json", documents)
    }

    fun readUpdateSequenceDocument(value: JSONObject): UpdateSequenceDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("sequences.json"), "sequences.json", documents)
        return parseUpdateSequenceDocument(value)
    }

    fun writeUpdateSequenceDocument(value: UpdateSequenceDocument): JSONObject = buildUpdateSequenceDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("sequences.json"), "sequences.json", documents)
    }

    fun readUpdateStateDocument(value: JSONObject): UpdateStateDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("state.json"), "state.json", documents)
        return parseUpdateStateDocument(value)
    }

    fun writeUpdateStateDocument(value: UpdateStateDocument): JSONObject = buildUpdateStateDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("state.json"), "state.json", documents)
    }

    fun readUpdateStatusDocument(value: JSONObject): UpdateStatusDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("status.json"), "status.json", documents)
        return parseUpdateStatusDocument(value)
    }

    fun writeUpdateStatusDocument(value: UpdateStatusDocument): JSONObject = buildUpdateStatusDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("status.json"), "status.json", documents)
    }

    fun readUpdateSystemKeyDocument(value: JSONObject): UpdateSystemKeyDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("system-keys.json"), "system-keys.json", documents)
        return parseUpdateSystemKeyDocument(value)
    }

    fun writeUpdateSystemKeyDocument(value: UpdateSystemKeyDocument): JSONObject = buildUpdateSystemKeyDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("system-keys.json"), "system-keys.json", documents)
    }

    fun readUpdaterStatusDocument(value: JSONObject): UpdaterStatusDocument {
        UpdateSchemaValidator.validate(value, documents.getValue("updater-status.json"), "updater-status.json", documents)
        return parseUpdaterStatusDocument(value)
    }

    fun writeUpdaterStatusDocument(value: UpdaterStatusDocument): JSONObject = buildUpdaterStatusDocument(value).also {
        UpdateSchemaValidator.validate(it, documents.getValue("updater-status.json"), "updater-status.json", documents)
    }

    private fun parseUpdateCachedCatalogDocument(value: JSONObject): UpdateCachedCatalogDocument = UpdateCachedCatalogDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `channel` = (value.get("channel") as String),
        `body` = (value.get("body") as String),
        `signature` = (value.get("signature") as String),
        `source` = (value.get("source") as String),
        `etag` = if (value.has("etag")) (value.get("etag") as String) else null
    )

    private fun buildUpdateCachedCatalogDocument(value: UpdateCachedCatalogDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("channel", value.`channel`)
        put("body", value.`body`)
        put("signature", value.`signature`)
        put("source", value.`source`)
        if (value.`etag` != null) put("etag", value.`etag`)
    }

    private fun parseUpdateCatalogDocument(value: JSONObject): UpdateCatalogDocument = UpdateCatalogDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `channel` = (value.get("channel") as String),
        `seq` = (value.get("seq") as Long),
        `published` = (value.get("published") as Long),
        `expires` = (value.get("expires") as Long),
        `release` = parseUpdateRelease(value.get("release") as JSONObject),
        `mirrors` = if (value.has("mirrors")) list(value.get("mirrors") as JSONArray) { item -> (item as String) } else null
    )

    private fun buildUpdateCatalogDocument(value: UpdateCatalogDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("channel", value.`channel`)
        put("seq", value.`seq`)
        put("published", value.`published`)
        put("expires", value.`expires`)
        put("release", buildUpdateRelease(value.`release`))
        if (value.`mirrors` != null) put("mirrors", JSONArray().apply { value.`mirrors`.forEach { item -> put(item) } })
    }

    private fun parseUpdateRelease(value: JSONObject): UpdateRelease = UpdateRelease(
        `version` = (value.get("version") as String),
        `code` = (value.get("code") as Long).toInt(),
        `api` = (value.get("api") as Long).toInt(),
        `device` = list(value.get("device") as JSONArray) { item -> (item as String) },
        `sdk` = (value.get("sdk") as Long).toInt(),
        `sha256` = (value.get("sha256") as String),
        `size` = (value.get("size") as Long),
        `signer` = (value.get("signer") as String),
        `urls` = list(value.get("urls") as JSONArray) { item -> (item as String) },
        `modules` = list(value.get("modules") as JSONArray) { item -> parseUpdateModule(item as JSONObject) },
        `notes` = stringMap(value.get("notes") as JSONObject),
        `source` = (value.get("source") as String)
    )

    private fun buildUpdateRelease(value: UpdateRelease): JSONObject = JSONObject().apply {
        put("version", value.`version`)
        put("code", value.`code`.toLong())
        put("api", value.`api`.toLong())
        put("device", JSONArray().apply { value.`device`.forEach { item -> put(item) } })
        put("sdk", value.`sdk`.toLong())
        put("sha256", value.`sha256`)
        put("size", value.`size`)
        put("signer", value.`signer`)
        put("urls", JSONArray().apply { value.`urls`.forEach { item -> put(item) } })
        put("modules", JSONArray().apply { value.`modules`.forEach { item -> put(buildUpdateModule(item)) } })
        put("notes", JSONObject(value.`notes`))
        put("source", value.`source`)
    }

    private fun parseUpdateModule(value: JSONObject): UpdateModule = UpdateModule(
        `id` = (value.get("id") as String),
        `kind` = (value.get("kind") as String),
        `version` = (value.get("version") as String),
        `code` = (value.get("code") as Long).toInt(),
        `policy` = (value.get("policy") as String),
        `sha256` = (value.get("sha256") as String),
        `size` = (value.get("size") as Long),
        `urls` = list(value.get("urls") as JSONArray) { item -> (item as String) },
        `manifest_urls` = list(value.get("manifest_urls") as JSONArray) { item -> (item as String) }
    )

    private fun buildUpdateModule(value: UpdateModule): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("kind", value.`kind`)
        put("version", value.`version`)
        put("code", value.`code`.toLong())
        put("policy", value.`policy`)
        put("sha256", value.`sha256`)
        put("size", value.`size`)
        put("urls", JSONArray().apply { value.`urls`.forEach { item -> put(item) } })
        put("manifest_urls", JSONArray().apply { value.`manifest_urls`.forEach { item -> put(item) } })
    }

    private fun parseUpdateDebugKeyDocument(value: JSONObject): UpdateDebugKeyDocument = UpdateDebugKeyDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `test` = parseUpdatePublicKey(value.get("test") as JSONObject)
    )

    private fun buildUpdateDebugKeyDocument(value: UpdateDebugKeyDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("test", buildUpdatePublicKey(value.`test`))
    }

    private fun parseUpdateFailureDocument(value: JSONObject): UpdateFailureDocument = UpdateFailureDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `entry` = parseUpdateInstallFailure(value.get("entry") as JSONObject)
    )

    private fun buildUpdateFailureDocument(value: UpdateFailureDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("entry", buildUpdateInstallFailure(value.`entry`))
    }

    private fun parseUpdateInstallHistory(value: JSONObject): UpdateInstallHistory = UpdateInstallHistory(
        `schema` = (value.get("schema") as Long).toInt(),
        `failures` = list(value.get("failures") as JSONArray) { item -> parseUpdateInstallFailure(item as JSONObject) },
        `result` = if (value.has("result")) parseUpdateInstallResult(value.get("result") as JSONObject) else null
    )

    private fun buildUpdateInstallHistory(value: UpdateInstallHistory): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("failures", JSONArray().apply { value.`failures`.forEach { item -> put(buildUpdateInstallFailure(item)) } })
        if (value.`result` != null) put("result", buildUpdateInstallResult(value.`result`))
    }

    private fun parseUpdateInstallFailure(value: JSONObject): UpdateInstallFailure = UpdateInstallFailure(
        `code` = (value.get("code") as Long).toInt(),
        `version` = (value.get("version") as String),
        `count` = (value.get("count") as Long).toInt(),
        `blocked` = (value.get("blocked") as Boolean),
        `last_transaction` = (value.get("last_transaction") as String),
        `reason` = (value.get("reason") as String)
    )

    private fun buildUpdateInstallFailure(value: UpdateInstallFailure): JSONObject = JSONObject().apply {
        put("code", value.`code`.toLong())
        put("version", value.`version`)
        put("count", value.`count`.toLong())
        put("blocked", value.`blocked`)
        put("last_transaction", value.`last_transaction`)
        put("reason", value.`reason`)
    }

    private fun parseUpdateInstallResult(value: JSONObject): UpdateInstallResult = UpdateInstallResult(
        `transaction_id` = (value.get("transaction_id") as String),
        `kind` = (value.get("kind") as String),
        `code` = (value.get("code") as Long).toInt(),
        `version` = (value.get("version") as String),
        `recorded` = parseUpdateInstallTime(value.get("recorded") as JSONObject),
        `notice_ms` = (value.get("notice_ms") as Long),
        `notes` = stringMap(value.get("notes") as JSONObject),
        `reason` = if (value.has("reason")) (value.get("reason") as String) else null,
        `needs_recovery_clear` = (value.get("needs_recovery_clear") as Boolean)
    )

    private fun buildUpdateInstallResult(value: UpdateInstallResult): JSONObject = JSONObject().apply {
        put("transaction_id", value.`transaction_id`)
        put("kind", value.`kind`)
        put("code", value.`code`.toLong())
        put("version", value.`version`)
        put("recorded", buildUpdateInstallTime(value.`recorded`))
        put("notice_ms", value.`notice_ms`)
        put("notes", JSONObject(value.`notes`))
        if (value.`reason` != null) put("reason", value.`reason`)
        put("needs_recovery_clear", value.`needs_recovery_clear`)
    }

    private fun parseUpdateRepairRecord(value: JSONObject): UpdateRepairRecord = UpdateRepairRecord(
        `schema` = (value.get("schema") as Long).toInt(),
        `id` = (value.get("id") as String),
        `state` = (value.get("state") as String),
        `current` = parseUpdateApkIdentity(value.get("current") as JSONObject),
        `pending` = parseUpdateRepairFile(value.get("pending") as JSONObject),
        `previous` = if (value.has("previous")) parseUpdateRepairFile(value.get("previous") as JSONObject) else null,
        `history` = if (value.has("history")) parseUpdateRepairFile(value.get("history") as JSONObject) else null,
        `root_state` = (value.get("root_state") as String),
        `recorded` = parseUpdateInstallTime(value.get("recorded") as JSONObject),
        `bound_transaction` = if (value.has("bound_transaction")) (value.get("bound_transaction") as String) else null,
        `prior_marker` = if (value.has("prior_marker")) parseUpdateRepairFile(value.get("prior_marker") as JSONObject) else null
    )

    private fun buildUpdateRepairRecord(value: UpdateRepairRecord): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("id", value.`id`)
        put("state", value.`state`)
        put("current", buildUpdateApkIdentity(value.`current`))
        put("pending", buildUpdateRepairFile(value.`pending`))
        if (value.`previous` != null) put("previous", buildUpdateRepairFile(value.`previous`))
        if (value.`history` != null) put("history", buildUpdateRepairFile(value.`history`))
        put("root_state", value.`root_state`)
        put("recorded", buildUpdateInstallTime(value.`recorded`))
        if (value.`bound_transaction` != null) put("bound_transaction", value.`bound_transaction`)
        if (value.`prior_marker` != null) put("prior_marker", buildUpdateRepairFile(value.`prior_marker`))
    }

    private fun parseUpdateRepairFile(value: JSONObject): UpdateRepairFile = UpdateRepairFile(
        `size` = (value.get("size") as Long),
        `sha256` = (value.get("sha256") as String),
        `key` = (value.get("key") as String),
        `modified` = (value.get("modified") as String)
    )

    private fun buildUpdateRepairFile(value: UpdateRepairFile): JSONObject = JSONObject().apply {
        put("size", value.`size`)
        put("sha256", value.`sha256`)
        put("key", value.`key`)
        put("modified", value.`modified`)
    }

    private fun parseUpdateKeyDocument(value: JSONObject): UpdateKeyDocument = UpdateKeyDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `channel` = parseUpdateKeyPair(value.get("channel") as JSONObject),
        `registry` = parseUpdateKeyPair(value.get("registry") as JSONObject)
    )

    private fun buildUpdateKeyDocument(value: UpdateKeyDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("channel", buildUpdateKeyPair(value.`channel`))
        put("registry", buildUpdateKeyPair(value.`registry`))
    }

    private fun parseUpdatePublicKey(value: JSONObject): UpdatePublicKey = UpdatePublicKey(
        `id` = (value.get("id") as String),
        `algorithm` = (value.get("algorithm") as String),
        `spki` = (value.get("spki") as String)
    )

    private fun buildUpdatePublicKey(value: UpdatePublicKey): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("algorithm", value.`algorithm`)
        put("spki", value.`spki`)
    }

    private fun parseUpdateKeyPair(value: JSONObject): UpdateKeyPair = UpdateKeyPair(
        `active` = parseUpdatePublicKey(value.get("active") as JSONObject),
        `backup` = parseUpdatePublicKey(value.get("backup") as JSONObject)
    )

    private fun buildUpdateKeyPair(value: UpdateKeyPair): JSONObject = JSONObject().apply {
        put("active", buildUpdatePublicKey(value.`active`))
        put("backup", buildUpdatePublicKey(value.`backup`))
    }

    private fun parseModuleUpdatePending(value: JSONObject): ModuleUpdatePending = ModuleUpdatePending(
        `schema` = (value.get("schema") as Long).toInt(),
        `id` = (value.get("id") as String),
        `root_id` = (value.get("root_id") as String),
        `previous_root_id` = (value.get("previous_root_id") as String),
        `catalog_digest` = (value.get("catalog_digest") as String),
        `channel` = (value.get("channel") as String),
        `from` = parseUpdateApkIdentity(value.get("from") as JSONObject),
        `to` = parseUpdateApkIdentity(value.get("to") as JSONObject),
        `mode` = (value.get("mode") as String),
        `phase` = (value.get("phase") as String),
        `operation` = (value.get("operation") as String),
        `operation_id` = if (value.has("operation_id")) (value.get("operation_id") as String) else null,
        `selected` = list(value.get("selected") as JSONArray) { item -> parseModuleUpdateTarget(item as JSONObject) },
        `staged` = list(value.get("staged") as JSONArray) { item -> (item as String) },
        `created` = parseUpdateInstallTime(value.get("created") as JSONObject),
        `attempts` = (value.get("attempts") as Long).toInt(),
        `max_attempts` = (value.get("max_attempts") as Long).toInt(),
        `health_ms` = (value.get("health_ms") as Long),
        `poll_ms` = (value.get("poll_ms") as Long),
        `reboot_boot` = if (value.has("reboot_boot")) (value.get("reboot_boot") as Long) else null,
        `trial_id` = if (value.has("trial_id")) (value.get("trial_id") as String) else null,
        `trial_started` = if (value.has("trial_started")) parseUpdateInstallTime(value.get("trial_started") as JSONObject) else null,
        `reason` = if (value.has("reason")) (value.get("reason") as String) else null,
        `feature` = if (value.has("feature")) (value.get("feature") as String) else null
    )

    private fun buildModuleUpdatePending(value: ModuleUpdatePending): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("id", value.`id`)
        put("root_id", value.`root_id`)
        put("previous_root_id", value.`previous_root_id`)
        put("catalog_digest", value.`catalog_digest`)
        put("channel", value.`channel`)
        put("from", buildUpdateApkIdentity(value.`from`))
        put("to", buildUpdateApkIdentity(value.`to`))
        put("mode", value.`mode`)
        put("phase", value.`phase`)
        put("operation", value.`operation`)
        if (value.`operation_id` != null) put("operation_id", value.`operation_id`)
        put("selected", JSONArray().apply { value.`selected`.forEach { item -> put(buildModuleUpdateTarget(item)) } })
        put("staged", JSONArray().apply { value.`staged`.forEach { item -> put(item) } })
        put("created", buildUpdateInstallTime(value.`created`))
        put("attempts", value.`attempts`.toLong())
        put("max_attempts", value.`max_attempts`.toLong())
        put("health_ms", value.`health_ms`)
        put("poll_ms", value.`poll_ms`)
        if (value.`reboot_boot` != null) put("reboot_boot", value.`reboot_boot`)
        if (value.`trial_id` != null) put("trial_id", value.`trial_id`)
        if (value.`trial_started` != null) put("trial_started", buildUpdateInstallTime(value.`trial_started`))
        if (value.`reason` != null) put("reason", value.`reason`)
        if (value.`feature` != null) put("feature", value.`feature`)
    }

    private fun parseModuleUpdateTarget(value: JSONObject): ModuleUpdateTarget = ModuleUpdateTarget(
        `id` = (value.get("id") as String),
        `code` = (value.get("code") as Long).toInt()
    )

    private fun buildModuleUpdateTarget(value: ModuleUpdateTarget): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("code", value.`code`.toLong())
    }

    private fun parseUpdatePending(value: JSONObject): UpdatePending = UpdatePending(
        `schema` = (value.get("schema") as Long).toInt(),
        `transaction_id` = (value.get("transaction_id") as String),
        `phase` = (value.get("phase") as String),
        `channel` = (value.get("channel") as String),
        `catalog_digest` = (value.get("catalog_digest") as String),
        `seq` = (value.get("seq") as Long),
        `from` = parseUpdateApkIdentity(value.get("from") as JSONObject),
        `to` = parseUpdateApkIdentity(value.get("to") as JSONObject),
        `mode` = (value.get("mode") as String),
        `attempts` = (value.get("attempts") as Long).toInt(),
        `max_attempts` = (value.get("max_attempts") as Long).toInt(),
        `health_ms` = (value.get("health_ms") as Long),
        `notice_ms` = (value.get("notice_ms") as Long),
        `failure_limit` = (value.get("failure_limit") as Long).toInt(),
        `created` = parseUpdateInstallTime(value.get("created") as JSONObject),
        `notes` = stringMap(value.get("notes") as JSONObject),
        `session_id` = if (value.has("session_id")) (value.get("session_id") as Long).toInt() else null,
        `rollback_session_id` = if (value.has("rollback_session_id")) (value.get("rollback_session_id") as Long).toInt() else null,
        `trial_id` = if (value.has("trial_id")) (value.get("trial_id") as String) else null,
        `trial_started` = if (value.has("trial_started")) parseUpdateInstallTime(value.get("trial_started") as JSONObject) else null
    )

    private fun buildUpdatePending(value: UpdatePending): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("transaction_id", value.`transaction_id`)
        put("phase", value.`phase`)
        put("channel", value.`channel`)
        put("catalog_digest", value.`catalog_digest`)
        put("seq", value.`seq`)
        put("from", buildUpdateApkIdentity(value.`from`))
        put("to", buildUpdateApkIdentity(value.`to`))
        put("mode", value.`mode`)
        put("attempts", value.`attempts`.toLong())
        put("max_attempts", value.`max_attempts`.toLong())
        put("health_ms", value.`health_ms`)
        put("notice_ms", value.`notice_ms`)
        put("failure_limit", value.`failure_limit`.toLong())
        put("created", buildUpdateInstallTime(value.`created`))
        put("notes", JSONObject(value.`notes`))
        if (value.`session_id` != null) put("session_id", value.`session_id`.toLong())
        if (value.`rollback_session_id` != null) put("rollback_session_id", value.`rollback_session_id`.toLong())
        if (value.`trial_id` != null) put("trial_id", value.`trial_id`)
        if (value.`trial_started` != null) put("trial_started", buildUpdateInstallTime(value.`trial_started`))
    }

    private fun parseUpdateApkIdentity(value: JSONObject): UpdateApkIdentity = UpdateApkIdentity(
        `version` = (value.get("version") as String),
        `code` = (value.get("code") as Long).toInt(),
        `sha256` = (value.get("sha256") as String),
        `size` = (value.get("size") as Long),
        `signer` = (value.get("signer") as String)
    )

    private fun buildUpdateApkIdentity(value: UpdateApkIdentity): JSONObject = JSONObject().apply {
        put("version", value.`version`)
        put("code", value.`code`.toLong())
        put("sha256", value.`sha256`)
        put("size", value.`size`)
        put("signer", value.`signer`)
    }

    private fun parseUpdateInstallTime(value: JSONObject): UpdateInstallTime = UpdateInstallTime(
        `elapsed` = (value.get("elapsed") as Long),
        `boot` = if (value.has("boot")) (value.get("boot") as Long) else null,
        `wall` = if (value.has("wall")) (value.get("wall") as Long) else null
    )

    private fun buildUpdateInstallTime(value: UpdateInstallTime): JSONObject = JSONObject().apply {
        put("elapsed", value.`elapsed`)
        if (value.`boot` != null) put("boot", value.`boot`)
        if (value.`wall` != null) put("wall", value.`wall`)
    }

    private fun parseUpdateSequenceDocument(value: JSONObject): UpdateSequenceDocument = UpdateSequenceDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `stable` = if (value.has("stable")) parseUpdateSequence(value.get("stable") as JSONObject) else null,
        `beta` = if (value.has("beta")) parseUpdateSequence(value.get("beta") as JSONObject) else null
    )

    private fun buildUpdateSequenceDocument(value: UpdateSequenceDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        if (value.`stable` != null) put("stable", buildUpdateSequence(value.`stable`))
        if (value.`beta` != null) put("beta", buildUpdateSequence(value.`beta`))
    }

    private fun parseUpdateSequence(value: JSONObject): UpdateSequence = UpdateSequence(
        `seq` = (value.get("seq") as Long),
        `digest` = (value.get("digest") as String)
    )

    private fun buildUpdateSequence(value: UpdateSequence): JSONObject = JSONObject().apply {
        put("seq", value.`seq`)
        put("digest", value.`digest`)
    }

    private fun parseUpdateStateDocument(value: JSONObject): UpdateStateDocument = UpdateStateDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `channel` = (value.get("channel") as String),
        `phase` = (value.get("phase") as String),
        `downloaded` = (value.get("downloaded") as Long),
        `size` = (value.get("size") as Long),
        `seq` = if (value.has("seq")) (value.get("seq") as Long) else null,
        `catalog_digest` = if (value.has("catalog_digest")) (value.get("catalog_digest") as String) else null,
        `error` = if (value.has("error")) (value.get("error") as String) else null,
        `ready_elapsed` = if (value.has("ready_elapsed")) (value.get("ready_elapsed") as Long) else null,
        `ready_boot` = if (value.has("ready_boot")) (value.get("ready_boot") as Long) else null,
        `ready_wall` = if (value.has("ready_wall")) (value.get("ready_wall") as Long) else null
    )

    private fun buildUpdateStateDocument(value: UpdateStateDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("channel", value.`channel`)
        put("phase", value.`phase`)
        put("downloaded", value.`downloaded`)
        put("size", value.`size`)
        if (value.`seq` != null) put("seq", value.`seq`)
        if (value.`catalog_digest` != null) put("catalog_digest", value.`catalog_digest`)
        if (value.`error` != null) put("error", value.`error`)
        if (value.`ready_elapsed` != null) put("ready_elapsed", value.`ready_elapsed`)
        if (value.`ready_boot` != null) put("ready_boot", value.`ready_boot`)
        if (value.`ready_wall` != null) put("ready_wall", value.`ready_wall`)
    }

    private fun parseUpdateStatusDocument(value: JSONObject): UpdateStatusDocument = UpdateStatusDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `phase` = (value.get("phase") as String),
        `channel` = (value.get("channel") as String),
        `current_version` = (value.get("current_version") as String),
        `current_code` = (value.get("current_code") as Long).toInt(),
        `configured` = (value.get("configured") as Boolean),
        `can_check` = (value.get("can_check") as Boolean),
        `can_download` = (value.get("can_download") as Boolean),
        `can_install` = (value.get("can_install") as Boolean),
        `downloaded` = (value.get("downloaded") as Long),
        `size` = (value.get("size") as Long),
        `components` = list(value.get("components") as JSONArray) { item -> parseUpdateComponentStatus(item as JSONObject) },
        `version` = if (value.has("version")) (value.get("version") as String) else null,
        `code` = if (value.has("code")) (value.get("code") as Long).toInt() else null,
        `notes` = if (value.has("notes")) (value.get("notes") as String) else null,
        `error` = if (value.has("error")) (value.get("error") as String) else null,
        `blocked` = if (value.has("blocked")) (value.get("blocked") as String) else null,
        `silent_install` = if (value.has("silent_install")) (value.get("silent_install") as Boolean) else null,
        `result` = if (value.has("result")) parseUpdateResultStatus(value.get("result") as JSONObject) else null,
        `inventory` = if (value.has("inventory")) parseUpdateSystemInventory(value.get("inventory") as JSONObject) else null
    )

    private fun buildUpdateStatusDocument(value: UpdateStatusDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("phase", value.`phase`)
        put("channel", value.`channel`)
        put("current_version", value.`current_version`)
        put("current_code", value.`current_code`.toLong())
        put("configured", value.`configured`)
        put("can_check", value.`can_check`)
        put("can_download", value.`can_download`)
        put("can_install", value.`can_install`)
        put("downloaded", value.`downloaded`)
        put("size", value.`size`)
        put("components", JSONArray().apply { value.`components`.forEach { item -> put(buildUpdateComponentStatus(item)) } })
        if (value.`version` != null) put("version", value.`version`)
        if (value.`code` != null) put("code", value.`code`.toLong())
        if (value.`notes` != null) put("notes", value.`notes`)
        if (value.`error` != null) put("error", value.`error`)
        if (value.`blocked` != null) put("blocked", value.`blocked`)
        if (value.`silent_install` != null) put("silent_install", value.`silent_install`)
        if (value.`result` != null) put("result", buildUpdateResultStatus(value.`result`))
        if (value.`inventory` != null) put("inventory", buildUpdateSystemInventory(value.`inventory`))
    }

    private fun parseUpdateComponentStatus(value: JSONObject): UpdateComponentStatus = UpdateComponentStatus(
        `id` = (value.get("id") as String),
        `version` = (value.get("version") as String),
        `code` = (value.get("code") as Long).toInt(),
        `state` = (value.get("state") as String),
        `installed_code` = if (value.has("installed_code")) (value.get("installed_code") as Long).toInt() else null,
        `downloaded` = if (value.has("downloaded")) (value.get("downloaded") as Long) else null,
        `size` = if (value.has("size")) (value.get("size") as Long) else null,
        `result` = if (value.has("result")) (value.get("result") as String) else null
    )

    private fun buildUpdateComponentStatus(value: UpdateComponentStatus): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("version", value.`version`)
        put("code", value.`code`.toLong())
        put("state", value.`state`)
        if (value.`installed_code` != null) put("installed_code", value.`installed_code`.toLong())
        if (value.`downloaded` != null) put("downloaded", value.`downloaded`)
        if (value.`size` != null) put("size", value.`size`)
        if (value.`result` != null) put("result", value.`result`)
    }

    private fun parseUpdateResultStatus(value: JSONObject): UpdateResultStatus = UpdateResultStatus(
        `kind` = (value.get("kind") as String),
        `version` = (value.get("version") as String),
        `code` = (value.get("code") as Long).toInt(),
        `message` = (value.get("message") as String),
        `notes` = (value.get("notes") as String),
        `reason` = if (value.has("reason")) (value.get("reason") as String) else null
    )

    private fun buildUpdateResultStatus(value: UpdateResultStatus): JSONObject = JSONObject().apply {
        put("kind", value.`kind`)
        put("version", value.`version`)
        put("code", value.`code`.toLong())
        put("message", value.`message`)
        put("notes", value.`notes`)
        if (value.`reason` != null) put("reason", value.`reason`)
    }

    private fun parseUpdateSystemInventory(value: JSONObject): UpdateSystemInventory = UpdateSystemInventory(
        `updater` = (value.get("updater") as String),
        `modules` = list(value.get("modules") as JSONArray) { item -> parseUpdateInstalledComponent(item as JSONObject) }
    )

    private fun buildUpdateSystemInventory(value: UpdateSystemInventory): JSONObject = JSONObject().apply {
        put("updater", value.`updater`)
        put("modules", JSONArray().apply { value.`modules`.forEach { item -> put(buildUpdateInstalledComponent(item)) } })
    }

    private fun parseUpdateInstalledComponent(value: JSONObject): UpdateInstalledComponent = UpdateInstalledComponent(
        `id` = (value.get("id") as String),
        `installed_code` = if (value.has("installed_code")) (value.get("installed_code") as Long).toInt() else null
    )

    private fun buildUpdateInstalledComponent(value: UpdateInstalledComponent): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        if (value.`installed_code` != null) put("installed_code", value.`installed_code`.toLong())
    }

    private fun parseUpdateSystemKeyDocument(value: JSONObject): UpdateSystemKeyDocument = UpdateSystemKeyDocument(
        `schema` = (value.get("schema") as Long).toInt(),
        `active` = parseUpdatePublicKey(value.get("active") as JSONObject),
        `backup` = parseUpdatePublicKey(value.get("backup") as JSONObject)
    )

    private fun buildUpdateSystemKeyDocument(value: UpdateSystemKeyDocument): JSONObject = JSONObject().apply {
        put("schema", value.`schema`.toLong())
        put("active", buildUpdatePublicKey(value.`active`))
        put("backup", buildUpdatePublicKey(value.`backup`))
    }

    private fun parseUpdaterStatusDocument(value: JSONObject): UpdaterStatusDocument = UpdaterStatusDocument(
        `protocol` = (value.get("protocol") as Long).toInt(),
        `keys` = (value.get("keys") as Long).toInt(),
        `transaction` = (value.get("transaction") as Boolean),
        `id` = (value.get("id") as String),
        `phase` = (value.get("phase") as Long).toInt(),
        `result` = (value.get("result") as Long).toInt(),
        `rollback_attempted` = (value.get("rollback_attempted") as Boolean),
        `modules` = list(value.get("modules") as JSONArray) { item -> parseUpdaterModuleStatus(item as JSONObject) },
        `setup` = if (value.has("setup")) parseUpdaterSetupCapabilities(value.get("setup") as JSONObject) else null
    )

    private fun buildUpdaterStatusDocument(value: UpdaterStatusDocument): JSONObject = JSONObject().apply {
        put("protocol", value.`protocol`.toLong())
        put("keys", value.`keys`.toLong())
        put("transaction", value.`transaction`)
        put("id", value.`id`)
        put("phase", value.`phase`.toLong())
        put("result", value.`result`.toLong())
        put("rollback_attempted", value.`rollback_attempted`)
        put("modules", JSONArray().apply { value.`modules`.forEach { item -> put(buildUpdaterModuleStatus(item)) } })
        if (value.`setup` != null) put("setup", buildUpdaterSetupCapabilities(value.`setup`))
    }

    private fun parseUpdaterModuleStatus(value: JSONObject): UpdaterModuleStatus = UpdaterModuleStatus(
        `id` = (value.get("id") as String),
        `code` = (value.get("code") as Long).toInt(),
        `floor` = (value.get("floor") as Long).toInt(),
        `staged` = (value.get("staged") as Long).toInt(),
        `active` = if (value.has("active")) (value.get("active") as Boolean) else null
    )

    private fun buildUpdaterModuleStatus(value: UpdaterModuleStatus): JSONObject = JSONObject().apply {
        put("id", value.`id`)
        put("code", value.`code`.toLong())
        put("floor", value.`floor`.toLong())
        put("staged", value.`staged`.toLong())
        if (value.`active` != null) put("active", value.`active`)
    }

    private fun parseUpdaterSetupCapabilities(value: JSONObject): UpdaterSetupCapabilities = UpdaterSetupCapabilities(
        `timezone` = (value.get("timezone") as Boolean),
        `language` = (value.get("language") as Boolean),
        `device_owner` = (value.get("device_owner") as Boolean)
    )

    private fun buildUpdaterSetupCapabilities(value: UpdaterSetupCapabilities): JSONObject = JSONObject().apply {
        put("timezone", value.`timezone`)
        put("language", value.`language`)
        put("device_owner", value.`device_owner`)
    }

    private fun stringMap(value: JSONObject): Map<String, String> = java.util.Collections.unmodifiableMap(value.keys().asSequence().associateWith { value.getString(it) })
    private fun <T> list(value: JSONArray, read: (Any) -> T): List<T> = java.util.Collections.unmodifiableList((0 until value.length()).map { read(value.get(it)) })
}
