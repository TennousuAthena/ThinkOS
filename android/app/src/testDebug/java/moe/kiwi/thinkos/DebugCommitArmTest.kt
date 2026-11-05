package moe.kiwi.thinkos

import moe.kiwi.thinkos.update.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class DebugCommitArmTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun id(n: Int) = n.toString(16).padStart(32, '0')
    private val from = UpdateApkIdentity("0.1.1", 1001, "1".repeat(64), 123, "a".repeat(64))
    private val to = UpdateApkIdentity("0.1.2", 1002, "2".repeat(64), 456, "a".repeat(64))
    private fun staged() = UpdatePending(1, id(1), "INSTALLING", "beta", "b".repeat(64), 10, from, to,
        "manual", 0, 3, 600_000, 86_400_000, 3, UpdateInstallTime(100, 1, 1000), mapOf("en" to "explicit fixture"), session_id = 23)
    private fun expected(p: UpdatePending = staged()) = DebugCommitArm.Expected(id(2), "SPARE123", p.channel, p.seq,
        p.catalog_digest, p.from, p.to, p.notes, 100)
    private fun arm() = DebugCommitArm().apply { arm(expected(), "SPARE123") }
    private fun trial(p: UpdatePending = staged(), attempts: Int = 1) = p.copy(phase = "TRIAL", attempts = attempts,
        trial_id = id(10 + attempts), trial_started = UpdateInstallTime(200L + attempts, 1, 2000))

    @Test fun ordinaryAndUnsubmittedStartsHaveNoFaultPermission() {
        val policy = DebugCommitArm()
        assertFalse(policy.beforeCommit(staged(), 23, 101) { fail("No arm may publish") })
        assertEquals("NONE", policy.state(id(2), 101))
        val marker = DebugCommitArm.Bound(id(2), staged())
        assertFalse(DebugCommitArm.shouldCrash(marker, null, to))
        assertFalse(DebugCommitArm.shouldCrash(marker, staged().copy(phase = "PREPARED", session_id = null), to))
        assertFalse(DebugCommitArm.shouldCrash(marker, staged(), to))
        assertFalse(DebugCommitArm.shouldCrash(marker, trial(), from))
    }

    @Test fun everyExactTupleAndNativeAdmissionFieldIsRequiredBeforePublication() {
        val mutations: List<(UpdatePending) -> UpdatePending> = listOf(
            { it.copy(channel = "stable") }, { it.copy(seq = 11) }, { it.copy(catalog_digest = "c".repeat(64)) },
            { it.copy(from = from.copy(sha256 = "3".repeat(64))) }, { it.copy(from = from.copy(size = 124)) },
            { it.copy(from = from.copy(signer = "4".repeat(64))) }, { it.copy(from = from.copy(version = "0.1.0", code = 1000)) },
            { it.copy(to = to.copy(sha256 = "5".repeat(64))) }, { it.copy(to = to.copy(size = 457)) },
            { it.copy(to = to.copy(signer = "6".repeat(64))) }, { it.copy(to = to.copy(version = "0.1.3", code = 1003)) },
            { it.copy(notes = mapOf("en" to "changed")) }, { it.copy(mode = "automatic") },
            { it.copy(phase = "PREPARED") }, { it.copy(session_id = null) }, { it.copy(session_id = 24) },
            { it.copy(rollback_session_id = 25) }, { it.copy(attempts = 1) },
            { it.copy(max_attempts = 4) }, { it.copy(health_ms = 900_000) },
            { it.copy(trial_id = id(3)) }, { it.copy(trial_started = UpdateInstallTime(110, 1)) })
        for (mutate in mutations) {
            val policy = arm()
            assertFalse(policy.beforeCommit(mutate(staged()), 23, 101) { fail("A mismatched commit published") })
            assertEquals("NONE", policy.state(id(2), 101)) // unrelated work revokes the lease
        }
        assertFalse(arm().beforeCommit(staged(), 24, 101) { fail("Wrong live session") })
    }

    @Test fun exactCommitPublishesOnceAndClosingCannotRevokeDurableBinding() {
        val policy = arm(); var written: DebugCommitArm.Bound? = null
        assertTrue(policy.beforeCommit(staged(), 23, 101) { written = it })
        assertEquals(DebugCommitArm.Bound(id(2), staged()), written)
        assertFalse(policy.beforeCommit(staged(), 23, 102) { fail("Repeated publication") })
        policy.revoke(id(2))
        assertEquals("BOUND_TO_PENDING", policy.state(id(2), Long.MAX_VALUE))
        assertThrows(IllegalArgumentException::class.java) { policy.arm(expected(), "SPARE123") }
    }

    @Test fun timeoutClockRollbackRevocationAndWrongOwnerCannotPublish() {
        for (now in listOf(99L, 100L + DebugCommitArm.LEASE_MS + 1)) {
            val policy = arm()
            assertFalse(policy.beforeCommit(staged(), 23, now) { fail("Expired or reversed lease") })
        }
        val policy = arm()
        policy.revoke(id(9)); assertEquals("ARMED", policy.state(id(2), 101))
        policy.revoke(id(2)); assertEquals("NONE", policy.state(id(2), 101))
        assertFalse(policy.beforeCommit(staged(), 23, 101) { fail("Revoked lease") })
        assertEquals("EXPIRED", arm().state(id(2), 100 + DebugCommitArm.LEASE_MS + 1))
    }

    @Test fun publishFailureConsumesLeaseAndPartialMarkerNeverBecomesStartupPermission() {
        val policy = arm()
        assertThrows(IOException::class.java) {
            policy.beforeCommit(staged(), 23, 101) { throw IOException("fixture write failure") }
        }
        assertEquals("NONE", policy.state(id(2), 102))
        assertFalse(policy.beforeCommit(staged(), 23, 102) { fail("Blind retry") })
        assertThrows(IllegalArgumentException::class.java) { DebugCommitArm.parse("{\"schema\":2".toByteArray()) }
    }

    @Test fun markerIsStrictV2AndLegacyUnboundFilesCannotArmAnyStartup() {
        val proof = DebugCommitArm.Bound(id(2), staged())
        val raw = DebugCommitArm.marker(proof)
        assertEquals(proof, DebugCommitArm.parse(raw))
        val text = raw.toString(Charsets.UTF_8)
        for (invalid in listOf("{\"transaction\":\"${id(1)}\"}", text.replaceFirst("{", "{\"schema\":2,"),
                text.dropLast(1) + ",\"extra\":true}", text.replace("\"schema\":2", "\"schema\":\"2\""))) {
            assertThrows(IllegalArgumentException::class.java) { DebugCommitArm.parse(invalid.toByteArray()) }
        }
        assertThrows(IllegalArgumentException::class.java) { DebugCommitArm.parse(ByteArray(DebugCommitArm.MARKER_LIMIT + 1)) }
    }

    @Test fun exactTrialMayAdvanceOnlyNativeTrialFieldsAndMustMatchNativeInstalledBytes() {
        val proof = DebugCommitArm.Bound(id(2), staged())
        for (attempt in 1..3) assertTrue(DebugCommitArm.shouldCrash(proof, trial(attempts = attempt), to))
        for (mutated in listOf(trial(attempts = 0), trial(attempts = 4), trial().copy(phase = "ROLLING_BACK"),
                trial().copy(transaction_id = id(3)), trial().copy(session_id = 24), trial().copy(catalog_digest = "c".repeat(64)),
                trial().copy(from = from.copy(sha256 = "3".repeat(64))), trial().copy(notes = mapOf("en" to "changed")),
                trial().copy(health_ms = 900_000), trial().copy(created = UpdateInstallTime(101, 1, 1000)))) {
            assertFalse(DebugCommitArm.shouldCrash(proof, mutated, to))
        }
        for (actual in listOf(to.copy(sha256 = "3".repeat(64)), to.copy(size = 457), to.copy(signer = "4".repeat(64)),
                to.copy(version = "0.1.3", code = 1003))) assertFalse(DebugCommitArm.shouldCrash(proof, trial(), actual))
    }

    @Test fun twoCommitThreadsCannotPublishTwiceOrRevokeDuringACompletedPublication() {
        val policy = arm(); val entered = CountDownLatch(1); val leave = CountDownLatch(1)
        val count = AtomicInteger(); val error = AtomicReference<Throwable?>()
        val first = Thread {
            try { policy.beforeCommit(staged(), 23, 101) { count.incrementAndGet(); entered.countDown(); check(leave.await(5, TimeUnit.SECONDS)) } }
            catch (failure: Throwable) { error.set(failure) }
        }
        first.start(); assertTrue(entered.await(5, TimeUnit.SECONDS))
        val second = Thread {
            try { policy.revoke(id(2)); policy.beforeCommit(staged(), 23, 102) { count.incrementAndGet() } }
            catch (failure: Throwable) { error.set(failure) }
        }
        second.start(); leave.countDown(); first.join(5000); second.join(5000)
        assertFalse(first.isAlive); assertFalse(second.isAlive); assertNull(error.get()); assertEquals(1, count.get())
        assertEquals("BOUND_TO_PENDING", policy.state(id(2), 103))
    }

    @Test fun protectedPhysicalDeviceUnknownAndMismatchedSpareAreRefused() {
        for ((actual, expected) in listOf("HUA0BWUT" to "HUA0BWUT", "HUA0BWUT" to "SPARE123", "SPARE123" to "HUA0BWUT",
                "unknown" to "unknown", "null" to "null", "0" to "0", "SPARE123" to "SPARE456")) {
            assertThrows(IllegalArgumentException::class.java) { DebugCommitArm.requireSpare(actual, expected) }
        }
        DebugCommitArm.requireSpare("SPARE123", "SPARE123")
    }

    @Test fun realNativeStoreProducesExactlyThreeFaultEligibleTrialsThenOwnsRollbackAndBlacklist() {
        val fixture = UpdateFixture()
        val catalog = fixture.verified(fixture.catalog())
        val source = temporary.newFile().apply { writeText("previous APK stand-in; not executed") }
        val old = from.copy(sha256 = UpdateCatalog.sha256(source.readBytes()), size = source.length(), signer = fixture.signer)
        val release = catalog.document.release
        val verified = UpdatePackageVerifier.Verified(File(temporary.root, "candidate.apk"), catalog.digest,
            release.sha256, release.size, release.code, release.signer)
        val store = UpdateInstallStore(temporary.newFolder())
        val pending = store.prepare(catalog, verified, old, source, "manual", UpdateInstallStore.Limits(3, 600_000, 86_400_000, 3),
            UpdateInstallTime(100, 1, 1000), id(1))
        val staged = store.session(pending.transaction_id, 23)
        val policy = DebugCommitArm().apply { arm(expected(staged), "SPARE123") }
        var proof: DebugCommitArm.Bound? = null
        assertTrue(policy.beforeCommit(staged, 23, 101) { proof = DebugCommitArm.parse(DebugCommitArm.marker(it)) })
        repeat(3) { index ->
            val startup = store.startup(UpdateInstallStore.Running(staged.to.version, staged.to.code, staged.to.signer),
                UpdateInstallTime(200L + index, 1, 2000), id(10 + index))
            assertEquals(UpdateInstallStore.Action.TRIAL, startup.action)
            assertTrue(DebugCommitArm.shouldCrash(requireNotNull(proof), startup.pending, staged.to))
        }
        val rollback = store.startup(UpdateInstallStore.Running(staged.to.version, staged.to.code, staged.to.signer),
            UpdateInstallTime(300, 1, 3000), id(20))
        assertEquals(UpdateInstallStore.Action.ROLLBACK, rollback.action)
        assertFalse(DebugCommitArm.shouldCrash(requireNotNull(proof), rollback.pending, staged.to))
        assertTrue(store.blocked(staged.to.code))
        assertEquals(1, store.snapshot().history.failures.single().count)
        assertArrayEquals(source.readBytes(), store.previous().readBytes())
    }
    @Test fun readOnlyCompatibilityExercisesActualCodecAndGatesWithoutArmingAnotherPolicy() {
        val untouched = DebugCommitArm()
        val result = DebugCommitArm.compatibility(to, id(7), "SPARE123")
        assertTrue(result.getBoolean("complete"))
        assertEquals("policy_roundtrip_only", result.getString("scope"))
        val raw = java.util.Base64.getDecoder().decode(result.getString("marker_base64"))
        val proof = DebugCommitArm.parse(raw)
        assertEquals(to, proof.pending.to)
        assertEquals(UpdateCatalog.sha256(raw), result.getString("marker_sha256"))
        assertTrue(result.getJSONObject("observed").getBoolean("trial_3_eligible"))
        assertFalse(result.getJSONObject("observed").getBoolean("trial_4_eligible"))
        assertTrue(untouched.isIdle())
        assertFalse(untouched.beforeCommit(staged(), 23, 101) { fail("No production arm may be created") })
    }

    @Test fun readOnlyCompatibilityRejectsProtectedDeviceAndInvalidInstalledIdentity() {
        for (physical in listOf("HUA0BWUT", "unknown", "0")) {
            assertThrows(IllegalArgumentException::class.java) { DebugCommitArm.compatibility(to, id(7), physical) }
        }
        assertThrows(IllegalArgumentException::class.java) { DebugCommitArm.compatibility(to.copy(code = 3), id(7), "SPARE123") }
    }

}
