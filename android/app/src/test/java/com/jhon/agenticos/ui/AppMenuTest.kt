package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMenuTest {
    // screen 1000 x 2000, safe area 40..960 x 100..1900, menu 600 x 500, gap 30
    private fun at(l: Int, t: Int, r: Int, b: Int) = AppMenu.place(l, t, r, b, 600, 500, 40, 100, 960, 1900, 30)

    @Test fun belowWhenItFits_leftEdgesLineUp() = assertEquals(AppMenu.Spot(100, 330, false, 600, 500), at(100, 200, 300, 300))

    @Test fun aboveNearTheBottom_rightEdgesLineUp() = assertEquals(AppMenu.Spot(300, 1170, true, 600, 500), at(700, 1700, 900, 1800))

    @Test fun clampedInsideTheSafeArea() = assertEquals(AppMenu.Spot(40, 330, false, 600, 500), at(0, 200, 120, 300))

    // menu 1500 tall: fits neither above nor below an icon in the middle, so it goes beside, centred on the icon

    @Test fun tooTall_leftHalfIcon_goesRightOfIt() =
        assertEquals(AppMenu.Spot(330, 400, false, 600, 1500), AppMenu.place(100, 1050, 300, 1250, 600, 1500, 40, 100, 960, 1900, 30))

    @Test fun tooTall_rightHalfIcon_goesLeftOfIt() =
        assertEquals(AppMenu.Spot(70, 400, false, 600, 1500), AppMenu.place(700, 1050, 900, 1250, 600, 1500, 40, 100, 960, 1900, 30))

    @Test fun tooTall_shortSide_narrowsAndNeverCoversTheIcon() {
        // icon in the middle (centre 550, right half): 410 px left of it minus the gap and margin = 340 wide
        val s = AppMenu.place(410, 1050, 690, 1250, 600, 1500, 40, 100, 960, 1900, 30)
        assertEquals(AppMenu.Spot(40, 400, false, 340, 1500), s)
        assertTrue(s.x + s.w <= 410 - 30)
    }

    @Test fun tooTall_clampedVertically() =
        assertEquals(AppMenu.Spot(330, 100, false, 600, 1500), AppMenu.place(100, 400, 300, 500, 600, 1500, 40, 100, 960, 1900, 30))

    // the same middle icon, but beside it is under the 400 px minimum: it goes in the roomier of above or below,
    // full width, height capped to that room (its rows scroll), and never over the icon

    @Test fun narrowSide_goesAboveCappedAndScrolls() {
        val s = AppMenu.place(410, 1050, 690, 1250, 600, 1500, 40, 100, 960, 1900, 30, 400)
        assertEquals(AppMenu.Spot(90, 100, true, 600, 920), s)
        assertTrue(s.y >= 100 && s.y + s.h <= 1050 - 30)
    }

    @Test fun narrowSide_goesBelowWhenThatIsRoomier() {
        val s = AppMenu.place(410, 650, 690, 850, 600, 1500, 40, 100, 960, 1900, 30, 400)
        assertEquals(AppMenu.Spot(90, 880, false, 600, 1020), s)
        assertTrue(s.y >= 850 + 30 && s.y + s.h <= 1900)
    }

    @Test fun wideSideStillGoesBeside() =
        assertEquals(AppMenu.Spot(330, 400, false, 600, 1500), AppMenu.place(100, 1050, 300, 1250, 600, 1500, 40, 100, 960, 1900, 30, 400))

    @Test fun besideIsCappedToTheScreen() =
        assertEquals(AppMenu.Spot(330, 100, false, 600, 1800), AppMenu.place(100, 1050, 300, 1250, 600, 2500, 40, 100, 960, 1900, 30, 400))
}
