package com.jhon.agenticos

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.MediaStore
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.util.Log
import com.jhon.agenticos.core.Link
import com.jhon.agenticos.core.atomicWrite
import com.jhon.agenticos.core.osHttp
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.ln
import kotlin.math.sqrt

/** Jhon, 31 Oct: "I'm driving, I tell Jharvis open Waze and search for X." The mic stays open with any app on
 *  screen; only what he says with "Jharvis" goes to Jharvis, and the reply is spoken and opened here.
 *  Runs in :core with ChatListener. The home screen starts it each time it shows (Android lets a mic service start
 *  only from a visible app), so a kill costs at most until he next goes Home. */
class ListenService : Service() {
    companion object {
        const val RATE = 16000
        val WAKE = Regex("""\b(jh?|dj|ch|h)?[ae]r?vis\b""", RegexOption.IGNORE_CASE)  // same as the page's WAKE
        fun start(c: Context) = c.startForegroundService(Intent(c, ListenService::class.java))
        fun stop(c: Context) = c.stopService(Intent(c, ListenService::class.java))
        /** Side button held (AssistActivity): the next thing he says goes to Jharvis without "Jharvis". With the mic
         *  switched off, it listens for this one turn only. Must be called from a visible screen (mic FGS rule). */
        fun now(c: Context) = c.startForegroundService(Intent(c, ListenService::class.java).setAction("now").putExtra("once", !wanted(c)))
        /** The mic switch: its own file in Link.dir, read fresh by every process (the shared "aos" prefs are cached
         *  per process, and Home's stale copy turned the mic back on after he switched it off in the dashboard).
         *  Until the first switch after this change it falls back to the old pref. */
        fun wanted(c: Context): Boolean {
            val f = File(Link.dir, "mic")
            return if (f.exists()) f.readText().trim() != "off" else c.getSharedPreferences("aos", MODE_PRIVATE).getBoolean("wake", true)
        }
        fun want(c: Context, on: Boolean) {
            try { atomicWrite(File(Link.dir, "mic"), if (on) "on" else "off") } catch (e: Exception) { Log.w("Listen", "mic switch not saved", e) }
        }
        @Volatile private var live: ListenService? = null

        /** The side-button overlay (AssistActivity) follows a turn through these in-app broadcasts: "state" is
         *  hearing, thinking (with "text" once transcribed), speaking ("text" = what is said), done ("more" = it asked
         *  him something and listens for the answer), offline, listening (back to waiting), or level ("level" 0..1,
         *  20 a second while the overlay is up). */
        const val STATE = "com.jhon.agenticos.JHARVIS_STATE"

        /** Mic loudness (RMS of 16-bit samples) as 0..1 on a log scale: a quiet room near 0, the 900 speech
         *  threshold about 0.35, normal talking 0.5 to 0.8. */
        fun level(rms: Double) = ((ln(rms.coerceAtLeast(1.0)) - ln(250.0)) / (ln(9000.0) - ln(250.0))).toFloat().coerceIn(0f, 1f)

        /** Jharvis speaks first (a chat came in); his answer then needs no "Jharvis". False when the mic is off. */
        fun announce(text: String): Boolean {
            val s = live ?: return false
            Thread({ try { s.say(text); s.followUntil = System.currentTimeMillis() + 12000 } catch (e: Throwable) { Log.w("Jharvis", "announce failed", e) } }, "jharvis-announce").start()
            return true
        }
    }

    @Volatile private var followUntil = 0L  // he was asked something ("What should I say, sir?"): no "Jharvis" needed
    @Volatile private var spokeUntil = 0L  // when the last thing said ended: speech heard before that was our own voice
    @Volatile private var showUntil = 0L  // the side-button overlay is listening with him: send it the mic level

