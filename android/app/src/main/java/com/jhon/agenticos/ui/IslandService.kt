package com.jhon.agenticos.ui

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.jhon.agenticos.ChatListener
import com.jhon.agenticos.core.Snooze

/** Hosts the island (Island.kt). Only an accessibility overlay may sit above the status bar, so this is an
 *  accessibility service, set to receive nothing (res/xml/island.xml): no events, no screen content.
 *  Own process (:island), so neither the home screen nor :core can take it down. What it draws comes from:
 *  notifications, sent by ChatListener (the notification listener, in :core) as an in-app broadcast;
 *  what's playing, from MediaSessionManager, which trusts us because ChatListener is our own enabled listener;
 *  charging, from the system. Nothing is sent anywhere; the only thing stored is which apps he muted, and until when
 *  (Link.dir/snooze.json). It also performs Home's global actions (open the shade, lock the phone), sent as the in-app
 *  broadcast com.jhon.agenticos.GLOBAL while the service is connected. Every entry point is guarded: a bug logs, it
 *  never crashes :island. */
class IslandService : AccessibilityService() {
    private var island: Island? = null
    private var controller: MediaController? = null
    private var playing: NowPlaying? = null
    private val sessions by lazy { getSystemService(MediaSessionManager::class.java) }
    private val listener by lazy { ComponentName(this, ChatListener::class.java) }
    private val icons = HashMap<String, Drawable?>()
    private var lastKey = ""
    private var lastAt = 0L
    private var snoozed: Map<String, Long> = emptyMap()  // main thread; pkg -> muted until
    private var io: HandlerThread? = null                // snooze.json reads and writes, while connected
    private val main by lazy { Handler(mainLooper) }

