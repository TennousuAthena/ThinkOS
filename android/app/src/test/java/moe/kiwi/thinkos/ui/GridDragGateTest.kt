package moe.kiwi.thinkos.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GridDragGateTest {
    @Test fun jitterKeepsTapsAndHorizontalDragsCancelInBothDirections() {
        val gate = GridDragGate(12f)
        gate.down(100f, 100f)
        assertFalse(gate.move(112f, 101f))
        assertTrue(gate.move(125f, 103f))
        gate.down(100f, 100f)
        assertTrue(gate.move(75f, 98f))
    }

    @Test fun anEstablishedVerticalScrollSurvivesLaterHorizontalMovement() {
        val gate = GridDragGate(12f)
        gate.down(100f, 100f)
        assertFalse(gate.move(102f, 130f))
        assertFalse(gate.move(200f, 150f))
    }

    @Test fun cancellationLastsUntilTheNextGesture() {
        val gate = GridDragGate(12f)
        gate.down(100f, 100f)
        assertTrue(gate.move(150f, 100f))
        assertTrue(gate.move(101f, 101f))
        gate.down(100f, 100f)
        assertFalse(gate.move(101f, 101f))
        assertFalse(gate.move(102f, 125f))
    }
}
