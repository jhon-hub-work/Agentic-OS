package com.jhon.agenticos.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Chats a mode muted, kept so nothing is lost (REDESIGN.md Phase 2 "Digest"). ChatListener (:core) appends while
 *  Modes.current() mutes a chat and is the only writer of digest.json; Home shows unseen() when the mode is back to
 *  Off and clears with seen(newest shown time), its own file, so no write races between processes.
 *  Link.dir/digest.json = [{"app","pkg","title","line","time"}], oldest first, newest last.
 *  Link.dir/digest_seen = the newest time Home has shown (UI process writes). */
object Digest {
    const val CAP = 200
    internal var file: File? = null  // tests point this at a temp file
    private fun file() = file ?: File(Link.dir, "digest.json")
    private fun seenFile() = File(file().parentFile, "digest_seen")

    /** Pure (unit tested): one entry per chat (pkg + title) with its latest line, at most cap, oldest dropped. */
    fun merged(list: JSONArray, item: JSONObject, cap: Int = CAP): JSONArray {
        val key = item.optString("pkg") + "|" + item.optString("title")
        val keep = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optString("pkg") + "|" + it.optString("title") != key } + item
        return JSONArray(keep.takeLast(cap))
    }

    /** Pure (unit tested): entries newer than the seen mark, order kept. */
    fun after(list: JSONArray, seen: Long) =
        JSONArray((0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { it.optLong("time") > seen })

    private fun seenMark() = try { seenFile().readText().trim().toLong() } catch (_: Exception) { 0L }

    /** UI: he saw everything up to this time. */
    fun seen(upTo: Long) = atomicWrite(seenFile(), upTo.toString())

    /** What he hasn't seen yet, oldest first; empty on any read problem. Any process. */
    fun unseen(): JSONArray = try { after(JSONArray(file().readText()), seenMark()) } catch (_: Exception) { JSONArray() }

    @Synchronized fun add(app: String, pkg: String, title: String, line: String, time: Long = System.currentTimeMillis()) {
        val f = file()
        val now = try { JSONArray(f.readText()) } catch (_: Exception) { JSONArray() }  // missing or torn: start again
        val item = JSONObject().put("app", app).put("pkg", pkg).put("title", title).put("line", line).put("time", time)
        atomicWrite(f, merged(after(now, seenMark()), item).toString())  // seen ones are dropped so the file stays small
    }
}
