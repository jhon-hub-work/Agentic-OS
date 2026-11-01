package com.jhon.agenticos.ui

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ActivityLogTest {
    private val manila = ZoneId.of("Asia/Manila")
    // 2 Nov 2026 05:00 Manila = 1 Nov 21:00 UTC; Manila's day started at 1 Nov 16:00 UTC
    private val now = ActivityLog.ms("2026-11-01T21:00:00+00:00")
    private fun run(raw: JSONObject) = ActivityLog.rows(raw, manila, now, { "t$it" }, { "App($it)" })
    private fun audit(vararg e: String) = JSONArray(e.joinToString(",", "[", "]"))

    @Test fun foldsSchedulerNoiseAndKeepsOnlyTodayInManila() {
        val raw = JSONObject().put("audit", audit(
            """{"action":"job_run","job":"Upwork invites","output_preview":"{\"new\": 0}","timestamp":"2026-11-01T15:30:00+00:00","id":"y"}""",  // yesterday in Manila
            """{"action":"job_run","job":"Upwork invites","output_preview":"{\"new\": 0}","timestamp":"2026-11-01T16:30:00.123456+00:00","id":"a"}""",
            """{"action":"job_run","job":"Upwork invites","output_preview":"{\"new\": 0}","timestamp":"2026-11-01T17:00:00+00:00","id":"b"}""",
            """{"action":"skill_run","skill":"heartbeat","output_preview":"Heartbeat is **degraded**","timestamp":"2026-11-01T18:01:00+00:00","id":"c"}""",
            """{"action":"job_run","job":"Heartbeat Check","agent":null,"output_preview":"Heartbeat is **degraded**","timestamp":"2026-11-01T18:01:01+00:00","id":"d"}""",
            """{"action":"jharvis_approval","msg_preview":"approved: run_shell","timestamp":"2026-11-01T18:10:00+00:00","id":"e"}"""))
        val rows = run(raw)
        assertEquals(listOf("Heartbeat Check ran", "Upwork invites ran 2 times"), rows.map { it.text })
        assertEquals("Heartbeat is degraded", rows[0].receipt)  // markdown gone; the skill's duplicate line folded away
        assertEquals("First at t${ActivityLog.ms("2026-11-01T16:30:00.123456+00:00")}", rows[1].receipt)  // raw JSON preview dropped
    }

    @Test fun undoOnlyWhereAnEndpointReverses() {
        val raw = JSONObject()
            .put("audit", audit("""{"action":"job_created","job":"Nightly","timestamp":"2026-11-01T19:00:00+00:00","id":"j"}""",
                """{"action":"kanban_task_created","title":"Ship it","timestamp":"2026-11-01T19:05:00+00:00","id":"k"}"""))
            .put("jobs", JSONArray("""[{"id":"ab12cd34","name":"Nightly","enabled":true}]"""))
            .put("tidy", JSONArray("""[{"id":"t1","rel":"Work/tmp","pick":"Move it","status":"done","decided_ts":${now / 1000 - 60},"moves":[["a","b"]]},
                {"id":"t2","rel":"Old","status":"done","decided_ts":${now / 1000 - 90000},"moves":[["a","b"]]}]"""))
            .put("mutes", JSONArray("""["com.whatsapp|Family"]"""))
        val rows = run(raw)
        val sched = rows.first { it.text == "Created the schedule Nightly" }.undo!!
        assertEquals("PATCH", sched.method)
        assertEquals("/api/scheduler/jobs/ab12cd34", sched.path)
        assertEquals(false, JSONObject(sched.body).getBoolean("enabled"))
        assertNull(rows.first { it.text == "Added a task" }.undo)  // no DELETE route for kanban: no button
        assertEquals("/api/tidy/t1/undo", rows.first { it.key == "tidy:t1" }.undo!!.path)
        assertTrue(rows.none { it.key == "tidy:t2" })  // yesterday
        val mute = rows.last()
        assertTrue(mute.muted)
        assertEquals("Family", mute.text)
        assertEquals("App(com.whatsapp)", mute.receipt)
        assertEquals("""{"conv":"com.whatsapp|Family","muted":false}""", mute.undo!!.body)
    }

    @Test fun approvalsAndPhoneActionsReadAsSentences() {
        val raw = JSONObject().put("audit", JSONArray())
            .put("approved", JSONArray("""[{"id":"x","tool":"run_shell","args":{"cmd":"dir"},"status":"approved","asked":"2026-11-01T19:00:00+00:00","decided":"2026-11-01T19:00:05+00:00"}]"""))
            .put("expired", JSONArray("""[{"id":"z","tool":"send_text","args":{},"status":"expired","asked":"2026-11-01T19:30:00+00:00"}]"""))
            .put("messages", JSONArray("""[{"id":"m","role":"jharvis","text":"Sent, sir.","timestamp":"2026-11-01T20:00:00+00:00","open":["sms:my+love?body=hi"]},
                {"id":"n","role":"owner","text":"hi","timestamp":"2026-11-01T20:00:00+00:00","open":["app:x"]}]"""))
        val rows = run(raw)
        assertEquals(listOf("Texted My Love", "Send text request expired unanswered", "You approved run shell"), rows.map { it.text })
        assertEquals("""{"cmd":"dir"}""", rows[2].receipt)
        assertEquals("", rows[1].receipt)
    }

    @Test fun describesOpensAndSurvivesTheCache() {
        assertEquals("Opened Google Maps", ActivityLog.describe("app:google maps"))
        assertEquals("Opened youtube.com", ActivityLog.describe("https://www.youtube.com/results?q=1"))
        val rows = run(JSONObject().put("mutes", JSONArray("""["a|B"]""")))
        val back = ActivityLog.read(ActivityLog.write(rows))
        assertEquals("B", back[0].text)
        assertNotNull(back[0].undo)
        assertTrue(back[0].muted)
    }
}