    @Volatile private var running = true
    @Volatile private var once = false  // started by the side button with the mic switched off: stop after this turn
    private var tts: TextToSpeech? = null

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("listen", "Listening for Jharvis", NotificationManager.IMPORTANCE_LOW))
        // without "Appear on top" Android blocks opening apps from the background: the notification takes him there once
        val canOpen = Settings.canDrawOverlays(this)
        val tap = if (canOpen) Intent().setClassName(this, "com.jhon.agenticos.MainActivity")  // literal: same class under .dev
            else Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
        val n = Notification.Builder(this, "listen")
            .setSmallIcon(R.drawable.ic_fg)
            .setContentTitle("Listening for “Jharvis”")
            .setContentText(if (canOpen) "Say “Jharvis” and what you need." else "Tap to allow opening apps (Appear on top).")
            .setContentIntent(PendingIntent.getActivity(this, 0, tap.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .setOngoing(true)
            .build()
        // refused when started from the background or without the mic grant: stop quietly instead of crashing :core
        try { startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) }
        catch (e: Throwable) { Log.w("Jharvis", "mic service refused", e); stopSelf(); return START_NOT_STICKY }
        if (tts == null) tts = TextToSpeech(this) {}
        live = this
        if (i?.action == "now") {
            once = i.getBooleanExtra("once", false) && (!started || once)  // already listening for good: stay that way
            followUntil = System.currentTimeMillis() + 10000
            showUntil = followUntil
            try {
                val tone = android.media.ToneGenerator(android.media.AudioManager.STREAM_MUSIC, 60)
                tone.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 120)
                Thread { Thread.sleep(400); tone.release() }.start()
            } catch (_: RuntimeException) {}  // no tone: still listening
        } else once = false
        if (!started) { started = true; Thread({ try { loop() } catch (e: Throwable) { Log.w("Jharvis", "listen loop died", e); stopSelf() } }, "jharvis-listen").start() }
        return START_NOT_STICKY  // a mic service can't restart itself from the background on Android 14+
    }
    private var started = false

    override fun onDestroy() { running = false; live = null; tts?.shutdown(); super.onDestroy() }

    private fun loop() {
        while (running) {
            if (once && System.currentTimeMillis() > followUntil) { stopSelf(); return }  // the one side-button turn is over
            val listenedFrom = System.currentTimeMillis()
            val clip = try { utterance() } catch (_: SecurityException) { stopSelf(); return }
                catch (e: Exception) { Log.w("Jharvis", "mic failed", e); Thread.sleep(1000); null }  // mic busy or broken: try a fresh recorder
            if (clip == null || spokeUntil > listenedFrom) { state("listening"); continue }  // a click, or an announcement played meanwhile
            state("thinking")  // he stopped talking
            try {
                val said = transcribe(wav(clip))
                if (said.isBlank() || (System.currentTimeMillis() > followUntil && !WAKE.containsMatchIn(said))) { state("listening"); continue }  // not for him
                Log.i("Jharvis", "heard: $said")
                state("thinking", said)
                val reply = ask(said)
                if (reply == null) { Log.w("Jharvis", "no answer in 90 s"); state("offline"); continue }
                val text = com.jhon.agenticos.core.Modes.brief(brief(reply.optString("text")))  // Modes (builder D): Driving cuts it to one short sentence
                val failed = (reply.optJSONArray("open") ?: JSONArray()).let { o ->
                    (0 until o.length()).map { o.getString(it) to openTarget(o.getString(it)) }
                        .onEach { Log.i("Jharvis", "open ${it.first} -> ${it.second}") }.filter { it.second !in setOf("opened", "sent") }
                }
                // a failed open or text is said instead of his "done", so he never thinks it went when it didn't
                val spoken = if (failed.isEmpty()) text else failed.joinToString(". ") { "Sorry sir, that didn't work: ${it.second}" }
                state("speaking", spoken)
                say(spoken)
                followUntil = if (failed.isEmpty() && text.trimEnd().endsWith("?")) System.currentTimeMillis() + 12000 else 0
                val more = followUntil > 0
                showUntil = if (more && showUntil > 0) followUntil else 0  // the overlay stays for his answer
                sendBroadcast(Intent(STATE).setPackage(packageName).putExtra("state", "done").putExtra("more", more))
            } catch (e: Exception) { Log.w("Jharvis", "turn failed", e); state("offline"); Thread.sleep(2000) }  // PC asleep or offline: keep listening
        }
    }

    /** One stretch of speech: starts on sound, ends after 1.1 s of quiet or 12 s. Null when it was only a click. */
    private fun utterance(): ShortArray? {
        val size = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(RATE)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
        var len = 0  // samples kept in pcm
        var speech = 0; var quiet = 0
        var show = false
        try {
            if (rec.state != AudioRecord.STATE_INITIALIZED) { Log.w("Jharvis", "mic not ready"); Thread.sleep(1000); return null }
            rec.startRecording()
            while (running) {
                if (len == 0) show = System.currentTimeMillis() < showUntil  // decided per utterance, kept while he talks
                var n = 0; var sum = 0.0
                repeat(2) {
                    val k = rec.read(frame, n, frame.size / 2)
                    if (k > 0) {
                        var half = 0.0
                        for (j in n until n + k) half += frame[j] * frame[j].toDouble()
                        sum += half; n += k
                        if (show) sendBroadcast(Intent(STATE).setPackage(packageName).putExtra("state", "level").putExtra("level", level(sqrt(half / k))))
                    }
                }
                if (n <= 0) { Log.w("Jharvis", "mic read failed"); Thread.sleep(1000); return null }  // dead recorder: the caller builds a new one instead of spinning
                val loud = sqrt(sum / n) > 900  // shortcut: fixed level; tune if road noise keeps it open
                if (len == 0 && !loud) { if (once && System.currentTimeMillis() > followUntil) break; continue }
                if (len == 0) state("hearing")
                frame.copyInto(pcm, len, 0, n); len += n
                if (loud) { speech++; quiet = 0 } else quiet++
                if (quiet >= 11 || len >= RATE * 12) break
            }
        } finally { try { rec.stop() } catch (_: IllegalStateException) {}; rec.release() }  // stop throws if it never started
        return if (speech >= 4) pcm.copyOf(len) else null
    }
    private val frame = ShortArray(RATE / 10)  // 100 ms, read in two halves so the overlay gets the level 20 times a second
    private val pcm = ShortArray(RATE * 12 + RATE / 10)  // one utterance, at most 12 s; reused (only the listen thread touches these)

    private fun state(s: String, text: String? = null) =
        sendBroadcast(Intent(STATE).setPackage(packageName).putExtra("state", s).apply { text?.let { putExtra("text", it) } })

    private fun wav(pcm: ShortArray): ByteArray {
        val data = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN).apply { asShortBuffer().put(pcm) }.array()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + data.size).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(RATE).putInt(RATE * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(data.size)
        return h.array() + data
    }

    private fun http(path: String, method: String = "GET", type: String? = null, body: ByteArray? = null) = osHttp(path, method, type, body)

    private fun transcribe(wav: ByteArray): String {
        val b = "aos" + System.nanoTime()
        val body = ByteArrayOutputStream().apply {
            write("--$b\r\nContent-Disposition: form-data; name=\"file\"; filename=\"talk.wav\"\r\nContent-Type: audio/wav\r\n\r\n".toByteArray())
            write(wav); write("\r\n--$b--\r\n".toByteArray())
        }.toByteArray()
        return JSONObject(String(http("/api/jharvis/transcribe", "POST", "multipart/form-data; boundary=$b", body))).optString("text")
    }

    /** Send his words, then wait (up to 90 s) for Jharvis's answer to them. */
    private fun ask(text: String): JSONObject? {
        val msg = JSONObject().put("role", "owner").put("text", text.trim()).put("client", "phone")
        val id = JSONObject(String(http("/api/jharvis/messages", "POST", "application/json", msg.toString().toByteArray()))).getString("id")
        val until = System.currentTimeMillis() + 90000
        // not stopped by the mic switch: Jhon taps it off right after talking (push-to-talk habit), and that
        // dropped the Grab answer on 31 Oct. Once asked, the answer still opens and is spoken.
        while (System.currentTimeMillis() < until) {
            Thread.sleep(1000)
            val all = JSONObject(String(http("/api/jharvis/messages?limit=20"))).getJSONArray("messages")
            val at = (0 until all.length()).indexOfFirst { all.getJSONObject(it).optString("id") == id }
            if (at < 0) continue
            for (k in at + 1 until all.length()) all.getJSONObject(k).takeIf { it.optString("role") == "jharvis" }?.let { return it }
        }
        return null
    }

    /** His voice (VoiceStudio on the PC), else the phone's own; waits until it's done so the mic doesn't hear it. */
    @Synchronized private fun say(text: String) {
        if (text.isBlank()) return
        spokeUntil = Long.MAX_VALUE
        val done = CountDownLatch(1)
        var mp: MediaPlayer? = null
        try {
            val f = File(cacheDir, "reply.wav").apply { writeBytes(http("/api/jharvis/speak", "POST", "application/json", JSONObject().put("text", text).toString().toByteArray())) }
            mp = MediaPlayer().apply {
                setOnCompletionListener { done.countDown() }
                setOnErrorListener { _, _, _ -> done.countDown(); true }
                setDataSource(f.path); prepare(); start()
            }
        } catch (_: Exception) {
            mp?.release(); mp = null  // a failed prepare kept the player alive before
            tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) = done.countDown()
                @Deprecated("old API") override fun onError(id: String?) = done.countDown()
            })
            tts?.speak(text.take(600), TextToSpeech.QUEUE_FLUSH, null, "reply") ?: done.countDown()
        }
        try { done.await(60, TimeUnit.SECONDS) } finally {
            mp?.release()  // released here, whatever ended it: done, an error, or the 60 s cap
            spokeUntil = System.currentTimeMillis()
        }
    }

    // the page's brief(): Slack links and emoji out, "Jhon," becomes "Sir,"
    private fun brief(t: String) = t.replace(Regex("""<https?:[^|>]+\|([^>]+)>"""), "$1").replace(Regex("""<https?:[^>]+>|https?://\S+"""), "")
        .replace(Regex(""":[a-z_]+:"""), "").replace(Regex("""^\s*Jhon,"""), "Sir,").replace(Regex("""[*_`#>]"""), "").replace(Regex("""\s+"""), " ").trim()
}

