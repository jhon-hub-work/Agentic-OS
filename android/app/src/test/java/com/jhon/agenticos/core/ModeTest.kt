package com.jhon.agenticos.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class ModeTest {
    private val manila = ZoneId.of("Asia/Manila")
    private val core = Shift(LocalTime.of(1, 0), LocalTime.of(4, 0), DayOfWeek.values().toSet(), manila)  // Jhon's 1 to 4 AM
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 11, 1, h, m, 0, 0, manila).toInstant().toEpochMilli()

    @Before fun tmp() {
        val d = Files.createTempDirectory("aos").toFile()
        Modes.file = File(d, "mode.json"); Modes.carFile = File(d, "car.json"); Snooze.file = File(d, "snooze.json")
    }
    // atomicWrite's rename can't replace a file on Windows (it can on Android), so a test writes through it once
    private fun seed(s: ModeState) = Modes.file!!.writeText(s.json())

    @Test fun noFileMeansOff() {
        assertEquals(Mode.OFF, Modes.current(at(12)))
        assertEquals(ModeState(), Modes.state())
    }

    @Test fun drivingEndsByItselfAfterThreeHours() {
        Modes.set(Mode.DRIVING, at(12))
        assertEquals(Mode.DRIVING, Modes.current(at(14, 59)))
        assertEquals(Mode.OFF, Modes.current(at(15)))
    }

    @Test fun nightPickWithoutCoreHoursHoldsUntilChanged() {
        Modes.set(Mode.NIGHT, at(12))
        assertEquals(Long.MAX_VALUE, Modes.state().until)
        assertEquals(Mode.NIGHT, Modes.current(at(12) + 30L * 24 * 3600_000))
        seed(ModeState(Mode.OFF, Long.MAX_VALUE))
        assertEquals(Mode.OFF, Modes.current(at(13)))
    }

    @Test fun followsCoreHours() {
        val s = ModeState(auto = true)
        assertEquals(Mode.OFF, Modes.resolve(s, at(0, 59), core))
        assertEquals(Mode.NIGHT, Modes.resolve(s, at(1), core))
        assertEquals(Mode.NIGHT, Modes.resolve(s, at(3, 59), core))
        assertEquals(Mode.OFF, Modes.resolve(s, at(4), core))
        assertEquals(Mode.OFF, Modes.resolve(ModeState(auto = false), at(2), core))  // switch off: hours ignored
    }

    @Test fun offDuringCoreHoursHoldsOnlyUntilTheyEnd() {
        // he turns Night shift off at 2 AM: Off until 4 AM, then tonight's 1 AM start brings it back
        val until = Modes.untilFor(Mode.OFF, at(2), core)
        assertEquals(at(4), until)
        val s = ModeState(Mode.OFF, until, auto = true)
        assertEquals(Mode.OFF, Modes.resolve(s, at(2), core))
        assertEquals(Mode.OFF, Modes.resolve(s, at(12), core))
        assertEquals(Mode.NIGHT, Modes.resolve(s, at(1) + 24 * 3600_000L, core))
    }

    @Test fun drivingBeatsCoreHoursThenHandsBack() {
        val s = ModeState(Mode.DRIVING, Modes.untilFor(Mode.DRIVING, at(0), core), auto = true)
        assertEquals(Mode.DRIVING, Modes.resolve(s, at(2), core))
        assertEquals(Mode.NIGHT, Modes.resolve(s, at(3), core))  // 3 h are up, still inside core hours
    }

    @Test fun followRetimesALivePickAndDropsAnExpiredOne() {
        // Driving ended at 15:00: nothing to keep
        assertEquals(ModeState(null, 0L, true), Modes.followed(ModeState(Mode.DRIVING, at(15)), true, at(16), core))
        // a Night pick made with no core hours (forever) now holds only until they next start
        assertEquals(ModeState(Mode.NIGHT, at(1) + 24 * 3600_000L, true), Modes.followed(ModeState(Mode.NIGHT, Long.MAX_VALUE), true, at(12), core))
        // switch off again: the pick holds until changed
        assertEquals(ModeState(Mode.NIGHT, Long.MAX_VALUE, false), Modes.followed(ModeState(Mode.NIGHT, at(16), true), false, at(12), null))
        Modes.follow(true, at(16))  // and the real write lands
        assertEquals(ModeState(null, 0L, true), Modes.state())
    }

    @Test fun stateSurvivesTheFileAndATornOne() {
        val s = ModeState(Mode.DRIVING, 123L, true)
        assertEquals(s, ModeState.parse(s.json()))
        assertEquals(ModeState(), ModeState.parse("{\"manual\":"))
        assertEquals(ModeState(null, 0L, true), ModeState.parse("{\"manual\":null,\"until\":0,\"auto\":true}"))
    }

    @Test fun dockKeys() {
        assertEquals("dock2", Modes.dockKey(2, Mode.OFF))
        assertEquals("dock_night_0", Modes.dockKey(0, Mode.NIGHT))
        assertEquals("dock_driving_3", Modes.dockKey(3, Mode.DRIVING))
        assertEquals("dock1", Modes.dockKey(1))  // no file: Off
    }

    @Test fun cycleForTheTile() {
        assertEquals(Mode.NIGHT, Modes.next(Mode.OFF))
        assertEquals(Mode.DRIVING, Modes.next(Mode.NIGHT))
        assertEquals(Mode.OFF, Modes.next(Mode.DRIVING))
    }

    @Test fun chatsByMode() {
        Modes.set(Mode.DRIVING, at(12))
        assertTrue(Modes.skipChat(group = true, now = at(13)))
        assertEquals(false, Modes.skipChat(group = false, now = at(13)))  // personal chats still spoken
        assertEquals(false, Modes.quietChats(at(13)))
        seed(ModeState(Mode.NIGHT, Long.MAX_VALUE))
        assertTrue(Modes.quietChats(at(14)))
        assertEquals(false, Modes.skipChat(group = true, now = at(14)))  // still sent: Jharvis keeps the record
    }

    @Test fun drivingKeepsRepliesToOneShortSentence() {
        assertEquals("Opening Waze now, sir.", Modes.shorten("Opening Waze now, sir. Traffic looks light on EDSA."))
        assertEquals("Sure. Searching for SM North in Waze.", Modes.shorten("Sure. Searching for SM North in Waze. It is 20 minutes away."))
        val long = Modes.shorten("word ".repeat(60).trim())
        assertTrue(long.length <= 161 && long.endsWith("word."))
        Modes.set(Mode.OFF, at(12))
        assertEquals("One. Two.", Modes.brief("One. Two.", at(12)))
    }

    @Test fun carClearsOnlyWhatTheCarSet() {
        val on = Modes.carOn(ModeState(auto = true, dnd = true), at(12))
        assertEquals(ModeState(Mode.DRIVING, at(15), true, Modes.CAR, true), on)
        assertEquals(ModeState(null, 0L, true, null, true), Modes.carOff(on))  // the car turned it on: the car turns it off
        val his = ModeState(Mode.DRIVING, at(15), by = null)
        assertEquals(his, Modes.carOff(his))  // he picked Driving himself: it stays
        val night = ModeState(Mode.NIGHT, Long.MAX_VALUE)
        assertEquals(night, Modes.carOff(night))
        // he picks a mode while the car's Driving is on: it's his now, so the car leaving doesn't undo it
        val picked = Modes.picked(on, Mode.NIGHT, at(13), null)
        assertEquals(ModeState(Mode.NIGHT, Long.MAX_VALUE, true, null, true), picked)
        assertEquals(picked, Modes.carOff(picked))
    }

    @Test fun carWritesFollowTheSavedCar() {
        assertEquals(false, Modes.isCar("AA:BB:CC:DD:EE:FF"))  // no car picked
        Modes.saveCar(Car("AA:BB:CC:DD:EE:FF", "Vios"))
        assertEquals(Car("AA:BB:CC:DD:EE:FF", "Vios"), Modes.savedCar())
        assertTrue(Modes.isCar("aa:bb:cc:dd:ee:ff"))
        assertEquals(false, Modes.isCar("11:22:33:44:55:66"))
        assertEquals(false, Modes.isCar(null))
        Modes.car(true, at(12))
        assertEquals(Mode.DRIVING, Modes.current(at(13)))
        assertEquals(Modes.CAR, Modes.state().by)
        Modes.saveCar(null)
        assertEquals(null, Modes.savedCar())
    }

    @Test fun silenceAndCarSurviveEveryWrite() {
        seed(ModeState(Mode.DRIVING, at(15), false, Modes.CAR, dnd = true))
        assertEquals(ModeState(Mode.DRIVING, at(15), false, Modes.CAR, true), ModeState.parse(Modes.file!!.readText()))
        // following core hours keeps the car's mark on a live pick and the silence switch
        assertEquals(ModeState(Mode.DRIVING, at(15), true, Modes.CAR, true), Modes.followed(Modes.state(), true, at(12), core))
        assertEquals(ModeState(null, 0L, true, null, true), Modes.followed(Modes.state(), true, at(16), core))  // expired: mark goes
        assertEquals(false, ModeState.parse("{\"manual\":null,\"until\":0,\"auto\":true}").dnd)  // older files: off
        assertEquals(null, ModeState.parse("{\"by\":null}").by)
    }

    @Test fun snoozeKeepsOnlyLiveMutes() {
        val m = mapOf("com.whatsapp" to at(13), "com.old" to at(11))
        assertTrue(Snooze.muted(m, "com.whatsapp", at(12)))
        assertEquals(false, Snooze.muted(m, "com.whatsapp", at(13)))  // the hour is up
        assertEquals(false, Snooze.muted(m, "com.other", at(12)))
        assertEquals(mapOf("com.whatsapp" to at(13)), Snooze.live(m, at(12)))
        assertEquals(m, Snooze.parse(Snooze.json(m)))
        assertEquals(emptyMap<String, Long>(), Snooze.parse("{torn"))
        assertEquals(emptyMap<String, Long>(), Snooze.load(at(12)))  // no file
        Snooze.save(m, at(12))
        assertEquals(mapOf("com.whatsapp" to at(13)), Snooze.load(at(12)))
    }

    @Test fun muteForTodayEndsAtLocalMidnight() {
        assertEquals(at(0) + 24 * 3600_000L, Snooze.endOfDay(at(12), manila))
        assertEquals(at(0) + 24 * 3600_000L, Snooze.endOfDay(at(23, 59), manila))
        assertEquals(at(0) + 24 * 3600_000L, Snooze.endOfDay(at(0), manila))  // just after midnight: the whole day
    }
}
