package moe.kiwi.thinkos.update

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpdateSourceHealthTest {
    @get:Rule val temporary = TemporaryFolder()
    private val a = "https://a.example/ota/stable.json"
    private val b = "https://b.example/ota/stable.json"
    private val c = "http://192.168.1.8:8080/ota/stable.json"

    @Test fun withoutHistoryTheConfiguredOrderIsKept() {
        val health = UpdateSourceHealth(null)
        assertEquals(listOf(a, b, c), health.order(listOf(a, b, c)))
        assertEquals(listOf(a), health.order(listOf(a)))
        assertEquals(emptyList<String>(), health.order(emptyList()))
    }

    @Test fun anUnreachableHostGoesLastButIsNeverDropped() {
        val health = UpdateSourceHealth(null)
        health.beginCheck()
        health.failure(a)
        assertEquals(listOf(b, c, a), health.order(listOf(a, b, c)))
        assertEquals(listOf(b, a), health.order(listOf(a, b)))
        assertEquals(listOf(a), health.order(listOf(a)))
        health.success(a)
        assertEquals(listOf(a, b, c), health.order(listOf(a, b, c)))
    }

    @Test fun demotionExpiresAfterAFewChecksNotAfterWallClockTime() {
        val health = UpdateSourceHealth(null)
        health.beginCheck()
        health.failure(a)
        assertTrue(health.isDemoted(a))
        health.beginCheck()
        assertTrue("still inside the penalty", health.isDemoted(a))
        health.beginCheck()
        assertFalse("retried after the penalty", health.isDemoted(a))
        assertEquals(listOf(a, b), health.order(listOf(a, b)))
    }

    @Test fun manyFailuresInOneCheckCountOnceAndRepeatedChecksBackOffLonger() {
        val health = UpdateSourceHealth(null)
        health.beginCheck()
        repeat(5) { health.failure(a) }
        health.beginCheck(); health.beginCheck()
        assertFalse("one outage is one strike", health.isDemoted(a))
        repeat(3) { health.failure(a) }
        health.beginCheck(); health.beginCheck()
        assertTrue("second strike lasts longer", health.isDemoted(a))
        health.beginCheck()
        assertFalse(health.isDemoted(a))
        repeat(8) { health.beginCheck(); health.failure(a) }
        repeat(8) { health.beginCheck() }
        assertTrue("capped at eight checks, so a repaired mirror comes back", health.isDemoted(a))
        health.beginCheck()
        assertFalse(health.isDemoted(a))
    }

    @Test fun hostsAreKeyedByHostAndPortNotByPath() {
        val health = UpdateSourceHealth(null)
        health.beginCheck()
        health.failure("https://a.example/one/stable.json")
        assertTrue(health.isDemoted("https://A.EXAMPLE/other.apk"))
        assertFalse(health.isDemoted("https://a.example:8443/other.apk"))
        health.failure("not a url")
        assertFalse(health.isDemoted("not a url"))
    }

    @Test fun historySurvivesARestartAndCorruptOrOversizedStateIsIgnored() {
        val file = File(temporary.newFolder(), "update/source-health.json")
        UpdateSourceHealth(file).also { it.beginCheck(); it.failure(a) }
        val again = UpdateSourceHealth(file)
        assertTrue(again.isDemoted(a))
        assertEquals(listOf(b, a), again.order(listOf(a, b)))
        file.writeText("{not json")
        assertFalse(UpdateSourceHealth(file).isDemoted(a))
        file.writeBytes(ByteArray(64 * 1024) { '{'.code.toByte() })
        assertFalse(UpdateSourceHealth(file).isDemoted(a))
        file.writeText("""{"checks":3,"hosts":{"a.example:443":{"fails":99,"until":5,"at":3},"b.example:443":{"fails":1,"until":-4,"at":0},"c.example:443":{"fails":2,"until":4,"at":2}}}""")
        val partial = UpdateSourceHealth(file)
        assertFalse("out-of-range entries are dropped", partial.isDemoted(a))
        assertFalse(partial.isDemoted(b))
        assertTrue(partial.isDemoted("https://c.example/x"))
    }

    @Test fun unwritableStateNeverBreaksAnUpdate() {
        val blocker = temporary.newFile("blocker")
        val health = UpdateSourceHealth(File(blocker, "child/health.json"))
        health.beginCheck()
        health.failure(a)
        assertTrue("kept in memory even when it cannot be saved", health.isDemoted(a))
    }

    @Test fun theNumberOfRememberedHostsIsBounded() {
        val file = File(temporary.newFolder(), "health.json")
        val health = UpdateSourceHealth(file)
        health.beginCheck()
        repeat(40) { health.failure("https://host$it.example/x") }
        assertTrue(file.length() < 8 * 1024)
        assertFalse("oldest entries were evicted", health.isDemoted("https://host0.example/x"))
        assertTrue(health.isDemoted("https://host39.example/x"))
    }
}