// links that reach inside the phone never open, whatever Jharvis sends (the OS server refuses them too)
private val BLOCKED = setOf("intent", "javascript", "file", "content", "data", "about", "blob", "android-app", "http")

/** What Jharvis sent: "app:<name>", "sms:<who>?body=<text>", "call:<who>", or any app's link (https, spotify:, geo:).
 *  Works from the background only with "Appear on top" on. Returns what happened, said aloud when it failed. */
fun Context.openTarget(target: String): String = try {
    val scheme = target.substringBefore(':').lowercase()
    when {
        scheme == "app" -> launchApp(target.removePrefix("app:").trim())
        scheme == "sms" -> {
            val who = Uri.decode(target.removePrefix("sms:").substringBefore('?').replace('+', ' '))
            val body = Uri.decode(target.substringAfter("?body=", "").replace('+', ' '))
            val number = numberFor(who) ?: return "no contact named $who"
            val sms = getSystemService(android.telephony.SmsManager::class.java)
            sms.sendMultipartTextMessage(number, null, sms.divideMessage(body), null, null)
            "sent"
        }
        scheme == "call" -> {
            val who = Uri.decode(target.removePrefix("call:").replace('+', ' '))
            val number = numberFor(who) ?: return "no contact named $who"
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "opened"
        }
        scheme in BLOCKED || !Regex("[a-z][a-z0-9+.-]{1,20}").matches(scheme) -> "refused"
        else -> view(Uri.parse(target))
    }
} catch (_: android.content.ActivityNotFoundException) { "not found" } catch (_: SecurityException) { "no permission" }

