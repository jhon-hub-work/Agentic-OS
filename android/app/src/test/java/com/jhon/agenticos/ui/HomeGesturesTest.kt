package com.jhon.agenticos.ui

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGesturesTest {
    @Test fun homePressAnimatesOnlyWhenAlreadyInFront() {
        assertTrue(HomeMath.alreadyOnHome(true, 0))
        assertFalse(HomeMath.alreadyOnHome(true, Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT))  // back from an app
        assertFalse(HomeMath.alreadyOnHome(false, 0))                                      // shade or a dialog had focus
    }

    @Test fun topTwelvePercentIsTheShade() {
        assertTrue(HomeMath.inShadeZone(100f, 1000f))
        assertFalse(HomeMath.inShadeZone(120f, 1000f))
        assertFalse(HomeMath.inShadeZone(400f, 1000f))
    }

    @Test fun doubleTapNeedsTimeAndPlace() {
        assertTrue(HomeMath.doubleTap(1000, 50f, 50f, 1200, 60f, 55f, 300, 100f))
        assertFalse(HomeMath.doubleTap(0, 50f, 50f, 1200, 50f, 50f, 300, 100f))      // no first tap
        assertFalse(HomeMath.doubleTap(1000, 50f, 50f, 1400, 50f, 50f, 300, 100f))   // too slow
        assertFalse(HomeMath.doubleTap(1000, 50f, 50f, 1020, 50f, 50f, 300, 100f))   // a bounce, not a tap
        assertFalse(HomeMath.doubleTap(1000, 50f, 50f, 1200, 250f, 50f, 300, 100f))  // somewhere else
    }

    @Test fun badgeCountsReadTheContract() {
        val m = HomeMath.badgeCounts("""{"a":{"count":3,"notes":[]},"b":{"count":0},"c":{"notes":[]},"d":5}""")
        assertEquals(mapOf("a" to 3), m)
        assertEquals(emptyMap<String, Int>(), HomeMath.badgeCounts(""))
        assertEquals(emptyMap<String, Int>(), HomeMath.badgeCounts("not json"))
    }

    @Test fun badgeTextAndSpeech() {
        assertEquals("7", HomeMath.badgeText(7))
        assertEquals("99+", HomeMath.badgeText(140))
        assertEquals("Chat", HomeMath.slotSays("Chat", 0))
        assertEquals("Chat, 1 notification", HomeMath.slotSays("Chat", 1))
        assertEquals("Chat, 4 notifications", HomeMath.slotSays("Chat", 4))
    }
}
