package moe.kiwi.thinkos.plugin

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.kiwi.thinkos.settings.SettingsPersistence
import moe.kiwi.thinkos.settings.SettingsStore
import moe.kiwi.thinkos.settings.SystemSettings
import moe.kiwi.thinkos.update.RegistryModels
import moe.kiwi.thinkos.update.UpdateFixture
import moe.kiwi.thinkos.update.UpdateJson
import moe.kiwi.thinkos.update.UpdateKeys
import moe.kiwi.thinkos.update.UpdateTestOrigin
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class RegistryRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixture = UpdateFixture()
    private val id = "probe"
    private data class Package(val version: String, val digest: String, val index: JSONObject, val blob: ByteArray,
                               val sha: String, val pluginId: String, val permissions: List<String>)
    private fun pack(version: String, pluginId: String = id, permissions: List<String> = emptyList()): Package {
        val blob = JSONObject().put("id", pluginId).put("name", "Probe").put("version", version)
            .put("api", PluginSchema.API).put("permissions", JSONArray(permissions)).toString().toByteArray()
        val sha = RegistryPolicy.hash(blob)
        val digest = RegistryPolicy.hash("manifest.json\u0000$sha\n".toByteArray())
        val index = JSONObject().put("id", pluginId).put("version", version).put("digest", digest)
            .put("files", JSONArray().put(JSONObject().put("path", "manifest.json").put("sha256", sha).put("size", blob.size)))
        return Package(version, digest, index, blob, sha, pluginId, permissions)
    }
    private val old = pack("1.0.0")
    private val next = pack("1.1.0")
    private fun record(pkg: Package, trial: Boolean = false): JSONObject = JSONObject().put("id", pkg.pluginId).put("version", pkg.version)
        .put("digest", pkg.digest).put("commit", "a".repeat(40)).put("repo", "thinkos-tests/${pkg.pluginId}").put("tier", "reviewed")
        .put("name", "Probe").put("confirmed", JSONArray(pkg.permissions)).put("auto", true).put("enabled", true).put("trial", trial)
    private fun trial(): JSONObject = record(next, true).put("previous", record(old).apply {
        listOf("id", "auto", "enabled", "trial").forEach { remove(it) }
    })
    private fun state(record: JSONObject) = JSONObject().put("schema", 1).put("disabled", false)
        .put("installed", JSONArray().put(record)).put("blocked", JSONArray())
    private fun revocation(digest: String, pluginId: String = id) = JSONObject().put("id", pluginId).put("digest", digest).put("reason", "withdrawn")
    private fun catalog(base: String = "http://127.0.0.1/", seq: Long = 1, revoked: List<JSONObject> = emptyList(), pkg: Package = next): JSONObject {
        val why = JSONObject().also { value -> pkg.permissions.forEach { value.put(it, JSONObject().put("en", "Read fixture data")) } }
        val release = JSONObject().put("version", pkg.version).put("commit", "a".repeat(40)).put("digest", pkg.digest)
            .put("api", PluginSchema.API).put("size", pkg.blob.size).put("permissions", JSONArray(pkg.permissions)).put("why", why).put("apps", JSONArray())
        val plugin = JSONObject().put("id", pkg.pluginId).put("tier", "reviewed").put("name", JSONObject().put("en", "Probe"))
            .put("summary", JSONObject().put("en", "Test")).put("author", "ThinkOS tests").put("repo", "thinkos-tests/${pkg.pluginId}")
            .put("license", "MIT").put("tags", JSONArray()).put("releases", JSONArray().put(release))
        return JSONObject().put("schema", 1).put("seq", seq).put("generated", 1).put("expires", 9_007_199_254_740_991L)
            .put("blob_bases", JSONArray().put(base)).put("revoked", JSONArray(revoked)).put("plugins", JSONArray().put(plugin))
    }
    private fun settings(source: String = ""): SettingsStore {
        val schema = File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata").parentFile.resolve("schema/settings/system.json").readText()
        return SettingsStore(object : SettingsPersistence {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
        }).also {
            it.register(SystemSettings.parse(schema))
            it.set(SettingsStore.SYSTEM, "plugins.mirror", source)
            it.set(SettingsStore.SYSTEM, "plugins.auto_update", "off")
            assertEquals(600L, it.get(SettingsStore.SYSTEM, "plugins.trial_s"))
        }
    }
    /** Manual waits expose registration before the test advances virtual time. */
    private class TestClock(var wallMillis: Long = 1_000) {
        data class Waiting(val millis: Long, val deadline: Long, val release: CompletableDeferred<Unit>)
        val scheduled = Channel<Waiting>(Channel.UNLIMITED)
        private var elapsed = 0L
        private val pending = mutableListOf<Waiting>()
        suspend fun await(millis: Long) {
            val waiting = synchronized(this) {
                Waiting(millis, elapsed + millis, CompletableDeferred()).also(pending::add)
            }
            scheduled.send(waiting)
            try { waiting.release.await() }
            finally { synchronized(this) { pending.remove(waiting) } }
        }
        fun advanceBy(millis: Long) {
            require(millis >= 0)
            val due = synchronized(this) {
                elapsed += millis; wallMillis += millis
                pending.filter { it.deadline <= elapsed }.also { pending.removeAll(it.toSet()) }
            }
            due.forEach { it.release.complete(Unit) }
        }
    }
    /** Ordinary temporary files replace only Android AtomicFile/resource/asset access. */
    private class FilesEnvironment(override val filesDir: File, private val roots: UpdateKeys,
                                   private val clock: TestClock? = null) : RegistryRepository.Environment {
        override suspend fun keys() = roots
        override fun text(key: String) = key
        override fun read(file: File, limit: Int): JSONObject? = if (!file.exists()) null else UpdateJson.read(file.readBytes(), limit)
        var failCacheWrite = false
        var onStateWrite: ((JSONObject) -> Unit)? = null
        override fun write(file: File, bytes: ByteArray) {
            if (failCacheWrite && file.name == "cache.json") throw IOException("Interrupted registry cache publication")
            check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()); file.writeBytes(bytes)
            if (file.name == "registry-state.json") onStateWrite?.invoke(UpdateJson.read(bytes, 256 * 1024))
        }
        override suspend fun await(millis: Long) {
            if (clock == null) super<RegistryRepository.Environment>.await(millis) else clock.await(millis)
        }
        override fun wallTimeMillis(): Long = clock?.wallMillis ?: super<RegistryRepository.Environment>.wallTimeMillis()
        fun state(): JSONObject = read(File(filesDir, "plugins/registry-state.json"), 256 * 1024)!!.also { RegistryModels.readRegistryState(it) }
        fun seed(value: JSONObject) = write(File(filesDir, "plugins/registry-state.json"), value.toString().toByteArray())
    }
    private fun environment(value: JSONObject): FilesEnvironment = FilesEnvironment(temporary.newFolder(), fixture.keys()).also { it.seed(state(value)) }
    private fun cache(environment: FilesEnvironment, doc: JSONObject, badSignature: Boolean = false) {
        val bytes = doc.toString().toByteArray()
        val signature = fixture.sign(bytes, if (badSignature) fixture.active else fixture.registry)
        environment.write(File(environment.filesDir, "registry/cache.json"), JSONObject().put("schema", 1)
            .put("document", Base64.getEncoder().encodeToString(bytes)).put("signature", Base64.getEncoder().encodeToString(signature)).toString().toByteArray())
        environment.write(File(environment.filesDir, "registry/ledger.json"), JSONObject().put("schema", 1).put("seq", doc.getLong("seq"))
            .put("digest", RegistryPolicy.hash(bytes)).toString().toByteArray())
    }
    private class RepositoryHost(private val environment: FilesEnvironment) : RegistryRepository.Host {
        val activated = CopyOnWriteArrayList<String>()
        val activationEvents = Channel<String>(Channel.UNLIMITED)
        val removals = Channel<String>(Channel.UNLIMITED)
        val unavailable = mutableSetOf<String>()
        val prepared = CopyOnWriteArrayList<String>()
        val consents = CopyOnWriteArrayList<RegistryConsent>()
        var onConsent: suspend (RegistryConsent) -> Boolean = { true }
        @Volatile var activeDigest: String? = null
        @Volatile var busy = false
        override fun prepare(index: PluginFiles.Index, blobs: Map<String, File>, record: JSONObject): File {
            prepared += index.digest
            val directory = File(environment.filesDir, "plugins/${index.id}/${index.version}-${index.digest}")
            PluginFiles.copy(index, directory) { blobs.getValue(it).inputStream() }
            return directory
        }
        override suspend fun activate(record: JSONObject): Boolean {
            // Confirmations and both recovery identities must already be durable before execution.
            val journal = RegistryPolicy.objects(environment.state().getJSONArray("installed"))
                .single { it.getString("id") == record.getString("id") }
            val digest = record.getString("digest")
            val durable = if (journal.getString("digest") == digest) journal else requireNotNull(journal.optJSONObject("previous"))
            check(durable.getString("digest") == digest)
            check(RegistryPolicy.strings(durable.getJSONArray("confirmed")) == RegistryPolicy.strings(record.getJSONArray("confirmed")))
            activated.add(digest)
            if (digest in unavailable) return false
            activeDigest = digest
            activationEvents.send(digest)
            return true
        }
        override suspend fun remove(id: String) { activeDigest = null; removals.send(id) }
        override suspend fun open(id: String) = activeDigest != null
        override suspend fun busy(id: String) = busy
        override suspend fun consent(value: RegistryConsent): Boolean { consents += value; return onConsent(value) }
        override fun changed() = Unit
        override fun ready() = Unit
    }
    private fun publish(files: MutableMap<String, ByteArray>, doc: JSONObject, badSignature: Boolean = false) {
        val bytes = doc.toString().toByteArray()
        files["/index.json"] = bytes
        files["/index.json.sig"] = fixture.sign(bytes, if (badSignature) fixture.active else fixture.registry)
    }
    private suspend fun withOffer(pkg: Package, environment: FilesEnvironment,
                                  block: suspend (RegistryRepository, RepositoryHost, UpdateTestOrigin, MutableMap<String, ByteArray>) -> Unit) {
        val files = ConcurrentHashMap<String, ByteArray>()
        files["/registry/files/${pkg.digest}.json"] = pkg.index.toString().toByteArray()
        files["/blobs/${pkg.sha.take(2)}/${pkg.sha}"] = pkg.blob
        UpdateTestOrigin { exchange ->
            val bytes = files[exchange.requestURI.path]
            UpdateTestOrigin.reply(exchange, bytes ?: byteArrayOf(), if (bytes == null) 404 else 200)
        }.use { source ->
            publish(files, catalog(source.url("/"), pkg = pkg))
            val host = RepositoryHost(environment)
            val repository = RegistryRepository(environment, settings(source.url("/index.json")), host)
            try {
                assertNull(repository.catalog(mapOf("refresh" to true))["error"])
                block(repository, host, source, files)
            } finally { repository.close() }
        }
    }
    private suspend fun liveTrial(block: suspend (RegistryRepository, FilesEnvironment, RepositoryHost, UpdateTestOrigin, MutableMap<String, ByteArray>) -> Unit) {
        val environment = environment(record(old))
        val files = ConcurrentHashMap<String, ByteArray>()
        files["/registry/files/${next.digest}.json"] = next.index.toString().toByteArray()
        files["/blobs/${next.sha.take(2)}/${next.sha}"] = next.blob
        UpdateTestOrigin { exchange ->
            val bytes = files[exchange.requestURI.path]
            UpdateTestOrigin.reply(exchange, bytes ?: byteArrayOf(), if (bytes == null) 404 else 200)
        }.use { source ->
            publish(files, catalog(source.url("/")))
            val host = RepositoryHost(environment).also { it.activeDigest = old.digest }
            val repository = RegistryRepository(environment, settings(source.url("/index.json")), host)
            try {
                assertNull(repository.catalog(mapOf("refresh" to true))["error"])
                assertTrue(repository.mutate("update", id))
                assertEquals(next.digest, host.activeDigest)
                assertEquals(next.digest, host.activationEvents.receive())
                block(repository, environment, host, source, files)
            } finally { repository.close() }
        }
    }
    private suspend fun removed(repository: RegistryRepository, host: RepositoryHost) {
        assertEquals(id, withTimeout(5_000) { host.removals.receive() })
        repository.mutate("open", id) // Mutex barrier: the failure transaction has completed.
    }
    private fun quarantined(environment: FilesEnvironment, reason: String, failure: String) {
        val state = environment.state()
        val value = state.getJSONArray("installed").getJSONObject(0)
        assertEquals(next.digest, value.getString("digest"))
        assertEquals(old.digest, value.getJSONObject("previous").getString("digest"))
        assertFalse(value.getBoolean("enabled")); assertFalse(value.getBoolean("trial"))
        assertEquals(reason, value.getString("error"))
        val blocked = state.getJSONArray("blocked").getJSONObject(0)
        assertEquals(id, blocked.getString("id")); assertEquals(next.digest, blocked.getString("digest")); assertEquals(failure, blocked.getString("reason"))
    }

    private fun ledger(environment: FilesEnvironment, doc: JSONObject) {
        environment.write(File(environment.filesDir, "registry/ledger.json"), JSONObject().put("schema", 1).put("seq", doc.getLong("seq"))
            .put("digest", RegistryPolicy.hash(doc.toString().toByteArray())).toString().toByteArray())
    }
    private fun trialPreserved(environment: FilesEnvironment) {
        val state = environment.state()
        val pending = state.getJSONArray("installed").getJSONObject(0)
        assertEquals(next.digest, pending.getString("digest")); assertTrue(pending.getBoolean("trial"))
        assertEquals(old.digest, pending.getJSONObject("previous").getString("digest"))
        assertEquals(0, state.getJSONArray("blocked").length())
    }

    @Test(timeout = 10_000) fun theRealTrialWaitRemainsProvisionalAt599999AndPromotesAt600000VirtualMillis() = runBlocking<Unit> {
        val clock = TestClock()
        val environment = FilesEnvironment(temporary.newFolder(), fixture.keys(), clock).also { it.seed(state(record(old))) }
        withOffer(next, environment) { repository, host, _, _ ->
            val promoted = CompletableDeferred<JSONObject>()
            environment.onStateWrite = { document ->
                val value = document.getJSONArray("installed").getJSONObject(0)
                if (value.getString("digest") == next.digest && !value.getBoolean("trial")) promoted.complete(document)
            }
            assertTrue(repository.mutate("update", id))
            val waiting = withTimeout(5_000) { clock.scheduled.receive() }
            assertEquals(600_000L, waiting.millis)
            clock.advanceBy(599_999)
            assertTrue(repository.mutate("open", id)) // Public mutex barrier; no health-state shortcut.
            trialPreserved(environment)
            assertFalse(waiting.release.isCompleted)
            assertFalse(promoted.isCompleted)
            clock.advanceBy(1)
            val document = withTimeout(5_000) { promoted.await() }
            assertTrue(repository.mutate("open", id)) // Wait for the real health transaction to release its mutex.
            assertFalse(repository.isTrial(id, next.digest))
            val healthy = document.getJSONArray("installed").getJSONObject(0)
            assertEquals(next.digest, healthy.getString("digest"))
            assertTrue(healthy.getBoolean("enabled")); assertFalse(healthy.getBoolean("trial"))
            assertFalse(healthy.has("previous"))
            assertFalse(environment.state().getJSONArray("installed").getJSONObject(0).getBoolean("trial"))
            assertEquals(listOf(next.digest), host.activated)
        }
    }

    @Test(timeout = 10_000) fun catalogExpiryUsesTheEnvironmentWallClockWithoutFetchingOrRewritingState() = runBlocking<Unit> {
        val clock = TestClock()
        val environment = FilesEnvironment(temporary.newFolder(), fixture.keys(), clock).also { it.seed(state(record(old))) }
        withOffer(next, environment) { repository, _, source, files ->
            val expires = UpdateJson.read(files.getValue("/index.json"), 2 * 1024 * 1024).getLong("expires")
            val stateFile = File(environment.filesDir, "plugins/registry-state.json")
            val before = stateFile.readBytes()
            val requests = source.hits.size
            clock.wallMillis = expires - 1
            assertEquals(false, repository.catalog(emptyMap<String, Any>())["expired"])
            clock.wallMillis = expires
            assertEquals(false, repository.catalog(emptyMap<String, Any>())["expired"])
            clock.wallMillis = expires + 1
            assertEquals(true, repository.catalog(emptyMap<String, Any>())["expired"])
            assertEquals(requests, source.hits.size)
            assertArrayEquals(before, stateFile.readBytes())
        }
    }

    @Test(timeout = 10_000) fun refusedConsentCannotDownloadPreparePersistOrActivateThePermissionIncrease() = runBlocking<Unit> {
        val environment = environment(record(old))
        val requested = pack("1.1.0", permissions = listOf("network"))
        withOffer(requested, environment) { repository, host, source, _ ->
            host.activeDigest = old.digest
            host.onConsent = { false }
            val stateFile = File(environment.filesDir, "plugins/registry-state.json")
            val before = stateFile.readBytes()
            val requests = source.hits.size
            val error = runCatching { repository.mutate("update", id) }.exceptionOrNull()
            assertTrue(error is IllegalStateException)
            assertEquals("cancelled", error?.message)
            assertEquals(listOf("network"), host.consents.single().permissions.map { it.first })
            assertEquals(requests, source.hits.size)
            assertTrue(host.prepared.isEmpty()); assertTrue(host.activated.isEmpty())
            assertEquals(old.digest, host.activeDigest)
            assertArrayEquals(before, stateFile.readBytes())
        }
    }

    @Test(timeout = 10_000) fun permissionIncreaseRequiresNewConsentAndDurableConfirmationBeforeExecution() = runBlocking<Unit> {
        val environment = environment(record(old))
        val requested = pack("1.1.0", permissions = listOf("network"))
        withOffer(requested, environment) { repository, host, source, _ ->
            host.activeDigest = old.digest
            val stateFile = File(environment.filesDir, "plugins/registry-state.json")
            val before = stateFile.readBytes()
            val requests = source.hits.size
            val automaticError = runCatching { repository.mutate("update", id, automatic = true) }.exceptionOrNull()
            assertTrue(automaticError is IllegalArgumentException)
            assertEquals("confirmation-required", automaticError?.message)
            assertTrue(host.consents.isEmpty()); assertTrue(host.prepared.isEmpty()); assertTrue(host.activated.isEmpty())
            assertEquals(requests, source.hits.size)
            assertArrayEquals(before, stateFile.readBytes())
            val entered = CompletableDeferred<RegistryConsent>()
            val approval = CompletableDeferred<Boolean>()
            host.onConsent = { entered.complete(it); approval.await() }
            val attempt = async { repository.mutate("update", id) }
            try {
                val consent = withTimeout(5_000) { entered.await() }
                assertEquals(id, consent.id)
                assertEquals("thinkos-tests/probe", consent.repo)
                assertEquals(listOf("network"), consent.permissions.map { it.first })
                assertEquals(requests, source.hits.size)
                assertTrue(host.prepared.isEmpty()); assertTrue(host.activated.isEmpty())
                assertEquals(old.digest, host.activeDigest)
                assertArrayEquals(before, stateFile.readBytes())
                approval.complete(true)
                assertTrue(withTimeout(5_000) { attempt.await() })
                // Host.activate itself rereads this journal and checks the
                // confirmed set before recording any execution event.
                val durable = environment.state().getJSONArray("installed").getJSONObject(0)
                assertEquals(setOf("network"), RegistryPolicy.strings(durable.getJSONArray("confirmed")))
                assertEquals(requested.digest, durable.getString("digest")); assertTrue(durable.getBoolean("trial"))
                assertEquals(old.digest, durable.getJSONObject("previous").getString("digest"))
                assertEquals(listOf(requested.digest), host.prepared)
                assertEquals(listOf(requested.digest), host.activated)
                assertEquals(1, host.consents.size)
            } finally { approval.complete(false); attempt.cancelAndJoin() }
        }
    }

    @Test(timeout = 10_000) fun publicInstallReachesSixteenThenRefusesSeventeenBeforeConsentOrDownload() = runBlocking<Unit> {
        val environment = FilesEnvironment(temporary.newFolder(), fixture.keys())
        val seeded = (1..15).map { record(pack("1.0.0", pluginId = "seed-$it")) }
        environment.seed(JSONObject().put("schema", 1).put("disabled", false)
            .put("installed", JSONArray(seeded)).put("blocked", JSONArray()))
        val sixteenth = pack("1.0.0", pluginId = "sixteenth")
        withOffer(sixteenth, environment) { repository, host, source, files ->
            assertEquals(15, repository.installed().size)
            assertTrue(repository.mutate("install", sixteenth.pluginId))
            assertEquals(16, repository.installed().size)
            assertTrue(repository.installed().map { it["id"] }.containsAll(seeded.map { it.getString("id") }))
            val seventeenth = pack("1.0.0", pluginId = "seventeenth")
            files["/registry/files/${seventeenth.digest}.json"] = seventeenth.index.toString().toByteArray()
            files["/blobs/${seventeenth.sha.take(2)}/${seventeenth.sha}"] = seventeenth.blob
            publish(files, catalog(source.url("/"), 2, pkg = seventeenth))
            assertNull(repository.catalog(mapOf("refresh" to true))["error"])
            val stateFile = File(environment.filesDir, "plugins/registry-state.json")
            val before = stateFile.readBytes()
            val requests = source.hits.size
            val error = runCatching { repository.mutate("install", seventeenth.pluginId) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("plugin-limit", error?.message)
            assertEquals(16, repository.installed().size)
            assertArrayEquals(before, stateFile.readBytes())
            assertEquals(requests, source.hits.size)
            assertEquals(listOf(sixteenth.pluginId), host.consents.map { it.id })
            assertEquals(listOf(sixteenth.digest), host.prepared)
            assertEquals(listOf(sixteenth.digest), host.activated)
            assertEquals(sixteenth.digest, host.activeDigest)
        }
    }

    @Test(timeout = 10_000) fun malformedStateReturnsAnEmptyRepositoryWithoutOverwritingTheRawEvidence() = runBlocking<Unit> {
        val invalid = listOf(ByteArray(0), "{\"schema\":".toByteArray(),
            "{\"schema\":1,\"schema\":1}".toByteArray(),
            state(record(old)).put("installed", "not-an-array").toString().toByteArray(),
            ByteArray(256 * 1024 + 1) { ' '.code.toByte() })
        for (bytes in invalid) {
            val environment = environment(record(old))
            val stateFile = File(environment.filesDir, "plugins/registry-state.json").apply { writeBytes(bytes) }
            val retainedDirectory = File(environment.filesDir, "plugins/$id/${old.version}-${old.digest}").apply { check(mkdirs()) }
            val retainedManifest = File(retainedDirectory, "manifest.json").apply { writeBytes(old.blob) }
            val stateWrites = java.util.concurrent.atomic.AtomicInteger()
            environment.onStateWrite = { stateWrites.incrementAndGet() }
            val host = RepositoryHost(environment)
            val repository = RegistryRepository(environment, settings(), host)
            try {
                repository.start()
                val catalog = repository.catalog(emptyMap<String, Any>()) // Actual initialize/refresh mutex barrier.
                assertTrue(repository.installed().isEmpty())
                assertTrue((catalog["items"] as List<*>).isEmpty())
                assertNotNull(catalog["error"])
                assertTrue(host.prepared.isEmpty()); assertTrue(host.activated.isEmpty())
                val error = runCatching { repository.mutate("install", id) }.exceptionOrNull()
                assertTrue(error is IllegalStateException)
                assertEquals("state-corrupt", error?.message)
                assertArrayEquals(bytes, stateFile.readBytes())
                assertEquals(0, stateWrites.get())
                assertTrue(retainedDirectory.isDirectory)
                assertArrayEquals(old.blob, retainedManifest.readBytes())
                assertFalse(File(environment.filesDir, "registry/ledger.json").exists())
            } finally { repository.close() }
        }
    }

    @Test(timeout = 10_000) fun anotherNewerUpdateCannotReplaceARunningTrialOrItsHealthyPredecessor() = runBlocking<Unit> {
        liveTrial { repository, environment, host, source, files ->
            val newer = pack("1.2.0")
            files["/registry/files/${newer.digest}.json"] = newer.index.toString().toByteArray()
            files["/blobs/${newer.sha.take(2)}/${newer.sha}"] = newer.blob
            val doc = catalog(source.url("/"), 2)
            doc.getJSONArray("plugins").getJSONObject(0).getJSONArray("releases").getJSONObject(0)
                .put("version", newer.version).put("digest", newer.digest).put("size", newer.blob.size)
            publish(files, doc)
            assertNull(repository.catalog(mapOf("refresh" to true))["error"])
            val stateFile = File(environment.filesDir, "plugins/registry-state.json")
            val ledger = File(environment.filesDir, "registry/ledger.json")
            val beforeState = stateFile.readBytes()
            val beforeLedger = ledger.readBytes()
            val error = runCatching { repository.mutate("update", id) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException)
            assertEquals("trial-in-progress", error?.message)
            assertArrayEquals(beforeState, stateFile.readBytes())
            assertArrayEquals(beforeLedger, ledger.readBytes())
            trialPreserved(environment)
            assertEquals(next.digest, host.activeDigest)
            assertEquals(listOf(next.digest), host.activated)
            assertTrue(host.removals.tryReceive().isFailure)
        }
    }

    @Test(timeout = 10_000) fun authenticatedRevocationOfPreviousPreventsExecutionAfterTheRunningTrialFails() = runBlocking<Unit> {
        liveTrial { repository, environment, host, source, files ->
            publish(files, catalog(source.url("/"), 2, listOf(revocation(old.digest))))
            assertNull(repository.catalog(mapOf("refresh" to true))["error"])
            assertEquals(next.digest, host.activeDigest)
            repository.failed(id, next.digest)
            removed(repository, host)
            assertEquals(listOf(next.digest), host.activated)
            assertNull(host.activeDigest)
            quarantined(environment, "previous-revoked", "trial-failed")
            assertEquals(setOf(old, next).map { File(environment.filesDir, "plugins/$id/${it.version}-${it.digest}").absolutePath }.toSet(), repository.keepDirectories())
        }
    }

    @Test(timeout = 10_000) fun interruptedTrialAuthenticatesCachedRevocationsBeforeSelectingPrevious() = runBlocking<Unit> {
        val environment = environment(trial())
        cache(environment, catalog(revoked = listOf(revocation(old.digest))))
        val host = RepositoryHost(environment).also { it.activeDigest = old.digest }
        val repository = RegistryRepository(environment, settings(), host)
        try {
            assertFalse(repository.mutate("open", id))
            assertEquals(id, withTimeout(5_000) { host.removals.receive() })
            assertTrue(host.activated.isEmpty()); assertNull(host.activeDigest)
            quarantined(environment, "previous-revoked", "trial_interrupted")
        } finally { repository.close() }
    }

    @Test(timeout = 10_000) fun unavailablePreviousRetainsBothRecordsAndRemovesTheFailedHost() = runBlocking<Unit> {
        liveTrial { repository, environment, host, _, _ ->
            host.unavailable.add(old.digest)
            repository.failed(id, next.digest)
            removed(repository, host)
            assertEquals(listOf(next.digest, old.digest), host.activated)
            assertNull(host.activeDigest)
            quarantined(environment, "previous-unavailable", "trial-failed")
        }
    }

    @Test(timeout = 10_000) fun unavailablePreviousAfterRestartStillRetainsTheOriginalTrialEvidence() = runBlocking<Unit> {
        val environment = environment(trial())
        cache(environment, catalog())
        val host = RepositoryHost(environment).also { it.unavailable.add(old.digest); it.activeDigest = next.digest }
        val repository = RegistryRepository(environment, settings(), host)
        try {
            repository.start()
            removed(repository, host)
            assertEquals(listOf(old.digest), host.activated)
            assertNull(host.activeDigest)
            quarantined(environment, "previous-unavailable", "trial_interrupted")
        } finally { repository.close() }
    }

    @Test(timeout = 10_000) fun exactIdAndDigestAllowSafePreviousAndRecoverySurvivesAnotherRestartBeforeTheStartupBatch() = runBlocking<Unit> {
        val environment = environment(trial())
        cache(environment, catalog(revoked = listOf(revocation(old.digest, "other"), revocation("f".repeat(64)))))
        val first = RegistryRepository(environment, settings(), RepositoryHost(environment))
        try {
            first.mutate("open", id) // Initialize without starting the deferred activation loop.
            val pending = environment.state().getJSONArray("installed").getJSONObject(0)
            assertEquals(next.digest, pending.getString("digest")); assertTrue(pending.getBoolean("trial")); assertFalse(pending.getBoolean("enabled"))
            assertEquals(old.digest, pending.getJSONObject("previous").getString("digest"))
        } finally { first.close() }
        val host = RepositoryHost(environment)
        val restarted = RegistryRepository(environment, settings(), host)
        try {
            restarted.start()
            assertEquals(old.digest, withTimeout(5_000) { host.activationEvents.receive() })
            restarted.mutate("open", id)
            assertEquals(listOf(old.digest), host.activated)
            val restored = environment.state().getJSONArray("installed").getJSONObject(0)
            assertEquals(old.digest, restored.getString("digest")); assertTrue(restored.getBoolean("enabled")); assertFalse(restored.getBoolean("trial"))
            assertFalse(restored.has("previous"))
        } finally { restarted.close() }
    }

    @Test(timeout = 10_000) fun unauthenticatedRevocationsCannotDisableTheSafePrevious() = runBlocking<Unit> {
        liveTrial { repository, environment, host, source, files ->
            publish(files, catalog(source.url("/"), 2, listOf(revocation(old.digest))), badSignature = true)
            assertEquals("catalog-unavailable", repository.catalog(mapOf("refresh" to true))["error"])
            repository.failed(id, next.digest)
            assertEquals(old.digest, withTimeout(5_000) { host.activationEvents.receive() })
            repository.mutate("open", id)
            assertEquals(listOf(next.digest, old.digest), host.activated)
            assertEquals(old.digest, environment.state().getJSONArray("installed").getJSONObject(0).getString("digest"))
        }
    }

    @Test(timeout = 10_000) fun revokedCurrentIsRemovedBeforeAnyCachedStartupActivation() = runBlocking<Unit> {
        val environment = environment(record(old))
        cache(environment, catalog(revoked = listOf(revocation(old.digest))))
        val host = RepositoryHost(environment).also { it.activeDigest = old.digest }
        val repository = RegistryRepository(environment, settings(), host)
        try {
            repository.mutate("open", id)
            assertEquals(id, withTimeout(5_000) { host.removals.receive() })
            assertTrue(host.activated.isEmpty()); assertNull(host.activeDigest)
            val value = environment.state().getJSONArray("installed").getJSONObject(0)
            assertFalse(value.getBoolean("enabled")); assertEquals("revoked", value.getString("error"))
        } finally { repository.close() }
    }

    @Test(timeout = 20_000) fun acceptedFloorWithMissingStaleOrInvalidCacheCannotExecuteOrRollbackUntilAuthenticatedRecovery() = runBlocking<Unit> {
        val files = ConcurrentHashMap<String, ByteArray>()
        UpdateTestOrigin { exchange ->
            val bytes = files[exchange.requestURI.path]
            UpdateTestOrigin.reply(exchange, bytes ?: byteArrayOf(), if (bytes == null) 404 else 200)
        }.use { source ->
            val earlier = catalog(source.url("/"))
            val accepted = catalog(source.url("/"), 2, listOf(revocation(old.digest)))
            for (variant in listOf("stale", "missing", "malformed", "wrong-role", "fork")) {
                val environment = environment(trial())
                cache(environment, earlier)
                val cacheFile = File(environment.filesDir, "registry/cache.json")
                when (variant) {
                    "missing" -> check(cacheFile.delete())
                    "malformed" -> cacheFile.writeText("{")
                    "wrong-role" -> cache(environment, accepted, badSignature = true)
                    "fork" -> cache(environment, catalog(source.url("/"), 2))
                }
                // Exact cross-file cut: the newer floor is durable, its matching cache is not.
                ledger(environment, accepted)
                publish(files, earlier)
                val host = RepositoryHost(environment).also { it.activeDigest = next.digest }
                val repository = RegistryRepository(environment, settings(source.url("/index.json")), host)
                try {
                    repository.mutate("open", id)
                    removed(repository, host)
                    assertTrue("$variant cache activated code", host.activated.isEmpty()); assertNull(host.activeDigest)
                    trialPreserved(environment)
                    repository.start()
                    repository.failed(id, next.digest)
                    removed(repository, host) // The async failure path must also wait for revocation evidence.
                    trialPreserved(environment)
                    assertTrue(host.activated.isEmpty())
                    assertEquals("catalog-unavailable", repository.catalog(mapOf("refresh" to true))["error"])
                    trialPreserved(environment)
                    assertTrue("A below-floor catalog opened the gate", host.activated.isEmpty())
                    publish(files, accepted)
                    assertNull(repository.catalog(mapOf("refresh" to true))["error"])
                    removed(repository, host)
                    assertTrue("The recovered revocation must still prevent A", host.activated.isEmpty())
                    quarantined(environment, "previous-revoked", "trial_interrupted")
                } finally { repository.close() }
            }
        }
    }

    @Test(timeout = 10_000) fun aMatchingAuthenticatedCatalogResumesSafeRecoveryAndThenWorksOffline() = runBlocking<Unit> {
        val files = ConcurrentHashMap<String, ByteArray>()
        UpdateTestOrigin { exchange ->
            val bytes = files[exchange.requestURI.path]
            UpdateTestOrigin.reply(exchange, bytes ?: byteArrayOf(), if (bytes == null) 404 else 200)
        }.use { source ->
            val environment = environment(trial())
            cache(environment, catalog(source.url("/")))
            val accepted = catalog(source.url("/"), 2)
            ledger(environment, accepted)
            publish(files, accepted)
            val host = RepositoryHost(environment).also { it.activeDigest = next.digest }
            val repository = RegistryRepository(environment, settings(source.url("/index.json")), host)
            try {
                repository.mutate("open", id)
                removed(repository, host)
                trialPreserved(environment); assertTrue(host.activated.isEmpty())
                assertNull(repository.catalog(mapOf("refresh" to true))["error"])
                repository.start()
                assertEquals(old.digest, withTimeout(5_000) { host.activationEvents.receive() })
                repository.mutate("open", id)
                val restored = environment.state().getJSONArray("installed").getJSONObject(0)
                assertEquals(old.digest, restored.getString("digest")); assertTrue(restored.getBoolean("enabled")); assertFalse(restored.getBoolean("trial"))
            } finally { repository.close() }
            val requests = source.hits.size
            val offlineHost = RepositoryHost(environment)
            val offline = RegistryRepository(environment, settings(), offlineHost)
            try {
                offline.start()
                assertEquals(old.digest, withTimeout(5_000) { offlineHost.activationEvents.receive() })
                offline.mutate("open", id)
                assertEquals(listOf(old.digest), offlineHost.activated)
                assertEquals(requests, source.hits.size)
            } finally { offline.close() }
        }
    }

    @Test(timeout = 10_000) fun failureBetweenFloorAndCachePublicationStopsTheRunningTrialWithoutErasingRecoveryEvidence() = runBlocking<Unit> {
        liveTrial { repository, environment, host, source, files ->
            val accepted = catalog(source.url("/"), 2, listOf(revocation(old.digest)))
            publish(files, accepted)
            environment.failCacheWrite = true
            try {
                repository.catalog(mapOf("refresh" to true))
                fail("The cache write fault was not observed")
            } catch (_: IOException) { }
            removed(repository, host)
            assertNull(host.activeDigest)
            assertEquals(listOf(next.digest), host.activated)
            trialPreserved(environment)
            assertEquals(2L, environment.read(File(environment.filesDir, "registry/ledger.json"), 4096)!!.getLong("seq"))
            val cached = environment.read(File(environment.filesDir, "registry/cache.json"), 2 * 1024 * 1024)!!
            assertEquals(1L, UpdateJson.read(Base64.getDecoder().decode(cached.getString("document")), 1024 * 1024).getLong("seq"))
            repository.failed(id, next.digest)
            removed(repository, host)
            trialPreserved(environment)
            assertEquals(listOf(next.digest), host.activated)
            environment.failCacheWrite = false
            assertNull(repository.catalog(mapOf("refresh" to true))["error"])
            removed(repository, host)
            quarantined(environment, "previous-revoked", "trial_interrupted")
            assertEquals(listOf(next.digest), host.activated)
        }
    }
}
