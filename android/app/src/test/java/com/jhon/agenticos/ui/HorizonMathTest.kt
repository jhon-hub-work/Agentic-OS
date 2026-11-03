package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class HorizonMathTest {
    private val H = HorizonMath

    @Test fun theNearLineStaysWhereverTheOrbGoesAndFarPointsMeetAtIt() {
        val ax = 540f; val ay = 2130f; val u = -330f
        for ((vx, vy) in listOf(540f to 1170f, 120f to 300f, 960f to 1500f)) {
            assertEquals(ax + u, H.x(u, 1f, ax, vx), 0.001f)
            assertEquals(ay, H.y(1f, ay, vy), 0.001f)
            assertEquals(vx, H.x(u, 0.0001f, ax, vx), 0.5f)
            assertEquals(vy, H.y(0.0001f, ay, vy), 0.5f)
        }
    }

    @Test fun aLaneIsAStraightLineIntoTheVanishingPoint() {
        val ax = 540f; val ay = 2130f; val vx = 200f; val vy = 600f; val u = 400f
        for (d in listOf(-0.8f, 0f, 1f, 3f, 12f, 40f)) {
            val s = H.scale(d); val x = H.x(u, s, ax, vx); val y = H.y(s, ay, vy)
            val cross = (x - vx) * (ay - vy) - (y - vy) * (ax + u - vx)  // zero when on the line from the vanishing point
            assertEquals(0f, cross / 1e6f, 1e-4f)
        }
    }

    @Test fun scaleIsOneOnTheNearLineAndShrinksWithDepth() {
        assertEquals(1f, H.scale(0f), 0f)
        assertEquals(0.75f, H.scale(1f), 1e-6f)
        assertTrue(H.scale(-0.5f) > 1f)
        assertEquals(0f, H.scale(-H.NEAR), 0f)  // behind the camera: nothing
        for (s in listOf(0.1f, 0.5f, 1f, 1.3f)) assertEquals(s, H.scale(H.depth(s)), 1e-5f)
        var last = 2f
        for (d in 0..40) { val s = H.scale(d.toFloat()); assertTrue(s < last); last = s }
    }

    @Test fun alphaIsGoneFarOffAndPastTheSlideOff() {
        assertEquals(0f, H.alpha(H.S_FAR), 0f)
        assertEquals(0f, H.alpha(0.05f), 0f)
        assertEquals(1f, H.alpha(H.S_CLEAR), 1e-6f)
        assertEquals(1f, H.alpha(1f), 1e-6f)
        assertEquals(0f, H.alpha(H.S_OFF), 0f)
        assertTrue(H.alpha(1.2f) in 0.01f..0.99f)
        assertTrue(H.alpha(0.2f) in 0.01f..0.99f)
    }

    @Test fun visibleRowsFollowTheScroll() {
        assertEquals(0, H.firstRow(0f))
        assertEquals(0, H.firstRow(-2f))  // pulled back past the start
        val last0 = H.lastRow(0f, 100)
        assertTrue(H.alpha(H.scale(last0.toFloat())) > 0f)
        assertEquals(0f, H.alpha(H.scale(last0 + 1f)), 0f)  // the next one is too far to draw
        // five rows on: the rows that have slid past are gone, as many more show at the back
        assertTrue(H.firstRow(5f) in 4..5)
        assertEquals(0f, H.alpha(H.scale(H.firstRow(5f) - 1 - 5f)), 0f)
        assertEquals(last0 + 5, H.lastRow(5f, 100))
        assertEquals(9, H.lastRow(5f, 10))  // never past the last row
        assertTrue(H.lastRow(0f, 0) < H.firstRow(0f))  // no apps: no rows
    }

    @Test fun aTileLeansTowardTheOrbAndNotAtAllUnderIt() {
        assertEquals(0f, H.lean(540f, 2000f, 540f, 1000f), 0f)
        // skewX k: a point y px up (negative y) moves -k*y across, so k < 0 leans right
        assertTrue(H.lean(300f, 2000f, 540f, 1000f) < 0f)
        assertTrue(H.lean(800f, 2000f, 540f, 1000f) > 0f)
        assertEquals(0f, H.lean(300f, 1000f, 540f, 1000f), 0f)  // on the horizon: no slant to follow
    }

    @Test fun hitTakesTheNearestTileFirstAndSkipsFarOnes() {
        val tile = 100f; val target = 48f
        val bx = floatArrayOf(500f, 500f, 900f); val by = floatArrayOf(1000f, 960f, 1000f)
        val s = floatArrayOf(1f, 0.75f, 0.3f); val k = floatArrayOf(0f, 0f, 0f)
        val ok = booleanArrayOf(true, true, false)
        val order = intArrayOf(0, 1, 2)
        assertEquals(0, H.hit(500f, 950f, order, 3, bx, by, s, k, ok, tile, target))  // both cover it: the front one
        assertEquals(1, H.hit(500f, 890f, order, 3, bx, by, s, k, ok, tile, target))  // only the one behind
        assertEquals(-1, H.hit(900f, 985f, order, 3, bx, by, s, k, ok, tile, target)) // too far to tap
        assertEquals(-1, H.hit(700f, 950f, order, 3, bx, by, s, k, ok, tile, target))
        // a leaning tile's middle moves with its lean
        val lean = floatArrayOf(-0.4f)
        assertEquals(0, H.hit(520f, 950f, intArrayOf(0), 1, floatArrayOf(500f), floatArrayOf(1000f), floatArrayOf(1f), lean, booleanArrayOf(true), tile, target))
        assertEquals(-1, H.hit(455f, 950f, intArrayOf(0), 1, floatArrayOf(500f), floatArrayOf(1000f), floatArrayOf(1f), lean, booleanArrayOf(true), tile, target))
    }

    @Test fun onAnS25FeAtRestSeveralRowsTakeTapsOnFullTargets() {
        // 1080 x 2340 px at 2.625 px/dp, nav bar 63 px; the view's own layout numbers
        val dp = 2.625f; val w = 1080f; val h = 2340f
        val road = w - 32 * dp
        val cols = H.cols(road / dp)
        assertEquals(3, cols)
        val lane = road / cols
        val tile = min(lane * H.TILE, 72 * dp)
        val ay = h - 63f - 56 * dp; val vy = h / 2
        val cut = H.TAP_DP * dp; val target = 48 * dp
        val rows = tappableRows(tile, ay, vy, cut)
        assertTrue("at least two rows take taps: $rows", rows.size >= 2)
        assertEquals((0 until rows.size).toList(), rows)  // the nearest ones, none skipped
        for (r in rows) {
            val s = H.scale(r.toFloat())
            assertTrue("drawn at least the cut", tile * s >= cut)
            // targets of 48 dp neither overlap across lanes nor with the next row in
            assertTrue(lane * s >= target)
            if (r > 0) assertTrue(abs(H.y(H.scale(r - 1f), ay, vy) - H.y(s, ay, vy)) >= target)
        }
        // a wide screen runs four lanes
        assertEquals(4, H.cols(800f))
    }

    @Test fun withTheOrbAtTheFloorLimitOnlyTheFrontRowTakesTaps() {
        // the rows crowd together: row 1 shows a 16 dp strip over row 0, too thin to tap
        val dp = 2.625f; val tile = 72 * dp; val ay = 2130f; val vy = ay - tile - 64 * dp
        assertEquals(listOf(0), tappableRows(tile, ay, vy, H.TAP_DP * dp))
    }

    @Test fun aTapNearTheBaseOfAStronglyLeaningTileHitsIt() {
        // orb in a corner: k -0.77, the base sits well left of the middle; the old test used the middle x at every height
        val one = intArrayOf(0); val ok = booleanArrayOf(true)
        val bx = floatArrayOf(500f); val by = floatArrayOf(1000f); val s = floatArrayOf(1f); val k = floatArrayOf(-0.77f)
        assertEquals(0, H.hit(410f, 995f, one, 1, bx, by, s, k, ok, 189f, 126f))
        assertEquals(-1, H.hit(610f, 995f, one, 1, bx, by, s, k, ok, 189f, 126f))  // frost right of the base: nothing
    }

    @Test fun theCeilingMirrorsTheFloorAcrossTheHorizon() {
        // with the vanishing point halfway between the near lines, every ceiling point is a floor point flipped over it
        val ax = 540f; val ay = 2130f; val ayC = 390f; val vy = (ay + ayC) / 2; val vx = 300f
        for (s in listOf(0.06f, 0.3f, 0.75f, 1f, 1.3f)) {
            assertEquals(2 * vy - H.y(s, ay, vy), H.y(s, ayC, vy), 0.01f)
            // a hanging tile leans toward the orb as a standing one does: the mirrored slant
            val x = H.x(-330f, s, ax, vx)
            assertEquals(-H.lean(x, H.y(s, ay, vy), vx, vy), H.lean(x, H.y(s, ayC, vy), vx, vy), 1e-5f)
        }
        // left of the orb, a hanging tile's lower end (local y down) is carried right, toward it
        assertTrue(H.lean(300f, 400f, 540f, 1170f) > 0f)
        assertTrue(H.lean(800f, 400f, 540f, 1170f) < 0f)
    }

    @Test fun rowsAlternateFloorAndCeilingMostUsedFirst() {
        assertEquals(listOf(false, true, false, true, false, true), (0..5).map { H.ceiling(it) })
        assertEquals(listOf(0, 0, 1, 1, 2, 2), (0..5).map { H.depthOf(it) })  // the nearest floor and ceiling rows hold the top apps
        assertEquals(listOf(0, 1, 1, 2, 2, 3), listOf(0, 1, 2, 3, 4, 5).map { H.depths(it) })
    }

    @Test fun eachAppKeepsOneOfTheThreeNeons() {
        val keys = (0 until 30).map { "com.app$it/.Main" } + "polygenelubricants"  // the last hashes to Int.MIN_VALUE
        for (k in keys) { assertTrue(H.neon(k) in H.NEON.indices); assertEquals(H.neon(k), H.neon(String(k.toCharArray()))) }
        assertEquals(setOf(0, 1, 2), keys.map { H.neon(it) }.toSet())
    }

    @Test fun onAnS25FeTheCeilingTakesTapsOnItsOwnRowsEvenWithTheOrbAtTheFloorLimit() {
        val dp = 2.625f; val h = 2340f
        val tile = 72 * dp; val ay = h - 63f - 56 * dp; val ayC = h * 0.10f + 60 * dp; val cut = H.TAP_DP * dp
        val rest = tappableRows(tile, ayC, h / 2, cut, down = true)
        assertTrue("at least two ceiling rows take taps: $rest", rest.size >= 2)
        assertEquals((0 until rest.size).toList(), rest)
        // the orb down at the floor's limit crowds the floor to one row; the ceiling, judged on its own rows, opens out
        val low = ay - tile - 64 * dp
        assertEquals(listOf(0), tappableRows(tile, ay, low, cut))
        assertTrue(tappableRows(tile, ayC, low, cut, down = true).size >= 3)
        // and mirrored: the orb at the ceiling's limit leaves the floor several rows
        val high = ayC + tile + 64 * dp
        assertEquals(listOf(0), tappableRows(tile, ayC, high, cut, down = true))
        assertTrue(tappableRows(tile, ay, high, cut).size >= 3)
    }

    @Test fun aHangingTileIsHitBelowItsBaseNotAbove() {
        val one = intArrayOf(0); val ok = booleanArrayOf(true); val tile = 100f; val target = 48f
        val bx = floatArrayOf(500f); val by = floatArrayOf(400f); val s = floatArrayOf(1f); val k = floatArrayOf(0f)
        val down = booleanArrayOf(true)
        assertEquals(0, H.hit(500f, 450f, one, 1, bx, by, s, k, ok, tile, target, down))
        assertEquals(0, H.hit(530f, 495f, one, 1, bx, by, s, k, ok, tile, target, down))
        assertEquals(-1, H.hit(500f, 350f, one, 1, bx, by, s, k, ok, tile, target, down))  // above its base: frost
        assertEquals(0, H.hit(500f, 350f, one, 1, bx, by, s, k, ok, tile, target))         // where a standing one would be
        // leaning right (k > 0, left of the orb), its lower end is carried right
        val lean = floatArrayOf(0.5f)
        assertEquals(0, H.hit(540f, 490f, one, 1, bx, by, s, lean, ok, tile, target, down))
        assertEquals(-1, H.hit(455f, 490f, one, 1, bx, by, s, lean, ok, tile, target, down))
    }

    // rows 0..10 at rest that take taps on one plane (near line ay), each judged by how far it reaches past the row in
    // front on that plane
    private fun tappableRows(tile: Float, ay: Float, vy: Float, cut: Float, down: Boolean = false): List<Int> {
        var front = if (down) -Float.MAX_VALUE else Float.MAX_VALUE
        return (0..10).filter {
            val s = H.scale(it.toFloat()); val y = H.y(s, ay, vy); val h = tile * s
            val shows = H.shows(front, y, h, down); front = if (down) y + h else y - h
            H.tappable(s, H.alpha(s), tile, cut, shows)
        }
    }
}
