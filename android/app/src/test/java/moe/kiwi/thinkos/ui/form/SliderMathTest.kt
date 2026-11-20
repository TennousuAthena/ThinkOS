package moe.kiwi.thinkos.ui.form

import org.junit.Assert.assertEquals
import org.junit.Test

class SliderMathTest {
    @Test fun trackClampsAtBothEnds() {
        assertEquals(0f, SliderMath.fractionAt(0f, 10f, 110f, rtl = false), 0f)
        assertEquals(1f, SliderMath.fractionAt(120f, 10f, 110f, rtl = false), 0f)
    }

    @Test fun directionMirrorsTheFractionAndThumbPosition() {
        assertEquals(.25f, SliderMath.fractionAt(35f, 10f, 110f, rtl = false), 0f)
        assertEquals(.75f, SliderMath.fractionAt(35f, 10f, 110f, rtl = true), 0f)
        assertEquals(35f, SliderMath.xAt(.75f, 10f, 110f, rtl = true), 0f)
    }

    @Test fun stepsSnapTheValueIndependentlyOfTheThumb() {
        assertEquals(.3, SliderMath.snap(.29, .1, .9, .1), 0.0)
        assertEquals(.29, SliderMath.snap(.29, .1, .9, 0.0), 0.0)
        assertEquals(.85, SliderMath.snap(.85, .1, .85, .2), 0.0)
    }

    @Test fun singleValueHasNoTravel() {
        assertEquals(0f, SliderMath.fraction(8.0, 5.0, 5.0), 0f)
        assertEquals(5.0, SliderMath.snap(8.0, 5.0, 5.0, .25), 0.0)
    }
}
