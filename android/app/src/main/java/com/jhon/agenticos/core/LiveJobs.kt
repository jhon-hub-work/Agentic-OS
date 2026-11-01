package com.jhon.agenticos.core

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.format.DateFormat
import android.util.Log
import com.jhon.agenticos.R
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Date
import java.util.concurrent.atomic.AtomicBoolean

/** Renders, edits and sessions running on the PC as live pills (lock screen, Now Bar): one promoted ongoing
 *  notification per running item, turned into a plain "Finished" or "Failed" one when it ends. Runs in :core on its
 *  own thread (the chat loop stays free); 20 s while something runs, 60 s idle, 120 s while the PC doesn't answer. */
object LiveJobs {
    const val BOARD = "https://desktop-tge4frg.tailcb1411.ts.net:8765"
    private const val CHANNEL = "live"
    private const val TAG = "LiveJobs"
    private const val STALE_MS = 10 * 60_000L
    private const val SCREEN_OFF_CHECK_MS = 10_000L
    private val started = AtomicBoolean(false)

    enum class State { RUNNING, DONE, FAILED, HELD, WAITING_YOU, GONE }

    /** label is the whole line under the title: what it's doing now, or how it ended. */
    data class Item(val id: String, val title: String, val label: String, val step: Int, val steps: Int, val state: State, val link: String)

    private class Seen(var item: Item, var at: Long, var stale: Boolean = false)
    private val shown = HashMap<String, Seen>()  // only the poll thread touches it
    private val fails = IntArray(2)

    fun start(c: Context) {
        if (!started.compareAndSet(false, true)) return
        val app = c.applicationContext
        Thread({ loop(app) }, "live-jobs").apply { isDaemon = true }.start()
    }

    // ── pure: PC JSON in, items out (unit tested) ──────────────────────────────────────────────────

    /** The DYE board's /api/uploads: uploading, waiting and editing are live; the rest say how it ended. */
    fun board(json: String): List<Item> {
        val jobs = JSONObject(json).optJSONArray("jobs") ?: return emptyList()
        return (0 until jobs.length()).map { k ->
            val j = jobs.getJSONObject(k)
            val st = j.optJSONObject("stage") ?: JSONObject()
            val step = st.optInt("step"); val steps = st.optInt("steps")
            val now = st.optString("label").ifEmpty { "Editing" }
            val why = j.optString("reason").trim()
            fun ended(word: String) = if (why.isEmpty()) word else "$word: $why"
            val (state, label) = when (j.optString("state")) {
                "uploading" -> State.RUNNING to "Uploading"
                "waiting", "editing" -> State.RUNNING to if (step > 0 && steps > 0) "$now, step $step of $steps" else now
                "done" -> State.DONE to "Edit finished"
                "failed" -> State.FAILED to ended("Edit failed")
                "broll" -> State.HELD to ended("Not edited")
                else -> State.GONE to ""
            }
            Item("dye:" + j.optString("id"), j.optString("name").ifEmpty { "DYE film" }, label,
                if (state == State.RUNNING) step else 0, if (state == State.RUNNING) steps else 0, state, "$BOARD/")
        }
    }

    /** The OS's /api/sessions?light=1: a running session or meeting shows its round. */
    fun sessions(json: String): List<Item> {
        val list = JSONObject(json).optJSONArray("sessions") ?: return emptyList()
        return (0 until list.length()).map { k ->
            val s = list.getJSONObject(k)
            val round = s.optInt("round"); val rounds = s.optInt("rounds")
            val who = s.optJSONArray("working")?.let { w -> (0 until w.length()).map { w.optString(it).replaceFirstChar { c -> c.uppercase() } } }.orEmpty()
            val running = s.optString("status") == "running"
            val label = when {
                running -> listOfNotNull(if (round > 0 && rounds > 0) "Round $round of $rounds" else "Starting",
                    who.takeIf { it.isNotEmpty() }?.let { it.joinToString(" and ") + " working" }).joinToString(", ")
                s.optString("status") == "awaiting_user" -> "Waiting on you"
                else -> "Finished"
            }
            val state = when { running -> State.RUNNING; label == "Waiting on you" -> State.WAITING_YOU; else -> State.DONE }
            val id = s.optString("id")
            Item("session:$id", s.optString("title").ifEmpty { if (s.optBoolean("meeting")) "Meeting" else "Session" }, label,
                if (running) round else 0, if (running) rounds else 0, state, "${Link.ORIGIN}/#/sessions/$id")
        }
    }

    /** The short chip in the status bar and Now Bar: "5/8". Nothing when there's no real count yet. */
    fun chip(i: Item): String? = if (i.step > 0 && i.steps > 0) "${i.step}/${i.steps}" else null

    fun interval(anyLive: Boolean, pcAnswered: Boolean) = when { !pcAnswered -> 120_000L; anyLive -> 20_000L; else -> 60_000L }

    // ── the poll thread ─────────────────────────────────────────────────────────────────────────────