/** A link goes to the app that owns it (food.grab.com → Grab), else his browser. 27launch takes web links too, so
 *  Android asked "Open with" for every web link and nothing opened hands-free (31 Oct). */
private fun Context.view(uri: Uri): String {
    val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (uri.scheme == "https" || uri.scheme == "http") {
        fun handlers(u: Uri) = packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, u), 0).map { it.activityInfo.packageName }
        val browsers = handlers(Uri.parse("https://example.com/"))
        val owner = handlers(uri).firstOrNull { it !in browsers }
        val default = packageManager.resolveActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")), 0)
            ?.activityInfo?.packageName?.takeIf { it in browsers }  // "android" (the chooser) when none is set
        i.setPackage(owner ?: default ?: browsers.firstOrNull { it == "com.brave.browser" } ?: browsers.firstOrNull())
    }
    startActivity(i)
    return "opened"
}

/** A number as given, or the contact whose name matches best ("Mom", "lei ann"). */
fun Context.numberFor(who: String): String? {
    if (Regex("""[+\d][\d\s()-]{2,}""").matches(who)) return who
    val cols = arrayOf(Phone.DISPLAY_NAME, Phone.NUMBER)
    val found = ArrayList<Pair<String, String>>()
    contentResolver.query(Phone.CONTENT_URI, cols, "${cols[0]} LIKE ?", arrayOf("%$who%"), null)?.use { c ->
        while (c.moveToNext()) found.add(c.getString(0) to c.getString(1))
    }
    return (found.firstOrNull { it.first.equals(who, true) } ?: found.minByOrNull { it.first.length })?.second
}

fun Context.launcherApps(): List<Pair<String, String>> =
    packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        .map { it.loadLabel(packageManager).toString() to it.activityInfo.packageName }

/** Open the installed app whose name best matches ("spotify", "google maps", "camera"). */
fun Context.launchApp(name: String): String {
    if (name.isEmpty()) return "not found"
    if (name.equals("camera", true)) {
        startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return "opened"
    }
    val apps = launcherApps()  // fresh: it may have been installed a moment ago
    // exact, then "spotify" in "Spotify: Music", then "Maps" in "google maps" (same rule as Jharvis's phone_has)
    val hit = apps.firstOrNull { it.first.equals(name, true) }
        ?: apps.filter { it.first.contains(name, true) }.minByOrNull { it.first.length }
        ?: apps.filter { it.first.length >= 3 && name.contains(it.first, true) }.maxByOrNull { it.first.length }
        ?: return "not found"
    startActivity((packageManager.getLaunchIntentForPackage(hit.second) ?: return "not found").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    return "opened"
}

