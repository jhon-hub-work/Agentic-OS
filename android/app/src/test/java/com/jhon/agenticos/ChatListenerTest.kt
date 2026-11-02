package com.jhon.agenticos

import com.jhon.agenticos.core.Quiet
import com.jhon.agenticos.core.QuietState
import com.jhon.agenticos.core.SendQueue
import com.jhon.agenticos.ui.Checks
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

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

    @Test fun dndChangeWeAskedForIsNotHis() {
        assertTrue(ownDnd(on = true, asks = listOf(true to 1_000L), now = 3_000L))  // Night shift or the PC just turned it on
        assertFalse(ownDnd(on = false, asks = listOf(true to 1_000L), now = 3_000L))  // he turned it off: his
        assertFalse(ownDnd(on = true, asks = emptyList(), now = 3_000L))  // we never asked: his or the schedule's
        assertFalse(ownDnd(on = true, asks = listOf(true to 1_000L), now = 60_000L))  // long after ours: the schedule's
    }

    @Test fun twoQuickAsksBothEchoAsOurs() {
        val asks = listOf(true to 1_000L, false to 1_200L)  // Sleep, then Off a moment later: both asked before either echo
        assertTrue(ownDnd(on = true, asks = asks, now = 1_500L))  // the first echo arrives after the second ask: still ours
        assertTrue(ownDnd(on = false, asks = asks, now = 1_600L))
    }

    @Test fun nightShiftEndingLeavesHisOwnDndOnOldAndroid() {
        assertFalse(dndChange(want = false, last = true, sdk = 34, his = true))  // would end the DND he turned on himself
        assertTrue(dndChange(want = false, last = true, sdk = 35, his = true))  // 35+: ends only our own rule
        assertTrue(dndChange(want = false, last = true, sdk = 34, his = false))
    }

    @Test fun dndChangedWhileAwayIsReportedOnConnect() {
        assertFalse(missedDnd(now = true, seen = null, own = false))  // first run: nothing to compare with
        assertTrue(missedDnd(now = false, seen = true, own = false))  // the schedule ended while the listener was gone
        assertTrue(missedDnd(now = true, seen = false, own = false))  // or started
        assertFalse(missedDnd(now = true, seen = true, own = false))
        assertFalse(missedDnd(now = true, seen = false, own = true))  // our own request whose echo we missed
    }

    @Test fun ourOwnOffEndingQuietByPhoneIsStillReported() {
        val byPhone = QuietState(on = true, by = "phone")
        assertTrue(reportDnd(on = false, own = true, pc = byPhone))  // API 35+: his schedule ended under our rule, our "off" let it reach ALL
        assertFalse(reportDnd(on = true, own = true, pc = byPhone))  // an own "on" stays ours: Night shift never starts quiet
        assertFalse(reportDnd(on = false, own = true, pc = QuietState(on = true, by = "mode")))  // the chip's Sleep: not his DND
        assertFalse(reportDnd(on = false, own = true, pc = QuietState()))
        assertTrue(reportDnd(on = true, own = false, pc = QuietState()))  // his or the schedule's
        assertTrue(missedDnd(now = false, seen = true, own = true, pc = byPhone))  // the same echo missed while away
    }

    @Test fun aRestartHearsHisOffBeforeAskingAgain() {
        val d = Files.createTempDirectory("aos").toFile()
        val q = File(d, "queue")
        Quiet.file = File(d, "quiet.json"); Quiet.queue = SendQueue(q)
        Quiet.set(true, "mode"); q.listFiles()!!.forEach { it.delete() }  // Sleep from the chip, long sent
        Quiet.askedDnd = true; Quiet.seenDnd = true  // the PC's "dnd" item turned it on
        val asked = ArrayList<Boolean>()
        // :core died, he turned DND off from the shade, :core is back: the first round sees the filter off
        val want = dndRound(away = false, own = { false }, modeDnd = false, last = null, sdk = 36, ask = { asked += it }, report = Quiet::phoneDnd)
        val put = q.listFiles { f -> f.name.endsWith(".json") }!!.map { JSONObject(it.readText()) }.single()
        assertEquals("PUT", put.getString("method"))
        assertEquals(JSONObject().put("on", false).put("by", "phone").toString(), put.getString("body"))
        assertFalse(true in asked)  // DND not turned back on
        assertFalse(want); assertFalse(Quiet.askedDnd); assertEquals(false, Quiet.seenDnd)
    }

    @Test fun pollCatchesUpOnAMissedDndItem() {
        assertEquals(true, dndCatchUp(QuietState(on = true, by = "jharvis"), asked = false))  // the "dnd" item expired while offline
        assertEquals(false, dndCatchUp(QuietState(on = false, by = "mode"), asked = true))
        assertNull(dndCatchUp(QuietState(on = true, by = "mode"), asked = true))  // already done
        assertNull(dndCatchUp(QuietState(on = false, by = "phone"), asked = true))  // his own change: the phone is already there
        assertNull(dndCatchUp(QuietState(on = false, by = ""), asked = false))  // never set
    }

    @Test fun pcMessagesQueuedBeforeQuietAreKeptForTheSummary() {
        assertTrue(holdOnPhone("notify", quiet = true))
        assertTrue(holdOnPhone("say", quiet = true))
        assertFalse(holdOnPhone("reply", quiet = true))  // his own reply still goes out
        assertFalse(holdOnPhone("dnd", quiet = true))
        assertFalse(holdOnPhone("say", quiet = false))
    }

    @Test fun boundedMapDropsLeastRecentlyUsed() {
        val m = boundedMap<String>(3)
        m["a"] = "1"; m["b"] = "2"; m["c"] = "3"
        m["a"]  // used: stays
        m["d"] = "4"
        assertEquals(setOf("a", "c", "d"), m.keys)
    }
}
