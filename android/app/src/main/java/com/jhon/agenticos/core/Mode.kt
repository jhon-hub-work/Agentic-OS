package com.jhon.agenticos.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.jhon.agenticos.R
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Jhon, 1 Nov: "Driving: personal chats stay spoken, group chats muted. Night shift: chat alerts muted." */
enum class Mode(val label: String) { OFF("Off"), NIGHT("Night shift"), DRIVING("Driving") }

/** What mode.json holds. manual = his last pick, in force while now < until; auto = Night shift follows the core hours;
 *  by = "car" when the car's Bluetooth set the pick (null = he did); dnd ("mode_dnd") = silence other notifications too
 *  while a mode is on (CORE acts on it). */
data class ModeState(val manual: Mode? = null, val until: Long = 0L, val auto: Boolean = false, val by: String? = null, val dnd: Boolean = false) {
    fun json(): String = JSONObject().put("manual", manual?.name ?: JSONObject.NULL).put("until", until).put("auto", auto)
        .put("by", by ?: JSONObject.NULL).put("mode_dnd", dnd).toString()
    companion object {
        fun parse(s: String): ModeState = try {
            val o = JSONObject(s)
            ModeState(o.optString("manual").let { m -> Mode.values().firstOrNull { it.name == m } }, o.optLong("until"), o.optBoolean("auto"),
                if (o.isNull("by")) null else o.optString("by"), o.optBoolean("mode_dnd"))
        } catch (_: Exception) { ModeState() }  // a torn or hand-edited file means Off, never a crash in :core
    }
}

/** The Bluetooth device that means "in the car" (Link.dir/car.json = {"address","name"}). */
data class Car(val address: String, val name: String)

/** The phone's mode, worked out fresh on every read from Link.dir/mode.json, so :core, :island and the home screen
 *  always agree and nothing needs an alarm: a pick simply stops counting when its "until" passes. */
object Modes {
    const val DRIVING_MS = 3 * 60 * 60_000L  // a forgotten Driving can't silence group chats for days
    const val CAR = "car"
    internal var file: File? = null           // tests point this at a temp file
    private fun file() = file ?: File(Link.dir, "mode.json")

    fun state(): ModeState = file().takeIf { it.exists() }?.let { f -> try { ModeState.parse(f.readText()) } catch (_: Exception) { null } } ?: ModeState()

    fun current(now: Long = System.currentTimeMillis()): Mode = state().let { s -> resolve(s, now, if (s.auto) shift() else null) }

    /** His pick: Driving holds 3 h; Night shift or Off hold until the core hours next start or end when following them,
     *  else until he changes it. */
    fun set(m: Mode, now: Long = System.currentTimeMillis()) {
        val s = state()
        atomicWrite(file(), picked(s, m, now, if (s.auto) shift() else null).json())
    }

    // his own pick, so by goes back to null: the car leaving won't undo it
    internal fun picked(s: ModeState, m: Mode, now: Long, shift: Shift?) = s.copy(manual = m, until = untilFor(m, now, shift), by = null)

    /** "Silence other notifications too" (mode.json "mode_dnd"). */
    fun silence(on: Boolean) = atomicWrite(file(), state().copy(dnd = on).json())

    /** CarReceiver: his car connected or went. Writes only when something changes. */
    fun car(connected: Boolean, now: Long = System.currentTimeMillis()) {
        val s = state()
        val next = if (connected) carOn(s, now) else carOff(s)
        if (next != s) atomicWrite(file(), next.json())
    }

    internal fun carOn(s: ModeState, now: Long) = s.copy(manual = Mode.DRIVING, until = now + DRIVING_MS, by = CAR)

    /** Only undo what the car did: a mode he picked himself stays when the car goes. */
    internal fun carOff(s: ModeState) = if (s.by == CAR) s.copy(manual = null, until = 0L, by = null) else s

    internal var carFile: File? = null  // tests point this at a temp file
    private fun carFile() = carFile ?: File(Link.dir, "car.json")

    fun savedCar(): Car? = try {
        carFile().takeIf { it.exists() }?.readText()?.let { JSONObject(it) }?.let { o -> Car(o.getString("address"), o.optString("name")) }
    } catch (_: Exception) { null }

    /** null forgets the car. Throws IOException if the disk refuses. */
    fun saveCar(c: Car?) { if (c == null) carFile().delete() else atomicWrite(carFile(), JSONObject().put("address", c.address).put("name", c.name).toString()) }

    fun isCar(address: String?) = address != null && savedCar()?.address.equals(address, ignoreCase = true)

