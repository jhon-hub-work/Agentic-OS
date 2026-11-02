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

    /** Every text colour on every surface it can land on: the ground, the frost light, the glass over both, the bar,
     *  rows tinted with any accent, the count pill over any icon. 4.5:1 throughout (body text; large text needs 3). */
    @Test fun everyTextColourReadsOnEveryGround() {
        val bad = mutableListOf<String>()
        val black = 0xFF000000.toInt(); val white = 0xFFFFFFFF.toInt()
        fun check(what: String, fg: Int, bg: Int) { val r = Themes.contrast(fg, bg); if (r < 4.5) bad += "$what: %.2f".format(r) }
        for (t in Themes.all) for ((an, a) in Themes.accents + ("own" to t.accent)) {
            val word = Themes.readable(t, a)
            val inks = listOf("ink" to t.ink, "ink2" to t.ink2, "ink3" to t.ink3, "navy" to t.navy,
                "ok" to t.ok, "warn" to t.warn, "bad" to t.bad, "accent word" to word)
            Themes.grounds(t, a).forEachIndexed { i, g -> for ((n, c) in inks) check("${t.id}/$an $n on ground $i", c, g) }
            val glass = Themes.over(t.glass, t.ground)
            val tinted = Themes.over((a and 0xFFFFFF) or (46 shl 24), glass)  // dock pick, the mode menu's current row
            check("${t.id}/$an ink2 on accent row", t.ink2, tinted)
            for (under in listOf(black, white))  // the count pill sits on app icons of any colour
                check("${t.id} pill on ${"%08X".format(under)}", t.ink, Themes.over((t.ground and 0xFFFFFF) or (245 shl 24), under))
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** A needs card over his photo or the phone wallpaper: every card ink on the card's tint (with and without its
     *  sheen) over plain white and plain black, the worst pictures there are. Body text, so 4.5:1 throughout. */
    @Test fun cardTextReadsOverAnyWallpaper() {
        val bad = mutableListOf<String>()
        for (t in Themes.all) for ((an, a) in Themes.accents + ("own" to t.accent)) {
            val tint = Themes.cardTint(t, a)
            assertTrue("${t.id} tint lighter than its ink", (Themes.luminance(tint or (0xFF shl 24)) > 0.18) != t.dark)
            for (under in Themes.extremes) {
                val card = Themes.over(tint, under)
                for (bg in listOf(card, Themes.over(Themes.cardSheen(t), card)))
                    Themes.cardInks(t, a).forEachIndexed { i, c ->
                        val r = Themes.contrast(c, bg)
                        if (r < 4.5) bad += "${t.id}/$an ink $i over ${"%08X".format(under)}: %.2f".format(r)
                    }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    /** Words straight on the wallpaper take dark ink on a light picture and light ink on a dark one, whatever the theme,
     *  under Home's resting veil (the default 30% and the 10% floor), and that ink reads on it. */
    @Test fun wallpaperInkFollowsTheWallpaper() {
        val light = listOf(0xFFFFFFFF.toInt(), 0xFFE4EAF0.toInt(), 0xFFC9D3DC.toInt())  // white, the dot-wave ice, a pale photo
        val dark = listOf(0xFF000000.toInt(), 0xFF1A2230.toInt(), 0xFF2B2B2B.toInt())  // black, navy night, charcoal (#333 under a 30% light veil is mid grey: 4.49)
        val bad = mutableListOf<String>()
        for (t in Themes.all) for (dim in listOf(26, 77)) for ((samples, wantDark) in listOf(light to false, dark to true)) for (s in samples) {
            val behind = Themes.over((t.ground and 0xFFFFFF) or (dim shl 24), s)
            val ink = Themes.bare(t, behind)
            val what = "${t.id} dim $dim on ${"%08X".format(s)}"
            if (ink.dark != wantDark) bad += "$what: ${ink.id} ink"
            val r = Themes.contrast(ink.ink, behind)
            if (r < 4.5) bad += "$what ink: %.2f".format(r)
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
        assertEquals(Themes.night, Themes.bare(Themes.night, 0xFF000000.toInt()))      // already right: kept
        assertEquals(Themes.glacier, Themes.bare(Themes.night, 0xFFFFFFFF.toInt()))     // night on the ice: its twin's ink
        assertEquals(0xFF777777.toInt(), Themes.grey(Themes.luminance(0xFF777777.toInt())))
    }

    @Test fun accentWordKeepsAnAccentThatAlreadyReads() {
        val glacierBlue = 0xFF3FB6EC.toInt()
        assertEquals(glacierBlue, Themes.readable(Themes.night, glacierBlue))     // light blue on navy: as is
        val onIce = Themes.readable(Themes.glacier, glacierBlue)                     // 2:1 on the ice: deepened
        assertTrue(onIce != glacierBlue && Themes.contrast(onIce, Themes.glacier.ground) >= 4.5)
        assertTrue(onIce != Themes.glacier.ink)                                      // still a blue, not the ink
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
