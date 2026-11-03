package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class MercuryMathTest {
    private fun two(d: Float, r: Float) = MercuryMath.field(d / 2, 0f, floatArrayOf(0f, d), floatArrayOf(0f, 0f), floatArrayOf(r, r), 2)

    @Test fun aLoneDropsEdgeIsItsRadius() {
        val r = 68f
        val one = { x: Float -> MercuryMath.field(x, 0f, floatArrayOf(0f), floatArrayOf(0f), floatArrayOf(r), 1) }
        assertEquals(MercuryMath.LEVEL, one(r), 1e-5f)
        assertTrue(one(r - 1f) > MercuryMath.LEVEL); assertTrue(one(r + 1f) < MercuryMath.LEVEL)
        assertEquals(1f, one(0f), 0f)
        assertEquals(0f, one(r * MercuryMath.REACH + 0.01f), 0f)  // nothing past its reach: the shader can skip it
        assertEquals(0f, MercuryMath.kernel(10f, 0f), 0f)  // a drop of no size adds nothing
    }

    @Test fun dropsAtRestStayApartAndCloseOnesMelt() {
        val r = 68f
        assertTrue("three radii apart (the field's gap): two drops", two(3 * r, r) < MercuryMath.LEVEL)
        assertTrue("2.2 radii apart: one liquid with a neck", two(2.2f * r, r) > MercuryMath.LEVEL)
        // tray drops: 22 dp on a 56 dp step (2.625 px/dp) stay separate beads
        assertTrue(two(56 * 2.625f, 22 * 2.625f) < MercuryMath.LEVEL)
    }

    @Test fun pullLeansTowardTheOrbAndFadesAtBothEnds() {
        val out = FloatArray(2); val reach = 300f; val most = 36f
        MercuryMath.pull(100f, 0f, reach, most, out)
        assertEquals(most, out[0], 0.01f); assertEquals(0f, out[1], 0f)  // the peak, a third of reach out, toward the orb
        MercuryMath.pull(0f, -200f, reach, most, out)
        assertTrue(out[1] < 0f && out[1] > -most); assertEquals(0f, out[0], 0f)
        MercuryMath.pull(0f, 0f, reach, most, out); assertEquals(0f, out[0], 0f); assertEquals(0f, out[1], 0f)  // right under it: no flip
        MercuryMath.pull(0f, reach, reach, most, out); assertEquals(0f, out[1], 0f)
        for (d in 1..299) { MercuryMath.pull(d.toFloat(), 0f, reach, most, out); assertTrue(out[0] in 0f..most + 0.01f); assertTrue("never past the orb", out[0] <= d) }
    }

    @Test fun theDraggedOrbSwallowsOnlyWhatItCovers() {
        val orbR = 91f; val dropR = 68f
        assertTrue(MercuryMath.swallows(80f, 40f, orbR, dropR))
        assertTrue(MercuryMath.swallows(orbR + dropR * 0.29f, 0f, orbR, dropR))
        assertFalse(MercuryMath.swallows(orbR + dropR * 0.31f, 0f, orbR, dropR))
        // a drop at rest (clear of the orb by a drop and a margin) leaning by the most pull is never swallowed
        val rest = orbR + dropR + 52f
        assertFalse(MercuryMath.swallows(rest - 37f, 0f, orbR, dropR))
    }

    @Test fun aPopCarriesTheFlickAndBurstsTowardHome() {
        val out = FloatArray(2)
        MercuryMath.pop(2000f, 0f, 0f, 300f, 0.35f, 1800f, out)
        assertEquals(700f, out[0], 0.01f); assertEquals(1800f, out[1], 0.01f)
        MercuryMath.pop(0f, 0f, 0f, 0f, 0.35f, 1800f, out)  // home right under the orb: it still leaves, upward
        assertEquals(0f, out[0], 0f); assertEquals(-1800f, out[1], 0f)
    }

    private fun springs(n: Int) = Array(10) { FloatArray(n) }.also { it[9].fill(1f) }  // ox oy vx vy tx ty ks kv kt damp

    @Test fun dropsSettleToRestAndThenCostNothing() {
        val s = springs(3)
        val ox = s[0]; val oy = s[1]; val vx = s[2]; val vy = s[3]; val tx = s[4]; val ty = s[5]; val ks = s[6]; val kv = s[7]; val kt = s[8]; val damp = s[9]
        tx[0] = 40f; ty[1] = -25f; ks.fill(1f); kt.fill(1f); kt[2] = 0f  // one leans, one lifts, one melts away
        var frames = 0
        while (MercuryMath.step(ox, oy, vx, vy, tx, ty, ks, kv, kt, damp, 3, 0.4f, 1f / 120)) { frames++; assertTrue("settles", frames < 1200) }
        assertEquals(40f, ox[0], 0f); assertEquals(-25f, oy[1], 0f); assertEquals(0f, ks[2], 0f)
        assertEquals(0f, vx[0], 0f); assertEquals(0f, kv[2], 0f)
        assertFalse("at rest: the frame loop can stop", MercuryMath.step(ox, oy, vx, vy, tx, ty, ks, kv, kt, damp, 3, 0.4f, 1f / 120))
    }

    @Test fun calmDropsNeverOvershootAndAPoppedOneSplashes() {
        fun peak(d: Float, v0: Float): Float {
            val s = springs(1); s[0][0] = -300f; s[2][0] = v0; s[6][0] = 1f; s[8][0] = 1f; s[9][0] = d
            var most = -300f
            repeat(1200) { MercuryMath.step(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], s[8], s[9], 1, 0.4f, 1f / 120); most = maxOf(most, s[0][0]) }
            assertEquals("lands and takes calm damping back", 1f, s[9][0], 0f)
            return most
        }
        assertTrue(peak(1f, 0f) <= 0.3f)
        assertTrue("splash: one overshoot past home", peak(0.62f, 1500f) > 20f)
    }

    // S25 FE: 1080 x 2340 px at 2.625 px/dp, drop 26 dp, gap three drops, lens 91 px
    private val r = 26 * 2.625f; private val gap = 3 * r
    private val l = 42 + r; private val right = 1080 - 42 - r; private val t = 234 + 168 + r; private val b = 2340 - 63 - 42 - r
    private val cx = 540f; private val cy = 1170f; private val clear = 91 + r + 52

    @Test fun placementIsAPoissonScatterRankedOutFromTheOrb() {
        val n = 160
        val xs = FloatArray(n); val ys = FloatArray(n)
        val low = MercuryMath.place(n, l, t, right, b, gap, cx, cy, clear, 1234, xs, ys)
        for (i in 0 until n) {
            assertTrue("inside across", xs[i] in l..right); assertTrue("below the search field", ys[i] >= t)
            assertTrue("clear of the orb", hypot(xs[i] - cx, ys[i] - cy) >= clear - 0.01f)
            for (j in 0 until i) assertTrue("no two closer than the gap", hypot(xs[i] - xs[j], ys[i] - ys[j]) >= gap - 0.01f)
            if (i > 0) assertTrue("the most used nearest", hypot(xs[i] - cx, ys[i] - cy) >= hypot(xs[i - 1] - cx, ys[i - 1] - cy))
        }
        assertEquals(ys.max(), low, 0f)
        assertTrue("a long list runs past the screen", low > b)
        val again = FloatArray(n); val againY = FloatArray(n)
        MercuryMath.place(n, l, t, right, b, gap, cx, cy, clear, 1234, again, againY)
        assertTrue("same list, same field", xs.contentEquals(again) && ys.contentEquals(againY))
        MercuryMath.place(n, l, t, right, b, gap, cx, cy, clear, 99, again, againY)
        assertFalse("another list, another scatter", xs.contentEquals(again))
        assertEquals(t, MercuryMath.place(0, l, t, right, b, gap, cx, cy, clear, 1, xs, ys), 0f)
    }

    @Test fun hidingAnAppKeepsEveryOtherPlace() {
        for (n in intArrayOf(12, 40, 160, 300)) {
            val xs = FloatArray(n); val ys = FloatArray(n); val xs2 = FloatArray(n); val ys2 = FloatArray(n)
            MercuryMath.place(n, l, t, right, b, gap, cx, cy, clear, 1234, xs, ys)
            MercuryMath.place(n - 1, l, t, right, b, gap, cx, cy, clear, 1234, xs2, ys2)
            for (i in 0 until n - 1) { assertEquals("$n: place $i", xs[i], xs2[i], 0f); assertEquals(ys[i], ys2[i], 0f) }
        }
    }

    @Test fun theShadersDropsComeSortedByTopEdge() {
        val s = MercuryMath.SPARE; val n = 49
        val a = FloatArray(n * s); val rnd = java.util.Random(3)
        for (i in 0 until n) { a[i * s] = i.toFloat(); a[i * s + 1] = rnd.nextFloat(); a[i * s + 2] = i * 10f; a[i * s + 3] = rnd.nextFloat() * 2000 }
        val before = (0 until n).map { i -> (0 until s).map { a[i * s + it] } }.toSet()
        MercuryMath.sortByTop(a, n)
        for (i in 1 until n) assertTrue(a[(i - 1) * s + 3] <= a[i * s + 3])
        assertEquals("every record kept whole", before, (0 until n).map { i -> (0 until s).map { a[i * s + it] } }.toSet())
        MercuryMath.sortByTop(a, 0); MercuryMath.sortByTop(a, 1)  // nothing to sort: no harm
    }

    @Test fun neverMoreDropsOnScreenThanTheShaderTakes() {
        val n = 300
        val xs = FloatArray(n); val ys = FloatArray(n)
        val low = MercuryMath.place(n, l, t, right, b, gap, cx, cy, clear, 7, xs, ys)
        val reach = r * MercuryMath.REACH
        var s = 0f
        while (s <= low) {
            val shown = (0 until n).count { ys[it] - s + reach > 0f && ys[it] - s - reach < 2340f }
            assertTrue("$shown drops at scroll $s", shown <= 48)
            s += 40f
        }
    }

    @Test fun theTrayRingsKeepThumbTargetsApart() {
        val step = 56 * 2.625f; val r0 = 91f + 22 * 2.625f + 12 * 2.625f
        val xs = FloatArray(12); val ys = FloatArray(12)
        assertEquals(12, MercuryMath.fan(12, cx, cy, r0, step, 74f, 400f, 1006f, 2203f, xs, ys))
        assertEquals(cx, xs[0], 0.01f); assertEquals(cy - r0, ys[0], 0.01f)  // the first one straight above the orb
        for (i in 0 until 12) {
            val d = hypot(xs[i] - cx, ys[i] - cy)
            assertTrue("on a ring", d >= r0 - 0.01f && d <= r0 + step + 0.01f)  // twelve fit in two rings
            for (j in 0 until i) assertTrue("48 dp targets, 8 dp between", hypot(xs[i] - xs[j], ys[i] - ys[j]) >= step - 0.01f)
        }
        // the orb parked in a corner: the slots off screen are skipped and the rings grow outward instead
        val n = MercuryMath.fan(12, 140f, 460f, r0, step, 74f, 400f, 1006f, 2203f, xs, ys)
        assertEquals(12, n)
        for (i in 0 until n) { assertTrue(xs[i] in 74f..1006f); assertTrue(ys[i] in 400f..2203f) }
        assertEquals(0, MercuryMath.fan(0, cx, cy, r0, step, 74f, 400f, 1006f, 2203f, xs, ys))
    }
}
