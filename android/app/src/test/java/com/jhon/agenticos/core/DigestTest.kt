package com.jhon.agenticos.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class DigestTest {
    private fun item(pkg: String, title: String, line: String) = JSONObject().put("app", pkg).put("pkg", pkg).put("title", title).put("line", line).put("time", 1)

    @Test fun samechatKeepsOnlyLatestLineAtTheEnd() {
        var a = JSONArray()
        a = Digest.merged(a, item("wa", "Lei Ann", "hi"))
        a = Digest.merged(a, item("wa", "Team", "standup?"))
        a = Digest.merged(a, item("wa", "Lei Ann", "call me"))
        assertEquals(2, a.length())
        assertEquals("Team", a.getJSONObject(0).getString("title"))
        assertEquals("call me", a.getJSONObject(1).getString("line"))
    }

    @Test fun sameTitleInAnotherAppIsAnotherChat() {
        val a = Digest.merged(Digest.merged(JSONArray(), item("wa", "Mom", "a")), item("tg", "Mom", "b"))
        assertEquals(2, a.length())
    }

    @Test fun capDropsOldest() {
        var a = JSONArray()
        for (k in 1..5) a = Digest.merged(a, item("wa", "c$k", "l$k"), cap = 3)
        assertEquals(listOf("c3", "c4", "c5"), (0 until a.length()).map { a.getJSONObject(it).getString("title") })
    }

    @Test fun afterKeepsOnlyNewerInOrder() {
        val a = JSONArray().put(item("wa", "a", "1").put("time", 5)).put(item("wa", "b", "2").put("time", 10)).put(item("wa", "c", "3").put("time", 20))
        assertEquals(listOf("b", "c"), Digest.after(a, 5).let { r -> (0 until r.length()).map { r.getJSONObject(it).getString("title") } })
        assertEquals(3, Digest.after(a, 0).length())
    }

    @Test fun unseenHidesWhatHomeMarkedSeen() {
        val f = Files.createTempDirectory("digest").toFile().resolve("digest.json")
        Digest.file = f
        try {
            assertEquals(0, Digest.unseen().length())  // no file yet: empty, no throw
            f.writeText(JSONArray().put(item("wa", "a", "old").put("time", 5)).put(item("wa", "b", "new").put("time", 10)).toString())
            assertEquals(2, Digest.unseen().length())
            Digest.seen(5)
            val u = Digest.unseen()
            assertEquals(1, u.length())
            assertEquals("new", u.getJSONObject(0).getString("line"))
        } finally { Digest.file = null }
    }

    @Test fun addStartsFromMissingFile() {
        // shortcut: one add only; a second rename over the file fails on Windows (atomicWrite), fine on the phone
        val f = Files.createTempDirectory("digest").toFile().resolve("digest.json")
        Digest.file = f
        try {
            Digest.add("WhatsApp", "wa", "Lei Ann", "hi", 5)
            val a = JSONArray(f.readText())
            assertEquals(1, a.length())
            assertEquals("hi", a.getJSONObject(0).getString("line"))
            assertEquals(5, a.getJSONObject(0).getLong("time"))
        } finally { Digest.file = null }
    }
}
