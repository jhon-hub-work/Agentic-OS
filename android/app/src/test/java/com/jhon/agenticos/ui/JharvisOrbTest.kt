package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class JharvisOrbTest {
    @Test fun cloudIsUnitPointsSpreadTopToBottom() {
        val out = FloatArray(OrbMath.DOTS * 3)
        OrbMath.cloud(OrbMath.DOTS, out)
        var front = 0
        for (i in 0 until OrbMath.DOTS) {
            val x = out[i * 3]; val y = out[i * 3 + 1]; val z = out[i * 3 + 2]
            assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-4f)
            if (z > 0f) front++
        }
        assertTrue(out[1] > 0.99f && out[(OrbMath.DOTS - 1) * 3 + 1] < -0.99f)
        assertTrue(front in OrbMath.DOTS * 2 / 5..OrbMath.DOTS * 3 / 5)  // about half face the viewer
        // jittered, yet no two dots land on top of each other
        var nearest = 9f
        for (i in 0 until OrbMath.DOTS) for (j in i + 1 until OrbMath.DOTS) {
            val dx = out[i * 3] - out[j * 3]; val dy = out[i * 3 + 1] - out[j * 3 + 1]; val dz = out[i * 3 + 2] - out[j * 3 + 2]
            nearest = minOf(nearest, sqrt(dx * dx + dy * dy + dz * dz))
        }
        assertTrue(nearest > 0.08f)
    }

    @Test fun bandsCoverFrontToBack() {
        assertEquals(0, OrbMath.band(-1f, 6))
        assertEquals(5, OrbMath.band(1f, 6))
        assertEquals(3, OrbMath.band(0.01f, 6))
        assertTrue(OrbMath.bandMid(0, 6) < OrbMath.bandMid(5, 6))
    }

    @Test fun frontDotsSpreadWiderYetStayInsideTheLens() {
        assertTrue(OrbMath.depthScale(1f) > OrbMath.depthScale(-1f))
        // the cloud's widest projected point, at full voice wobble, stays inside the glass's rim
        var widest = 0f
        for (i in 0..200) { val z = -1f + i / 100f; val q = sqrt(1f - z * z); widest = maxOf(widest, q * OrbMath.depthScale(z) * OrbMath.refract(q, z)) }
        assertTrue(OrbMath.CLOUD * widest * 1.04f < 0.92f)
    }

    @Test fun glassPullsTheBackInAndBunchesTheFrontAtTheRim() {
        assertTrue(OrbMath.refract(0.6f, -0.8f) < 0.85f)        // a back dot slips toward the centre
        assertTrue(OrbMath.refract(0.2f, 0.98f) > 1.15f)        // the front magnifies in the middle
        assertEquals(1f, OrbMath.refract(1f, 0f), 1e-6f)        // and meets the limb where it was
        // the front mapping keeps its order: a dot farther out stays farther out
        var last = 0f
        for (i in 0..100) { val q = i / 100f; val z = sqrt(1f - q * q); val p = q * OrbMath.refract(q, z); assertTrue(p >= last); last = p }
    }

    @Test fun mutedAccentsGetChromaVividOnesAndGreyPassThrough() {
        val steel = 0xFF7C92AB.toInt(); val glacier = 0xFF3FB6EC.toInt(); val grey = 0xFF808080.toInt()
        fun chroma(c: Int) = maxOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF) - minOf(c shr 16 and 0xFF, c shr 8 and 0xFF, c and 0xFF)
        assertTrue(chroma(OrbMath.vivid(steel)) >= 75)
        assertEquals(glacier, OrbMath.vivid(glacier))
        assertEquals(grey, OrbMath.vivid(grey))
    }

    @Test fun ringGrowsFadesAndEnds() {
        assertEquals(1f, OrbMath.ringRadius(0f), 1e-6f)
        assertEquals(1.9f, OrbMath.ringRadius(OrbMath.RING_S), 1e-5f)
        assertTrue(OrbMath.ringRadius(0.3f) < OrbMath.ringRadius(0.6f))
        assertEquals(0.95f, OrbMath.ringAlpha(0f), 1e-6f)
        assertTrue(OrbMath.ringAlpha(0.6f) < OrbMath.ringAlpha(0.1f))
        assertEquals(0f, OrbMath.ringAlpha(OrbMath.RING_S), 0f)
        assertEquals(0f, OrbMath.ringAlpha(-0.5f), 0f)  // no ring before the first pulse
    }

    @Test fun breathingGrowsWithVoiceAndShimmerIsSmall() {
        assertTrue(OrbMath.haloRadius(1f) > OrbMath.haloRadius(0f))
        assertTrue(OrbMath.haloAlpha(1f) > OrbMath.haloAlpha(0f) && OrbMath.haloAlpha(1f) <= 1f)
        assertEquals(OrbMath.coreRadius(0f, 0f, 3f), OrbMath.coreRadius(0f, 0f, 0f), 0f)  // no stir, no shimmer
        val c = OrbMath.coreRadius(0f, 1f, 0.17f) / OrbMath.coreRadius(0f, 0f, 0f)
        assertTrue(c in 0.94f..1.06f)
    }

    @Test fun dotsAreLitLikeTheBody() {
        assertEquals(1f, OrbMath.light(-0.53f, -0.63f), 1e-3f)   // upper left, near the cloud's edge
        assertEquals(0.45f, OrbMath.light(0.53f, 0.63f), 1e-3f)  // lower right, in shadow
        assertTrue(OrbMath.light(0f, 0f) in 0.6f..0.8f)
    }

    @Test fun cloudWrapsAwayAtItsEdge() {
        assertEquals(1f, OrbMath.edgeFade(0.5f), 1e-6f)
        assertTrue(OrbMath.edgeFade(1f) < 0.2f)                // the limb nearly vanishes
        assertTrue(OrbMath.edgeFade(0.9f) in 0.3f..0.8f)      // a gradual fade, not a hard ring
    }

    @Test fun glintStaysTheBrightestThing() {
        assertTrue(OrbMath.glintClear(0f) < 0.2f)              // dots right under it dim away
        assertEquals(1f, OrbMath.glintClear(0.3f), 1e-6f)      // the rest of the cloud is untouched
    }

    @Test fun compactKeepsAboutADozenDots() {
        assertEquals(OrbMath.DOTS, (0 until OrbMath.DOTS).count { OrbMath.keep(it, 0f) })
        assertTrue((0 until OrbMath.DOTS).count { OrbMath.keep(it, 1f) } in 9..16)
    }

    @Test fun haloHugsTheBallAtRest() {
        assertTrue(OrbMath.haloRadius(0f) <= 1.35f)
    }

    @Test fun restingCoreIsSmallButSwellsWithVoice() {
        assertTrue(OrbMath.coreRadius(0f, 0f, 0f) <= 0.26f)
        assertTrue(OrbMath.coreRadius(1f, 0f, 0f) > 1.5f * OrbMath.coreRadius(0f, 0f, 0f))
    }

    @Test fun nothingClipsInsideTheView() {
        // the farthest glow, ring or shadow, plus a 2.2 dp stroke on a 170 dp overlay, stays inside half the min side
        assertTrue(OrbMath.reach() * OrbMath.LENS + 1.1f / 170f < 0.5f)
    }

    @Test fun darkColoursLiftOnTheLens() {
        val graphite = 0xFF1C1C1E.toInt(); val glacier = 0xFF3FB6EC.toInt()
        assertEquals(glacier, OrbMath.lift(glacier))
        val lifted = OrbMath.lift(graphite)
        assertTrue((lifted shr 8 and 0xFF) > 0x70)
        assertEquals(0xFF, lifted ushr 24)
    }
}
