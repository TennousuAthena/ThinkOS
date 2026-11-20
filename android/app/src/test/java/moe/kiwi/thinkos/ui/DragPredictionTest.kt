package moe.kiwi.thinkos.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class DragPredictionTest {
    @Test fun predictionUsesConsecutiveMovementAndShrinksWhenTheFingerSlows() {
        val prediction = DragPrediction(8)
        assertEquals(10f, prediction.sample(10f, 100L, 1_000f), .001f)
        assertEquals(28f, prediction.sample(20f, 110L, 1_000f), .001f)
        assertEquals(23.6f, prediction.sample(22f, 120L, 1_000f), .001f)
        assertEquals(22f, prediction.sample(22f, 130L, 1_000f), .001f)
    }

    @Test fun reversalDropsThePreviousLeadWithoutCrossingTheOrigin() {
        val prediction = DragPrediction(8)
        prediction.sample(40f, 100L, 1_000f)
        assertEquals(58f, prediction.sample(50f, 110L, 1_000f), .001f)
        assertEquals(30f, prediction.sample(30f, 120L, -2_000f), .001f)
        assertEquals(0f, prediction.sample(5f, 130L, -2_500f), .001f)
        assertEquals(-13f, prediction.sample(-5f, 140L, -1_000f), .001f)
    }
}
