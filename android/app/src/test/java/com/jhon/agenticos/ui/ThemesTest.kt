package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemesTest {
    @Test fun everyTextColourReadsOnItsGroundAndGlass() {
        val bad = mutableListOf<String>()
        for (t in Themes.all) {
            val glass = Themes.over(t.glass, t.ground)
            for ((name, col) in listOf("ink" to t.ink, "ink2" to t.ink2, "ink3" to t.ink3, "ok" to t.ok, "warn" to t.warn, "bad" to t.bad))
                for ((on, bg) in listOf("ground" to t.ground, "glass" to glass)) {
                    val r = Themes.contrast(col, bg)
                    if (r < 4.5) bad += "${t.id} $name on $on: %.2f".format(r)
                }
            val nav = Themes.contrast(t.onNavy, t.navy)
            if (nav < 4.5) bad += "${t.id} onNavy: %.2f".format(nav)
            for ((an, a) in Themes.accents + ("own" to t.accent)) {
                val r = Themes.contrast(Themes.onChrome(t, a), Themes.chrome(t, a)[1])
                if (r < 4.5) bad += "${t.id} chrome text with $an: %.2f".format(r)
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test fun twinsPointAtTheOtherLightness() {
        for (t in Themes.all) assertTrue(t.id, Themes.byId(t.pair).dark != t.dark)
    }

    @Test fun resolveFlipsOnlyWhenAsked() {
        assertEquals("glacier", Themes.resolve(null, false, true, Appearance.FROST, true, null).id)
        assertEquals("night", Themes.resolve("glacier", true, true, Appearance.FROST, true, null).id)
        assertEquals("glacier", Themes.resolve("night", true, false, Appearance.FROST, true, null).id)
        assertEquals("moss", Themes.resolve("moss", true, false, Appearance.FROST, true, null).id)  // match is Glacier-only
        assertEquals("midnight", Themes.resolve("graphite", false, false, Appearance.PHOTO, true, true).id)  // dark photo: light ink
        assertEquals("graphite", Themes.resolve("graphite", false, false, Appearance.PHOTO, false, true).id)  // theme forces it
        assertEquals("ember", Themes.resolve("ember", false, false, Appearance.SYSTEM, true, null).id)  // unknown: keep
        assertEquals("glacier", Themes.resolve("nope", false, false, Appearance.FROST, true, null).id)
    }

    @Test fun colourMaths() {
        assertEquals(21.0, Themes.contrast(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
        assertEquals(0xFF808080.toInt(), Themes.over(0x80FFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(0xFF7F7F7F.toInt(), Themes.mix(0xFF000000.toInt(), 0xFFFFFFFF.toInt(), 0.5f))
    }
}
