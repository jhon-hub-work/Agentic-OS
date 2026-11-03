package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TypeMathTest {
    @Test fun mostUsedAreBiggestAndBoldestStepByStep() {
        val steps = (0 until 60).map { TypeMath.step(it, 60) }
        assertEquals(0, steps[0])
        assertEquals(TypeMath.LAST, steps[59])
        for (i in 1 until steps.size) assertTrue("rank $i", steps[i] >= steps[i - 1])  // never bigger further down
        assertEquals(6, steps.toSet().size)  // a few clear steps, like type, not a size per app
        for (s in 0 until TypeMath.LAST) {
            assertTrue(TypeMath.SIZES[s] > TypeMath.SIZES[s + 1])
            assertTrue(TypeMath.weight(s) > TypeMath.weight(s + 1))
        }
    }

    @Test fun neverOpenedAppsAreAllSmall() {
        assertEquals(TypeMath.LAST, TypeMath.step(0, 0))  // no history: nothing is headlined
        assertEquals(0, TypeMath.step(0, 2))
        assertEquals(TypeMath.LAST, TypeMath.step(2, 2))
        assertEquals(TypeMath.LAST, TypeMath.step(-1, 10))
    }

    @Test fun weightsStayReadable() {
        for (s in -2..TypeMath.LAST + 2) assertTrue(TypeMath.weight(s) in 300..800)
    }

    @Test fun bigTypeIsTrackedTighter() {
        assertTrue(TypeMath.tracking(28f) < 0f)
        assertTrue(TypeMath.tracking(12f) > 0f)
    }

    @Test fun linesFillTheMeasureAndTheLastStaysLeft() {
        val w = floatArrayOf(40f, 30f, 20f, 50f, 25f, 10f); val gap = FloatArray(6) { 5f }
        val line = IntArray(6); val x = FloatArray(6)
        val n = TypeMath.flow(w, gap, 100f, line, x)
        assertEquals(2, n)
        assertEquals(listOf(0, 0, 0, 1, 1, 1), line.toList())  // 40+5+30+5+20 = 100 fits; 50+5+25+5+10 = 95
        assertEquals(100f, x[2] + w[2], 0.01f)                // a full line ends on the right edge
        assertEquals(0f, x[3], 0.01f)
        assertEquals(85f, x[5], 0.01f)                        // the last line is not stretched: 50+5+25+5
    }

    @Test fun shortLinesAreJustifiedOnlyAsFarAsLooks() {
        val w = floatArrayOf(30f, 30f, 90f); val gap = FloatArray(3) { 5f }
        val line = IntArray(3); val x = FloatArray(3)
        assertEquals(2, TypeMath.flow(w, gap, 100f, line, x, loose = 4f))
        assertEquals(30f + 5f + 20f, x[1], 0.01f)  // extra space capped at 4x the gap (20), not the 35 that would reach the edge
        assertEquals(0f, x[2], 0.01f)
        // a name wider than the measure gets a line of its own
        val one = IntArray(2); val ox = FloatArray(2)
        assertEquals(2, TypeMath.flow(floatArrayOf(150f, 10f), floatArrayOf(5f, 5f), 100f, one, ox))
    }
}
