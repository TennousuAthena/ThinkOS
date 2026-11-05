package moe.kiwi.thinkos.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeTouchGateTest {
    @Test fun aNativeTouchReleaseGrantsExactlyOneApproval() {
        val gate = NativeTouchGate()
        gate.down(100, true); gate.up(130, true, true)
        assertTrue(gate.consume(131, true))
        assertFalse(gate.consume(132, true))
    }
    @Test fun directAccessibilityOrDebugClicksHaveNoCredit() {
        val gate = NativeTouchGate()
        assertFalse(gate.consume(100, true))
        gate.down(110, false); gate.up(120, false, true)
        assertFalse(gate.consume(121, true))
        gate.up(140, true, true)
        assertFalse(gate.consume(141, true))
    }
    @Test fun obscuredMultiplePointerCancelledAndOutsideTouchesCannotApprove() {
        for (kind in 0..3) {
            val gate = NativeTouchGate()
            gate.down(10, true)
            when (kind) {
                0 -> gate.up(20, false, true)
                1 -> { gate.cancel(); gate.up(20, true, true) }
                2 -> gate.up(20, true, false)
                else -> { gate.up(20, true, true); gate.cancel() }
            }
            assertFalse(gate.consume(21, true))
        }
    }
    @Test fun staleFutureAndUnfocusedCreditsAreConsumedWithoutApproval() {
        for ((now, focused) in listOf(631L to true, 129L to true, 131L to false)) {
            val gate = NativeTouchGate()
            gate.down(100, true); gate.up(130, true, true)
            assertFalse(gate.consume(now, focused)); assertFalse(gate.consume(131, true))
        }
        val gate = NativeTouchGate()
        gate.down(100, true); gate.up(5_101, true, true)
        assertFalse(gate.consume(5_102, true))
    }
    @Test fun anotherPressCannotReuseAnEarlierRelease() {
        val gate = NativeTouchGate()
        gate.down(100, true); gate.up(130, true, true); gate.down(140, false)
        assertFalse(gate.consume(150, true))
    }
}
