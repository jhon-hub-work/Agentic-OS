package com.jhon.agenticos.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.text.format.DateUtils
import com.jhon.agenticos.core.Link
import com.jhon.agenticos.core.Mode
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.MathContext
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** What the Orbit's search finds: apps (ranked), quick answers, shortcuts, contacts, calendar events, phone settings
 *  pages, and "Ask Jharvis". Matching and parsing are pure so they can be tested; the Android reads here are called
 *  off the main thread by OrbitView. */
object SmartSearch {
    // ── Matching (P1 7) ─────────────────────────────────────────────────────────────────────────────

    private val marks = Regex("\\p{Mn}+")

    /** Lower case without accents ("Café" -> "cafe"), the same on every phone.
     *  shortcut: Normalizer only folds accents, not other scripts to Latin; use android.icu Transliterator if he
     *  installs apps named in another script. */
    fun normalize(s: String): String = marks.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)

    /** A label normalized and split once, so a keystroke only compares prepared strings (P1 7). */
    private class Name(val n: String, val words: List<String>, val flat: String)

    // shortcut: only a wholesale clear past 4000 labels evicts, so renamed or removed apps leave a few stale entries
    private val names = ConcurrentHashMap<String, Name>()

    private fun prepped(s: String): Name = names[s] ?: run {
        val n = normalize(s)
        Name(n, n.split(' ', '-', '_', '.', ':').filter { it.isNotEmpty() }, n.replace(" ", "")).also {
            if (names.size > 4000) names.clear()
            names[s] = it
        }
    }

    /** Prepares labels before he types; called on the loader thread when the lists load. */
    fun prepare(labels: Iterable<String>) { for (l in labels) prepped(l) }

    /** The query as matching sees it: work it out once per keystroke, then pass it to [scoreKey]. */
    fun key(q: String): String = normalize(q).trim()

    /** 0 = name starts with q, 1 = a word in it starts with q, 2 = q appears anywhere, 3 = q's letters in order from a
     *  word start ("ytm" in "YT Music"), 4 = a near miss of 3+ letters ("spotfy"), -1 = no match. */
    fun score(q: String, name: String): Int = scoreKey(key(q), name)

    /** [score] with the query already passed through [key]. */
    fun scoreKey(k: String, name: String): Int {
        if (k.isEmpty()) return -1
        val p = prepped(name)
        val n = p.n
        val at = n.indexOf(k)
        if (at == 0) return 0
        if (at > 0) {
            var i = at
            while (i >= 0) {  // a later hit may sit at a word start ("Google Maps" for "ma" is word start, not just contains)
                if (i == 0 || !n[i - 1].isLetterOrDigit()) return 1
                i = n.indexOf(k, i + 1)
            }
            return 2
        }
        val tight = k.replace(" ", "")
        if (subsequence(tight, n)) return 3
        if (tight.length >= 3 && typo(tight, p)) return 4
        return -1
    }

    /** q's letters in order, the first at a word start. */
    internal fun subsequence(q: String, n: String): Boolean {
        if (q.isEmpty()) return false
        for (s in n.indices) {
            if (n[s] != q[0] || (s > 0 && n[s - 1].isLetterOrDigit())) continue
            var j = 1
            for (i in s + 1 until n.length) if (j < q.length && n[i] == q[j]) j++
            if (j == q.length) return true
        }
        return false
    }

    /** A typo away from the name's start or from one of its words. */
    private fun typo(q: String, p: Name): Boolean =
        p.words.any { jaroWinkler(q, it) >= 0.85 } || jaroWinkler(q, p.flat.take(q.length + 1)) >= 0.85

    /** Jaro-Winkler similarity, 0..1 (1 = the same). */
    internal fun jaroWinkler(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val range = (max(a.length, b.length) / 2 - 1).coerceAtLeast(0)
        val am = BooleanArray(a.length); val bm = BooleanArray(b.length)
        var m = 0
        for (i in a.indices) {
            for (j in max(0, i - range)..min(b.length - 1, i + range)) if (!bm[j] && a[i] == b[j]) { am[i] = true; bm[j] = true; m++; break }
        }
        if (m == 0) return 0.0
        var t = 0; var k = 0
        for (i in a.indices) if (am[i]) { while (!bm[k]) k++; if (a[i] != b[k]) t++; k++ }
        val jaro = (m.toDouble() / a.length + m.toDouble() / b.length + (m - t / 2.0) / m) / 3
        var p = 0
        while (p < min(4, min(a.length, b.length)) && a[p] == b[p]) p++
        return jaro + p * 0.1 * (1 - jaro)
    }

    /** Matches only, best score first; ties keep the input order (the caller passes most used first). */
    fun <T> rank(q: String, items: List<T>, name: (T) -> String): List<T> {
        val k = key(q)
        return items.withIndex().mapNotNull { (i, it) -> scoreKey(k, name(it)).takeIf { s -> s >= 0 }?.let { s -> Triple(it, s, i) } }
            .sortedWith(compareBy({ it.second }, { it.third })).map { it.first }
    }

    // ── Settings pages ──────────────────────────────────────────────────────────────────────────────

    /** Phone settings pages: label, words he might type, the Settings action. Shown only if the phone resolves it. */
    class Page(val label: String, val words: String, val action: String)

    val pages = listOf(
        Page("Wi-Fi", "wifi wireless internet network", Settings.ACTION_WIFI_SETTINGS),
        Page("Internet", "wifi mobile data network connection", Settings.Panel.ACTION_INTERNET_CONNECTIVITY),
        Page("Bluetooth", "headphones earbuds pair", Settings.ACTION_BLUETOOTH_SETTINGS),
        Page("Airplane mode", "flight offline", Settings.ACTION_AIRPLANE_MODE_SETTINGS),
        Page("Mobile data", "data usage cellular sim", Settings.ACTION_DATA_USAGE_SETTINGS),
        Page("Connections", "network hotspot tethering", Settings.ACTION_WIRELESS_SETTINGS),
        Page("Display", "brightness screen font dark", Settings.ACTION_DISPLAY_SETTINGS),
        Page("Eye comfort", "night light blue light filter", Settings.ACTION_NIGHT_DISPLAY_SETTINGS),
        Page("Sound and vibration", "ringtone volume vibrate", Settings.ACTION_SOUND_SETTINGS),
        Page("Volume", "sound loud quiet media", Settings.Panel.ACTION_VOLUME),
        Page("Do not disturb", "dnd quiet silence notifications", Settings.ACTION_ZEN_MODE_PRIORITY_SETTINGS),
        Page("Battery", "power usage charge", Intent.ACTION_POWER_USAGE_SUMMARY),
        Page("Battery saver", "power saving", Settings.ACTION_BATTERY_SAVER_SETTINGS),
        Page("Location", "gps", Settings.ACTION_LOCATION_SOURCE_SETTINGS),
        Page("Apps", "applications uninstall permissions", Settings.ACTION_APPLICATION_SETTINGS),
        Page("Default apps", "browser launcher", Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
        Page("Home app", "launcher home screen", Settings.ACTION_HOME_SETTINGS),
        Page("Date and time", "clock timezone", Settings.ACTION_DATE_SETTINGS),
        Page("Language", "locale keyboard", Settings.ACTION_LOCALE_SETTINGS),
        Page("Security", "lock screen password fingerprint face", Settings.ACTION_SECURITY_SETTINGS),
        Page("Privacy", "permissions", Settings.ACTION_PRIVACY_SETTINGS),
        Page("Accessibility", "talkback font size remove animations", Settings.ACTION_ACCESSIBILITY_SETTINGS),
        Page("VPN", "tailscale private network", Settings.ACTION_VPN_SETTINGS),
        Page("NFC", "tap to pay contactless", Settings.ACTION_NFC_SETTINGS),
        Page("Storage", "space files memory", Settings.ACTION_INTERNAL_STORAGE_SETTINGS),
        Page("About phone", "device info model version", Settings.ACTION_DEVICE_INFO_SETTINGS),
        Page("Accounts and sync", "google account backup", Settings.ACTION_SYNC_SETTINGS),
        Page("Cast", "screen mirror tv smart view", Settings.ACTION_CAST_SETTINGS),
    )

    /** A page matches on its label (best) or on any of its words; loose label matches come last. */
    fun pageScore(q: String, p: Page): Int {
        val s = score(q, p.label)
        if (s in 0..2) return s
        if (score(q, p.words) in 0..1) return 3  // a word match ranks after every real label match
        return if (s >= 3) s + 1 else -1
    }

    fun matchPages(q: String, pages: List<Page>): List<Page> =
        pages.withIndex().mapNotNull { (i, p) -> pageScore(q, p).takeIf { it >= 0 }?.let { Triple(p, it, i) } }
            .sortedWith(compareBy({ it.second }, { it.third })).map { it.first }

    /** The pages this phone can open: resolve once, off the main thread. */
    fun resolvable(c: Context): List<Page> = pages.filter { c.packageManager.resolveActivity(Intent(it.action), 0) != null }

    // ── Contacts ────────────────────────────────────────────────────────────────────────────────────

    class Contact(val id: Long, val key: String, val name: String, val number: String)

    /** Up to 4 people whose name or number matches. Empty if contacts aren't allowed. Call off the main thread. */
    fun contacts(c: Context, q: String): List<Contact> {
        if (q.isBlank() || c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q.trim()))
        val cols = arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID, ContactsContract.CommonDataKinds.Phone.LOOKUP_KEY,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME_PRIMARY, ContactsContract.CommonDataKinds.Phone.NUMBER)
        val out = LinkedHashMap<Long, Contact>()
        try {
            c.contentResolver.query(uri, cols, null, null, null)?.use { cur ->
                while (cur.moveToNext() && out.size < 4) {
                    val id = cur.getLong(0)
                    if (id !in out) out[id] = Contact(id, cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getString(3) ?: "")
                }
            }
        } catch (_: Exception) {}  // a provider hiccup means no contact rows, never a crash on the home screen
        return out.values.toList()
    }

    fun openContact(k: Contact): Intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.getLookupUri(k.id, k.key))
    fun dial(k: Contact): Intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + Uri.encode(k.number)))

    // ── Calendar ────────────────────────────────────────────────────────────────────────────────────

    class Event(val id: Long, val title: String, val begin: Long, val end: Long, val whenText: String)

    /** Up to 3 events from yesterday to 60 days out whose title, place or notes match. Empty without calendar access.
     *  Call off the main thread. */
    fun events(c: Context, q: String): List<Event> {
        if (q.trim().length < 2 || c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val now = System.currentTimeMillis(); val day = 86_400_000L
        val uri = CalendarContract.Instances.CONTENT_SEARCH_URI.buildUpon()
            .appendPath((now - day).toString()).appendPath((now + 60 * day).toString()).appendPath(q.trim()).build()
        val cols = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY)
        val out = ArrayList<Event>()
        try {
            c.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { cur ->
                while (cur.moveToNext() && out.size < 3) {
                    val begin = cur.getLong(2)
                    val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_WEEKDAY or
                        DateUtils.FORMAT_ABBREV_WEEKDAY or (if (cur.getInt(4) == 1) 0 else DateUtils.FORMAT_SHOW_TIME)
                    out += Event(cur.getLong(0), cur.getString(1)?.takeIf { it.isNotBlank() } ?: "Event", begin, cur.getLong(3),
                        DateUtils.formatDateTime(c, begin, flags))
                }
            }
        } catch (_: Exception) {}
        return out
    }

    fun openEvent(e: Event): Intent = Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, e.begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, e.end)

    // ── Pinned shortcuts (WIDGETS writes "pins" in "aos" prefs: [{"pkg","id","label"}]) ──────────────

    class Pin(val pkg: String, val id: String, val label: String)

    internal fun parsePins(s: String?): List<Pin> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            a.optJSONObject(i)?.let { o -> Pin(o.optString("pkg"), o.optString("id"), o.optString("label")) }
                ?.takeIf { it.pkg.isNotEmpty() && it.id.isNotEmpty() && it.label.isNotBlank() }
        }
    } catch (_: Exception) { emptyList() }

    fun pins(c: Context): List<Pin> = parsePins(Appearance.prefs(c).getString("pins", null))

    /** Takes a pin out of search and unpins it in Android too (the list keeps that app's other pins). Loader thread. */
    fun unpin(c: Context, pin: Pin) {
        val left = pins(c).filterNot { it.pkg == pin.pkg && it.id == pin.id }
        val arr = JSONArray().apply { for (p in left) put(JSONObject().put("pkg", p.pkg).put("id", p.id).put("label", p.label)) }
        Appearance.prefs(c).edit().putString("pins", arr.toString()).apply()
        try {
            c.getSystemService(android.content.pm.LauncherApps::class.java)
                ?.pinShortcuts(pin.pkg, left.filter { it.pkg == pin.pkg }.map { it.id }, android.os.Process.myUserHandle())
        } catch (_: Exception) {}  // not the default Home any more, or the app went: our list is what search shows
    }

    // ── Recent searches (last 8, shown when the field is empty) ─────────────────────────────────────

    internal fun remembered(old: List<String>, q: String): List<String> {
        val k = q.trim().take(80)
        if (k.isEmpty()) return old
        return (listOf(k) + old.filterNot { it.equals(k, ignoreCase = true) }).take(8)
    }

    fun recent(c: Context): List<String> = try {
        JSONArray(Appearance.prefs(c).getString("recent_q", "[]")).let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
    } catch (_: Exception) { emptyList() }

    /** Loader thread. */
    fun remember(c: Context, q: String) =
        Appearance.prefs(c).edit().putString("recent_q", JSONArray(remembered(recent(c), q)).toString()).apply()

    fun forget(c: Context) = Appearance.prefs(c).edit().remove("recent_q").apply()

    // ── Quick actions ───────────────────────────────────────────────────────────────────────────────

    sealed class Quick {
        data class Timer(val seconds: Int) : Quick()
        data class Answer(val text: String) : Quick()
        data class SetMode(val mode: Mode) : Quick()
    }

    private val unit = "(s|secs?|seconds?|m|mins?|minutes?|h|hrs?|hours?)?"
    private val timerFirst = Regex("""^(?:set (?:a )?)?timer (?:for )?(\d{1,4})\s*$unit$""")
    private val timerLast = Regex("""^(\d{1,4})\s*$unit timer$""")

    /** "timer 10" (minutes), "timer 90s", "5 min timer"; "12*7"; "night", "driving", "mode off". Null if it's none. */
    fun quick(q: String): Quick? {
        val k = q.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        (timerFirst.find(k) ?: timerLast.find(k))?.let { m ->
            val n = m.groupValues[1].toInt()
            val secs = when (m.groupValues[2].firstOrNull()) { 's' -> n; 'h' -> n * 3600; else -> n * 60 }
            return if (secs in 1..86_400) Quick.Timer(secs) else null
        }
        when (k) {
            "night", "night shift", "night mode", "night shift mode" -> return Quick.SetMode(Mode.NIGHT)
            "drive", "driving", "driving mode", "car mode" -> return Quick.SetMode(Mode.DRIVING)
            "mode off", "modes off", "normal mode", "no mode" -> return Quick.SetMode(Mode.OFF)
        }
        return calc(k)?.let { Quick.Answer(it) }
    }

    fun timer(seconds: Int): Intent = Intent(AlarmClock.ACTION_SET_TIMER)
        .putExtra(AlarmClock.EXTRA_LENGTH, seconds).putExtra(AlarmClock.EXTRA_SKIP_UI, false)

    fun timerWords(seconds: Int): String = when {
        seconds % 3600 == 0 -> plural(seconds / 3600, "hour")
        seconds % 60 == 0 -> plural(seconds / 60, "minute")
        else -> plural(seconds, "second")
    }

    private fun plural(n: Int, w: String) = "$n $w" + if (n == 1) "" else "s"

    /** Simple arithmetic: + - * x / ^ ( ), decimals, and "%" as percent ("200*15%" = 30). Null unless the whole query
     *  is a sum with at least one operator, or it divides by zero. */
    fun calc(q: String): String? {
        val s = q.replace(" ", "").replace('×', '*').replace('x', '*').replace('÷', '/').replace(',', '.')
        if (s.isEmpty() || !s.any { it.isDigit() } || !Regex("""[0-9.]+[%]?[-+*/^]|[0-9.]%""").containsMatchIn(s)) return null
        if (!s.all { it.isDigit() || it in ".+-*/^%()" }) return null
        val v = try { Calc(s).run() } catch (_: Exception) { return null } ?: return null
        if (v.isNaN() || v.isInfinite()) return null
        return BigDecimal(v).round(MathContext(12)).stripTrailingZeros().toPlainString().let { if (it == "-0") "0" else it }
    }

    /** Recursive descent: sum := term (+|- term)*, term := power (*|/ power)*, power := unary (^ power)?,
     *  unary := -unary | atom %?, atom := number | ( sum ). */
    private class Calc(val s: String) {
        var i = 0
        fun run(): Double? { val v = sum(); return if (i == s.length) v else null }
        fun sum(): Double { var v = term(); while (i < s.length && s[i] in "+-") { val op = s[i++]; val r = term(); v = if (op == '+') v + r else v - r }; return v }
        fun term(): Double {
            var v = power()
            while (i < s.length && s[i] in "*/") {
                val op = s[i++]; val r = power()
                v = if (op == '*') v * r else { if (r == 0.0) throw ArithmeticException(); v / r }
            }
            return v
        }
        fun power(): Double { val b = unary(); return if (i < s.length && s[i] == '^') { i++; b.pow(power()) } else b }
        fun unary(): Double {
            if (i < s.length && s[i] == '-') { i++; return -unary() }
            var v = atom()
            if (i < s.length && s[i] == '%') { i++; v /= 100 }
            return v
        }
        fun atom(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = sum(); if (i >= s.length || s[i] != ')') throw IllegalStateException(); i++; return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()  // "" or "1.2.3" throws: not a sum
        }
    }

    // ── Ask Jharvis ─────────────────────────────────────────────────────────────────────────────────

    /** Queue the question for Jharvis (15 min: a typed question hours later is noise) and try to send it now.
     *  Returns true if the PC answered in the last 60 s, so the dashboard can show the reply. Call off the main thread. */
    fun ask(q: String): Boolean {
        Link.queue.add("/api/jharvis/messages",
            JSONObject().put("role", "owner").put("text", q.trim().take(8000)).put("client", "phone").toString(), 15 * 60_000L)
        try { Link.queue.flush() } catch (_: Exception) {}
        return System.currentTimeMillis() - Link.lastBeat("pc") < 60_000L
    }
}
