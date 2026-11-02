package com.jhon.agenticos

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jhon.agenticos.core.Badges
import com.jhon.agenticos.core.Digest
import com.jhon.agenticos.core.Link
import com.jhon.agenticos.core.Mode
import com.jhon.agenticos.core.Modes
import com.jhon.agenticos.core.Quiet
import com.jhon.agenticos.core.QuietState
import com.jhon.agenticos.core.osHttp
import org.json.JSONObject
import java.io.File
import java.util.Collections
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Jhon, 1 Nov: "if I receive a notification of a personal chat, Jharvis informs me and asks if I want to reply,
 *  and sends my reply. A group chat, he asks me to mute it." Chat notifications go to the OS (Jharvis decides what
 *  to say); replies come back through the OS outbox and go out with the notification's own reply button, so the
 *  chat app never opens. Muting happens on the PC: a muted chat is dropped there.
 *  Runs in :core (see AndroidManifest): never reaches into the UI, so a home-screen crash can't silence it. */
class ChatListener : NotificationListenerService() {
    private val replyActions = boundedMap<Notification.Action>()  // conv -> the latest reply button for that chat
    private val lastText = boundedMap<String>()  // apps re-post the same notification; tell Jharvis once
    @Volatile private var connected = false
    @Volatile private var looping = false  // a quick disconnect/connect must not start a second loop
    private var work: Handler? = null  // one thread for what a notification sets off, so :core's main thread stays free
    private val bell = Semaphore(0)  // wakes the outbox loop early: a chat went out, or the screen came on
    @Volatile private var quietRounds = 0  // outbox rounds since the screen went off with nothing happening
    private var dndOn: Boolean? = null  // what we last asked of Do Not Disturb (outbox thread only); null = nothing yet
    private val asks = ArrayList<Pair<Boolean, Long>>()  // our Do Not Disturb requests of the last 10 s: their echoes aren't his change
    @Volatile private var catchUp = false  // just (re)connected: the next round looks for a DND change made while away
    @Volatile private var unlocked = false  // he unlocked since the last quiet poll (not counted while quiet is on)
    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (i.action == Intent.ACTION_USER_PRESENT) unlocked = true; wake() }
    }
    private val badgeTask = Runnable { guard("badges") { badges() } }

    override fun onCreate() {
        super.onCreate()
        work = Handler(HandlerThread("chat-notes").apply { start() }.looper)
        guard("screen receiver") { registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_USER_PRESENT) }, RECEIVER_NOT_EXPORTED) }
    }

    override fun onDestroy() {
        connected = false; wake()
        guard("screen receiver") { unregisterReceiver(screenOn) }
        work?.looper?.quitSafely(); work = null
        super.onDestroy()
    }

    override fun onListenerConnected() = guard("connect") {
        connected = true
        catchUp = true
        if (!looping) {
            looping = true
            Thread({ try { outboxLoop() } catch (e: Throwable) { Log.w("Jharvis", "outbox loop died", e) } finally { looping = false } }, "chat-outbox").start()
        }
        scheduleBadges()
    }

    // Android can unbind the listener without telling anyone; asking to be bound again is all it allows here.
    // The home screen's watchdog covers the case where even this never comes.
    override fun onListenerDisconnected() = guard("disconnect") {
        connected = false; wake()
        // the shade can't be read now: no count beats a frozen one; the next connect rebuilds them
        work?.let { it.removeCallbacks(badgeTask); it.post { guard("badges") { Badges.write(this, JSONObject()) } } }
        requestRebind(ComponentName(this, ChatListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) = guard("posted") {
        work?.post { guard("posted") { toIsland(sbn); chat(sbn) } }
        scheduleBadges()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) = guard("removed") { scheduleBadges() }

    /** Do Not Disturb or Sleep mode changed by him or the phone's schedule: the PC's quiet mode follows. Our own
     *  requests (Night shift, Driving, the PC's "dnd") echo back here and are skipped. */
    override fun onInterruptionFilterChanged(filter: Int) = guard("dnd changed") {
        if (filter == INTERRUPTION_FILTER_UNKNOWN) return@guard
        val on = filter != INTERRUPTION_FILTER_ALL
        val own = own(on)
        work?.post { guard("quiet") { Quiet.seenDnd = on; if (reportDnd(on, own, Quiet.state())) phoneChanged(on) } }
    }

    private fun own(on: Boolean) = synchronized(asks) { ownDnd(on, asks.toList(), System.currentTimeMillis()) }

    /** He or the phone's schedule changed Do Not Disturb: the PC's quiet mode follows. */
    private fun phoneChanged(on: Boolean) { Quiet.phoneDnd(on); wake() }

    private inline fun guard(what: String, f: () -> Unit) { try { f() } catch (e: Throwable) { Log.w("Jharvis", "$what failed", e) } }  // a bug here must not crash :core

    private fun wake() { quietRounds = 0; bell.release() }

    /** Badges follow the shade, 500 ms after the last change (a burst of posts writes once). */
    private fun scheduleBadges() { work?.let { it.removeCallbacks(badgeTask); it.postDelayed(badgeTask, 500) } }

    private fun badges() {
        if (!connected) return
        val on = Settings.Secure.getInt(contentResolver, "notification_badging", 1) != 0  // the phone's "App icon badges"
        val ranking = currentRanking
        val r = Ranking()
        val entries = (if (on) activeNotifications ?: emptyArray() else emptyArray()).map { sbn ->
            val n = sbn.notification
            val x = n.extras
            Badges.Entry(sbn.packageName, n.flags, n.number, ranking?.getRanking(sbn.key, r) != true || r.canShowBadge(),
                (x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty(),
                x.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty(), sbn.postTime)
        }
        Badges.write(this, Badges.build(entries, on, packageName))
    }

    private fun chat(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (sbn.packageName == packageName || n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val x = n.extras
        val messages = x.getParcelableArray(Notification.EXTRA_MESSAGES, Bundle::class.java)
        if (n.category != Notification.CATEGORY_MESSAGE && messages.isNullOrEmpty()) return  // only chats
        val last = messages?.lastOrNull()
        val text = (last?.getCharSequence("text") ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim()
        if (text.isNullOrEmpty()) return
        val convTitle = x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        val title = convTitle ?: x.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val group = x.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, convTitle != null)
        val sender = last?.getCharSequence("sender")?.toString() ?: title
        val conv = "${sbn.packageName}|$title"
        n.actions?.firstOrNull { a -> a.semanticAction == Notification.Action.SEMANTIC_ACTION_REPLY && !a.remoteInputs.isNullOrEmpty() }
            ?.let { replyActions[conv] = it }
            ?: n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }?.let { replyActions[conv] = it }
        if (lastText[conv] == text) return
        lastText[conv] = text
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
            catch (_: Exception) { sbn.packageName }
        val body = JSONObject().put("conv", conv).put("app", app).put("title", title).put("sender", sender).put("text", text)
            .put("group", group).put("can_reply", replyActions.containsKey(conv))
        val skip = Modes.skipChat(group)  // Modes (builder D): Driving mutes group chats, never sent so never read out
        if (skip || Modes.quietChats()) Digest.add(app, sbn.packageName, title, text)  // muted by the mode: kept for Home's digest
        if (skip) return
        // PC off: kept on the phone and sent when it's back, unless it's half an hour old by then
        Link.queue.add("/api/phone/chat", body.toString(), ttlMs = 30 * 60_000L)
        wake()  // the outbox loop sends it now, and Jharvis's answer comes back at the fast pace
    }

    /** Every new notification that would pop by itself also goes to the island (its own process, :island), which draws
     *  it at the camera. Not just chats: accessibility events miss most real app notifications, this listener doesn't. */
    private fun toIsland(sbn: StatusBarNotification) {
        val n = sbn.notification
        val quiet = Notification.FLAG_FOREGROUND_SERVICE or Notification.FLAG_GROUP_SUMMARY
        if (sbn.packageName == packageName || sbn.isOngoing || n.flags and quiet != 0) return  // music, downloads, "listening": not news
        val r = Ranking()
        if (currentRanking?.getRanking(sbn.key, r) == true && r.importance < NotificationManager.IMPORTANCE_DEFAULT) return  // silent: stays in the shade
        val x = n.extras
        val title = (x.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE) ?: x.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = (x.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: x.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        if ((n.category == Notification.CATEGORY_MESSAGE || x.containsKey(Notification.EXTRA_MESSAGES)) && Modes.quietChats()) return  // Modes (builder D): Night shift keeps chats off the island
        sendBroadcast(Intent("com.jhon.agenticos.ISLAND_NOTE").setPackage(packageName).putExtra("pkg", sbn.packageName)
            .putExtra("key", sbn.key).putExtra("title", title).putExtra("text", text).putExtra("open", n.contentIntent))
    }

    /** Send what's queued, then replies to send and things Jharvis wants said, from the OS. Every 3 s, stepping out to
     *  15 s and 60 s while the screen is off (not when Driving): always under Checks.LISTENER_STALE_MS. */
    private fun outboxLoop() {
        val pm = getSystemService(PowerManager::class.java)
        while (connected) {
            beat()
            var mode = Mode.OFF
            try {
                mode = Modes.current()
                followModeDnd(mode)
            } catch (e: Exception) { Log.w("Jharvis", "mode check failed", e) }
            try { if (Quiet.on()) unlocked = false } catch (_: Exception) {}  // an unlock during quiet isn't the first one after it
            try {
                if (Link.queue.flush() == 0) {  // PC still not taking queued ones: no point asking it
                    try {
                        Quiet.polled(String(osHttp("/api/jharvis/quiet", timeoutMs = 15000)), unlocked, present(pm))?.let { s ->
                            unlocked = false
                            dndCatchUp(s, Quiet.askedDnd)?.let { on -> dnd(on, mode) }  // a "dnd" item missed while offline or dead
                        }
                    } catch (e: Exception) { Log.w("Jharvis", "quiet check failed", e) }  // kept apart: the outbox still runs
                    val items = JSONObject(String(osHttp("/api/phone/outbox"))).getJSONArray("items")
                    if (items.length() > 0) quietRounds = 0
                    for (k in 0 until items.length()) {
                        val it = items.getJSONObject(k)
                        val kind = it.getString("kind")
                        // sent by the PC just before quiet began: kept for the summary instead (shown if it can't be kept)
                        val result = if (holdOnPhone(kind, Quiet.on()) && Quiet.hold(kind, it.getString("text"))) "held" else when (kind) {
                            "reply" -> reply(it.getString("conv"), it.getString("text"))
                            "notify" -> notify(it.getString("text"))  // a shade note only, never spoken
                            "dnd" -> dnd(it.getBoolean("on"), mode)
                            else -> say(it.getString("text"))
                        }
                        // queued: a result lost to a dropped line would make the PC hand the same reply out again
                        Link.queue.add("/api/phone/outbox/${it.getString("id")}", JSONObject().put("result", result).toString())
                    }
                    Link.queue.flush()
                }
            } catch (e: Exception) { Log.w("Jharvis", "outbox check failed", e) }
            beat()  // a slow PC turn must not age the heartbeat past the watchdog's limit
            val screen = pm?.isInteractive != false
            if (screen) quietRounds = 0
            val wait = outboxWait(quietRounds++, screen, mode == Mode.DRIVING)
            bell.tryAcquire(wait, TimeUnit.MILLISECONDS); bell.drainPermits()
        }
    }

    private fun beat() { try { Link.beat("listener") } catch (e: Exception) { Log.w("Jharvis", "heartbeat not written", e) } }

    /** He's using the phone right now: screen on and unlocked. */
    private fun present(pm: PowerManager?) = pm?.isInteractive == true && getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

    /** Do Not Disturb follows the mode, only when Jhon turned it on ("mode_dnd" in mode.json): Night shift and Driving
     *  ask for priority only, Off for everything again. The PC's quiet mode keeps it on too. Chats still reach us
     *  whatever the filter is. */
    private fun followModeDnd(mode: Mode) {
        // a change made while the listener was unbound or :core was dead never reached onInterruptionFilterChanged
        val away = if (catchUp) { catchUp = false; currentInterruptionFilter.let { if (it == INTERRUPTION_FILTER_UNKNOWN) null else it != INTERRUPTION_FILTER_ALL } } else null
        dndOn = dndRound(away, ::own, modeDnd(mode), dndOn, Build.VERSION.SDK_INT, ::ask, ::phoneChanged)
    }

    private fun modeDnd(mode: Mode) = mode != Mode.OFF &&
        try { JSONObject(File(Link.dir, "mode.json").readText()).optBoolean("mode_dnd") } catch (_: Exception) { false }

    /** The PC's quiet mode went on or off from the chip or Jharvis: Do Not Disturb follows, asked even when we think
     *  it already is (he may have set it himself). Night shift's own Do Not Disturb stays. */
    private fun dnd(on: Boolean, mode: Mode): String {
        Quiet.askedDnd = on
        val want = on || modeDnd(mode)
        ask(want)
        dndOn = want
        return "done"
    }

    private fun ask(on: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(asks) { asks.removeAll { now - it.second >= 10_000L }; asks += on to now }  // before the call: its echo can arrive before it returns
        requestInterruptionFilter(if (on) INTERRUPTION_FILTER_PRIORITY else INTERRUPTION_FILTER_ALL)  // throws when unbound: retried next round
    }

    private fun reply(conv: String, text: String): String {
        val action = replyActions[conv] ?: return "no_reply_button"
        return try {
            val inputs = action.remoteInputs
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            val fill = Intent().also { RemoteInput.addResultsToIntent(inputs, it, results) }
            action.actionIntent.send(this, 0, fill)
            lastText[conv] = text  // the app re-posts the chat with his reply in it: not news
            "sent"
        } catch (e: PendingIntent.CanceledException) { replyActions.remove(conv); "reply_button_expired" }
    }

    /** Spoken by the listener when the mic is on; otherwise a notification he can tap. */
    private fun say(text: String): String {
        if (Modes.quietChats()) return Modes.quietNote(this, text)  // Modes (builder D): Night shift, a silent shade note instead
        if (ListenService.announce(text)) return "spoken"
        return notify(text)
    }

    /** A notification he can tap, opening the dashboard. */
    private fun notify(text: String): String {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("chats", "Jharvis: your chats", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(this, 0, Intent().setClassName(this, "com.jhon.agenticos.MainActivity"), PendingIntent.FLAG_IMMUTABLE)  // literal: same class under .dev
        nm.notify(text.hashCode(), Notification.Builder(this, "chats").setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Jharvis").setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build())
        return "notified"
    }
}

/** Synchronized and capped (oldest-used dropped past max): the listener thread writes, the outbox thread reads. */
internal fun <V> boundedMap(max: Int = 200): MutableMap<String, V> = Collections.synchronizedMap(object : LinkedHashMap<String, V>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > max
})

/** Outbox pace: 3 s while he can see the phone or is driving; screen off, 3 s for 5 rounds, 15 s for 4, then 60 s. */
internal fun outboxWait(quietRounds: Int, screenOn: Boolean, driving: Boolean): Long = when {
    screenOn || driving || quietRounds < 5 -> 3_000L
    quietRounds < 9 -> 15_000L
    else -> 60_000L
}

/** Ask Do Not Disturb again only on a change. "Off" on older Android ends any DND, so it's asked there only after we
 *  turned it on, and never while quiet is on from his own DND (his: Night shift ending must not end his). On API 35+
 *  it just ends our own rule (even one left on by a :core restart). */
internal fun dndChange(want: Boolean, last: Boolean?, sdk: Int, his: Boolean = false): Boolean =
    want != last && (want || sdk >= 35 || (last == true && !his))

/** A Do Not Disturb change matching any request we made in the last 10 s is our own request coming back. */
internal fun ownDnd(on: Boolean, asks: List<Pair<Boolean, Long>>, now: Long): Boolean = asks.any { it.first == on && now - it.second < 10_000L }

/** Tell the PC about this Do Not Disturb edge: not our own echo, or our own "off" while the PC still has quiet on by
 *  "phone" (API 35+: his schedule ended under our rule, so only our "off" let the filter reach ALL). An own "on" stays ours. */
internal fun reportDnd(on: Boolean, own: Boolean, pc: QuietState): Boolean = !own || (!on && pc.on && pc.by == "phone")

/** On reconnect: Do Not Disturb isn't what the listener last saw, and it's worth reporting, so it changed while away. */
internal fun missedDnd(now: Boolean, seen: Boolean?, own: Boolean, pc: QuietState = QuietState()): Boolean =
    seen != null && now != seen && reportDnd(now, own, pc)

/** One outbox round's Do Not Disturb step. away: the filter now, read on the first round after a (re)connect, else null.
 *  That check comes before any request: a fresh :core asking again for what the PC last wanted would turn his own
 *  "off", made while it was gone, back on and hide it. Returns what we now want of Do Not Disturb. */
internal fun dndRound(away: Boolean?, own: (Boolean) -> Boolean, modeDnd: Boolean, last: Boolean?, sdk: Int,
                      ask: (Boolean) -> Unit, report: (Boolean) -> Unit): Boolean {
    if (away != null) {
        if (missedDnd(away, Quiet.seenDnd, own(away), Quiet.state())) report(away)
        Quiet.seenDnd = away
    }
    val want = modeDnd || Quiet.askedDnd
    val q = Quiet.state()
    if (dndChange(want, last, sdk, his = q.on && q.by == "phone")) ask(want)
    return want
}

/** The PC's quiet, set from the chip or Jharvis, differs from what it last asked of Do Not Disturb (its "dnd" item
 *  expired or never came): what to ask now, or null. A change by "phone" is already on the phone. */
internal fun dndCatchUp(pc: QuietState, asked: Boolean): Boolean? = if (pc.by.isNotEmpty() && pc.by != "phone" && pc.on != asked) pc.on else null

/** A say or notify the PC sent just before quiet began is kept for the summary, not shown. */
internal fun holdOnPhone(kind: String, quiet: Boolean) = quiet && (kind == "say" || kind == "notify")
