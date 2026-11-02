package com.jhon.agenticos.ui

import android.content.pm.ApplicationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupsTest {
    private val none = ApplicationInfo.CATEGORY_UNDEFINED

    @Test fun guessReadsTheNameFirst() {
        assertEquals("Money", AppGroups.guess(none, "GCash", "com.globe.gcash.android"))
        assertEquals("Money", AppGroups.guess(ApplicationInfo.CATEGORY_PRODUCTIVITY, "BPI", "com.bpi.ng.app"))  // a bank tagged productivity
        assertEquals("Money", AppGroups.guess(none, "Mybank Mobile", "ph.mybank"))                            // a piece of a word
        assertEquals("Chats", AppGroups.guess(none, "WhatsApp", "com.whatsapp"))
        assertEquals("Chats", AppGroups.guess(none, "Messages", "com.samsung.android.messaging"))
        assertEquals("Work", AppGroups.guess(none, "Gmail", "com.google.android.gm"))
        assertEquals("Play", AppGroups.guess(ApplicationInfo.CATEGORY_VIDEO, "YouTube", "com.google.android.youtube"))
        assertEquals("Create", AppGroups.guess(none, "CapCut", "com.lemon.lvoverseas"))
        assertEquals("Tools", AppGroups.guess(none, "Calculator", "com.sec.android.app.popupcalculator"))
    }

    @Test fun guessFallsBackToTheCategory() {
        assertEquals("Chats", AppGroups.guess(ApplicationInfo.CATEGORY_SOCIAL, "Zzz", "com.zzz"))
        assertEquals("Play", AppGroups.guess(ApplicationInfo.CATEGORY_GAME, "Zzz", "com.zzz"))
        assertEquals("Work", AppGroups.guess(ApplicationInfo.CATEGORY_PRODUCTIVITY, "Zzz", "com.zzz"))
        assertEquals("Create", AppGroups.guess(ApplicationInfo.CATEGORY_IMAGE, "Zzz", "com.zzz"))
        assertEquals("Create", AppGroups.guess(ApplicationInfo.CATEGORY_AUDIO, "Zzz", "com.zzz"))
        assertEquals("Tools", AppGroups.guess(ApplicationInfo.CATEGORY_MAPS, "Zzz", "com.zzz"))
        assertEquals("Other", AppGroups.guess(none, "Zzz", "com.zzz"))
        assertEquals("Other", AppGroups.guess(none, "Display", "com.display"))  // "pay" only counts as a whole word
    }

    @Test fun manualBeatsAiBeatsGuess() {
        val guess = AppGroups.Pick("Other", AppGroups.GUESS)
        val ai = AppGroups.Pick("Work", AppGroups.AI)
        val manual = AppGroups.Pick("Play", AppGroups.MANUAL)
        assertEquals("Work", AppGroups.merge(guess, ai).group)        // Jharvis replaces the guess
        assertEquals("Work", AppGroups.merge(ai, guess).group)        // a guess never replaces Jharvis
        assertEquals("Play", AppGroups.merge(ai, manual).group)       // his move replaces Jharvis
        assertEquals("Play", AppGroups.merge(manual, ai).group)       // and Jharvis never undoes it
        assertEquals("Play", AppGroups.merge(manual, guess).group)
        assertEquals("Chats", AppGroups.merge(manual, AppGroups.Pick("Chats", AppGroups.MANUAL)).group)  // his next move wins
        assertEquals("Other", AppGroups.merge(null, guess).group)
    }

    @Test fun storeRoundTripsAndDropsJunk() {
        val m = mapOf("com.a" to AppGroups.Pick("Work", AppGroups.MANUAL), "com.b/.Main#0" to AppGroups.Pick("Money", AppGroups.AI))
        val back = AppGroups.parse(AppGroups.format(m))
        assertEquals("Work", back["com.a"]!!.group); assertEquals(AppGroups.MANUAL, back["com.a"]!!.by)
        assertEquals("Money", back["com.b/.Main#0"]!!.group)
        assertTrue(AppGroups.parse("{torn").isEmpty())
        assertTrue(AppGroups.parse(null).isEmpty())
        assertTrue(AppGroups.parse("""{"com.c":{"g":"Snacks","by":"ai"}}""").isEmpty())  // not one of the groups
    }

    @Test fun answerKeepsKnownGroupsOnly() {
        val a = AppGroups.parseAnswer("""{"groups":{"com.a":"Chats","com.b":"Snacks","com.c":"Money"}}""")
        assertEquals(mapOf("com.a" to "Chats", "com.c" to "Money"), a)
        assertTrue(AppGroups.parseAnswer("oops").isEmpty())
    }

    @Test fun onlyGuessedOrNewAppsGoToJharvis() {
        val m = mapOf("g" to AppGroups.Pick("Other", AppGroups.GUESS), "a" to AppGroups.Pick("Work", AppGroups.AI),
            "m" to AppGroups.Pick("Play", AppGroups.MANUAL))
        assertEquals(listOf("g", "new"), AppGroups.unsorted(m, listOf("g", "a", "m", "new")))
    }

    @Test fun cardsKeepTheirOrderAndMostUsedFirst() {
        val pick = mapOf("mail" to "Work", "chat" to "Chats", "docs" to "Work", "odd" to "Snacks")
        val out = AppGroups.arrange(listOf("docs", "chat", "mail", "odd", "unknown"), pick)
        assertEquals(listOf("Work" to listOf("docs", "mail"), "Chats" to listOf("chat"), "Other" to listOf("odd", "unknown")), out)
    }
}
