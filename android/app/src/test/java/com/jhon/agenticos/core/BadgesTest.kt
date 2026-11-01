package com.jhon.agenticos.core

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BadgesTest {
    private fun e(pkg: String, time: Long, flags: Int = 0, number: Int = 0, canBadge: Boolean = true, title: String = "t$time") =
        Badges.Entry(pkg, flags, number, canBadge, title, "x$time", time)

    @Test fun countsPerAppSkippingOngoingSummariesAndOwn() {
        val out = Badges.build(listOf(
            e("wa", 1), e("wa", 3, number = 4), e("wa", 2),
            e("wa", 9, flags = Notification.FLAG_GROUP_SUMMARY),
            e("music", 5, flags = Notification.FLAG_ONGOING_EVENT),
            e("dl", 6, flags = Notification.FLAG_FOREGROUND_SERVICE),
            e("mail", 7, canBadge = false),
            e("me", 8)), enabled = true, own = "me")
        assertEquals(setOf("wa"), out.keys().asSequence().toSet())
        val wa = out.getJSONObject("wa")
        assertEquals(6, wa.getInt("count"))  // 1 + number 4 + 1
        val notes = wa.getJSONArray("notes")
        assertEquals(3, notes.length())
        assertEquals(listOf(3L, 2L, 1L), (0 until 3).map { notes.getJSONObject(it).getLong("time") })  // newest first
        assertEquals("x3", notes.getJSONObject(0).getString("text"))
    }

    @Test fun onlyLatestThreeNotes() {
        val out = Badges.build((1L..5L).map { e("tg", it) }, enabled = true, own = "me")
        val notes = out.getJSONObject("tg").getJSONArray("notes")
        assertEquals(5, out.getJSONObject("tg").getInt("count"))
        assertEquals(listOf("t5", "t4", "t3"), (0 until notes.length()).map { notes.getJSONObject(it).getString("title") })
    }

    @Test fun badgingOffWritesEmptyMap() {
        assertFalse(Badges.build(listOf(e("wa", 1)), enabled = false, own = "me").keys().hasNext())
    }
}
