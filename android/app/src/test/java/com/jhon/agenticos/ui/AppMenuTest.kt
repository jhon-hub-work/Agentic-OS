package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMenuTest {
    // screen 1000 x 2000, safe area 40..960 x 100..1900, menu 600 x 500, gap 30
    private fun at(l: Int, t: Int, r: Int, b: Int) = AppMenu.place(l, t, r, b, 600, 500, 40, 100, 960, 1900, 30)

    @Test fun belowWhenItFits_leftEdgesLineUp() = assertEquals(AppMenu.Spot(100, 330, false, 600), at(100, 200, 300, 300))

    @Test fun aboveNearTheBottom_rightEdgesLineUp() = assertEquals(AppMenu.Spot(300, 1170, true, 600), at(700, 1700, 900, 1800))

    @Test fun clampedInsideTheSafeArea() = assertEquals(AppMenu.Spot(40, 330, false, 600), at(0, 200, 120, 300))

    // menu 1500 tall: fits neither above nor below an icon in the middle, so it goes beside, centred on the icon

    @Test fun tooTall_leftHalfIcon_goesRightOfIt() =
        assertEquals(AppMenu.Spot(330, 400, false, 600), AppMenu.place(100, 1050, 300, 1250, 600, 1500, 40, 100, 960, 1900, 30))

    @Test fun tooTall_rightHalfIcon_goesLeftOfIt() =
        assertEquals(AppMenu.Spot(70, 400, false, 600), AppMenu.place(700, 1050, 900, 1250, 600, 1500, 40, 100, 960, 1900, 30))

    @Test fun tooTall_shortSide_narrowsAndNeverCoversTheIcon() {
        // icon in the middle (centre 550, right half): 410 px left of it minus the gap and margin = 340 wide
        val s = AppMenu.place(410, 1050, 690, 1250, 600, 1500, 40, 100, 960, 1900, 30)
        assertEquals(AppMenu.Spot(40, 400, false, 340), s)
        assertTrue(s.x + s.w <= 410 - 30)
    }

    @Test fun tooTall_clampedVertically() =
        assertEquals(AppMenu.Spot(330, 100, false, 600), AppMenu.place(100, 400, 300, 500, 600, 1500, 40, 100, 960, 1900, 30))
}
