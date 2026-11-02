package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sqrt

class SphereMathTest {
    private fun len(p: FloatArray, i: Int) = sqrt(p[i * 3] * p[i * 3] + p[i * 3 + 1] * p[i * 3 + 1] + p[i * 3 + 2] * p[i * 3 + 2])

    @Test fun pointsAreEvenUnitPointsWithTheFirstFacingTheViewer() {
        for (n in listOf(1, 2, 7, 40, 120, 300)) {
            val p = SphereMath.points(n)
            assertEquals(n * 3, p.size)
            var minD = Float.MAX_VALUE
            for (i in 0 until n) {
                assertEquals(1f, len(p, i), 1e-4f)
                for (j in i + 1 until n) {
                    val dx = p[i * 3] - p[j * 3]; val dy = p[i * 3 + 1] - p[j * 3 + 1]; val dz = p[i * 3 + 2] - p[j * 3 + 2]
                    minD = minOf(minD, sqrt(dx * dx + dy * dy + dz * dz))
                }
            }
            // no two on top of each other: every gap is at least half the even spacing sqrt(4 pi / n)
            if (n > 1) assertTrue("n=$n min $minD", minD > 0.5f * sqrt(4 * PI.toFloat() / n))
            assertTrue("most used faces the viewer (n=$n)", p[2] >= p[(n - 1) * 3 + 2])
        }
        assertEquals(1f, SphereMath.points(1)[2], 0f)  // a lone app sits right in front
        assertTrue(SphereMath.points(120)[2] > 0.95f)
    }

    @Test fun rotationKeepsLengthAndComposes() {
        val a = FloatArray(4); val b = FloatArray(4); val ab = FloatArray(4)
        SphereMath.axisAngle(0f, 1f, 0f, 0.7f, a)
        SphereMath.axisAngle(1f, 0.3f, -0.4f, -1.9f, b)  // axis needn't be unit length
        SphereMath.mul(a, b, ab)
        val m = FloatArray(9); val out = FloatArray(3); val mid = FloatArray(3); val two = FloatArray(3)
        val v = floatArrayOf(0.3f, -0.5f, 0.81f)
        val vl = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        SphereMath.matrix(ab, m); SphereMath.apply(m, v[0], v[1], v[2], out)
        assertEquals(vl, sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2]), 1e-5f)
        // ab = b first, then a
        SphereMath.matrix(b, m); SphereMath.apply(m, v[0], v[1], v[2], mid)
        SphereMath.matrix(a, m); SphereMath.apply(m, mid[0], mid[1], mid[2], two)
        for (k in 0 until 3) assertEquals(two[k], out[k], 1e-5f)
        // a quarter turn about the vertical axis carries the front point to the right (drag right = spin right)
        SphereMath.axisAngle(0f, 1f, 0f, (PI / 2).toFloat(), a); SphereMath.matrix(a, m); SphereMath.apply(m, 0f, 0f, 1f, out)
        assertEquals(1f, out[0], 1e-5f); assertEquals(0f, out[2], 1e-5f)
        // a drag's axis moves the front point the way the finger went
        val q = FloatArray(4)
        SphereMath.drag(0f, 30f, 100f, q); SphereMath.matrix(q, m); SphereMath.apply(m, 0f, 0f, 1f, out)
        assertTrue(out[1] > 0.2f && kotlin.math.abs(out[0]) < 1e-5f)
        SphereMath.drag(0f, 0f, 100f, q)  // no movement, no turn
        assertEquals(1f, q[0], 0f)
    }

    @Test fun nearerIconsAreBiggerAndBrighterAndOnlyTheFrontIsHittable() {
        assertEquals(1f, SphereMath.scale(1f), 1e-6f)
        assertTrue(SphereMath.scale(0f) < 1f && SphereMath.scale(-1f) < SphereMath.scale(0f))
        assertTrue(SphereMath.scale(-1f) >= 0.45f)  // back icons still read as icons
        assertEquals(1f, SphereMath.alpha(1f), 1e-6f)
        assertTrue(SphereMath.alpha(-1f) < 0.35f && SphereMath.alpha(-1f) > 0f)
        assertTrue(SphereMath.alpha(-0.5f) < SphereMath.alpha(0.5f))
        // the silhouette's widest point is exactly one globe radius
        var widest = 0f
        for (k in -100..100) { val z = k / 100f; widest = maxOf(widest, sqrt(1 - z * z) * SphereMath.spread(z)) }
        assertEquals(1f, widest, 1e-3f)
        assertTrue(SphereMath.front(0.6f)); assertFalse(SphereMath.front(-0.05f)); assertFalse(SphereMath.front(-0.8f))
        // review 2 Nov: an icon drawn near full strength takes a tap (a rim band looked solid but did nothing)
        var z = -1f
        while (z <= 1f) { if (SphereMath.alpha(z) >= 0.95f) assertTrue("z=$z", SphereMath.front(z)); z += 0.01f }
    }

    @Test fun hitTestPicksTheNearestFrontIcon() {
        val xs = floatArrayOf(0f, 100f, 50f, 52f); val ys = floatArrayOf(0f, 0f, 0f, 0f)
        val rs = floatArrayOf(20f, 20f, 20f, -1f)
        val zs = floatArrayOf(0.9f, 0.5f, -0.6f, 0.9f)  // the third is on the back, the fourth not shown
        assertEquals(0, SphereMath.nearest(10f, 0f, xs, ys, rs, zs, 4, 4f))
        assertEquals(1, SphereMath.nearest(88f, 3f, xs, ys, rs, zs, 4, 4f))
        assertEquals(-1, SphereMath.nearest(50f, 0f, xs, ys, rs, zs, 4, 4f))  // only a back icon there
    }

    @Test fun flingFrictionDecaysToRest() {
        var v = 4000f; var t = 0f; var travelled = 0f
        val dt = 1f / 120
        while (v > SphereMath.REST && t < 10f) { travelled += v * dt; v = SphereMath.decay(v, dt); t += dt }
        assertTrue("rests in a few seconds, took $t", t in 1f..5f)
        assertEquals(project(4000f), travelled, project(4000f) * 0.05f)  // lands where Honeycomb's flick would
        assertEquals(0f, SphereMath.decay(0f, dt), 0f)
    }

    @Test fun idleSpinIsGentleAndOffUnderReducedMotion() {
        assertEquals(0f, SphereMath.idle(still = true, ramp = 1f), 0f)
        assertEquals(0f, SphereMath.idle(still = false, ramp = 0f), 0f)
        val full = SphereMath.idle(still = false, ramp = 1f)
        assertTrue(full > 0f && full < 0.3f)  // well under a turn every 20 s
        assertTrue(SphereMath.idle(false, 0.3f) < full)
    }
}