    private val notes = receiver { note(it) }
    private val power = receiver {
        island?.charging(getSystemService(BatteryManager::class.java)?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return@receiver)
    }
    private val global = receiver { i ->
        when (i.getStringExtra("a")) {
            "shade" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "lock" -> performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)  // keeps biometric unlock, unlike a device admin's lockNow
        }
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { guard { follow(it.orEmpty()) } }
    private val player = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = guard { publish() }
        override fun onMetadataChanged(metadata: MediaMetadata?) = guard { publish() }
        override fun onSessionDestroyed() = guard { follow(active()) }
    }

    private fun receiver(f: (Intent) -> Unit) = object : BroadcastReceiver() { override fun onReceive(c: Context, i: Intent) = guard { f(i) } }

    override fun onServiceConnected() = guard {
        teardown()  // connected again without a disconnect: start clean, never twice registered
        io = HandlerThread("island-io").also { t ->
            t.start()
            Handler(t.looper).post { val m = Snooze.load(); main.post { snoozed = m + snoozed } }  // a mute made meanwhile wins
        }
        island = Island(this, ::mute).also { it.attach() }
        registerReceiver(notes, IntentFilter(ISLAND_NOTE), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(global, IntentFilter(GLOBAL), Context.RECEIVER_NOT_EXPORTED)
        registerReceiver(power, IntentFilter(Intent.ACTION_POWER_CONNECTED))
        // refused until notification access is on for ChatListener: the island then simply shows no music
        runCatching { sessions.addOnActiveSessionsChangedListener(sessionsChanged, listener) }
        follow(active())
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {}

    override fun onInterrupt() {}

    // switched off in Accessibility: stop hearing Home's global actions right away
    override fun onUnbind(intent: Intent?): Boolean { guard { teardown() }; return super.onUnbind(intent) }

    override fun onDestroy() {
        guard { teardown() }
        super.onDestroy()
    }

    private fun teardown() {
        listOf(notes, global, power).forEach { r -> runCatching { unregisterReceiver(r) } }  // throws when not registered
        runCatching { sessions.removeOnActiveSessionsChangedListener(sessionsChanged) }
        runCatching { controller?.unregisterCallback(player) }; controller = null; playing = null
        island?.detach(); island = null
        io?.quitSafely(); io = null  // a queued snooze write still lands
    }

    /** From the island: that app's alerts stay off it until then. */
    private fun mute(pkg: String, until: Long) {
        val now = System.currentTimeMillis()
        snoozed = snoozed.filterValues { it > now } + (pkg to until)
        val m = snoozed
        io?.let { Handler(it.looper).post { try { Snooze.save(m) } catch (e: Exception) { Log.w("Island", "mute not saved", e) } } }
    }

    private inline fun guard(f: () -> Unit) {
        try { f() } catch (t: Throwable) { Log.w("Island", "island call failed", t) }
    }

    private fun note(i: Intent) {
        val pkg = i.getStringExtra("pkg") ?: return
        if (pkg == "android" || pkg.startsWith("com.android.systemui")) return  // Android's own housekeeping
        if (Snooze.muted(snoozed, pkg, System.currentTimeMillis())) return  // he muted it from the island
        val key = i.getStringExtra("key").orEmpty()
        val now = SystemClock.uptimeMillis()
        if (key == lastKey && now - lastAt < REPEAT_MS) return  // apps re-post to update (progress, typing): one pop per burst
        lastKey = key; lastAt = now
        val title = i.getStringExtra("title").orEmpty().ifEmpty { appName(pkg) }
        island?.alert(Alert(key, pkg, icon(pkg), title, i.getStringExtra("text").orEmpty(), i.getParcelableExtra("open", PendingIntent::class.java)))
    }

    private fun active() = runCatching { sessions.getActiveSessions(listener) }.getOrDefault(emptyList())

    /** Track the player that's playing (or, failing that, the most recent one). */
    private fun follow(list: List<MediaController>) {
        val pick = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (pick?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(player)
            controller = pick
            pick?.registerCallback(player)
        }
        publish()
    }

    private fun publish() {
        val c = controller
        val meta = c?.metadata
        val state = c?.playbackState?.state
        val on = state == PlaybackState.STATE_PLAYING || state == PlaybackState.STATE_PAUSED || state == PlaybackState.STATE_BUFFERING
        val next = if (c == null || meta == null || !on) null else {
            val isPlaying = state == PlaybackState.STATE_PLAYING
            val t = c.transportControls
            NowPlaying(
                title = meta.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().ifBlank { appName(c.packageName) },
                artist = (meta.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: meta.getString(MediaMetadata.METADATA_KEY_ALBUM)).orEmpty(),
                art = (meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: meta.getBitmap(MediaMetadata.METADATA_KEY_ART)
                    ?: meta.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON))?.let { BitmapDrawable(resources, it) }
                    ?: icon(c.packageName),
                playing = isPlaying,
                playPause = { if (isPlaying) t.pause() else t.play() },
                next = { t.skipToNext() },
                previous = { t.skipToPrevious() },
            )
        }
        if (same(next, playing)) return  // players report often; redraw only on a real change
        playing = next
        island?.setMusic(next)
    }

    private fun same(a: NowPlaying?, b: NowPlaying?) = a?.title == b?.title && a?.artist == b?.artist && a?.playing == b?.playing &&
        pixels(a) === pixels(b)

    /** Cover art is re-wrapped on every update; compare the bitmap underneath. */
    private fun pixels(n: NowPlaying?): Any? = (n?.art as? BitmapDrawable)?.bitmap ?: n?.art

    // the same Drawable each time, so an unchanged player isn't redrawn
    private fun icon(pkg: String) = icons.getOrPut(pkg) { runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull() }

    private fun appName(pkg: String) = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }
        .getOrDefault(pkg)

    private companion object {
        const val ISLAND_NOTE = "com.jhon.agenticos.ISLAND_NOTE"  // sent by ChatListener
        const val GLOBAL = "com.jhon.agenticos.GLOBAL"  // sent by Home: extra "a" = "shade" or "lock"
        const val REPEAT_MS = 5_000L
    }
}
