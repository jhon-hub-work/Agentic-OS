package com.jhon.agenticos.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** His whole launcher setup as one versioned JSON file, so a reinstall, a key change or a new phone loses nothing.
 *  Carries the UI "aos" prefs (dock, per-mode docks, hidden and locked apps, pins, appearance, glance), the launch
 *  history, the widget providers in order (ids belong to this install, so they are rebound on import) and the
 *  Link.dir mode and shift files. Never the pairing cookie (it lives in a file this never reads) or the photo bytes.
 *  The pure parts (encode, decode) run in plain unit tests; settings does the SAF streams and the widget rebinding. */
object Backup {
    const val KIND = "agentic-os-setup"
    const val VERSION = 1
    const val MAX_BYTES = 2_000_000           // a real setup is a few KB; anything this big isn't ours
    val FILES = listOf("mode.json", "shift.json")  // the only Link.dir files read or written, by exact name

    /** Keys that belong to this install, not to him: Android's widget ids, grants he must give again after a reinstall
     *  (setup ticks, asked-once flags), the appearance counter (import bumps it instead) and import's own to-do list. */
    fun portable(key: String) = key !in LOCAL && !key.startsWith("setup_") && !key.endsWith("_asked")
    private val LOCAL = setOf("widgets", "ap_v", "restore_widgets", "backup_last", "mode_tile_added")

    class Setup(val prefs: Map<String, Any>, val launches: Map<String, Any>, val widgets: List<String>,
                val files: Map<String, String>, val made: Long, val app: String)

    /** Thrown with a sentence he can read when a file isn't a setup this build can take. */
    class Bad(message: String) : Exception(message)

    fun encode(prefs: Map<String, *>, launches: Map<String, *>, widgets: List<String>, files: Map<String, String>,
               app: String, now: Long): JSONObject = JSONObject()
        .put("kind", KIND).put("version", VERSION).put("app", app).put("made", now)
        .put("prefs", typed(prefs)).put("launches", typed(launches))
        .put("widgets", JSONArray(widgets))
        .put("files", JSONObject().apply {
            for (n in FILES) files[n]?.let { t -> try { put(n, JSONObject(t)) } catch (_: Exception) {} }  // a torn file stays behind
        })

    fun decode(text: String): Setup {
        val o = try { JSONObject(text) } catch (_: Exception) { throw Bad("That file isn't an Agentic OS setup.") }
        if (o.optString("kind") != KIND) throw Bad("That file isn't an Agentic OS setup.")
        val v = o.optInt("version", 0)
        if (v < 1) throw Bad("That setup file is damaged.")
        if (v > VERSION) throw Bad("That setup is from a newer Agentic OS. Update this one first.")
        val widgets = o.optJSONArray("widgets")?.let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String } }
            ?.filter { it.contains('/') }.orEmpty()
        val f = o.optJSONObject("files")
        val files = FILES.mapNotNull { n -> f?.optJSONObject(n)?.let { n to it.toString() } }.toMap()
        return Setup(untyped(o.optJSONObject("prefs")), untyped(o.optJSONObject("launches")), widgets, files,
            o.optLong("made", 0L), o.optString("app"))
    }

    /** Prefs by type, so an Int comes back an Int: {"b":{}, "i":{}, "l":{}, "f":{}, "s":{}, "ss":{}}. */
    internal fun typed(m: Map<String, *>): JSONObject {
        val o = JSONObject()
        fun g(t: String) = o.optJSONObject(t) ?: JSONObject().also { o.put(t, it) }
        for ((k, v) in m) if (portable(k)) when (v) {
            is Boolean -> g("b").put(k, v)
            is Int -> g("i").put(k, v)
            is Long -> g("l").put(k, v)
            is Float -> g("f").put(k, v.toDouble())
            is String -> g("s").put(k, v)
            is Set<*> -> g("ss").put(k, JSONArray(v.filterIsInstance<String>().sorted()))
        }
        return o
    }

    /** The other way, skipping anything of the wrong type or a key this install keeps for itself. */
    internal fun untyped(o: JSONObject?): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        if (o == null) return out
        fun each(t: String, read: (JSONObject, String) -> Any?) {
            val m = o.optJSONObject(t) ?: return
            for (k in m.keys()) if (portable(k)) try { read(m, k)?.let { out[k] = it } } catch (_: Exception) {}
        }
        each("b") { m, k -> m.get(k) as? Boolean }
        each("i") { m, k -> (m.get(k) as? Number)?.toInt() }
        each("l") { m, k -> (m.get(k) as? Number)?.toLong() }
        each("f") { m, k -> (m.get(k) as? Number)?.toFloat() }
        each("s") { m, k -> m.get(k) as? String }
        each("ss") { m, k -> m.getJSONArray(k).let { a -> (0 until a.length()).mapNotNull { a.opt(it) as? String }.toSet() } }
        return out
    }

    // ── on the phone (call off the main thread) ──

    fun write(c: Context, out: OutputStream, widgets: List<String>, app: String, now: Long = System.currentTimeMillis()) {
        val files = FILES.mapNotNull { n -> File(Link.dir, n).takeIf { it.isFile }?.let { n to it.readText() } }.toMap()
        val json = encode(prefs(c).all, launches(c).all, widgets, files, app, now)
        out.write(json.toString(1).toByteArray())
        out.flush()
    }

    fun read(input: InputStream): Setup {
        val buf = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            buf.write(chunk, 0, n)
            if (buf.size() > MAX_BYTES) throw Bad("That file is too big to be a setup.")
        }
        return decode(buf.toString(Charsets.UTF_8.name()))
    }

    /** Replaces his portable prefs, launch history and the mode and shift files with the backup's. Keys this install
     *  keeps for itself (widget ids, grants) are left alone. Committed, so a reader right after sees it all. */
    fun apply(c: Context, s: Setup) {
        fun put(p: android.content.SharedPreferences, m: Map<String, Any>, all: Boolean) {
            val e = p.edit()
            p.all.keys.filter { all || portable(it) }.forEach { e.remove(it) }
            for ((k, v) in m) when (v) {
                is Boolean -> e.putBoolean(k, v); is Int -> e.putInt(k, v); is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v); is String -> e.putString(k, v)
                is Set<*> -> e.putStringSet(k, v.filterIsInstance<String>().toSet())
            }
            if (!e.commit()) throw IOException("could not save settings")
        }
        put(prefs(c), s.prefs, all = false)
        put(launches(c), s.launches, all = true)
        for ((n, t) in s.files) if (n in FILES) atomicWrite(File(Link.dir, n), t)
    }

    private fun prefs(c: Context) = c.getSharedPreferences("aos", Context.MODE_PRIVATE)
    private fun launches(c: Context) = c.getSharedPreferences("launches", Context.MODE_PRIVATE)  // LaunchCounts' history
}
