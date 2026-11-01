package com.jhon.agenticos.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppsTest {
    @Test fun keysStayPlainForMainSingleActivityApps() {
        assertEquals("com.a", AppKey.of("com.a", "com.a/.Main", mainUser = true, single = true, serial = 0))
        assertEquals("com.a/.Main#0", AppKey.of("com.a", "com.a/.Main", mainUser = true, single = false, serial = 0))
        assertEquals("com.a/.Main#10", AppKey.of("com.a", "com.a/.Main", mainUser = false, single = true, serial = 10))
        assertEquals("com.a", AppKey.pkg("com.a"))
        assertEquals("com.a", AppKey.pkg("com.a/.Main#10"))
        assertEquals(10L, AppKey.serial("com.a/.Main#10"))
        assertNull(AppKey.serial("com.a"))
    }

    @Test fun frecencyFavoursRecentAndThisMoment() {
        val h = listOf(  // newest first
            LaunchCounts.Launch("maps", 9, bucket = 2, mode = "DRIVING"),
            LaunchCounts.Launch("chat", 8, bucket = 0, mode = "OFF"),
            LaunchCounts.Launch("chat", 7, bucket = 0, mode = "OFF"),
            LaunchCounts.Launch("mail", 6, bucket = 2, mode = "OFF"))
        // at 1 AM, mode off: chat (twice, this part of the day, this mode) leads
        val night = LaunchCounts.frecency(h, bucket = 0, mode = "OFF")
        assertEquals(listOf("chat", "mail", "maps"), night.entries.sortedByDescending { it.value }.map { it.key })
        // driving in the afternoon: maps (newest, same part of day, same mode) leads
        val drive = LaunchCounts.frecency(h, bucket = 2, mode = "DRIVING")
        assertEquals("maps", drive.maxByOrNull { it.value }!!.key)
        assertEquals(0, LaunchCounts.bucket(3)); assertEquals(0, LaunchCounts.bucket(0)); assertEquals(5, LaunchCounts.bucket(23))
    }

    @Test fun orderUsesFrecencyThenOldCountsThenSeed() {
        val freq = mapOf("b" to 2f)
        val order = LaunchCounts.order(listOf("a", "b", "c", "d"), 0L, freq) { if (it == "c") 5f to 0L else 0f to 0L }
        assertEquals(listOf("b", "c", "a", "d"), order)
    }

    @Test fun historyRoundTripsAndDropsTornLines() {
        val h = listOf(LaunchCounts.Launch("com.a", 5, 1, "OFF"), LaunchCounts.Launch("com.b/.X#10", 4, 0, "NIGHT"))
        val back = LaunchCounts.parse(LaunchCounts.format(h) + "\nbroken\tline\n\tx\ty\tz")
        assertEquals(listOf("com.a", "com.b/.X#10"), back.map { it.key })
        assertEquals(listOf(5L, 4L), back.map { it.time })
        assertEquals("NIGHT", back[1].mode)
        assertTrue(LaunchCounts.parse(null).isEmpty())
    }

    @Test fun badgesParse() {
        val b = AppBadges.parse("""{"com.wa":{"count":3,"notes":[{"title":"Mom","text":"Call me","time":3},{"title":"A","text":"b","time":2},
            {"title":"C","text":"d","time":1},{"title":"E","text":"f","time":0}]},"com.x":{"count":0,"notes":[]},"com.y":{"count":2}}""")
        assertEquals(setOf("com.wa", "com.y"), b.keys)
        assertEquals(3, b["com.wa"]!!.count)
        assertEquals(3, b["com.wa"]!!.notes.size)
        assertEquals("Mom", b["com.wa"]!!.notes[0].title)
        assertTrue(AppBadges.parse("{torn").isEmpty())
        assertTrue(AppBadges.parse("").isEmpty())
        assertEquals("99+", AppBadges.word(140)); assertEquals("7", AppBadges.word(7))
    }
}
