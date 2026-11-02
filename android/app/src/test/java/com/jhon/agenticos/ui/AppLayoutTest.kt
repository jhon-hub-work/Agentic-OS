package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLayoutTest {
    @Test fun unknownLayoutIsTheOrbit() {
        assertEquals(Layouts.ORBIT, Layouts.valid(null))
        assertEquals(Layouts.ORBIT, Layouts.valid(""))
        assertEquals(Layouts.ORBIT, Layouts.valid("grid"))
        for (id in Layouts.IDS) assertEquals(id, Layouts.valid(id))
    }

    @Test fun everyLayoutHasItsOwnPlainName() {
        val names = Layouts.IDS.map { Layouts.name(it) }
        assertEquals(listOf("Orbit", "Smart groups", "Honeycomb", "Jharvis picks", "Sphere"), names)
        for (id in Layouts.IDS) {
            val about = Layouts.about(id)
            assertTrue(about.isNotBlank())
            assertFalse(about, about.contains('—') || about.contains('·') || about.endsWith("."))
        }
    }

    @Test fun cornerOrbStaysClearOfTheStatusBarAndInsideTheTopBand() {
        // S25 FE: 1080 x 2340 px, status bar 105 px, lens 59 px in the corner, gaps 42 and 5 px
        val w = 1080f; val h = 2340f; val top = 105f; val r = 59f
        val x = HomeMath.cornerX(w, 0f, r, 42f); val y = HomeMath.cornerY(h, top, r, 5f)
        assertEquals(w - 42f - r, x, 0.01f)
        assertTrue("under the status bar", y - r >= top)
        assertTrue("inside the top 10% the layouts keep clear", y + r <= 0.10f * h + 1f)
        // a tall status bar (or no room): it moves down rather than under the bar
        assertTrue(HomeMath.cornerY(h, 300f, r, 5f) - r >= 300f)
        // a cutout or bar on the right moves it in
        assertEquals(x - 30f, HomeMath.cornerX(w, 30f, r, 42f), 0.01f)
    }
}
