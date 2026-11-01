package com.jhon.agenticos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.io.File

class ShiftTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val nevada = ZoneId.of("America/Los_Angeles")
    private fun at(s: String) = Instant.parse(s)
    private val core = Shift.DEFAULT  // 1:00 to 4:00 Manila, every day = 17:00 to 20:00 UTC the day before

    @Test fun coreHoursOnAndOff() {
        assertFalse(core.isOn(at("2026-11-01T16:59:00Z")))
        assertTrue(core.isOn(at("2026-11-01T17:00:00Z")))
        assertTrue(core.isOn(at("2026-11-01T19:59:59Z")))
        assertFalse(core.isOn(at("2026-11-01T20:00:00Z")))
        assertEquals(at("2026-11-01T17:00:00Z"), core.nextChange(at("2026-11-01T10:00:00Z")))
        assertEquals(at("2026-11-01T20:00:00Z"), core.nextChange(at("2026-11-01T18:00:00Z")))
    }

    @Test fun lineCountsDownThenGoesQuiet() {
        assertEquals("Core hours in 2 h 10 m", core.line(at("2026-11-01T14:50:00Z")))  // 10:50 PM Manila
        assertEquals("Core hours, 1 h 20 m left", core.line(at("2026-11-01T18:40:00Z")))
        assertEquals("Core hours, 1 m left", core.line(at("2026-11-01T19:59:30Z")))  // rounds up, never 0 m
        assertNull(core.line(at("2026-11-01T21:00:00Z")))  // 5 AM Manila: done for the day
        assertEquals("Core hours in 12 h", core.line(at("2026-11-01T05:00:00Z")))
    }

    @Test fun overnightBelongsToTheDayItStarts() {
        // Fridays only, 10 PM to 6 AM Manila: on early Saturday, off early Friday
        val fri = Shift(LocalTime.of(22, 0), LocalTime.of(6, 0), setOf(DayOfWeek.FRIDAY), manila)
        assertTrue(fri.isOn(at("2026-11-06T19:00:00Z")))   // Sat 3 AM Manila
        assertFalse(fri.isOn(at("2026-11-05T19:00:00Z")))  // Fri 3 AM Manila
        assertEquals(at("2026-11-06T22:00:00Z"), fri.nextChange(at("2026-11-06T19:00:00Z")))
        assertEquals(at("2026-11-13T14:00:00Z"), fri.nextChange(at("2026-11-06T23:00:00Z")))  // next Friday 10 PM
    }

    @Test fun usClocksGoBackOnFirstNovember2026() {
        // 6 AM Nevada: 1 PM UTC under PDT on 31 Oct, 2 PM UTC under PST from 1 Nov
        val va = Shift(LocalTime.of(6, 0), LocalTime.of(18, 0), DayOfWeek.values().toSet(), nevada)
        assertEquals(at("2026-10-31T13:00:00Z"), va.nextChange(at("2026-10-31T12:00:00Z")))
        assertEquals(at("2026-11-01T14:00:00Z"), va.nextChange(at("2026-11-01T12:00:00Z")))
        // 1 to 4 AM on the change day: 1 AM happens twice, java.time takes the first, so the run is 4 h long
        val night = Shift(LocalTime.of(1, 0), LocalTime.of(4, 0), DayOfWeek.values().toSet(), nevada)
        assertEquals(at("2026-11-01T08:00:00Z"), night.nextChange(at("2026-11-01T07:00:00Z")))
        assertEquals(at("2026-11-01T12:00:00Z"), night.nextChange(at("2026-11-01T08:00:00Z")))
    }

    @Test fun startInsideTheSpringGapStillRuns() {
        // 2:30 AM doesn't exist in Nevada on 8 Mar 2026; the run starts at 3:30 PDT instead
        val gap = Shift(LocalTime.of(2, 30), LocalTime.of(5, 0), DayOfWeek.values().toSet(), nevada)
        assertEquals(at("2026-03-08T10:30:00Z"), gap.nextChange(at("2026-03-08T09:00:00Z")))
        assertTrue(gap.isOn(at("2026-03-08T11:00:00Z")))
    }

    @Test fun noDaysNeverOn() {
        val none = core.copy(days = emptySet())
        assertFalse(none.isOn(at("2026-11-01T18:00:00Z")))
        assertNull(none.line(at("2026-11-01T18:00:00Z")))
        assertEquals(Duration.ofDays(7), Duration.between(at("2026-11-01T18:00:00Z"), none.nextChange(at("2026-11-01T18:00:00Z"))))
    }

    @Test fun savesLoadsSeedsAndClears() {
        val f = File(Files.createTempDirectory("aos").toFile(), "shift.json")
        assertEquals(Shift.DEFAULT, Shift.load(f))  // nothing saved yet: his core hours
        val s = Shift(LocalTime.of(21, 15), LocalTime.of(5, 0), setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY), nevada)
        Shift.save(s, f)
        assertEquals(s, Shift.load(f))
        f.delete()  // atomicWrite's rename replaces on Android, not on the Windows JVM running this test
        Shift.save(null, f)
        assertNull(Shift.load(f))
        f.writeText("not json")
        assertEquals(Shift.DEFAULT, Shift.load(f))
    }

    @Test fun spans() {
        assertEquals("45 m", Shift.span(Duration.ofMinutes(45)))
        assertEquals("2 h", Shift.span(Duration.ofHours(2)))
        assertEquals("1 h 5 m", Shift.span(Duration.ofMinutes(64).plusSeconds(10)))
    }
}
