package com.jhon.agenticos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveJobsTest {
    @Test fun boardEditingIsLiveWithStepsAndEndedJobsSayHow() {
        val json = """{"jobs":[
            {"id":"a1","name":"Melanoma back","state":"editing","reason":"","stage":{"label":"Rendering the video","step":5,"steps":8,"quiet_s":3}},
            {"id":"b2","name":"Mole check","state":"waiting","reason":"","stage":{"label":"Waiting its turn","step":0,"steps":8,"quiet_s":null}},
            {"id":"c3","name":"Old one","state":"failed","reason":"render crashed","stage":{}},
            {"id":"d4","name":"Clip","state":"broll","reason":"no face","stage":{}},
            {"id":"e5","name":"Gone","state":"cancelled","reason":"","stage":{}}]}"""
        val (edit, wait, failed, broll, gone) = LiveJobs.board(json)
        assertEquals(LiveJobs.State.RUNNING, edit.state)
        assertEquals("dye:a1", edit.id)
        assertEquals("Rendering the video, step 5 of 8", edit.label)
        assertEquals("5/8", LiveJobs.chip(edit))
        assertEquals("Waiting its turn", wait.label)
        assertNull(LiveJobs.chip(wait))  // no real count yet: no chip
        assertEquals(LiveJobs.State.FAILED, failed.state)
        assertEquals("Edit failed: render crashed", failed.label)
        assertEquals(0, failed.steps)
        assertEquals("Not edited: no face", broll.label)
        assertEquals(LiveJobs.State.GONE, gone.state)
    }

    @Test fun sessionsShowRoundAndEnd() {
        val json = """{"sessions":[
            {"id":"26c3514a","title":"Daily meeting","status":"running","round":1,"rounds":2,"working":["claude","codex"]},
            {"id":"ef190349","title":"","meeting":true,"status":"awaiting_user","round":2,"rounds":2,"working":[]},
            {"id":"11111111","title":"Plan","status":"idle","round":2,"rounds":2}]}"""
        val (run, ask, idle) = LiveJobs.sessions(json)
        assertEquals("Round 1 of 2, Claude and Codex working", run.label)
        assertEquals("1/2", LiveJobs.chip(run))
        assertEquals(Link.ORIGIN + "/#/sessions/26c3514a", run.link)
        assertEquals(LiveJobs.State.WAITING_YOU, ask.state)
        assertEquals("Meeting", ask.title)
        assertEquals(LiveJobs.State.DONE, idle.state)
        assertEquals("Finished", idle.label)
    }

    @Test fun pollsSlowerWhenIdleOrPcOff() {
        assertEquals(20_000L, LiveJobs.interval(anyLive = true, pcAnswered = true))
        assertEquals(60_000L, LiveJobs.interval(anyLive = false, pcAnswered = true))
        assertEquals(120_000L, LiveJobs.interval(anyLive = true, pcAnswered = false))
    }
}
