package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitTest {
    @Test fun searchRanksStartsWithThenWordThenContains() {
        val apps = listOf("Google Maps", "Gmail", "Smart Manager", "Maps Lite", "Calendar")
        assertEquals(listOf("Maps Lite", "Google Maps", "Smart Manager", "Gmail"), SmartSearch.rank("ma", apps) { it })
        assertEquals(listOf("Gmail", "Google Maps"), SmartSearch.rank("gm", apps) { it })  // Google Maps by its initials, after the real match
        assertEquals(listOf("Calendar"), SmartSearch.rank("lend", apps) { it })  // contains only
        assertEquals(emptyList<String>(), SmartSearch.rank("  ", apps) { it })
    }

    @Test fun searchScoreFindsALaterWordStart() {
        assertEquals(1, SmartSearch.score("map", "Bitmap Maps"))  // "map" inside Bitmap first, but Maps is a word start
        assertEquals(2, SmartSearch.score("map", "Bitmap"))
        assertEquals(0, SmartSearch.score("MAP", "maps"))
        assertEquals(-1, SmartSearch.score("x", "maps"))
    }

    @Test fun settingsMatchLabelBeforeKeywords() {
        val got = SmartSearch.matchPages("wi", SmartSearch.pages).map { it.label }
        assertEquals("Wi-Fi", got.first())
        assertTrue("Internet" in got)  // via its words
        assertEquals("Bluetooth", SmartSearch.matchPages("headphones", SmartSearch.pages).single().label)
    }

    @Test fun launchCountsOrderUsedFirstThenSeed() {
        val day = 86_400_000L
        val now = 100 * day
        val saved = mapOf("cam" to (3f to now), "old" to (8f to now - 70 * day), "chat" to (5f to now - day))
        val order = LaunchCounts.order(listOf("dial", "zoom", "chat", "cam", "old"), now) { saved[it] ?: (0f to 0L) }
        // chat ~4.76 > cam 3 > old 8 after 5 half-lives (0.25); never used keep their seed order
        assertEquals(listOf("chat", "cam", "old", "dial", "zoom"), order)
        assertEquals(4f, LaunchCounts.decayed(8f, 0L, 14 * day), 0.001f)
    }

    @Test fun ringsFillInnerFirstAndSnapToSlots() {
        assertEquals(listOf(8, 14, 3, 0), OrbitMath.fill(25, intArrayOf(8, 14, 20, 28)).toList())
        assertEquals(listOf(8, 14, 20, 28), OrbitMath.fill(500, intArrayOf(8, 14, 20, 28)).toList())
        assertEquals(8, OrbitMath.slots(100f, 64f, 8))
        assertEquals(9, OrbitMath.slots(100f, 64f, Int.MAX_VALUE))
        assertEquals(45f, OrbitMath.snap(30f, 45f), 0f)
        assertEquals(-45f, OrbitMath.snap(-30f, 45f), 0f)
        assertEquals(-20f, OrbitMath.wrap(340f), 0.001f)
        assertEquals(20f, OrbitMath.wrap(-340f), 0.001f)
    }
}
