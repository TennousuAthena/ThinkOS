package moe.kiwi.thinkos.cast.airplay

import org.junit.Assert.*
import org.junit.Test

class PlistTest {
    private fun sample(): Map<String, Any?> = linkedMapOf(
        "Content-Location" to "http://192.168.3.119:8099/a.m3u8", "Start-Position" to 0.25, "rate" to 1L, "ok" to true, "no" to false,
        "uuid" to "E2D5A6F0-0000-4000-8000-000000000001", "blob" to byteArrayOf(0, 1, 2, 3, -1), "title" to "测试 — ünïcode",
        "list" to listOf("a", 7L, 2.5, listOf(true)), "dict" to linkedMapOf("n" to 300L, "big" to 5_000_000_000L, "neg" to 0L),
    )

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is ByteArray && b is ByteArray -> a.contentEquals(b)
        a is Map<*, *> && b is Map<*, *> -> a.keys == b.keys && a.keys.all { same(a[it], b[it]) }
        a is List<*> && b is List<*> -> a.size == b.size && a.indices.all { same(a[it], b[it]) }
        else -> a == b
    }

    @Test fun binaryRoundTrips() { assertTrue(same(sample(), Plist.read(Plist.binary(sample())))) }
    @Test fun xmlRoundTrips() { assertTrue(same(sample(), Plist.read(Plist.xml(sample())))) }

    @Test fun readsAnXmlPlistTheWayASenderWritesIt() {
        val text = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict><key>Content-Location</key><string>http://tv.test/a?x=1&amp;y=2</string><key>Start-Position</key><real>0.5</real><key>n</key><integer>-3</integer></dict></plist>"""
        val p = Plist.read(text.toByteArray())
        assertEquals("http://tv.test/a?x=1&y=2", p["Content-Location"]); assertEquals(0.5, p["Start-Position"]); assertEquals(-3L, p["n"])
    }

    @Test fun anotherDocumentTypeOrAnEntityIsRefused() {
        val evil = "<?xml version=\"1.0\"?><!DOCTYPE plist [<!ENTITY x \"boom\">]><plist><dict><key>a</key><string>&x;</string></dict></plist>"
        assertThrows(IllegalArgumentException::class.java) { Plist.read(evil.toByteArray()) }
    }

    @Test fun malformedInputIsRefusedWithoutCrashing() {
        val good = Plist.binary(sample())
        val bad = ArrayList<ByteArray>()
        bad += ByteArray(0); bad += "bplist00".toByteArray(); bad += good.copyOf(good.size - 5); bad += good.copyOf(20)
        for (i in intArrayOf(8, 9, 12, 20, good.size - 32 + 6, good.size - 32 + 7, good.size - 32 + 15, good.size - 32 + 23, good.size - 32 + 31)) {
            bad += good.copyOf().also { it[i] = (it[i].toInt() xor 0xff).toByte() }
        }
        for (b in bad) { try { Plist.read(b) } catch (e: IllegalArgumentException) { /* refused, as it should be */ } }
        val random = java.util.Random(7)
        repeat(300) {
            val b = good.copyOf(); repeat(4) { b[random.nextInt(b.size)] = random.nextInt().toByte() }
            try { Plist.read(b) } catch (e: IllegalArgumentException) { /* fine */ }
        }
    }

    @Test fun aSelfReferencingBinaryListIsNotFollowedForever() {
        // object 0 is an array holding itself
        val doc = "bplist00".toByteArray() + byteArrayOf(0xA1.toByte(), 0x00) + byteArrayOf(0x08) +
            ByteArray(0) + byteArrayOf(0, 0, 0, 0, 0, 0, 1, 1) + ByteArray(8).also { it[7] = 1 } + ByteArray(8) + ByteArray(8).also { it[7] = 10 }
        assertThrows(IllegalArgumentException::class.java) { Plist.read(doc) }
    }

    @Test fun nestingAndSizeAreBounded() {
        var s = "<plist><array>".repeat(30) + "</array></plist>".repeat(30)
        assertThrows(IllegalArgumentException::class.java) { Plist.read(("<plist><dict><key>a</key>" + "<array>".repeat(40) + "</array>".repeat(40) + "</dict></plist>").toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { Plist.read(ByteArray(Plist.MAX_BYTES + 1)) }
    }
}