    private fun loop(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        try {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Live jobs", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Renders, edits and sessions running on the PC"
                setSound(null, null); enableVibration(false)  // a pill that updates every 20 s must never ring
            })
            // a :core restart forgets what it showed: clear old pills, the first poll puts the live ones back
            nm.activeNotifications.filter { it.notification.channelId == CHANNEL && it.isOngoing }.forEach { nm.cancel(it.id) }
        } catch (e: Exception) { Log.w(TAG, "setup", e) }
        val pm = c.getSystemService(PowerManager::class.java)
        while (true) {
            // screen off: nobody sees a pill, so no network; looks again soon so the lock screen is fresh on wake
            val wait = if (pm?.isInteractive == false) SCREEN_OFF_CHECK_MS
                else try { poll(c, nm) } catch (e: Throwable) { Log.w(TAG, "poll", e); 60_000L }  // never let this kill :core
            try { Thread.sleep(wait) } catch (_: InterruptedException) { return }
        }
    }

    private fun poll(c: Context, nm: NotificationManager): Long {
        val now = System.currentTimeMillis()
        var answered = false
        val sources = listOf<Pair<String, () -> List<Item>>>(
            "dye:" to { board(String(boardGet())) },
            "session:" to { sessions(String(osHttp("/api/sessions?light=1&limit=5", timeoutMs = 10000))) })
        sources.forEachIndexed { k, (prefix, fetch) ->
            val got = try { fetch() } catch (e: Exception) { null }
            if (got == null) { fails[k]++; if (fails[k] >= 2) stale(c, nm, prefix, now); return@forEachIndexed }
            fails[k] = 0; answered = true
            val ids = HashSet<String>()
            for (i in got) {
                ids += i.id
                if (i.state == State.RUNNING) {
                    val was = shown[i.id]
                    if (was == null || was.item != i || was.stale) post(c, nm, i, null)
                    shown[i.id] = Seen(i, now)
                } else if (shown.remove(i.id) != null) end(c, nm, i)
            }
            // fell off the list (hidden on the board, or pushed out of the last 5 sessions): nothing to report
            shown.keys.filter { it.startsWith(prefix) && it !in ids }.forEach { shown.remove(it); nm.cancel(nid(it)) }
        }
        return interval(shown.isNotEmpty(), answered)
    }

    /** Two failed polls: say so on the pill; ten minutes without news: take it down, the work may have stopped. */
    private fun stale(c: Context, nm: NotificationManager, prefix: String, now: Long) {
        for (id in shown.keys.filter { it.startsWith(prefix) }) {
            val s = shown.getValue(id)
            if (now - s.at > STALE_MS) { shown.remove(id); nm.cancel(nid(id)) }
            else if (!s.stale) { s.stale = true; post(c, nm, s.item, s.at) }
        }
    }

    private fun boardGet(): ByteArray {
        // its own connection: the board has no login, and osHttp would send the OS cookie and mark the OS as up
        val h = URL("$BOARD/api/uploads").openConnection() as HttpURLConnection
        h.connectTimeout = 10000; h.readTimeout = 15000
        try {
            if (h.responseCode != 200) throw IOException("board ${h.responseCode}")
            return h.inputStream.use { it.readBytes() }  // about 30 KB gzipped (HttpURLConnection asks for gzip itself)
        } finally { h.disconnect() }
    }

    private fun allowed(c: Context) = c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun nid(id: String) = ("live:$id").hashCode()

    private fun post(c: Context, nm: NotificationManager, i: Item, lastSeen: Long?) {
        if (!allowed(c)) return
        val text = if (lastSeen != null) "PC not answering, last seen " + DateFormat.getTimeFormat(c).format(Date(lastSeen)) else i.label
        val b = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(i.title).setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(open(c, i))
            // API 36 has no setter for this; the key is the one NotificationCompat writes
            .addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
            if (i.steps > 0) style.setProgressSegments(listOf(Notification.ProgressStyle.Segment(i.steps))).setProgress(i.step)
            else style.setProgressIndeterminate(true)
            b.setStyle(style)
            chip(i)?.let { b.setShortCriticalText(it) }
            if (!nm.canPostPromotedNotifications()) b.addAction(Notification.Action.Builder(null, "Show in Now Bar",
                PendingIntent.getActivity(c, 1, Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        } else b.setProgress(i.steps, i.step, i.steps == 0)
        val n = b.build()
        // promotion can be refused without an error: leave a trace for the lead's logcat
        if (Build.VERSION.SDK_INT >= 36 && !n.hasPromotableCharacteristics()) Log.i(TAG, "not promotable: ${i.id}")
        try { nm.notify(nid(i.id), n) } catch (e: Exception) { Log.w(TAG, "notify", e) }
    }

    /** The pill becomes a normal notification that says how it ended (same id, so it replaces the pill). */
    private fun end(c: Context, nm: NotificationManager, i: Item) {
        if (i.state == State.GONE || !allowed(c)) { nm.cancel(nid(i.id)); return }
        val n = Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_fg)
            .setContentTitle(i.title).setContentText(i.label).setStyle(Notification.BigTextStyle().bigText(i.label))
            .setAutoCancel(true).setContentIntent(open(c, i)).build()
        try { nm.notify(nid(i.id), n) } catch (e: Exception) { Log.w(TAG, "notify", e) }
    }

    /** The board opens in the browser; OS items open the dashboard at that session (by name: :core can't import it). */
    private fun open(c: Context, i: Item): PendingIntent {
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(i.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!i.link.startsWith(BOARD)) view.setClassName(c, "com.jhon.agenticos.MainActivity")  // literal: the class keeps its name under the .dev suffix
        return PendingIntent.getActivity(c, nid(i.id), view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }
}
