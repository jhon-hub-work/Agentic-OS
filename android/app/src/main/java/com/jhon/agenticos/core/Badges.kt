package com.jhon.agenticos.core

import android.app.Notification
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Notification counts per app for the orbit and the dock (REDESIGN.md Phase 2 "Badges"). ChatListener (:core) works
 *  them out from the shade; the UI reads Link.dir/badges.json after the BADGES broadcast:
 *  {"<pkg>": {"count": n, "notes": [{"title","text","time"}]}}, notes = latest 3, newest first. */
object Badges {
    const val ACTION = "com.jhon.agenticos.BADGES"
    private const val SKIP = Notification.FLAG_ONGOING_EVENT or Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY

    /** One notification in the shade, as much as the count needs. canBadge = its channel allows a badge. */
    data class Entry(val pkg: String, val flags: Int, val number: Int, val canBadge: Boolean, val title: String, val text: String, val time: Long)

    /** Pure (unit tested). enabled = the phone's "App icon badges" setting; off writes an empty map. */
    fun build(entries: List<Entry>, enabled: Boolean, own: String): JSONObject {
        val out = JSONObject()
        if (!enabled) return out
        entries.filter { it.pkg != own && it.canBadge && it.flags and SKIP == 0 }.groupBy { it.pkg }.forEach { (pkg, list) ->
            val notes = JSONArray()
            list.sortedByDescending { it.time }.filter { it.title.isNotEmpty() || it.text.isNotEmpty() }.take(3)
                .forEach { notes.put(JSONObject().put("title", it.title).put("text", it.text).put("time", it.time)) }
            out.put(pkg, JSONObject().put("count", list.sumOf { if (it.number > 0) it.number else 1 }).put("notes", notes))
        }
        return out
    }

    private var last: String? = null  // only the listener's worker thread writes

    /** Writes the file and tells the UI, only when something changed (downloads re-post every second). */
    fun write(c: Context, json: JSONObject) {
        val s = json.toString()
        if (s == last) return
        atomicWrite(File(Link.dir, "badges.json"), s)
        last = s
        c.sendBroadcast(Intent(ACTION).setPackage(c.packageName))
    }
}
