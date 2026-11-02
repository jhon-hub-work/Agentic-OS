package com.jhon.agenticos.core

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Process
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.system.exitProcess

/** Android has no "go back to One UI Home" when a home app keeps crashing; AOSP's AppErrors marks a process bad after
 *  2 crashes inside 2 minutes and restricts the app. So the UI process counts its own crashes with the same rule, and
 *  2 inside 120 s opens safe mode. File lines are "time pid"; history is kept past the window so a loop can be read. */
class Crash(private val file: File, private val limit: Int = 2, private val windowMs: Long = 120_000) {
    private val flag get() = File(file.path + ".safe")

    /** Appends and syncs before Android's own handler kills the process. A pid already counted near that time is
     *  skipped, so a chained handler or the exit-reason scan never counts one crash twice. */
    @Synchronized fun record(now: Long = System.currentTimeMillis(), pid: Int = Process.myPid()) {
        if (entries().any { it.second == pid && abs(now - it.first) < 600_000 }) return
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { it.write("$now $pid\n".toByteArray()); it.fd.sync() }
    }

    /** since: crashes at or before it don't count (this build's install time). */
    fun looping(now: Long = System.currentTimeMillis(), since: Long = 0) =
        entries().count { it.first > since && now - it.first in 0 until windowMs } >= limit

    /** Once looping, stay in safe mode until he taps Try again or a new build is installed: the flag holds the build
     *  it was set under, and a different build clears it (and the old crash times) at the next check. */
    fun safe(now: Long = System.currentTimeMillis(), build: String = Crash.build): Boolean {
        if (build.isNotEmpty() && flag.exists() && flag.readText() != build) clear()
        // the old build's crashes never count, flag or not ("versionCode@lastUpdateTime")
        if (!flag.exists() && looping(now, build.substringAfter('@', "").toLongOrNull() ?: 0)) atomicWrite(flag, build)
        return flag.exists()
    }

    fun clear() { file.delete(); flag.delete() }

    /** Called off the crash path (the start-up scan): keeps the newest [keep] lines. */
    @Synchronized fun prune(keep: Int = 20) {
        val all = entries()
        if (all.size > keep * 2) replace(file, all.takeLast(keep).joinToString("") { "${it.first} ${it.second}\n" })
    }

    // "time pid", or just "time" from builds before 2 Nov; a half-written last line is skipped
    private fun entries(): List<Pair<Long, Int>> = if (!file.exists()) emptyList() else file.readLines().mapNotNull { l ->
        val p = l.trim().split(' ')
        p[0].toLongOrNull()?.let { it to (p.getOrNull(1)?.toIntOrNull() ?: -1) }
    }

