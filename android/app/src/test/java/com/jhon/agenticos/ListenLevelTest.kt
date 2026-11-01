package com.jhon.agenticos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ListenLevelTest {
    @Test fun levelMapsMicLoudnessToZeroOne() {
        assertEquals(0f, ListenService.level(0.0), 0f)          // silence, no log(0)
        assertEquals(0f, ListenService.level(200.0), 0f)        // a quiet room stays dark
        assertEquals(1f, ListenService.level(30000.0), 0f)      // shouting caps at 1
        val threshold = ListenService.level(900.0)              // the speech threshold sits low, so talking has room
        assertTrue(threshold in 0.3f..0.45f)
        assertTrue(ListenService.level(3000.0) in threshold..1f)
    }
}
