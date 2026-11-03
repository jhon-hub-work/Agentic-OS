package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class SplineMathTest {
    private val n = SplineMath.N

    /** The rest shape on a 1000 x 2000 px box, head at the middle, like the view lays it out. */
    private fun rest(): Pair<FloatArray, FloatArray> {
        val rx = FloatArray(n) { SplineMath.REST[it * 2] * 1000f }; val ry = FloatArray(n) { SplineMath.REST[it * 2 + 1] * 2000f }
        return rx to ry
    }

    @Test fun weightsRunFromTheOrbToAStillTail() {
        assertEquals(1f, SplineMath.weight(0, n), 0f)
        assertEquals(0f, SplineMath.weight(n - 1, n), 0f)
        for (i in 1 until n) assertTrue(SplineMath.weight(i, n) < SplineMath.weight(i - 1, n))
        for (i in 0 until n - 1) assertTrue("leader $i has a weight to divide by", SplineMath.weight(i, n) > 0f)
    }

    @Test fun parkedOrbBendsTheLineByEachWeightAndTheTailStays() {
        val (rx, ry) = rest()
        val px = rx.copyOf(); val py = ry.copyOf(); val vx = FloatArray(n); val vy = FloatArray(n)
        px[0] += 300f; py[0] -= 500f  // he drags the orb up and right and leaves it
        var frames = 0
        while (SplineMath.chain(px, py, vx, vy, rx, ry, n, 0.15f, 1f, 1f / 120)) { frames++; assertTrue("settles", frames < 600) }
        for (i in 1 until n) {
            assertEquals(rx[i] + 300f * SplineMath.weight(i, n), px[i], 0.5f)
            assertEquals(ry[i] - 500f * SplineMath.weight(i, n), py[i], 0.5f)
        }
        assertEquals(rx[n - 1], px[n - 1], 0f)
        assertEquals(0f, vx[3], 0f)  // landed: at rest, so the frame loop can stop
    }

    @Test fun criticallyDampedNeverOvershootsAndAFlickDoes() {
        fun peak(damping: Float): Float {
            val (rx, ry) = rest()
            val px = rx.copyOf(); val py = ry.copyOf(); val vx = FloatArray(n); val vy = FloatArray(n)
            px[0] += 400f
            var most = 0f
            repeat(600) {
                SplineMath.chain(px, py, vx, vy, rx, ry, n, 0.15f, damping, 1f / 120)
                most = maxOf(most, (px[1] - rx[1]) / (400f * SplineMath.weight(1, n)))  // share of its final bend
            }
            return most
        }
        assertTrue(peak(1f) <= 1.0005f)
        assertTrue(peak(0.72f) > 1.02f)
    }

    @Test fun theTailLagsTheHead() {
        val (rx, ry) = rest()
        val px = rx.copyOf(); val py = ry.copyOf(); val vx = FloatArray(n); val vy = FloatArray(n)
        px[0] += 400f
        repeat(6) { SplineMath.chain(px, py, vx, vy, rx, ry, n, 0.15f, 1f, 1f / 120) }
        val share = { i: Int -> (px[i] - rx[i]) / (400f * SplineMath.weight(i, n)) }
        assertTrue(share(1) > share(4)); assertTrue(share(4) > share(8))  // silk: the pull runs down the line a beat late
    }

    @Test fun curvePassesThroughEveryPointAndArcLengthIsTrue() {
        val (rx, ry) = rest()
        val m = SplineMath.size(n)
        val xs = FloatArray(m); val ys = FloatArray(m); val len = FloatArray(m)
        assertEquals(m, SplineMath.sample(rx, ry, n, xs, ys, len))
        for (i in 0 until n) { assertEquals(rx[i], xs[i * SplineMath.PER], 0.01f); assertEquals(ry[i], ys[i * SplineMath.PER], 0.01f) }
        for (k in 1 until m) assertTrue(len[k] >= len[k - 1])
        var chord = 0f
        for (i in 1 until n) chord += hypot(rx[i] - rx[i - 1], ry[i] - ry[i - 1])
        assertTrue("a curve is a little longer than its chords", len[m - 1] >= chord && len[m - 1] < chord * 1.3f)
    }

    @Test fun straightThreadPlacesByDistance() {
        val px = floatArrayOf(0f, 100f, 200f, 300f); val py = FloatArray(4)
        val m = SplineMath.size(4)
        val xs = FloatArray(m); val ys = FloatArray(m); val len = FloatArray(m); val out = FloatArray(4)
        SplineMath.sample(px, py, 4, xs, ys, len)
        assertEquals(300f, len[m - 1], 0.01f)
        SplineMath.at(150f, xs, ys, len, m, out)
        assertEquals(150f, out[0], 0.01f); assertEquals(0f, out[1], 0.01f)
        assertEquals(1f, out[2], 1e-4f); assertEquals(0f, out[3], 1e-4f)  // unit tangent along it
        SplineMath.at(-50f, xs, ys, len, m, out); assertEquals(0f, out[0], 0f)    // clamped to the ends
        SplineMath.at(900f, xs, ys, len, m, out); assertEquals(300f, out[0], 0.01f)
        assertEquals(200f, SplineMath.nearest(198f, 40f, xs, ys, len, m), 13f)
    }

    @Test fun mostUsedSitNearestTheOrbAndScrollSlidesThemAlong() {
        val lead = 70f; val pitch = 60f
        assertEquals(lead, SplineMath.beadAt(0, lead, pitch, 0f), 0f)
        assertTrue(SplineMath.beadAt(1, lead, pitch, 0f) > SplineMath.beadAt(0, lead, pitch, 0f))
        assertEquals(lead - 30f, SplineMath.beadAt(0, lead, pitch, 30f), 0f)  // sliding toward the orb
        // a 1000 px thread shows ranks 0..15 at rest; scrolled 600 px, the first nine have melted into the orb and
        // rank 9 sits right where it fades to nothing
        assertEquals(0, SplineMath.first(lead - pitch, lead, pitch, 0f))
        assertEquals(15, SplineMath.last(1000f, lead, pitch, 0f, 100))
        assertEquals(9, SplineMath.first(lead - pitch, lead, pitch, 600f))
        assertEquals(0f, SplineMath.fade(SplineMath.beadAt(9, lead, pitch, 600f), lead, pitch, 1000f), 1e-4f)
        assertEquals(5, SplineMath.last(1000f, lead, pitch, 0f, 6))  // never past the last app
    }

    @Test fun scrollEndsKeepTheLastBeadOnTheThread() {
        val lead = 70f; val pitch = 60f; val length = 1000f
        assertEquals(0f, SplineMath.maxScroll(10, lead, pitch, length), 0f)  // all fit: nothing to slide
        val mx = SplineMath.maxScroll(100, lead, pitch, length)
        assertEquals(length - pitch / 2, SplineMath.beadAt(99, lead, pitch, mx), 0.01f)
        assertEquals(1f, SplineMath.fade(length - pitch / 2, lead, pitch, length), 1e-4f)
        assertEquals(1f, SplineMath.fade(lead, lead, pitch, length), 1e-4f)
        assertEquals(0f, SplineMath.fade(lead - pitch, lead, pitch, length), 1e-4f)  // gone into the orb
        assertTrue(abs(SplineMath.fade(length, lead, pitch, length)) < 1e-4f)
    }
}
