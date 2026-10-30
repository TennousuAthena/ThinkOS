package moe.kiwi.thinkos.plugin

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RuntimePoliciesTest {
    @Test fun ordinaryErrorsRetainInstanceUntilFrequencyThreshold() {
        val health = PluginHealth()
        repeat(4) { assertEquals(PluginHealth.Action.KEEP, health.failure(it * 100L, false)) }
        assertEquals(PluginHealth.Action.RESTART, health.failure(500, false))
        assertEquals(2500, health.retryAt)
    }
    @Test fun resourcesBackOffAndRecoverAfterCoolingDown() {
        val health = PluginHealth()
        repeat(4) { assertEquals(PluginHealth.Action.RESTART, health.failure(it * 100L, true)) }
        assertEquals(PluginHealth.Action.COOL_DOWN, health.failure(500, true))
        assertEquals(PluginHealth.HOUR + 500, health.retryAt)
        health.cooledDown()
        assertEquals(PluginHealth.Action.KEEP, health.failure(PluginHealth.HOUR + 501, false))
    }
    @Test fun oldFailuresExpire() {
        val health = PluginHealth()
        repeat(4) { health.failure(it.toLong(), false) }
        assertEquals(PluginHealth.Action.KEEP, health.failure(PluginHealth.HOUR + 100, false))
    }
    @Test fun incrementalStateSizeMatchesSerializationAndSnapshotsAreIsolated() {
        val doc = JsonDocument()
        doc.merge(JSONObject("""{"now":{"value":1,"text":"香港😀"},"array":[null,1,true],"extra":"x"}"""))
        val before = doc.snapshot()
        doc.merge(JSONObject("""{"now":{"value":12,"nested":{"yes":false}},"extra":"longer"}"""))
        assertEquals(doc.snapshot().toString().toByteArray().size, doc.bytes)
        assertEquals(1, before.getJSONObject("now").getInt("value"))
        assertEquals(12, doc.value.getJSONObject("now").getInt("value"))
        assertEquals(3, doc.value.getJSONArray("array").length())
    }
    @Test fun rejectedPatchDoesNotMutateExistingBranches() {
        val doc = JsonDocument(JSONObject("""{"nested":{"v":1}}"""), 80)
        try {
            doc.merge(JSONObject().put("nested", JSONObject().put("v", 2).put("large", "x".repeat(100))))
            fail("Expected size limit")
        } catch (_: IllegalStateException) {}
        assertEquals(1, doc.value.getJSONObject("nested").getInt("v"))
        doc.set("ok", true)
        assertEquals(doc.snapshot().toString().toByteArray().size, doc.bytes)
    }
    @Test fun nativeCrashQuarantineExpiresWithoutNetworkOrRtc() {
        var elapsed = 1000L
        val records = JSONObject()
        val history = NativeExitHistory(records, 1, { elapsed }, { 0L })
        repeat(3) { history.record("bad@digest"); elapsed += 100 }
        assertTrue(history.blocked("bad@digest"))
        assertFalse(history.blocked("bad@new-version"))
        elapsed += PluginHealth.HOUR
        assertFalse(history.blocked("bad@digest"))
        history.record("bad@digest")
        assertEquals(1, records.getJSONObject("bad@digest").getInt("count"))
    }
    @Test fun nativeQuarantineRebasesAcrossBootWithoutPermanentBan() {
        var elapsed = 1000L
        val records = JSONObject()
        NativeExitHistory(records, 1, { elapsed }, { 0L }).apply { repeat(3) { record("bad@digest") } }
        elapsed = 10L
        val nextBoot = NativeExitHistory(records, 2, { elapsed }, { 0L })
        assertTrue(nextBoot.blocked("bad@digest"))
        elapsed += PluginHealth.HOUR + 1
        assertFalse(nextBoot.blocked("bad@digest"))
    }
    @Test fun incrementalSizesNormalizeFloatingPointNumbers() {
        val doc = JsonDocument()
        doc.set("value", 1.0)
        assertEquals(doc.snapshot().toString().toByteArray().size, doc.bytes)
        doc.set("value", 3.125)
        assertEquals(doc.snapshot().toString().toByteArray().size, doc.bytes)
    }
    @Test fun typedHostRoundTripPreservesEmptyArraysAndNulls() {
        val input = JSONObject().put("items", JSONArray()).put("nested", JSONObject().put("null", JSONObject.NULL))
        val result = JsonDocument.fromHost(JsonDocument.toHost(input)) as JSONObject
        assertTrue(result.getJSONArray("items").length() == 0)
        assertTrue(result.getJSONObject("nested").isNull("null"))
    }
}
