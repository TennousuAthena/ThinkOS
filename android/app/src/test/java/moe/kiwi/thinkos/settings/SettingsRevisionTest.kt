package moe.kiwi.thinkos.settings

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SettingsRevisionTest {
    private class Memory : SettingsPersistence {
        val files = mutableMapOf<String, ByteArray>()
        override fun read(name: String) = files[name]
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
    }
    private fun definition() = SettingsScope("system", "System", linkedMapOf(
        "cast.miracast" to SettingField.parse("cast.miracast", JSONObject("""{"type":"bool","label":"Miracast","default":false}"""), true),
        "test.value" to SettingField.parse("test.value", JSONObject("""{"type":"number","label":"Value","default":1,"min":0,"max":10}"""), true),
    ))
    private fun store() = SettingsStore(Memory()).also { it.register(definition()) }

    @Test fun successfulSameValueEditsAreNewIntentsAndCompoundEditAdvancesOnce() {
        val settings = store(); val initial = settings.revision()
        settings.set("system", "cast.miracast", false)
        assertEquals(initial + 1, settings.revision())
        var observed: Long? = null
        settings.setMany("system", mapOf("cast.miracast" to false, "test.value" to 2)) {
            observed = settings.revision()
            assertEquals(false, settings.get("system", "cast.miracast"))
            assertEquals(1L, settings.get("system", "test.value"))
        }
        assertEquals(initial + 1, observed)
        assertEquals(initial + 2, settings.revision())
    }

    @Test fun readsFlushAndRefusedWritesDoNotConsumeARevision() {
        val settings = store(); val revision = settings.revision()
        settings.get("system", "cast.miracast"); settings.snapshot("system")
        settings.resolved("system", "cast.miracast"); settings.hasValue("system", "cast.miracast")
        settings.scope("system"); settings.scopes(); settings.overrides(); settings.rejectedByServer(); settings.flush()
        assertEquals(revision, settings.revision())
        assertThrows(SettingError::class.java) { settings.set("system", "test.value", 99) }
        assertThrows(IllegalStateException::class.java) {
            settings.set("system", "cast.miracast", true) { error("refused") }
        }
        assertEquals(revision, settings.revision())
        assertEquals(false, settings.get("system", "cast.miracast"))
    }

    @Test fun allLayerAndDeclarationMutationsAdvanceWhileResetNoopDoesNot() {
        val settings = store()
        var before = settings.revision()
        settings.applyServer(JSONObject("""{"cast":{"miracast":true}}"""))
        assertEquals(before + 1, settings.revision())
        settings.setMany("system", mapOf("cast.miracast" to false, "test.value" to 2))
        before = settings.revision()
        settings.reset("system", "cast.miracast")
        assertEquals(before + 1, settings.revision())
        before = settings.revision(); settings.reset("system")
        assertEquals(before + 1, settings.revision())
        before = settings.revision(); settings.reset("system")
        assertEquals(before, settings.revision())
        settings.set("system", "test.value", 3)
        before = settings.revision(); settings.resetAll()
        assertEquals(before + 1, settings.revision())
        // A whole-device reset is a new explicit intent even with no overrides.
        before = settings.revision(); settings.resetAll()
        assertEquals(before + 1, settings.revision())
        before = settings.revision(); settings.register(definition())
        assertEquals(before + 1, settings.revision())
        before = settings.revision(); settings.unregister("system")
        assertEquals(before + 1, settings.revision())
        before = settings.revision(); settings.unregister("system")
        assertEquals(before, settings.revision())
    }

    @Test fun aRevisionCapturedByTheOriginalPrecommitCannotBeRebasedAfterPosting() {
        val settings = store()
        var expected = -1L
        assertThrows(SettingError::class.java) {
            settings.set("system", "cast.miracast", true) {
                expected = settings.revision()
                throw SettingError("feature_pending", message = "native inspection")
            }
        }
        assertEquals(expected, settings.revision())
        settings.set("system", "cast.miracast", false)
        val current = settings.revision()
        assertThrows(IllegalStateException::class.java) {
            settings.set("system", "cast.miracast", true) {
                check(settings.revision() == expected && settings.get("system", "cast.miracast") == false)
            }
        }
        assertEquals(current, settings.revision())
        assertEquals(false, settings.get("system", "cast.miracast"))
    }

    @Test fun aCommittedFalseIsObservableBeforeItsOutOfLockListenerReturns() {
        val settings = store(); val expected = settings.revision()
        val committed = CountDownLatch(1); val release = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        settings.addListener { _, _ ->
            committed.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test listener not released" }
        }
        val writer = Thread({
            try { settings.set("system", "cast.miracast", false) }
            catch (error: Throwable) { failure.set(error) }
        }, "settings-revision-test").apply { isDaemon = true; start() }
        try {
            assertTrue(committed.await(5, TimeUnit.SECONDS))
            assertTrue(settings.revision() > expected)
            assertThrows(IllegalStateException::class.java) {
                settings.set("system", "cast.miracast", true) {
                    check(settings.revision() == expected && settings.get("system", "cast.miracast") == false)
                }
            }
            assertEquals(false, settings.get("system", "cast.miracast"))
        } finally {
            release.countDown(); writer.join(5000)
        }
        assertFalse(writer.isAlive); assertNull(failure.get())
    }
}
