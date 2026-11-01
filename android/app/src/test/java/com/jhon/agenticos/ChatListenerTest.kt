package com.jhon.agenticos

import com.jhon.agenticos.ui.Checks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatListenerTest {
    @Test fun outboxBacksOffOnlyWithScreenOffAndNotDriving() {
        assertEquals(3_000L, outboxWait(100, screenOn = true, driving = false))
        assertEquals(3_000L, outboxWait(100, screenOn = false, driving = true))
        assertEquals(listOf(3_000L, 3_000L, 15_000L, 15_000L, 60_000L, 60_000L),
            listOf(0, 4, 5, 8, 9, 1000).map { outboxWait(it, screenOn = false, driving = false) })
    }

    @Test fun everyPaceStaysUnderTheWatchdog() {
        for (r in 0..50) assertTrue(outboxWait(r, screenOn = false, driving = false) <= Checks.LISTENER_STALE_MS / 2)  // half left for a slow PC turn
    }

    @Test fun dndAskedOnlyOnChange() {
        assertTrue(dndChange(want = true, last = null, sdk = 33))
        assertTrue(dndChange(want = false, last = true, sdk = 33))
        assertFalse(dndChange(want = true, last = true, sdk = 36))
        assertFalse(dndChange(want = false, last = false, sdk = 36))
        assertFalse(dndChange(want = false, last = null, sdk = 34))  // could end a DND he set himself
        assertTrue(dndChange(want = false, last = null, sdk = 35))  // ends only our own rule
    }

    @Test fun boundedMapDropsLeastRecentlyUsed() {
        val m = boundedMap<String>(3)
        m["a"] = "1"; m["b"] = "2"; m["c"] = "3"
        m["a"]  // used: stays
        m["d"] = "4"
        assertEquals(setOf("a", "c", "d"), m.keys)
    }
}
