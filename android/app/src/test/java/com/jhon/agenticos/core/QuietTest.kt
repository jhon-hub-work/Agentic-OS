package com.jhon.agenticos.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class QuietTest {
    private lateinit var q: File

    @Before fun tmp() {
        val d = Files.createTempDirectory("aos").toFile()
        Quiet.file = File(d, "quiet.json"); q = File(d, "queue"); Quiet.queue = SendQueue(q)
    }

    private fun queued() = (q.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }.map { JSONObject(it.readText()) }

    @Test fun noOrTornFileMeansAwake() {
        assertEquals(QuietState(), Quiet.state())
        assertFalse(Quiet.on())
        Quiet.file!!.writeText("{\"on\": tr")
        assertFalse(Quiet.on())
    }

    @Test fun readsWhatThePcSaid() {
        val s = QuietState.parse("""{"on": false, "since": null, "by": "phone", "held": 3, "summary": "waiting_unlock"}""")
        assertEquals(QuietState(on = false, summary = "waiting_unlock", by = "phone"), s)
        assertEquals(QuietState(on = true), QuietState.parse("""{"on": true, "by": null}"""))
    }

    @Test fun aWrongAnswerKeepsTheMirror() {
        Quiet.set(true, "mode"); q.listFiles()!!.forEach { it.delete() }  // sent
        try { Quiet.polled("""{"error": "x"}""", unlocked = false); fail("taken as awake") } catch (_: Exception) {}
        assertTrue(Quiet.on())
    }

    @Test fun anOlderAnswerNeverOverwritesOurQueuedChange() {
        Quiet.set(true, "mode")  // picked while the poll was on its way
        assertNull(Quiet.polled("""{"on": false, "summary": "waiting_unlock"}""", unlocked = true))
        assertTrue(Quiet.on())
        assertEquals(listOf("/api/jharvis/quiet"), queued().map { it.getString("path") })  // and no unlock call
    }

    @Test fun usingThePhoneWhenQuietEndsCountsAsTheUnlock() {
        assertTrue(sendUnlock(QuietState(summary = "waiting_unlock"), unlocked = false, present = true))  // he ended it from the phone
        assertTrue(sendUnlock(QuietState(summary = "waiting_unlock"), unlocked = true, present = false))
        assertFalse(sendUnlock(QuietState(summary = "waiting_unlock"), unlocked = false, present = false))
        assertFalse(sendUnlock(QuietState(on = true, summary = "waiting_unlock"), unlocked = true, present = true))
        assertFalse(sendUnlock(QuietState(summary = "due"), unlocked = true, present = true))
    }

    @Test fun whatThePcAskedOfDndSurvivesARestart() {
        assertFalse(Quiet.askedDnd); assertNull(Quiet.seenDnd)  // nothing yet
        Quiet.askedDnd = true; Quiet.seenDnd = false
        assertTrue(Quiet.askedDnd); assertEquals(false, Quiet.seenDnd)  // read back from the files, as a new :core would
    }

    @Test fun holdQueuesItForTheSummary() {
        assertTrue(Quiet.hold("notify", "Mole check: Edit finished"))
        val item = queued().single()
        assertEquals("/api/jharvis/quiet/held", item.getString("path"))
        val body = JSONObject(item.getString("body"))
        assertEquals("notify", body.getString("kind")); assertEquals("Mole check: Edit finished", body.getString("text"))
    }

    @Test fun pickingSleepQueuesItAndShowsAtOnce() {
        Quiet.set(true, "mode")
        assertTrue(Quiet.on())  // before the PC has even heard
        val item = queued().single()
        assertEquals("/api/jharvis/quiet", item.getString("path"))
        assertEquals("PUT", item.getString("method"))
        val body = JSONObject(item.getString("body"))
        assertEquals(true, body.getBoolean("on")); assertEquals("mode", body.getString("by"))
    }

    @Test fun firstUnlockAfterQuietEndsAsksForTheSummary() {
        Quiet.polled("""{"on": false, "summary": "waiting_unlock", "held": 2}""", unlocked = true)
        assertEquals("/api/jharvis/quiet/unlocked", queued().single().getString("path"))
        assertEquals("waiting_unlock", Quiet.state().summary)  // mirrored for the other processes
    }

    @Test fun noUnlockOrNothingWaitingAsksNothing() {
        Quiet.polled("""{"on": false, "summary": "waiting_unlock"}""", unlocked = false)  // he hasn't unlocked yet
        Quiet.file!!.delete()  // atomicWrite can't replace a file on Windows (it can on Android)
        Quiet.polled("""{"on": true, "summary": "none"}""", unlocked = true)  // still quiet: a look at the time at 2 AM
        Quiet.file!!.delete()
        Quiet.polled("""{"on": false, "summary": "due"}""", unlocked = true)  // already asked
        assertTrue(queued().isEmpty())
    }
}
