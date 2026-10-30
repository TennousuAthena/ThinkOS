package moe.kiwi.thinkos.astro

import moe.kiwi.thinkos.tz.FileTzSource
import moe.kiwi.thinkos.tz.TzDatabase
import moe.kiwi.thinkos.tz.Zones
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs

class MoonTest {
    @Test
    fun matchesIndependentLibastroLunarVectors() {
        val db = TzDatabase(FileTzSource(File(System.getProperty("tzdata.dir") ?: "../../shared/tzdata")))
        Zones.install(db)
        try {
            val vectors = javaClass.getResourceAsStream("/moon-golden.tsv")!!.bufferedReader().use { it.readLines() }
            var days = 0
            var phases = 0
            for (line in vectors.filterNot { it.startsWith("#") || it.isBlank() }) {
                val c = line.split('\t')
                val ms = c[1].toLong()
                if (c[0] == "D") {
                    days++
                    val place = Place(c[3].toDouble(), c[4].toDouble(), c[5].toDouble())
                    val day = Moon.day(ms, Zones.clock(c[2]), place)
                    val label = "${c[2]} $ms"
                    assertEquals("$label start", c[6].toLong(), day.start)
                    assertEquals("$label end", c[7].toLong(), day.end)
                    compareEvents("$label rise", c[8], day.rises)
                    compareEvents("$label set", c[9], day.sets)
                    assertEquals("$label altitude", c[10].toDouble(), day.position.altitude, 0.1)
                    // Compare sky separation: azimuth itself is singular at zenith/nadir.
                    val altitude = day.position.altitude * Astro.RAD
                    val truthAltitude = c[10].toDouble() * Astro.RAD
                    val azimuthDelta = (day.position.azimuth - c[11].toDouble()) * Astro.RAD
                    val separation = kotlin.math.acos((kotlin.math.sin(altitude) * kotlin.math.sin(truthAltitude) +
                        kotlin.math.cos(altitude) * kotlin.math.cos(truthAltitude) * kotlin.math.cos(azimuthDelta))
                        .coerceIn(-1.0, 1.0)) * Astro.DEG
                    assertEquals("$label sky position", 0.0, separation, 0.1)
                    assertEquals("$label illumination", c[12].toDouble(), day.phase.illumination, 0.01)
                    assertTrue(day.transits.all { it in day.start until day.end })
                    assertFalse(day.alwaysDown && day.alwaysUp)
                    if (day.alwaysDown || day.alwaysUp) assertTrue(day.rises.isEmpty() && day.sets.isEmpty())
                } else {
                    phases++
                    val phase = Moon.phase(ms)
                    assertEquals("illumination $ms", c[2].toDouble(), phase.illumination, 0.01)
                    val actual = listOf(phase.previousNewMoon, phase.nextNewMoon, phase.nextFirstQuarter,
                        phase.nextFullMoon, phase.nextLastQuarter)
                    actual.zip(c.drop(3)).forEachIndexed { i, (value, expected) ->
                        assertTrue("phase $ms event $i error ${(value - expected.toLong()) / 1000.0}s",
                            abs(value - expected.toLong()) <= 600_000L)
                    }
                    assertTrue(phase.previousNewMoon <= ms)
                    assertTrue(phase.nextNewMoon >= ms)
                    assertEquals((ms - phase.previousNewMoon) / 86_400_000.0, phase.ageDays, 1e-9)
                    assertTrue(phase.ageDays in 0.0..30.0)
                    assertEquals(phase.angle < 180, phase.waxing)
                }
            }
            assertTrue("geographic coverage", days >= 300)
            assertTrue("phase coverage", phases >= 180)
        } finally {
            Zones.install(null)
        }
    }

    private fun compareEvents(label: String, expected: String, actual: List<Long>) {
        val values = if (expected == "-") emptyList() else expected.split(',').map(String::toLong)
        assertEquals("$label count", values.size, actual.size)
        values.zip(actual).forEach { (truth, value) ->
            assertTrue("$label error ${(value - truth) / 1000.0}s", abs(value - truth) <= 180_000L)
        }
    }

    @Test
    fun crossingSolverKeepsActualVariableLunation() {
        val a = Moon.nextPhase(1_767_225_600_000L, 0) // 2026-01-01
        val b = Moon.nextPhase(a + 1000, 0)
        val c = Moon.nextPhase(b + 1000, 0)
        assertNotEquals("synodic months must not be a fixed duration", b - a, c - b)
        val atNew = Moon.phase(a)
        assertEquals(0.0, Astro.norm180(atNew.angle), 0.00001)
        assertTrue(atNew.ageDays < 1e-6)
        assertTrue(atNew.illumination < 0.003)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidQuarterIsRejected() {
        Moon.nextPhase(1_767_225_600_000L, 4)
    }
}
