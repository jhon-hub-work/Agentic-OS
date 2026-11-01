package com.jhon.agenticos.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Jhon's fixed work hours, held in their own zone so daylight saving lands right. Lives in core because Modes in
 *  :core reads it too. Link.dir/shift.json holds {"start":"HH:mm","end":"HH:mm","days":[1..7],"zone":"<ZoneId>"}.
 *  No file means the seeded default (1 to 4 AM Manila, every day, Jhon 1 Nov); "{}" means he cleared it. */
data class Shift(val start: LocalTime, val end: LocalTime, val days: Set<DayOfWeek>, val zone: ZoneId) {

    /** end <= start runs past midnight; days = the day a run starts on. */
    fun isOn(now: Instant): Boolean = runs(now).any { (s, e) -> now >= s && now < e }

    /** The next start or end after now (a week out when no day is ticked). */
    fun nextChange(now: Instant): Instant =
        runs(now).flatMap { listOf(it.first, it.second) }.filter { it > now }.minOrNull() ?: now.plus(Duration.ofDays(7))

    /** The home screen line: "Core hours in 2 h 10 m", "Core hours, 1 h 20 m left", or null once done for the day
     *  (the next start is more than 12 h off). */
    fun line(now: Instant): String? {
        val left = Duration.between(now, nextChange(now))
        return when {
            isOn(now) -> "Core hours, ${span(left)} left"
            left <= LEAD -> "Core hours in ${span(left)}"
            else -> null
        }
    }

    fun json(): String = JSONObject().put("start", start.toString()).put("end", end.toString())
        .put("days", JSONArray(days.map { it.value }.sorted())).put("zone", zone.id).toString()

    // every run starting from yesterday to a week ahead, worked out in the shift's zone (java.time resolves DST gaps
    // forward and overlaps to the earlier offset)
    private fun runs(now: Instant): List<Pair<Instant, Instant>> {
        val today = now.atZone(zone).toLocalDate()
        return (-1L..7L).map { today.plusDays(it) }.filter { it.dayOfWeek in days }.map { d ->
            ZonedDateTime.of(d, start, zone).toInstant() to
                ZonedDateTime.of(if (end <= start) d.plusDays(1) else d, end, zone).toInstant()
        }
    }

    companion object {
        val DEFAULT = Shift(LocalTime.of(1, 0), LocalTime.of(4, 0), DayOfWeek.values().toSet(), ZoneId.of("Asia/Manila"))
        private val LEAD = Duration.ofHours(12)

        private fun file() = File(Link.dir, "shift.json")

        /** Disk read: call off the main thread. A broken file falls back to the default rather than losing his hours. */
        fun load(f: File = file()): Shift? = if (!f.exists()) DEFAULT else try { parse(f.readText()) } catch (_: Exception) { DEFAULT }

        /** null clears it (nothing shown, Modes never follows it). Throws IOException if the disk refuses. */
        fun save(s: Shift?, f: File = file()) = atomicWrite(f, s?.json() ?: "{}")

        fun parse(text: String): Shift? {
            val o = JSONObject(text)
            if (!o.has("start")) return null
            val a = o.getJSONArray("days")
            return Shift(LocalTime.parse(o.getString("start")), LocalTime.parse(o.getString("end")),
                (0 until a.length()).map { DayOfWeek.of(a.getInt(it)) }.toSet(), ZoneId.of(o.getString("zone")))
        }

        /** "2 h 10 m", "2 h", "45 m"; rounds up so it never says 0 m while time is left. */
        fun span(d: Duration): String {
            val m = (d.seconds + 59) / 60
            val h = m / 60; val r = m % 60
            return when { h == 0L -> "$r m"; r == 0L -> "$h h"; else -> "$h h $r m" }
        }
    }
}
