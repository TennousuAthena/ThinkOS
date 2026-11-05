package moe.kiwi.thinkos.update

import moe.kiwi.thinkos.core.Attention
import moe.kiwi.thinkos.settings.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdatePolicyTest {
    @Test fun defaultsAndAllDurationsComeFromSharedSettings() {
        val schema = File(System.getProperty("tzdata.dir")).parentFile.resolve("schema/settings/system.json").readText()
        val store = SettingsStore(object : SettingsPersistence {
            override fun read(name: String): ByteArray? = null
            override fun write(name: String, bytes: ByteArray) = Unit
        }).also { it.register(SystemSettings.parse(schema)) }
        val values = SystemValues(store)
        val policy = values.updatePolicy
        assertTrue(policy.enabled); assertTrue(values.updateAutoInstall)
        assertEquals(3, values.updateInstallHour)
        assertEquals("stable", policy.channel)
        assertEquals(listOf("https://cnb.cool/kiwimoe/ThinkOS/-/releases/download/ota-stable/stable.json"), policy.sources)
        assertEquals(600_000L, policy.initialMinMillis); assertEquals(3_600_000L, policy.initialMaxMillis)
        assertEquals(86_400_000L, policy.intervalMillis); assertEquals(7_200_000L, policy.jitterMillis)
        assertEquals(60_000L, policy.retryMinMillis); assertEquals(3_600_000L, policy.retryMaxMillis)
        assertEquals(15_000L, policy.connectMillis); assertEquals(30_000L, policy.readMillis)
        store.set("system", "update.channel", "beta")
        store.set("system", "update.mirror", "http://192.168.1.8:8080/repo")
        assertEquals(listOf("http://192.168.1.8:8080/repo/ota/beta.json",
            "https://cnb.cool/kiwimoe/ThinkOS/-/releases/download/ota-beta/beta.json"), values.updatePolicy.sources)
        store.set("system", "update.enabled", false)
        assertFalse(values.updatePolicy.enabled)
    }

    @Test fun sourcesKeepFallbackAndDoNotAddDeviceData() {
        assertEquals(listOf("broken", "https://mirror.example/repo/ota/beta.json", "http://127.0.0.1/custom.json"),
            UpdatePolicy.sources("broken", "https://mirror.example/repo\nhttp://127.0.0.1/custom.json", "beta"))
        assertEquals(listOf("https://example.com/ota/stable.json"), UpdatePolicy.sources("https://example.com/", "https://example.com", "stable"))
        assertEquals(8, UpdatePolicy.sources("https://mirror.example", (1..20).joinToString("\n") { "https://mirror$it.example" }, "stable").size)
    }

    @Test fun channelPlaceholderPicksTheCatalogPerChannelButNeverTheHost() {
        val release = "https://mirror.example/org/repo/-/releases/download/ota-{channel}/{channel}.json"
        assertEquals(listOf("https://mirror.example/org/repo/-/releases/download/ota-beta/beta.json"), UpdatePolicy.sources("", release, "beta"))
        assertEquals(listOf("https://mirror.example/org/repo/-/releases/download/ota-stable/stable.json"), UpdatePolicy.sources("", release, "stable"))
        // Root-style addresses and explicit catalogs keep working beside it, mirror first.
        assertEquals(listOf("http://192.168.1.8/ota/stable.json", "https://mirror.example/org/repo/-/releases/download/ota-stable/stable.json"),
            UpdatePolicy.sources("http://192.168.1.8", release, "stable"))
        // The placeholder cannot steer which server is contacted: it stays unexpanded and fails URL parsing downstream.
        assertEquals(listOf("https://{channel}.mirror.example/x.json"), UpdatePolicy.sources("", "https://{channel}.mirror.example/x.json", "stable"))
        assertEquals(listOf("https://mirror.example:{channel}/x.json"), UpdatePolicy.sources("", "https://mirror.example:{channel}/x.json", "stable"))
        assertEquals("not a url {channel}", UpdatePolicy.expand("not a url {channel}", "stable"))
        assertEquals("https://h.example", UpdatePolicy.expand("https://h.example", "stable"))
    }

    @Test fun realMediaProjectionTreatsPausedVideoAndCastAsSessions() {
        fun project(kind: String, status: String, owner: String = "player", mirror: Boolean = false) = UpdateEnvironment.from(
            Attention.AMBIENT, mapOf("kind" to kind, "status" to status, "owner" to owner, "mirror" to mirror), false)
        assertFalse(project("video", "paused").allows(true))
        assertFalse(project("audio", "playing", "cast:airplay").allows(true))
        assertFalse(project("video", "buffering", "cast:miracast", true).allows(false))
        assertTrue(project("audio", "playing").allows(false))
        assertTrue(project("video", "stopped", "cast:dlna").allows(false))
        assertTrue(project("video", "ended").allows(false))
        assertFalse(UpdateEnvironment.from(Attention.AMBIENT, emptyMap(), true).allows(true))
    }
}