    /** "Night shift follows my core hours". A pick still in force keeps going, re-timed to the new rule. */
    fun follow(on: Boolean, now: Long = System.currentTimeMillis()) = atomicWrite(file(), followed(state(), on, now, if (on) shift() else null).json())

    internal fun followed(s: ModeState, on: Boolean, now: Long, shift: Shift?): ModeState {
        val pick = s.manual?.takeIf { now < s.until }
        return s.copy(manual = pick, until = pick?.let { untilFor(it, now, shift) } ?: 0L, auto = on, by = s.by.takeIf { pick != null })
    }

    /** Off -> Night shift -> Driving -> Off (the Quick Settings tile). */
    fun next(m: Mode): Mode = Mode.values()[(m.ordinal + 1) % Mode.values().size]

    /** Night shift or Sleep (Quiet): Jharvis doesn't speak or pop chats, and the island skips them. */
    fun quietChats(now: Long = System.currentTimeMillis()) = current(now) == Mode.NIGHT || Quiet.on()

    /** Driving: group chats aren't sent to the PC at all, so Jharvis never reads them out; personal ones still are. */
    fun skipChat(group: Boolean, now: Long = System.currentTimeMillis()) = group && current(now) == Mode.DRIVING

    /** Driving: what Jharvis says back is cut to one short sentence (the full reply stays on the dashboard). */
    fun brief(text: String, now: Long = System.currentTimeMillis()) = if (current(now) == Mode.DRIVING) shorten(text) else text

    /** Night shift's stand-in for speaking a chat: one silent note in the shade. Returns the outbox result. */
    fun quietNote(c: Context, text: String): String {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("chats_quiet", "Jharvis: chats in Night shift", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(c, 0, Intent().setClassName(c, "com.jhon.agenticos.MainActivity"), PendingIntent.FLAG_IMMUTABLE)
        nm.notify(text.hashCode(), Notification.Builder(c, "chats_quiet").setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Jharvis").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build())
        return "muted"
    }

    // shortcut: a missing or broken shift file means "no core hours"; Shift itself reports nothing else
    private fun shift(): Shift? = try { Shift.load() } catch (_: Exception) { null }

    internal fun resolve(s: ModeState, now: Long, shift: Shift?): Mode = when {
        s.manual != null && now < s.until -> s.manual
        s.auto && shift?.isOn(Instant.ofEpochMilli(now)) == true -> Mode.NIGHT
        else -> Mode.OFF
    }

    internal fun untilFor(m: Mode, now: Long, shift: Shift?): Long = when {
        m == Mode.DRIVING -> now + DRIVING_MS
        shift != null -> shift.nextChange(Instant.ofEpochMilli(now)).toEpochMilli()
        else -> Long.MAX_VALUE
    }

    /** The first sentence, plus the next when the first is only "Sure." or "Okay sir.", at most 160 characters. */
    internal fun shorten(text: String): String {
        val parts = text.trim().split(Regex("""(?<=[.!?])\s+"""))
        var out = parts.first()
        if (out.length < 12 && parts.size > 1) out += " " + parts[1]
        if (out.length <= 160) return out
        return out.take(160).substringBeforeLast(' ').trimEnd(',', ';', ':') + "."
    }
}

/** Apps he muted from the island: Link.dir/snooze.json = {"<pkg>": untilMs}. The island skips their alerts until then;
 *  CORE may read it too. Expired entries are dropped on every write. */
object Snooze {
    const val HOUR_MS = 60 * 60_000L
    internal var file: File? = null  // tests point this at a temp file
    private fun file() = file ?: File(Link.dir, "snooze.json")

    fun load(now: Long = System.currentTimeMillis()): Map<String, Long> =
        try { file().takeIf { it.exists() }?.let { live(parse(it.readText()), now) } } catch (_: Exception) { null } ?: emptyMap()

    /** Throws IOException if the disk refuses. */
    fun save(m: Map<String, Long>, now: Long = System.currentTimeMillis()) = atomicWrite(file(), json(live(m, now)))

    fun muted(m: Map<String, Long>, pkg: String, now: Long) = (m[pkg] ?: 0L) > now

    /** "Mute for today": until the next local midnight. */
    fun endOfDay(now: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    internal fun live(m: Map<String, Long>, now: Long) = m.filterValues { it > now }

    internal fun parse(s: String): Map<String, Long> = try {
        val o = JSONObject(s)
        o.keys().asSequence().associateWith { o.optLong(it) }
    } catch (_: Exception) { emptyMap() }

    internal fun json(m: Map<String, Long>): String = JSONObject().apply { m.forEach { (k, v) -> put(k, v) } }.toString()
}
