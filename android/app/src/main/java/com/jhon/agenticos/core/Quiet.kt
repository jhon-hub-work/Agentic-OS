package com.jhon.agenticos.core

import org.json.JSONObject
import java.io.File
import java.io.IOException

/** Jhon, 2 Nov: "a sleep mode or DND so Jharvis won't tell me each time there's a new notif; he summarizes it when I
 *  turn it off." The PC holds the truth (/api/jharvis/quiet); :core mirrors its answer into Link.dir/quiet.json every
 *  outbox round, so the island and home's mode chip read it without a network call. summary: "none",
 *  "waiting_unlock" (quiet ended, his first unlock asks for the summary) or "due". */
data class QuietState(val on: Boolean = false, val summary: String = "none", val by: String = "") {
    fun json(): String = JSONObject().put("on", on).put("summary", summary).put("by", by).toString()
    companion object {
        fun parse(s: String): QuietState = try {
            // isNull: Android's optString turns a JSON null into "null"
            JSONObject(s).let { QuietState(it.optBoolean("on"), it.optString("summary", "none"), if (it.isNull("by")) "" else it.optString("by")) }
        } catch (_: Exception) { QuietState() }  // a torn file means awake: chats are never lost, only spoken
    }
}

/** The first unlock after quiet ends asks for the summary; so does a poll that finds him already using the phone
 *  (quiet ended from the phone itself, so no unlock follows). */
internal fun sendUnlock(s: QuietState, unlocked: Boolean, present: Boolean) = !s.on && s.summary == "waiting_unlock" && (unlocked || present)

object Quiet {
    const val SLEEP = "Sleep"  // the mode chip's name for it
    private const val PATH = "/api/jharvis/quiet"
    internal var file: File? = null       // tests point this at a temp file
    internal var queue: SendQueue? = null  // and this at a temp queue
    private fun file() = file ?: File(Link.dir, "quiet.json")
    private fun queue() = queue ?: Link.queue

    fun state(): QuietState = file().takeIf { it.exists() }?.let { f -> try { QuietState.parse(f.readText()) } catch (_: Exception) { null } } ?: QuietState()

    fun on() = state().on

    /** Turned on or off on the phone: by "phone" (Do Not Disturb, his or the schedule's) or "mode" (the chip's Sleep).
     *  Queued, so a PC that's off hears it when back, in order (the last change wins); the mirror shows it at once. */
    fun set(on: Boolean, by: String) {
        queue().add(PATH, JSONObject().put("on", on).put("by", by).toString(), method = "PUT")
        try { atomicWrite(file(), state().copy(on = on, by = by).json()) } catch (_: IOException) {}  // the next poll writes the truth
    }

    /** He or the phone's schedule changed Do Not Disturb. The PUT is queued first: a poll answer already on its way
     *  then can't undo clearing askedDnd, so after his "off" a later mode switch or poll doesn't bring it back. */
    fun phoneDnd(on: Boolean) {
        set(on, "phone")
        if (!on) askedDnd = false
    }

    /** :core polled the PC. unlocked: he unlocked the phone since the last poll; present: he's using it now. Asking
     *  for the summary is queued, so it's sent until the PC takes it. Null when our own newer change is still queued:
     *  this answer is older, so the mirror keeps ours. An answer without "on" throws: it isn't "awake". */
    fun polled(answer: String, unlocked: Boolean, present: Boolean = false): QuietState? {
        JSONObject(answer).getBoolean("on")
        // shortcut: a change queued between this check and the write below is overwritten for one round (~3 s)
        if (PATH in queue().waiting()) return null
        val s = QuietState.parse(answer)
        if (sendUnlock(s, unlocked, present)) queue().add("$PATH/unlocked", "{}")
        atomicWrite(file(), s.json())
        return s
    }

    /** Kept for his summary instead of shown (the PC files it even if quiet just ended). False if it couldn't be
     *  queued: the caller shows it the usual way, never drops it. */
    fun hold(kind: String, text: String): Boolean = try {
        queue().add("$PATH/held", JSONObject().put("kind", kind).put("text", text).toString()); true
    } catch (_: IOException) { false }

    /** What the PC last asked of Do Not Disturb (outbox "dnd", or a poll catching up): a :core restart remembers it. */
    var askedDnd: Boolean
        get() = flag("quiet_dnd") == true
        set(v) = putFlag("quiet_dnd", v)

    /** The Do Not Disturb state the listener last saw, so a change made while it was gone shows on reconnect. */
    var seenDnd: Boolean?
        get() = flag("dnd_seen")
        set(v) { if (v != null) putFlag("dnd_seen", v) }

    private fun flag(name: String) = File(file().parentFile, name).takeIf { it.exists() }?.let { f ->
        try { f.readText().trim().toBooleanStrictOrNull() } catch (_: IOException) { null } }
    private fun putFlag(name: String, v: Boolean) { try { atomicWrite(File(file().parentFile, name), v.toString()) } catch (_: IOException) {} }
}
