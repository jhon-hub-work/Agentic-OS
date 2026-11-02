package com.jhon.agenticos.ui

import com.jhon.agenticos.core.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchTest {
    @Test fun normalizedSubsequenceAndTypoBands() {
        assertEquals(0, SmartSearch.score("cafe", "Café Bustelo"))           // accents fold away
        assertEquals(3, SmartSearch.score("ytm", "YT Music"))                // letters in order from a word start
        assertEquals(-1, SmartSearch.score("tm", "Gmail"))                   // must start at a word start
        assertEquals(3, SmartSearch.score("spotfy", "Spotify"))              // a missing letter is still in order
        assertEquals(4, SmartSearch.score("sptoify", "Spotify"))             // swapped letters: a typo
        assertEquals(4, SmartSearch.score("instagarm", "Instagram"))
        assertEquals(-1, SmartSearch.score("xq", "Spotify"))
        assertEquals(-1, SmartSearch.score("zzz", "Spotify"))                // too far for a typo
        val apps = listOf("Spotify", "YT Music", "Messages", "Maps")
        assertEquals(listOf("YT Music"), SmartSearch.rank("ytm", apps) { it })
        assertEquals(listOf("Messages", "Maps"), SmartSearch.rank("m", apps) { it }.take(2))  // prefix before anything looser
    }

    @Test fun jaroWinklerKnownValues() {
        assertEquals(1.0, SmartSearch.jaroWinkler("abc", "abc"), 1e-9)
        assertEquals(0.961, SmartSearch.jaroWinkler("martha", "marhta"), 0.001)
        assertEquals(0.0, SmartSearch.jaroWinkler("abc", "xyz"), 1e-9)
        assertEquals(0.0, SmartSearch.jaroWinkler("", "xyz"), 1e-9)
    }

    @Test fun settingsStillPreferLabels() {
        assertEquals("Bluetooth", SmartSearch.matchPages("headphones", SmartSearch.pages).first().label)
        assertEquals("Wi-Fi", SmartSearch.matchPages("wifi", SmartSearch.pages).first().label)
    }

    @Test fun timers() {
        assertEquals(SmartSearch.Quick.Timer(600), SmartSearch.quick("timer 10"))
        assertEquals(SmartSearch.Quick.Timer(90), SmartSearch.quick("Timer 90s"))
        assertEquals(SmartSearch.Quick.Timer(300), SmartSearch.quick("5 min timer"))
        assertEquals(SmartSearch.Quick.Timer(7200), SmartSearch.quick("set a timer for 2 hours"))
        assertNull(SmartSearch.quick("timer"))
        assertNull(SmartSearch.quick("timer 0"))
        assertEquals("10 minutes", SmartSearch.timerWords(600))
        assertEquals("1 hour", SmartSearch.timerWords(3600))
        assertEquals("90 seconds", SmartSearch.timerWords(90))
    }

    @Test fun modes() {
        assertEquals(SmartSearch.Quick.SetMode(Mode.NIGHT), SmartSearch.quick("Night"))
        assertEquals(SmartSearch.Quick.SetMode(Mode.DRIVING), SmartSearch.quick(" driving "))
        assertEquals(SmartSearch.Quick.SetMode(Mode.OFF), SmartSearch.quick("mode off"))
        assertNull(SmartSearch.quick("nightly"))
    }

    @Test fun arithmetic() {
        assertEquals("84", SmartSearch.calc("12*7"))
        assertEquals("14", SmartSearch.calc("2 + 3 x 4"))       // x is times, and times binds first
        assertEquals("25", SmartSearch.calc("(2+3)^2"))
        assertEquals("512", SmartSearch.calc("2^3^2"))          // power is right-associative
        assertEquals("0.3", SmartSearch.calc("0.1+0.2"))
        assertEquals("30", SmartSearch.calc("200*15%"))
        assertEquals("-2", SmartSearch.calc("-5+3"))
        assertEquals("2.5", SmartSearch.calc("5/2"))
        assertNull(SmartSearch.calc("5/0"))                     // no answer, not Infinity
        assertNull(SmartSearch.calc("2024"))                    // a number alone isn't a sum
        assertNull(SmartSearch.calc("-5"))
        assertNull(SmartSearch.calc("1.2.3+1"))
        assertNull(SmartSearch.calc("(2+3"))
        assertNull(SmartSearch.calc("xbox"))
        assertNull(SmartSearch.calc("covid-19"))
        assertEquals(SmartSearch.Quick.Answer("84"), SmartSearch.quick("12*7"))
    }

    @Test fun recentSearchesKeepEightNewestFirst() {
        var r = emptyList<String>()
        for (i in 1..10) r = SmartSearch.remembered(r, "q$i")
        assertEquals(listOf("q10", "q9", "q8", "q7", "q6", "q5", "q4", "q3"), r)
        r = SmartSearch.remembered(r, " Q5 ")
        assertEquals("Q5", r.first())
        assertEquals(8, r.size)
        assertEquals(1, r.count { it.equals("q5", true) })
        assertEquals(r, SmartSearch.remembered(r, "   "))
    }

    @Test fun pinsParseAndSkipJunk() {
        val got = SmartSearch.parsePins("""[{"pkg":"a","id":"1","label":"Call mom"},{"pkg":"","id":"2","label":"x"},{"pkg":"b","id":"3"}]""")
        assertEquals(1, got.size)
        assertEquals("Call mom", got[0].label)
        assertTrue(SmartSearch.parsePins("not json").isEmpty())
        assertTrue(SmartSearch.parsePins(null).isEmpty())
    }

    @Test fun aYesIsNeverQueued() {
        for (q in listOf("yes", "  Yes!", "Jharvis, yes", "jarvis yeah do it", "yep", "Yup", "approve", "Approved", "go ahead please",
                "ok", "Okay sure", "sure", "sige", "oo", "do it", "send it", "go", "Hey Jharvis, yes"))
            assertTrue(q, SmartSearch.isYes(q))
        for (q in listOf("yesterday's bills", "what did yes mean", "no", "eyes test", "approval status", "go home", "jharvis", "oops", "okra price", "surely not", "door code"))
            assertTrue(q, !SmartSearch.isYes(q))
    }
}
