package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexMathTest {
    @Test fun spiralFillsRingsFromTheMiddleWithNoRepeats() {
        val n = 1 + 6 + 12 + 5  // two full rings and part of the third
        val h = HexMath.spiral(n)
        val cells = (0 until n).map { h[it * 2] to h[it * 2 + 1] }
        assertEquals(n, cells.toSet().size)
        assertEquals(0 to 0, cells[0])
        val rings = cells.map { (q, r) -> HexMath.dist(q, r) }
        assertEquals(List(6) { 1 }, rings.subList(1, 7))
        assertEquals(List(12) { 2 }, rings.subList(7, 19))
        assertEquals(List(5) { 3 }, rings.subList(19, n))
        assertEquals(rings.sorted(), rings)  // never steps back toward the middle
        assertEquals(0, HexMath.spiral(0).size)
    }

    @Test fun neighboursAreOnePitchApart() {
        val h = HexMath.spiral(7)
        for (i in 1 until 7) {
            val x = HexMath.x(h[i * 2], h[i * 2 + 1], 10f); val y = HexMath.y(h[i * 2 + 1], 10f)
            assertEquals(10f, kotlin.math.hypot(x, y), 0.001f)
        }
    }

    @Test fun fisheyeKeepsTheMiddleAndGathersTheEdges() {
        assertEquals(1f, HexMath.pull(0f), 0f)
        var last = 0f
        for (k in 1..40) {  // drawn distance u * pull(u) grows, but never past one lens radius
            val u = k * 0.1f
            val d = u * HexMath.pull(u)
            assertTrue(d > last && d < 1f); last = d
        }
        assertEquals(1f, HexMath.scale(0f, 1.27f), 0f)       // full size in the middle (capped)
        assertTrue(HexMath.scale(1.2f, 1.27f) < 0.5f)          // small near the edge
        assertEquals(0f, HexMath.fade(0.1f), 0f)               // too small: gone, not piled at the rim
        assertEquals(1f, HexMath.fade(0.6f), 0f)
    }

    @Test fun magnifierGrowsOnlyNearTheFinger() {
        assertEquals(1.16f, HexMath.magnify(0f, 70f, 0.16f), 0.0001f)
        assertTrue(HexMath.magnify(200f, 70f, 0.16f) < 1.001f)
        assertEquals(1f, HexMath.magnify(0f, 70f, 0f), 0f)
    }

    @Test fun nearestPicksTheClosestShownIconWithinReach() {
        val xs = floatArrayOf(0f, 100f, 50f); val ys = floatArrayOf(0f, 0f, 0f)
        val rs = floatArrayOf(20f, 20f, -1f)  // the third isn't shown
        assertEquals(0, HexMath.nearest(10f, 0f, xs, ys, rs, 3, 4f))
        assertEquals(1, HexMath.nearest(85f, 5f, xs, ys, rs, 3, 4f))
        assertEquals(-1, HexMath.nearest(50f, 0f, xs, ys, rs, 3, 4f))  // between them: nothing
    }

    @Test fun panLimitsReachEveryCellAndAlwaysHoldRest() {
        val l = HexMath.limits(-300f, 200f, 100f)
        assertEquals(-100f, l[0], 0f)  // rightmost cell can come to 100 px right of the middle
        assertEquals(200f, l[1], 0f)   // leftmost to 100 px left
        val small = HexMath.limits(-50f, 50f, 100f)
        assertEquals(0f, small[0], 0f); assertEquals(0f, small[1], 0f)
    }

    @Test fun rubberBandResistsAndUnbandUndoesIt() {
        assertEquals(30f, HexMath.band(30f, 0f, 100f, 400f), 0f)
        val past = HexMath.band(180f, 0f, 100f, 400f)
        assertTrue(past > 100f && past < 180f)
        assertEquals(180f, HexMath.unband(past, 0f, 100f, 400f), 0.01f)
        val under = HexMath.band(-60f, 0f, 100f, 400f)
        assertEquals(-60f, HexMath.unband(under, 0f, 100f, 400f), 0.01f)
    }
}
