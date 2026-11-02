package com.jhon.agenticos.core

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.ZoneOffset

class CrashTest {
    private fun file() = File(Files.createTempDirectory("aos").toFile(), "crashes")

    @Test fun safeModeAfterTwoCrashesInTwoMinutesAndStaysUntilCleared() {
        val c = Crash(file())
        c.record(1_000, pid = 1)
        assertFalse(c.safe(2_000, build = "1@a"))
        c.record(100_000, pid = 2)
        assertTrue(c.safe(100_001, build = "1@a"))
        assertTrue(c.safe(900_000, build = "1@a"))  // a quiet spell isn't enough: he taps Try again
        c.clear()
        assertFalse(c.safe(900_000, build = "1@a"))
        c.record(0, pid = 3); c.record(130_000, pid = 4)  // two crashes, but not inside 120 s
        assertFalse(c.safe(130_001, build = "1@a"))
    }

    @Test fun samePidCountsOnce() {  // a chained handler or the exit scan seeing a crash the handler already wrote
        val f = file()
        val c = Crash(f)
        c.record(1_000, pid = 7); c.record(3_000, pid = 7)
        assertFalse(c.looping(4_000))
        assertEquals(1, f.readLines().size)
    }

    @Test fun historyIsKeptPastTheWindow() {  // 1 Nov: three crashes left one line because each write dropped the rest
        val f = file()
        val c = Crash(f)
        c.record(0, pid = 1); c.record(200_000, pid = 2); c.record(400_000, pid = 3)
        assertEquals(3, f.readLines().size)
    }

    @Test fun readsLinesFromOlderBuildsAndSkipsHalfWrittenOnes() {
        val f = file()
        f.writeText("1000\n50000 9\n6000")  // old "time" lines, a new one, and a torn tail
        assertTrue(Crash(f).looping(51_000))
        f.writeText("1000\n50000 9\nab")
        assertTrue(Crash(f).looping(51_000))
    }

    @Test fun newBuildClearsTheSafeFlag() {
        val c = Crash(file())
        c.record(1_000, pid = 1); c.record(2_000, pid = 2)
        assertTrue(c.safe(3_000, build = "1@100"))
        assertTrue(c.safe(4_000, build = ""))           // build unknown: never clears
        assertFalse(c.safe(5_000, build = "1@200"))     // reinstalled (same versionCode, new install time)
        assertFalse(c.looping(5_000))                   // and the old build's crashes are forgiven
    }

    @Test fun oldBuildCrashDoesNotCountTowardTheNewOne() {  // old build crashes, new one installed at 50 s crashes once
        val c = Crash(file())
        c.record(1_000, pid = 1)
        c.record(60_000, pid = 2)
        assertFalse(c.safe(61_000, build = "1@50000"))
        c.record(70_000, pid = 3)  // a second crash under this build does
        assertTrue(c.safe(71_000, build = "1@50000"))
    }

    @Test fun flagFromBeforeVersioningClearsOnNextBuild() {
        val f = file()
        File(f.path + ".safe").createNewFile()  // the old code's empty flag
        assertFalse(Crash(f).safe(1_000, build = "2@1"))
    }

    @Test fun pruneKeepsTheNewest() {
        val f = file()
        val c = Crash(f)
        for (i in 1..45) c.record(i * 1_000_000L, pid = i)
        c.prune(20)
        val lines = f.readLines()
        assertEquals(20, lines.size)
        assertEquals("45000000 45", lines.last())
        c.prune(20)
        assertEquals(20, f.readLines().size)  // under 2x: left alone
    }

    @Test fun nativeCrashesAndAnrsCount() {
        assertTrue(Crash.counts(ApplicationExitInfo.REASON_CRASH))
        assertTrue(Crash.counts(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue(Crash.counts(ApplicationExitInfo.REASON_ANR))
        assertFalse(Crash.counts(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertFalse(Crash.counts(ApplicationExitInfo.REASON_USER_REQUESTED))
    }

    @Test fun exitLineIsOneReadableLine() {
        val l = Crash.exitLine(0, "com.jhon.agenticos", ApplicationExitInfo.REASON_CRASH_NATIVE, 42, "signal 11\n  at foo", ZoneOffset.UTC)
        assertEquals("1970-01-01 00:00:00 com.jhon.agenticos native crash pid 42: signal 11 at foo", l)
        assertEquals("1970-01-01 00:00:00 x:core reason 99 pid 1", Crash.exitLine(0, "x:core", 99, 1, null, ZoneOffset.UTC))
    }

    // Jhon, 2 Nov: Settings showed three "package updated" lines from an install as "Recent problems"
    @Test fun onlyCrashesAndFreezesAreProblems() {
        fun line(r: Int) = Crash.exitLine(0, "com.jhon.agenticos", r, 7, "stop com.jhon.agenticos", ZoneOffset.UTC)
        for (r in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE, ApplicationExitInfo.REASON_ANR,
                ApplicationExitInfo.REASON_INITIALIZATION_FAILURE, ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE))
            assertTrue(line(r), Crash.isProblem(line(r)))
        for (r in listOf(ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_USER_REQUESTED,
                ApplicationExitInfo.REASON_EXIT_SELF, ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_SIGNALED))
            assertFalse(line(r), Crash.isProblem(line(r)))
    }

    @Test fun exitLogIsCapped() {
        val f = file()
        Crash.appendLog(f, (1..40).map { "a$it" })
        Crash.appendLog(f, (1..20).map { "b$it" })
        val lines = f.readLines()
        assertEquals(50, lines.size)
        assertEquals("a11", lines.first())
        assertEquals("b20", lines.last())
    }
}
