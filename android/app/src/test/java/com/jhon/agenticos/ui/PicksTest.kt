package com.jhon.agenticos.ui

import com.jhon.agenticos.core.Mode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PicksTest {
    @Test fun lettersFoldAccentsAndSendTheRestToHash() {
        assertEquals('E', PicksMath.letter("Écran"))
        assertEquals('N', PicksMath.letter("ñandu"))
        assertEquals('G', PicksMath.letter("  gmail"))
        assertEquals('#', PicksMath.letter("1Password"))
        assertEquals('#', PicksMath.letter("9GAG"))
        assertEquals('#', PicksMath.letter("@home"))
        assertEquals('#', PicksMath.letter("微信"))
        assertEquals('#', PicksMath.letter(""))
    }

    @Test fun sectionsRunAToZWithHashLast() {
        val got = PicksMath.sections(listOf("Zoom", "1Password", "Éclair", "camera", "Calendar", "eBay", "Chrome")) { it }
        assertEquals(listOf('C', 'E', 'Z', '#'), got.map { it.letter })
        assertEquals(listOf("Calendar", "camera", "Chrome"), got[0].items)  // by name, case and accents folded
        assertEquals(listOf("eBay", "Éclair"), got[1].items)
        assertEquals(listOf("1Password"), got[3].items)
    }

    @Test fun startsPointAtEachLetterRow() {
        // C (3 apps), E (2), Z (1): rows are C,a,a,a,E,a,a,Z,a
        assertArrayEquals(intArrayOf(0, 4, 7), PicksMath.starts(listOf(3, 2, 1)))
        assertArrayEquals(intArrayOf(), PicksMath.starts(emptyList()))
    }

    @Test fun scrubberMapsFingerToLetterAndHoldsTheEnds() {
        // 4 letters on a band 100..180 px: 20 px each
        assertEquals(0, PicksMath.at(100f, 100f, 80f, 4))
        assertEquals(1, PicksMath.at(125f, 100f, 80f, 4))
        assertEquals(3, PicksMath.at(179f, 100f, 80f, 4))
        assertEquals(0, PicksMath.at(40f, 100f, 80f, 4))    // above the band: the first letter
        assertEquals(3, PicksMath.at(500f, 100f, 80f, 4))   // below: the last
        assertEquals(-1, PicksMath.at(120f, 100f, 80f, 0))  // no letters
    }

    @Test fun reasonSaysWhatThePicksFollow() {
        assertEquals("Picked for night shift", PicksMath.reason(Mode.NIGHT, 14, true))
        assertEquals("Picked for driving", PicksMath.reason(Mode.DRIVING, 9, true))
        assertEquals("Picked for mornings", PicksMath.reason(Mode.OFF, 9, true))
        assertEquals("Picked for late nights", PicksMath.reason(Mode.OFF, 2, true))
        assertEquals("Apps you open most will show up here", PicksMath.reason(Mode.NIGHT, 2, false))
    }
}
