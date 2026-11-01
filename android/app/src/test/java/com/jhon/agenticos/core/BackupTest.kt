package com.jhon.agenticos.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private val prefs = mapOf<String, Any>(
        "dock_0" to "com.whatsapp", "dock_night_1" to "com.spotify.music",
        "hidden_apps" to setOf("com.a", "com.b"), "locked_apps" to setOf("com.bank"),
        "pins" to """[{"pkg":"com.x","id":"s1","label":"New chat"}]""",
        "ap_theme" to "graphite", "ap_accent" to 0xFF22A39A.toInt(), "ap_blur" to 16, "themed_icons" to true,
        "glance_lat" to 14.5995f, "glance_city" to "Manila", "wake" to false, "big" to 1_730_000_000_000L,
        // this install's own: must not travel
        "widgets" to "12,15", "ap_v" to 41, "setup_done" to true, "setup_vpn" to true, "chat_access_asked" to true,
        "restore_widgets" to "[]")
    private val launches = mapOf<String, Any>("s:com.whatsapp" to 3.25f, "t:com.whatsapp" to 1_730_000_000_123L)
    private val files = mapOf("mode.json" to """{"manual":"NIGHT","until":5}""", "shift.json" to """{"start":60}""",
        "pair_cookie" to "secret")

    private fun roundTrip() = Backup.decode(
        Backup.encode(prefs, launches, listOf("com.w/.Clock", "com.v/.Big"), files, "1.1.0", 99L).toString())

    @Test fun everyPortableValueComesBackWithItsType() {
        val s = roundTrip()
        assertEquals(prefs.filterKeys(Backup::portable), s.prefs)
        assertTrue(s.prefs["ap_accent"] is Int); assertTrue(s.prefs["big"] is Long); assertTrue(s.prefs["glance_lat"] is Float)
        assertEquals(launches, s.launches)
        assertEquals(listOf("com.w/.Clock", "com.v/.Big"), s.widgets)
        assertEquals(99L, s.made); assertEquals("1.1.0", s.app)
    }

    @Test fun installBoundKeysAndTheCookieNeverTravel() {
        val s = roundTrip()
        for (k in listOf("widgets", "ap_v", "setup_done", "setup_vpn", "chat_access_asked", "restore_widgets"))
            assertFalse(k, s.prefs.containsKey(k))
        assertEquals(setOf("mode.json", "shift.json"), s.files.keys)
        assertEquals("NIGHT", JSONObject(s.files["mode.json"]!!).getString("manual"))
        assertFalse(Backup.encode(prefs, launches, emptyList(), files, "x", 0).toString().contains("secret"))
    }

    @Test fun refusesWhatIsNotOurs() {
        for (bad in listOf("not json", "{}", """{"kind":"agentic-os-setup"}""", """{"kind":"other","version":1}"""))
            try { Backup.decode(bad); fail(bad) } catch (_: Backup.Bad) {}
        try { Backup.decode("""{"kind":"agentic-os-setup","version":${Backup.VERSION + 1}}"""); fail() }
        catch (e: Backup.Bad) { assertTrue(e.message!!.contains("newer")) }
    }

    @Test fun dropsWrongTypesUnknownFilesAndJunkWidgets() {
        val text = """{"kind":"agentic-os-setup","version":1,
            "prefs":{"b":{"wake":"yes","ok":true},"i":{"n":"7"},"ss":{"hidden_apps":["a",3,"b"]},"s":{"ap_v":"9"}},
            "widgets":["com.a/.W", 5, "nonsense"],
            "files":{"mode.json":{"manual":"OFF"},"../pair_cookie":{"x":1},"shift.json":"text"}}"""
        val s = Backup.decode(text)
        assertEquals(mapOf("ok" to true, "hidden_apps" to setOf("a", "b")), s.prefs)
        assertEquals(listOf("com.a/.W"), s.widgets)
        assertEquals(setOf("mode.json"), s.files.keys)
    }

    @Test fun readStopsAtTheSizeCap() {
        val big = ByteArray(Backup.MAX_BYTES + 10) { ' '.code.toByte() }
        try { Backup.read(big.inputStream()); fail() } catch (e: Backup.Bad) { assertTrue(e.message!!.contains("too big")) }
    }
}
