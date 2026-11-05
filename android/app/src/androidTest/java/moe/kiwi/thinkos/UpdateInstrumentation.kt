package moe.kiwi.thinkos

import android.app.Instrumentation
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.update.*
import okhttp3.Dns
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Installed production classes under the real application UID, providers and network policy.
 * Test keys stay in RAM. All test files live under one disposable cache directory.
 * Runtime phase uses silent video. Only explicit install_* phases intentionally replace this package. */
class UpdateInstrumentation : Instrumentation() {
    private var arguments = Bundle()
    private val checks = JSONArray()
    private lateinit var directory: File
    private fun quiet() { check((targetContext.getSystemService(android.content.Context.AUDIO_SERVICE) as AudioManager).getStreamVolume(3) == 0) }
    private suspend fun test(name: String, block: suspend () -> Unit) {
        quiet()
        try {
            block(); checks.put(JSONObject().put("name", name).put("passed", true))
            sendStatus(0, Bundle().apply { putString("pass", name) })
        } catch (error: Throwable) {
            checks.put(JSONObject().put("name", name).put("passed", false).put("error", error.stackTraceToString()))
            sendStatus(0, Bundle().apply { putString("failure", name + ": " + error.toString()) })
        }
        quiet()
    }
    private fun fails(block: () -> Unit) { var failed = false; try { block() } catch (_: Exception) { failed = true }; check(failed) }
    internal fun checkedSnapshot(): JSONArray = JSONArray(checks.toString()).also { values ->
        check(values.length() > 0 && (0 until values.length()).all { values.getJSONObject(it).getBoolean("passed") }) { "A preflight check failed; do not submit an installation" }
    }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); this.arguments = arguments ?: Bundle(); start() }
    override fun onStart() {
        val previous = Thread.currentThread().priority
        Thread.currentThread().priority = Thread.MIN_PRIORITY
        val result = JSONObject().put("sdk", Build.VERSION.SDK_INT).put("uid", Process.myUid()).put("pid", Process.myPid())
        var complete = false
        val ephemeralPhase = arguments.getString("phase") in setOf("production_roots", "debug_commit_fault_arm", "debug_commit_fault_capability_v2")
        try {
            check(Build.VERSION.SDK_INT == 27 && Build.SUPPORTED_ABIS.contains("armeabi-v7a") && Process.myUid() >= 10_000)
            val phase = arguments.getString("phase") ?: "suite"
            result.put("phase", phase)
            if (phase == "production_roots") {
                // A read-only test-APK phase: no run directory, report file,
                // RuntimeHost, fixture roots or action/working-path arguments.
                val observation = try {
                    check(arguments.keySet() == setOf("phase")) { "Only phase is accepted" }
                    runBlocking { ProductionRootsProbe.read(targetContext) }
                } catch (error: Throwable) {
                    JSONObject().put("schema", 1).put("complete", false).put("failure", "production_roots_phase_failed")
                        .put("failure_type", error.javaClass.simpleName.take(80))
                }
                complete = observation.optBoolean("complete", false)
                result.put("production_roots", observation)
                checks.put(JSONObject().put("name", "installed target APK loads its own independent deployment roots")
                    .put("passed", complete))
                return
            }
            if (phase == "debug_commit_fault_capability_v2") {
                val observation = try {
                    runBlocking { DebugCommitFaultCapabilityProbe.run(this@UpdateInstrumentation, arguments) }
                } catch (error: Throwable) {
                    JSONObject().put("schema", 1).put("complete", false).put("scope", "policy_roundtrip_only")
                        .put("failure_type", error.javaClass.simpleName.take(80)).put("runtime_crash_verified", false)
                }
                complete = observation.optBoolean("complete", false)
                result.put("debug_commit_fault_capability_v2", observation)
                checks.put(JSONObject().put("name", "installed owner-debug APK executes isolated v2 compatibility vectors")
                    .put("passed", complete))
                return
            }
            if (phase == "debug_commit_fault_arm") {
                // This ephemeral test phase observes only an existing production
                // READY plan; it never creates a work directory or installs.
                val observation = try {
                    runBlocking { DebugCommitFaultProbe.run(this@UpdateInstrumentation, arguments) }
                } catch (error: Throwable) {
                    JSONObject().put("complete", false).put("stage", "ARM_REFUSED")
                        .put("failure_type", error.javaClass.simpleName.take(80))
                }
                result.put("debug_commit_fault_arm", observation)
                // An armed/bound marker is never successful OTA qualification.
                checks.put(JSONObject().put("name", "debug arm is not installation or rollback evidence").put("passed", false))
                return
            }
            val token = arguments.getString("run_id") ?: error("Missing run_id")
            check(token.matches(Regex("[a-f0-9]{32}")))
            directory = File(targetContext.cacheDir, "update-device-$token").apply { mkdirs() }
            runBlocking {
                when (phase) {
                    "suite" -> suite(result)
                    "prepare" -> prepare()
                    "resume" -> resume()
                    "certs_tls" -> test("bounded CCADB TLS observations use the native system trust store") {
                        result.put("tls_observations", CertTlsProbe.run(context))
                    }
                    "repair_api27" -> test("real app UID confirms updater absence and API27 atomic evidence files") {
                        result.put("repair_api27", JSONObject(kotlinx.coroutines.withContext(Dispatchers.IO) {
                            UpdateRepairApi27Probe.appOnlyBoundaries(targetContext)
                        }))
                    }
                    "call_capabilities" -> test("API27 enumerates bounded camera and AVC metadata without creating devices or codecs") {
                        val observation = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            CallCapabilityProbe.run(targetContext)
                        }
                        result.put("call_capabilities", observation)
                        check(observation.getBoolean("enumeration_complete")) { "Metadata enumeration failed or exceeded bounds; see observations" }
                    }
                    "call_h264_frame" -> test("explicit fixed SDK encodes one gray H264 frame and releases its codec without capture or a call") {
                        val observation = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            CallH264EncodeProbe.run(context, targetContext)
                        }
                        result.put("call_h264_frame", observation)
                        check(observation.getBoolean("functional_complete")) { "Single-frame codec probe failed; see bounded observations" }
                    }
                    "call_webrtc_factory" -> test("explicit local CALL-0 library loads and enumerates factories without creating media devices or codecs") {
                        val observation = kotlinx.coroutines.withContext(Dispatchers.IO) {
                            CallWebRtcFactoryProbe.run(context, targetContext)
                        }
                        result.put("call_webrtc_factory", observation)
                        check(observation.getBoolean("enumeration_complete")) { "Factory enumeration failed; see bounded observations" }
                    }
                    "packages" -> packages(result)
                    "state_prepare" -> UpdateSessionProbe(targetContext, directory).prepare()
                    "state_resume" -> UpdateSessionProbe(targetContext, directory).resume(result, ::test)
                    "runtime" -> UpdateRuntimeProbe(this@UpdateInstrumentation, directory).run(result, ::test)
                    "registry" -> RegistryProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "components_ui" -> UpdateComponentsProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "repair_ui" -> UpdateRepairUiProbe.run(this@UpdateInstrumentation, directory, token, result, ::test,
                        fixtureV3Digest = arguments.getString("fixture_v3_sha256"),
                        fixturePublicDigest = arguments.getString("fixture_public_keys_sha256"))
                    "practice_hint_ui" -> PracticeHintProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "practice_stage_ui" -> PracticeStageProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "welcome_region_ui" -> WelcomeRegionUiProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "welcome_flow" -> WelcomeFlowProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "dlna_sleep" -> WelcomeFlowProbe.run(this@UpdateInstrumentation, directory, token, result, ::test, dlnaSleep = true)
                    "media_public_policy" -> MediaPublicPolicyProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "radio_http" -> RadioHttpProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "radio_discovery" -> RadioHttpProbe.run(this@UpdateInstrumentation, directory, token, result, ::test, discovery = true)
                    "install_probe" -> InstallPrototype.run(this@UpdateInstrumentation, directory, token)
                    "install_downgrade" -> InstallPrototype.run(this@UpdateInstrumentation, directory, token, downgrade = true)
                    "install_transaction" -> InstallTransactionProbe.suite(this@UpdateInstrumentation, directory, result, ::test)
                    "install_production" -> InstallTransactionProbe.submit(this@UpdateInstrumentation, directory, token)
                    "automatic_update" -> AutomaticUpdateProbe.run(this@UpdateInstrumentation, directory, token, result, ::test)
                    "install_abandon" -> test("the app UID abandons its pending prototype session") {
                        val request = JSONObject(File(directory, "install/request.json").readText())
                        val installer = targetContext.packageManager.packageInstaller
                        val id = request.getInt("session_id")
                        if (installer.getSessionInfo(id) != null) installer.abandonSession(id)
                    }
                    else -> error("Unknown update probe phase")
                }
            }
            complete = checks.length() > 0 && (0 until checks.length()).all { checks.getJSONObject(it).getBoolean("passed") }
        } catch (error: Throwable) { result.put("error", error.stackTraceToString()) }
        finally {
            Thread.currentThread().priority = previous
            result.put("complete", complete).put("checks", checks)
            if (!ephemeralPhase && ::directory.isInitialized) directory.resolve("report.json").writeText(result.toString(2))
            finish(if (complete) 0 else 1, Bundle().apply { putString("result", result.toString()) })
        }
    }

    private suspend fun packages(result: JSONObject) {
        val fixture = UpdateFixture()
        val folder = File(directory, "packages")
        val manifest = JSONObject(folder.resolve("fixtures.json").readText())
        @Suppress("DEPRECATION")
        val installed = targetContext.packageManager.getPackageInfo(targetContext.packageName, PackageManager.GET_SIGNATURES)
        val signer = UpdateCatalog.sha256((installed.signatures?.singleOrNull() ?: error("Missing installed signer")).toByteArray())
        val version = manifest.getString("version")
        val code = manifest.getInt("code")
        @Suppress("DEPRECATION")
        check(code > installed.versionCode && code == UpdateCatalog.versionCode(version))
        fun file(name: String): File = folder.resolve(name).also {
            check(name in setOf("valid.apk", "wrong-package.apk", "wrong-signer.apk", "tampered.apk", "wrong-abi.apk", "invalid.apk"))
            val item = manifest.getJSONObject("files").getJSONObject(name)
            check(it.isFile && it.length() == item.getLong("size") && it.length() <= Fetcher.ArtifactKind.APK.maxBytes)
            check(UpdateCatalog.sha256(it.readBytes()) == item.getString("sha256"))
        }
        fun catalog(file: File, seq: Long = 1, url: String = "https://updates.test/app.apk"): UpdateCatalog = fixture.verified(fixture.catalog(seq, url = url).apply {
            getJSONObject("release").put("version", version).put("code", code).put("signer", signer)
                .put("size", file.length()).put("sha256", UpdateCatalog.sha256(file.readBytes()))
        })
        suspend fun rejects(block: suspend () -> Unit) {
            var rejected = false
            try { block() } catch (_: IllegalArgumentException) { rejected = true }
            check(rejected)
        }
        val verifier = UpdatePackageVerifier.forContext(targetContext)
        val valid = file("valid.apk")
        test("real API27 PackageManager verifies the full newer signed ThinkOS APK") {
            val checked = verifier.verify(catalog(valid), valid)
            check(checked.versionCode == code && checked.signer == signer)
            result.put("candidate_version", version).put("candidate_code", code).put("candidate_signer", signer)
        }
        test("real APK version must match the signed catalog and exceed the installed code") {
            val doc = fixture.catalog().apply { getJSONObject("release").put("version", version).put("code", code)
                .put("signer", signer).put("size", valid.length()).put("sha256", UpdateCatalog.sha256(valid.readBytes())) }
            val next = code + 1
            doc.getJSONObject("release").put("code", next).put("version", "${next / 1_000_000}.${next / 1000 % 1000}.${next % 1000}")
            rejects { verifier.verify(fixture.verified(doc), valid) }
        }
        test("real APK signed by another certificate is rejected") {
            val file = file("wrong-signer.apk")
            rejects { verifier.verify(catalog(file), file) }
        }
        test("real signed APK for another package is rejected") {
            val file = file("wrong-package.apk")
            rejects { verifier.verify(catalog(file), file) }
        }
        test("tampered v2 APK is rejected by Android signature collection even with matching outer hash") {
            val file = file("tampered.apk")
            @Suppress("DEPRECATION")
            check(targetContext.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) == null)
            rejects { verifier.verify(catalog(file), file) }
        }
        test("matching signed catalog cannot turn a malformed file into an APK") {
            val file = file("invalid.apk")
            rejects { verifier.verify(catalog(file), file) }
        }
        test("real signed APK with an additional unsupported native ABI is rejected") {
            val file = file("wrong-abi.apk")
            @Suppress("DEPRECATION")
            check(targetContext.packageManager.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNATURES) != null)
            rejects { verifier.verify(catalog(file), file) }
        }
        test("real HTTP signed catalog downloads a full APK and passes native package verification") {
            val payload = valid.readBytes()
            val files = ConcurrentHashMap<String, ByteArray>()
            UpdateTestOrigin { exchange ->
                if (exchange.requestURI.path == "/app.apk") UpdateTestOrigin.artifact(exchange, payload)
                else UpdateTestOrigin.reply(exchange, files.getValue(exchange.requestURI.path))
            }.use { origin ->
                val doc = catalog(valid, 100, origin.url("/app.apk"))
                files["/stable.json"] = doc.bytes(); files["/stable.json.sig"] = doc.signature()
                Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(5000, 5000)).use { fetcher ->
                    val client = UpdateCatalogClient(fetcher, fixture.keys(), UpdateSequenceStore(directory.resolve("package-ledger")))
                    val checkedCatalog = client.check(listOf(origin.url("/stable.json")), "stable")
                    @Suppress("DEPRECATION")
                    val device = UpdateCatalog.Device(Build.DEVICE, Build.VERSION.SDK_INT, installed.versionCode, signer)
                    val checked = client.downloadVerifiedApk(checkedCatalog, device, directory.resolve("package-download"), verifier)
                    check(checked.sha256 == doc.document.release.sha256 && checked.versionCode == code)
                }
            }
        }
    }

    private suspend fun suite(result: JSONObject) {
        test("API10 declarations, factory authority, events and fresh-tap policy run on API27") {
            val manifest = JSONObject("""{"id":"update-probe","name":"Update","version":"0.1.0","api":10,"permissions":["system.update"]}""")
            moe.kiwi.thinkos.plugin.PluginSchema.validateManifest(manifest)
            fails { moe.kiwi.thinkos.plugin.PluginSchema.validateManifest(JSONObject(manifest.toString()).put("api", 9)) }
            val declared = setOf("system.update")
            moe.kiwi.thinkos.plugin.PluginUpdatePolicy.requireRead(10, declared, true)
            fails { moe.kiwi.thinkos.plugin.PluginUpdatePolicy.requireRead(10, declared, false) }
            val unprivileged = moe.kiwi.thinkos.plugin.PluginAuthority.granted(declared, false)
            check(!moe.kiwi.thinkos.plugin.PluginEventPolicy.allows("update", 10, unprivileged))
            check(moe.kiwi.thinkos.plugin.PluginEventPolicy.allows("update", 10, declared))
            val now = android.os.SystemClock.elapsedRealtime()
            check(moe.kiwi.thinkos.plugin.PluginUpdatePolicy.fresh(now, now, 5000))
            check(!moe.kiwi.thinkos.plugin.PluginUpdatePolicy.fresh(now - 5001, now, 5000))
            check(!moe.kiwi.thinkos.plugin.PluginUpdatePolicy.fresh(now + 1, now, 5000))
        }
        val fixture = UpdateFixture()
        val timeouts = UpdateHttpClient.Timeouts(3000, 3000)
        test("API27 parses and verifies all 117 system-module PEM-only roots") {
            val files = context.assets.list("cacerts")!!.sorted()
            check(files.size == 117)
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val fingerprints = files.map { name ->
                check(name.matches(Regex("[0-9a-f]{8}\\.[0-9]+")))
                val root = context.assets.open("cacerts/$name").use {
                    factory.generateCertificate(it) as java.security.cert.X509Certificate
                }
                check(root.basicConstraints >= 0 && root.subjectX500Principal == root.issuerX500Principal)
                root.checkValidity(); root.verify(root.publicKey)
                val hash = java.security.MessageDigest.getInstance("MD5").digest(root.subjectX500Principal.encoded)
                    .take(4).reversed().joinToString("") { "%02x".format(it.toInt() and 255) }
                check(name.startsWith("$hash."))
                UpdateTrust.fingerprint(root)
            }.sorted()
            check(fingerprints.distinct().size == 117)
            val digest = UpdateCatalog.sha256(fingerprints.joinToString("\n", postfix = "\n").toByteArray(Charsets.US_ASCII))
            result.put("module_roots", files.size).put("module_roots_fingerprints_sha256", digest)
        }
        test("installed APK loads and verifies all 16 CA resources") {
            val roots = UpdateTrust.bundledRoots(); check(roots.size == 16)
            val manifest = UpdateTrust::class.java.getResourceAsStream("roots-source.json")!!.use { JSONObject(it.bufferedReader().readText()) }.getJSONArray("certificates")
            roots.forEachIndexed { index, root -> root.verify(root.publicKey); check(UpdateTrust.fingerprint(root) == manifest.getJSONObject(index).getString("der_sha256")) }
        }
        test("native Android trust manager retains system and bundled anchors") {
            val all = UpdateTrust.default.manager.acceptedIssuers.map(UpdateTrust::fingerprint).toSet()
            val system = UpdateTrust.platform().acceptedIssuers.map(UpdateTrust::fingerprint).toSet()
            check(all.containsAll(system) && all.containsAll(UpdateTrust.bundledRoots().map(UpdateTrust::fingerprint)))
            result.put("system_roots", system.size).put("combined_roots", all.size).put("trust_provider", UpdateTrust.default.manager.javaClass.name)
        }
        test("API27 JSON preserves exact Int64 and Chinese while rejecting ambiguity") {
            val json = UpdateJson.read("{\"seq\":9223372036854775807,\"title\":\"中文😀\"}".toByteArray(), 1024)
            check(json.get("seq") == Long.MAX_VALUE && json.getString("title") == "中文😀")
            for (raw in listOf("{\"seq\":1,\"seq\":2}", "{seq:1}", "{\"seq\":1.0}", "{\"seq\":1e0}")) fails { UpdateJson.read(raw.toByteArray(), 1024) }
        }
        test("API27 P256 SPKI imports and active/backup role isolation") {
            val keys = fixture.keys(); val bytes = UpdateFixture.bytes(fixture.catalog())
            for (pair in listOf(fixture.active, fixture.backup)) check(keys.read(UpdateKeys.Role.CHANNEL, SignedDocument.Kind.CATALOG, bytes, fixture.sign(bytes, pair)) { true })
            fails { keys.read(UpdateKeys.Role.CHANNEL, SignedDocument.Kind.CATALOG, bytes, fixture.sign(bytes, fixture.registry)) { } }
            check(keys.read(UpdateKeys.Role.REGISTRY, SignedDocument.Kind.CATALOG, bytes, fixture.sign(bytes, fixture.registryBackup)) { true })
        }
        test("bad signatures never reach a JSON decoder and missing deployed keys fail closed") {
            var decoded = false
            fails { fixture.keys().read(UpdateKeys.Role.CHANNEL, SignedDocument.Kind.CATALOG, "not JSON".toByteArray(), ByteArray(71)) { decoded = true } }
            check(!decoded && UpdateKeys.load(targetContext).isFailure)
        }
        test("catalog compatibility and advisory expiry run on Android providers") {
            val catalog = fixture.verified(fixture.catalog(Long.MAX_VALUE))
            check(catalog.document.seq == Long.MAX_VALUE && catalog.ineligible(fixture.device) == null)
            check(catalog.ineligible(fixture.device.copy(sdk = 28)) == UpdateCatalog.Ineligible.SDK)
            check(catalog.ineligible(fixture.device.copy(signer = "b".repeat(64))) == UpdateCatalog.Ineligible.SIGNER)
            check(catalog.stale(201) && !catalog.stale(null))
        }
        test("private sequence ledger survives reopen and rejects rollback/equivocation") {
            val folder = directory.resolve("ledger/new")
            val store = UpdateSequenceStore(folder); store.accept(fixture.verified(fixture.catalog(9)))
            check(UpdateSequenceStore(folder).snapshot().stable!!.seq == 9L)
            fails { store.accept(fixture.verified(fixture.catalog(8))) }
            val changed = fixture.catalog(9).put("expires", 300)
            fails { store.accept(fixture.verified(changed)) }
            folder.resolve("sequences.json").writeText("{\"schema\":1}")
            fails { store.accept(fixture.verified(fixture.catalog(10))) }
        }
        test("real HTTP signed catalog commits a floor and downloads matching content") {
            val files = ConcurrentHashMap<String, ByteArray>()
            UpdateTestOrigin { exchange ->
                if (exchange.requestURI.path == "/app.apk") UpdateTestOrigin.artifact(exchange, fixture.payload)
                else UpdateTestOrigin.reply(exchange, files.getValue(exchange.requestURI.path))
            }.use { origin -> Fetcher(BuildConfig.VERSION_NAME, timeouts).use { fetcher ->
                @Suppress("DEPRECATION") val packageInfo = targetContext.packageManager.getPackageInfo(targetContext.packageName, PackageManager.GET_SIGNATURES)
                val signer = UpdateCatalog.sha256((packageInfo.signatures?.singleOrNull() ?: error("Missing installed APK signer")).toByteArray())
                val next = Math.addExact(packageInfo.versionCode, 1)
                val nextName = "${next / 1_000_000}.${next / 1000 % 1000}.${next % 1000}"
                val doc = fixture.catalog(17, url = origin.url("/app.apk")).apply {
                    getJSONObject("release").put("signer", signer).put("code", next).put("version", nextName)
                }
                val bytes = UpdateFixture.bytes(doc); files["/stable.json"] = bytes; files["/stable.json.sig"] = fixture.sign(bytes)
                val store = UpdateSequenceStore(directory.resolve("http-ledger"))
                val client = UpdateCatalogClient(fetcher, fixture.keys(), store)
                val catalog = client.check(listOf(origin.url("/stable.json")), "stable")
                val observed = UpdateCatalog.Device(Build.DEVICE, Build.VERSION.SDK_INT, packageInfo.versionCode, signer)
                val file = client.downloadApk(catalog, observed, directory.resolve("http-download"))
                check(file.readBytes().contentEquals(fixture.payload) && store.snapshot().stable!!.seq == 17L)
                check(origin.hits.all { it.headers["user-agent"] == listOf("ThinkOS/${BuildConfig.VERSION_NAME}") && "cookie" !in it.headers && "accept-language" !in it.headers })
            } }
        }
        test("private HTTP mirror rejects oversized bodies and keeps valid fallback") {
            UpdateTestOrigin { UpdateTestOrigin.reply(it, ByteArray(1025), chunked = true) }.use { bad ->
                UpdateTestOrigin { UpdateTestOrigin.reply(it, byteArrayOf(1, 2)) }.use { good -> Fetcher(BuildConfig.VERSION_NAME, timeouts).use { fetcher ->
                    val doc = fetcher.readDocument(listOf(bad.url(), good.url()), Fetcher.DocumentKind.SIGNATURE) as Fetcher.Document.Body
                    check(doc.bytes.contentEquals(byteArrayOf(1, 2)))
                } }
            }
        }
        test("cleartext public DNS is rejected before connecting") {
            val dns = object : Dns { override fun lookup(hostname: String) = listOf(InetAddress.getByAddress(byteArrayOf(8, 8, 8, 8))) }
            val client = UpdateHttpClient.create(BuildConfig.VERSION_NAME, timeouts, dns)
            fails { client.newCall(Request.Builder().url("http://update.test/a").build()).execute().close() }
            client.connectionPool.evictAll()
        }
        test("real TLS accepts an added CA and rejects hostname mismatch and unknown CA") {
            val root = TestCertificates.root(); val leaf = TestCertificates.leaf(root)
            val material = UpdateTrust.fromAnchors(emptyList(), listOf(root.cert))
            UpdateTestOrigin(TestCertificates.server(leaf, root), "https") { UpdateTestOrigin.reply(it, byteArrayOf(1)) }.use { origin ->
                val client = UpdateHttpClient.create(BuildConfig.VERSION_NAME, timeouts, trust = material)
                val url = origin.url().replace("127.0.0.1", "localhost")
                client.newCall(Request.Builder().url(url).build()).execute().use { check(it.code == 200) }
                fails { client.newCall(Request.Builder().url(origin.url()).build()).execute().close() }
                client.connectionPool.evictAll()
                val normal = UpdateHttpClient.create(BuildConfig.VERSION_NAME, timeouts)
                fails { normal.newCall(Request.Builder().url(url).build()).execute().close() }
                normal.connectionPool.evictAll()
            }
        }
        test("real TLS rejects an expired leaf") {
            val root = TestCertificates.root(); val leaf = TestCertificates.leaf(root, expired = true)
            UpdateTestOrigin(TestCertificates.server(leaf, root), "https") { UpdateTestOrigin.reply(it, byteArrayOf(1)) }.use { origin ->
                val client = UpdateHttpClient.create(BuildConfig.VERSION_NAME, timeouts, trust = UpdateTrust.fromAnchors(emptyList(), listOf(root.cert)))
                fails { client.newCall(Request.Builder().url(origin.url().replace("127.0.0.1", "localhost")).build()).execute().close() }
                client.connectionPool.evictAll()
            }
        }
        test("cancelling a stalled transfer closes it and keeps its partial file") {
            val payload = ByteArray(1024 * 1024 + 1) { it.toByte() }; val release = CountDownLatch(1)
            val artifact = Fetcher.Artifact(Fetcher.ArtifactKind.APK, payload.size.toLong(), UpdateCatalog.sha256(payload))
            val folder = directory.resolve("cancel"); val part = File(folder, "${artifact.sha256}.apk.part")
            UpdateTestOrigin { exchange ->
                exchange.responseHeaders.add("Content-Range", "bytes 0-1048575/${payload.size}")
                exchange.sendResponseHeaders(206, 1048576); exchange.responseBody.write(payload, 0, 8192); exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
            }.use { origin -> Fetcher(BuildConfig.VERSION_NAME, timeouts).use { fetcher ->
                try {
                    kotlinx.coroutines.coroutineScope {
                        val job = async(Dispatchers.IO) { fetcher.download(listOf(origin.url()), artifact, folder) }
                        withTimeout(5000) { while (part.length() == 0L) delay(10) }
                        withTimeout(5000) { job.cancelAndJoin() }
                    }
                    check(part.length() in 1..8192)
                } finally { release.countDown() }
            } }
        }
    }

    private fun payload() = ByteArray(2 * 1024 * 1024 + 257) { (it * 17).toByte() }
    private suspend fun prepare() {
        quiet()
        val bytes = payload(); val release = CountDownLatch(1)
        val artifact = Fetcher.Artifact(Fetcher.ArtifactKind.APK, bytes.size.toLong(), UpdateCatalog.sha256(bytes))
        val folder = directory.resolve("process-resume"); val part = File(folder, "${artifact.sha256}.apk.part")
        UpdateTestOrigin { exchange ->
            exchange.responseHeaders.add("Content-Range", "bytes 0-1048575/${bytes.size}")
            exchange.sendResponseHeaders(206, 1048576); exchange.responseBody.write(bytes, 0, 65536); exchange.responseBody.flush()
            release.await(60, TimeUnit.SECONDS)
        }.use { origin -> Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(15000, 30000)).use { fetcher ->
            kotlinx.coroutines.coroutineScope {
                val transfer = async(Dispatchers.IO) { fetcher.download(listOf(origin.url()), artifact, folder) }
                withTimeout(10000) { while (part.length() != 65536L) delay(10) }
                sendStatus(0, Bundle().apply { putString("checkpoint", "partial=65536 pid=${Process.myPid()}") })
                directory.resolve("checkpoint").writeText(JSONObject().put("bytes", 65536).put("sha256", artifact.sha256).put("pid", Process.myPid()).toString())
                transfer.await() // Host deliberately kills this real app process before the body can finish.
            }
        } }
    }
    private suspend fun resume() = test("a new API27 process resumes the killed transfer from byte 65536") {
        val bytes = payload(); val artifact = Fetcher.Artifact(Fetcher.ArtifactKind.APK, bytes.size.toLong(), UpdateCatalog.sha256(bytes))
        val folder = directory.resolve("process-resume"); check(File(folder, "${artifact.sha256}.apk.part").length() == 65536L)
        UpdateTestOrigin { UpdateTestOrigin.artifact(it, bytes) }.use { origin ->
            Fetcher(BuildConfig.VERSION_NAME, UpdateHttpClient.Timeouts(15000, 30000)).use { fetcher ->
                val file = fetcher.download(listOf(origin.url()), artifact, folder)
                check(UpdateCatalog.sha256(file.readBytes()) == artifact.sha256)
                check(origin.hits.first().headers.getValue("range").single() == "bytes=65536-1114111")
            }
        }
    }
}
