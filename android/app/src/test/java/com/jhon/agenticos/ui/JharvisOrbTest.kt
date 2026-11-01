package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class JharvisOrbTest {
    @Test fun sphereIsUnitPointsSpreadTopToBottom() {
        val out = FloatArray(OrbMath.DOTS * 3)
        OrbMath.sphere(OrbMath.DOTS, out)
        var front = 0
        for (i in 0 until OrbMath.DOTS) {
            val x = out[i * 3]; val y = out[i * 3 + 1]; val z = out[i * 3 + 2]
            assertEquals(1f, sqrt(x * x + y * y + z * z), 1e-4f)
            if (z > 0f) front++
        }
        assertTrue(out[1] > 0.99f && out[(OrbMath.DOTS - 1) * 3 + 1] < -0.99f)
        assertTrue(front in 80..120)  // about half face the viewer
    }

    @Test fun bandsCoverFrontToBack() {
        assertEquals(0, OrbMath.band(-1f, 6))
        assertEquals(5, OrbMath.band(1f, 6))
        assertEquals(3, OrbMath.band(0.01f, 6))
        assertTrue(OrbMath.bandMid(0, 6) < OrbMath.bandMid(5, 6))
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
