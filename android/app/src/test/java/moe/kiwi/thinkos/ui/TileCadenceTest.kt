package moe.kiwi.thinkos.ui

import org.junit.Assert.*
import org.junit.Test

class TileCadenceTest {
    @Test fun oneAtATimeRoundRobinSkipsEmptyFacesAndWaitsForAnimation() {
        val c = TileCadence(); val eligible = listOf(true, false, true)
        assertNull(c.select(0, true, 8000, eligible, false))
        assertNull(c.select(7999, true, 8000, eligible, false))
        assertEquals(0, c.select(8000, true, 8000, eligible, false))
        assertNull(c.select(16000, true, 8000, eligible, true))
        assertEquals(2, c.select(16001, true, 8000, eligible, false))
        assertEquals(0, c.select(24001, true, 8000, eligible, false))
    }
    @Test fun disabledOrLeavingRestartsTheIntervalAndSettingsChangesApply() {
        val c=TileCadence();val e=listOf(true)
        c.select(0,true,8000,e,false)
        assertNull(c.select(7000,false,8000,e,false))
        assertNull(c.select(9000,true,8000,e,false))
        assertNull(c.select(17000,true,5000,e,false))
        assertEquals(0,c.select(22000,true,5000,e,false))
        c.reset()
        assertNull(c.select(25000,true,5000,e,false))
        assertNull(c.select(30000,true,5000,listOf(false),false))
    }
}