    companion object {
        val ui by lazy { Crash(File(Link.dir, "ui_crashes")) }

        /** "versionCode@lastUpdateTime": changes with every install, even while versionCode stays 1. */
        @Volatile var build = ""; private set
        @Volatile private var installedAt = 0L

        /** UI process only, from App.onCreate. Reads the build, wraps the crash handler, and scans the exit reasons
         *  Android kept (once per start, off the main thread). */
        fun install(c: Context) {
            try {
                val pi = c.packageManager.getPackageInfo(c.packageName, 0)
                build = "${pi.longVersionCode}@${pi.lastUpdateTime}"; installedAt = pi.lastUpdateTime
            } catch (_: Exception) {}
            wrap()
            Thread({ scanExits(c) }, "aos-exits").start()  // one-shot at process start, ends itself
        }

        /** Also called as each screen is created: a handler set after ours (a vendor's or a library's) would otherwise
         *  skip us, which is how three crashes on 1 Nov could leave one line. Wrapping again is safe (pid dedupe). */
        fun wrap() {
            val before = Thread.getDefaultUncaughtExceptionHandler()
            if (before !is Recorder) Thread.setDefaultUncaughtExceptionHandler(Recorder(before))
        }

        private class Recorder(val before: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
            override fun uncaughtException(t: Thread, e: Throwable) {
                try { ui.record() } catch (_: Throwable) {}  // written and synced before anyone else sees the crash
                if (before != null) before.uncaughtException(t, e) else { Process.killProcess(Process.myPid()); exitProcess(10) }
            }
        }

        /** What counts toward safe mode when the Java handler never ran: native crashes and ANRs (and Java crashes
         *  the handler missed; the pid dedupe drops the ones it caught). */
        fun counts(reason: Int) = reason == ApplicationExitInfo.REASON_CRASH ||
            reason == ApplicationExitInfo.REASON_CRASH_NATIVE || reason == ApplicationExitInfo.REASON_ANR

        private fun scanExits(c: Context) {
            try {
                val am = c.getSystemService(ActivityManager::class.java) ?: return
                val seenFile = File(Link.dir, "exits.seen")
                val seen = seenFile.takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
                val fresh = am.getHistoricalProcessExitReasons(null, 0, 0).filter { it.timestamp > seen }.sortedBy { it.timestamp }
                if (fresh.isEmpty()) return
                appendLog(File(Link.dir, "exits.log"), fresh.map { exitLine(it.timestamp, it.processName, it.reason, it.pid, it.description) })
                // exits from before this install belong to the old build, which a new install forgives
                for (x in fresh) if (x.processName == c.packageName && counts(x.reason) && x.timestamp > installedAt) ui.record(x.timestamp, x.pid)
                ui.prune()
                replace(seenFile, fresh.last().timestamp.toString())
            } catch (_: Throwable) {}  // a diagnostics scan must never cost a crash
        }

        fun appendLog(f: File, lines: List<String>, max: Int = 50) {
            val old = if (f.exists()) f.readLines().filter { it.isNotBlank() } else emptyList()
            replace(f, (old + lines).takeLast(max).joinToString("\n", postfix = "\n"))
        }

        /** atomicWrite for a file that already exists: renameTo can't replace one on every JVM, an atomic move can. */
        private fun replace(f: File, text: String) {
            val tmp = File(f.parentFile, f.name + ".tmp" + System.nanoTime())
            tmp.writeText(text)
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        /** An exits.log line worth showing as a problem: the app crashed, froze or was stopped for misbehaving.
         *  Updates, swipes away and memory trims are routine. */
        fun isProblem(line: String) = PROBLEMS.any { " $it pid " in line }
        private val PROBLEMS = listOf("crash", "native crash", "ANR", "init failure", "excessive resource use")

        fun exitLine(time: Long, process: String, reason: Int, pid: Int, desc: String?, zone: ZoneId = ZoneId.systemDefault()): String {
            val at = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(zone).format(Instant.ofEpochMilli(time))
            val why = desc?.replace(Regex("\\s+"), " ")?.trim()?.take(160)?.takeIf { it.isNotEmpty() }
            return "$at $process ${reasonName(reason)} pid $pid" + (why?.let { ": $it" } ?: "")
        }

        private fun reasonName(r: Int) = when (r) {
            ApplicationExitInfo.REASON_EXIT_SELF -> "exit"
            ApplicationExitInfo.REASON_SIGNALED -> "signaled"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "low memory"
            ApplicationExitInfo.REASON_CRASH -> "crash"
            ApplicationExitInfo.REASON_CRASH_NATIVE -> "native crash"
            ApplicationExitInfo.REASON_ANR -> "ANR"
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "init failure"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "permission change"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "excessive resource use"
            ApplicationExitInfo.REASON_USER_REQUESTED -> "user requested"
            ApplicationExitInfo.REASON_USER_STOPPED -> "user stopped"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "dependency died"
            ApplicationExitInfo.REASON_FREEZER -> "freezer"
            ApplicationExitInfo.REASON_PACKAGE_STATE_CHANGE -> "package state change"
            ApplicationExitInfo.REASON_PACKAGE_UPDATED -> "package updated"
            else -> "reason $r"
        }
    }
}
